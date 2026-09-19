package com.gios.lightsports.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gios.lightsports.data.Feed
import com.gios.lightsports.data.Leagues
import com.gios.lightsports.data.Prefs
import com.gios.lightsports.data.SpecialEvents
import com.gios.lightsports.data.SportsRepository
import com.gios.lightsports.model.Celebration
import com.gios.lightsports.model.FieldEvent
import com.gios.lightsports.model.Game
import com.gios.lightsports.model.GameState
import com.gios.lightsports.model.Play
import com.gios.lightsports.model.ScoringPlay
import com.gios.lightsports.model.League
import com.gios.lightsports.model.SportKind
import com.gios.lightsports.model.TeamSeason
import com.gios.lightsports.util.Fmt
import com.gios.lightsports.model.Loudness
import com.gios.lightsports.model.StandingsGroup
import com.gios.lightsports.model.TeamRef
import com.gios.lightsports.notify.LiveRelay
import com.gios.lightsports.notify.LiveTicker
import com.gios.lightsports.notify.Notifier
import com.gios.lightsports.notify.ScoreWatcher
import com.gios.lightsports.notify.TickerPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

class SportsViewModel(app: Application) : AndroidViewModel(app) {

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 450L

        /**
         * How far the chevrons reach, in days. Both sit inside the fetched window
         * (`SportsRepository.BACK_DAYS` / `AHEAD_DAYS`), which is what lets a page turn
         * skip the network — widen one of these and the matching constant has to go with it.
         */
        const val FIRST_DAY = -7
        const val LAST_DAY = 14
    }

    private val repo = SportsRepository(app)
    val prefs = Prefs(app)

    /**
     * The one day the feed is showing. Built from [FeedState.games] without a fetch, so
     * the chevrons answer immediately.
     */
    data class FeedPage(
        val sections: List<Feed.Section> = emptyList(),
        /** "TODAY", "YESTERDAY", "TOMORROW", or "SAT SEP 19". */
        val title: String = "SPORTS",
        /** "SAT SEP 19" whatever the title says, so the empty page still names its day. */
        val date: String = "",
        /** The date, plus "ALL FINAL" and "3–1 FOR YOUR TEAMS" on a day gone by. */
        val subtitle: String? = null,
    ) {
        /**
         * One game swapped in where it already sits. A relay message moves the row it
         * belongs to and nothing else — rebuilding the page would re-sort it out from
         * under a thumb that is halfway down the list.
         */
        fun withGame(updated: Game): FeedPage = copy(
            sections = sections.map { section ->
                section.copy(items = section.items.map { item ->
                    if (item is Feed.Item.GameItem && item.game.id == updated.id) {
                        Feed.Item.GameItem(updated)
                    } else item
                })
            },
        )

        /**
         * The games in progress on this page, which is not the same as the games in progress
         * in [FeedState.games] — that holds three weeks either side of today. The page is what
         * the user can see, and what the user can see is the only thing worth a fetch.
         */
        val liveGames: List<Game>
            get() = sections
                .flatMap { it.items }
                .filterIsInstance<Feed.Item.GameItem>()
                .map { it.game }
                .filter { it.state == GameState.LIVE }
    }

    data class FeedState(
        val loading: Boolean = false,
        /** Every game in the fetched window, which is more than the page shows. */
        val games: List<Game> = emptyList(),
        /**
         * Race weekends and golf tournaments, kept beside the sections so the screen
         * behind a card can be looked up by id and stays live through a refresh.
         */
        val events: List<FieldEvent> = emptyList(),
        val updatedAt: Long = 0L,
        val offline: Boolean = false,
        /** Days away from today: 0 is today, -1 yesterday, 1 tomorrow. */
        val dayOffset: Int = 0,
        val page: FeedPage = FeedPage(),
    ) {
        val sections: List<Feed.Section> get() = page.sections
        val title: String get() = page.title
        val subtitle: String? get() = page.subtitle

        /** See [FeedPage.withGame]. */
        fun withGame(updated: Game): FeedState = copy(
            games = games.map { if (it.id == updated.id) updated else it },
            page = page.withGame(updated),
        )
    }

    /** The lookup screen: what was typed, and what it found. */
    data class SearchState(
        val query: String = "",
        val loading: Boolean = false,
        val sections: List<Feed.Section> = emptyList(),
        val games: List<Game> = emptyList(),
        /** The query the sections belong to, so stale results are not shown under new text. */
        val resultsFor: String = "",
    )

    private val _search = MutableStateFlow(SearchState())
    val search: StateFlow<SearchState> = _search.asStateFlow()
    private var searchJob: kotlinx.coroutines.Job? = null

    /**
     * Type-ahead lookup of any team or league. Debounced: the team lists are cached but
     * a scoreboard is a real fetch, so nothing goes out until the typing pauses.
     */
    fun search(query: String, immediate: Boolean = false) {
        _search.value = _search.value.copy(query = query)
        searchJob?.cancel()
        if (query.trim().length < 2) {
            _search.value = _search.value.copy(loading = false, sections = emptyList(), games = emptyList(), resultsFor = "")
            return
        }
        searchJob = viewModelScope.launch {
            if (!immediate) kotlinx.coroutines.delay(SEARCH_DEBOUNCE_MS)
            _search.value = _search.value.copy(loading = true)
            val now = System.currentTimeMillis()
            val zone = ZoneId.systemDefault()
            val games = withContext(Dispatchers.IO) { repo.search(query, now, zone) }
            // Crests for whatever came back: the team lists are already on disk from the
            // lookup itself, so this is a file read per league.
            games.map { it.leagueId }.distinct().forEach { id -> Leagues.byId(id)?.let { loadTeams(it) } }
            if (_search.value.query == query) {
                _search.value = _search.value.copy(
                    loading = false,
                    sections = Feed.build(games, emptyList(), now, zone),
                    games = games,
                    resultsFor = query,
                )
            }
        }
    }

    /** The live game a team is in right now, if the feed or the last search has one. */
    fun liveGameFor(leagueId: String, teamId: String): Game? =
        (_feed.value.games + _search.value.games).firstOrNull {
            it.leagueId == leagueId && it.state == GameState.LIVE &&
                (it.home.teamId == teamId || it.away.teamId == teamId)
        }

    /** One team's season, keyed `leagueId:teamId`. */
    private val _seasons = MutableStateFlow<Map<String, TeamSeason>>(emptyMap())
    val seasons: StateFlow<Map<String, TeamSeason>> = _seasons.asStateFlow()

    private val _feed = MutableStateFlow(FeedState())
    val feed: StateFlow<FeedState> = _feed.asStateFlow()

    /**
     * A game as the relay just reported it. Folded into the feed in place so the row and
     * the open game screen move the moment the socket says so, without a fetch. Arrives
     * on the socket's thread; the flows are thread-safe and Compose reads them on main.
     */
    private val onRelayGame: (Game) -> Unit = { updated ->
        val state = _feed.value
        if (state.games.any { it.id == updated.id }) {
            _feed.value = state.withGame(updated).copy(updatedAt = System.currentTimeMillis())
        }
        // A game whose plays are on screen gets its play-by-play refreshed with the score.
        if (updated.state == GameState.LIVE && _plays.value.containsKey(updated.id)) {
            Leagues.byId(updated.leagueId)?.let { loadPlays(it, updated.id) }
        }
        // The ticker redraws the card off the same socket, but only while it is up, and it
        // is not up when the platform refused it a foreground service. With the app open,
        // this view model is the one thing certain to be listening.
        pushCards(listOf(updated))
    }

    init {
        LiveRelay.addListener(onRelayGame)
    }

    override fun onCleared() {
        LiveRelay.removeListener(onRelayGame)
        super.onCleared()
    }

    private val _follows = MutableStateFlow(prefs.follows)
    val follows: StateFlow<Set<String>> = _follows.asStateFlow()

    /** Followed teams that don't interrupt. A subset of [follows]. */
    private val _muted = MutableStateFlow(prefs.muted)
    val muted: StateFlow<Set<String>> = _muted.asStateFlow()

    private val _teams = MutableStateFlow<Map<String, List<TeamRef>>>(emptyMap())
    val teams: StateFlow<Map<String, List<TeamRef>>> = _teams.asStateFlow()

    /** Crest URLs by `leagueId:teamId`, derived from whatever team lists are loaded. */
    private val _logos = MutableStateFlow<Map<String, String>>(emptyMap())
    val logos: StateFlow<Map<String, String>> = _logos.asStateFlow()

    private val _standings = MutableStateFlow<Map<String, List<StandingsGroup>>>(emptyMap())
    val standings: StateFlow<Map<String, List<StandingsGroup>>> = _standings.asStateFlow()

    /** The last few plays of the open game, newest first, keyed by game id. */
    private val _plays = MutableStateFlow<Map<String, List<Play>>>(emptyMap())
    val plays: StateFlow<Map<String, List<Play>>> = _plays.asStateFlow()

    /** Scoring plays by game id, plus the score they were fetched at (to know when to refetch). */
    private val _scoring = MutableStateFlow<Map<String, Pair<String, List<ScoringPlay>>>>(emptyMap())
    val scoring: StateFlow<Map<String, Pair<String, List<ScoringPlay>>>> = _scoring.asStateFlow()

    /** The recap story by game id, in paragraphs. Empty list means the provider has none. */
    private val _recap = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val recap: StateFlow<Map<String, List<String>>> = _recap.asStateFlow()

    fun refresh() = refresh(_feed.value.dayOffset)

    /**
     * Page the feed a day either way. Zero is today.
     *
     * One fetch already holds every day the chevrons can reach — [SportsRepository.BACK_DAYS]
     * and [SportsRepository.AHEAD_DAYS] are what [FIRST_DAY] and [LAST_DAY] are set from — so
     * a page turn re-buckets what is in hand rather than going back to the network. That is
     * what makes the chevrons answer at once instead of after a spinner. An empty state is
     * the one case with nothing to re-bucket, and it fetches.
     */
    fun shiftDay(delta: Int) {
        val next = (_feed.value.dayOffset + delta).coerceIn(FIRST_DAY, LAST_DAY)
        val current = _feed.value
        if (next == current.dayOffset) return
        if (current.games.isEmpty() && current.events.isEmpty()) {
            refresh(next)
            return
        }
        _feed.value = current.copy(
            dayOffset = next,
            page = page(current.games, current.events, System.currentTimeMillis(), ZoneId.systemDefault(), next),
        )
    }

    private fun refresh(dayOffset: Int) {
        if (_feed.value.loading) return
        _feed.value = _feed.value.copy(loading = true, dayOffset = dayOffset)
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val zone = ZoneId.systemDefault()
            // No shift: the window is fetched around today and every page comes out of it.
            val (games, races) = withContext(Dispatchers.IO) { repo.followedGames(now, zone) }
            _feed.value = FeedState(
                loading = false,
                games = games,
                events = races,
                updatedAt = now,
                // Followed teams but nothing came back: almost always the network,
                // and worth saying so rather than showing a bare "no games".
                offline = prefs.follows.isNotEmpty() && games.isEmpty() && races.isEmpty(),
                dayOffset = dayOffset,
                page = page(games, races, now, zone, dayOffset),
            )
            syncTicker(games)
            // The relay socket follows the feed too, so opening the app during a game
            // connects it even before the ticker's next poll does.
            runCatching { LiveRelay.sync(getApplication(), games.filter { it.involves(prefs.notifyKeys) }, now) }
        }
    }

    /**
     * One day of the window, worked out from games already in hand. Pure, so a page turn
     * costs nothing.
     *
     */
    private fun page(
        games: List<Game>,
        races: List<FieldEvent>,
        nowMillis: Long,
        zone: ZoneId,
        dayOffset: Int,
    ): FeedPage {
        val day = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate().plusDays(dayOffset.toLong())
        val sections = Feed.build(
            games, races, nowMillis, zone,
            backDays = -FIRST_DAY.toLong(),
            aheadDays = LAST_DAY.toLong(),
            onlyDay = day.toEpochDay(),
        )
        val shown = sections.flatMap { it.items }.mapNotNull { (it as? Feed.Item.GameItem)?.game }
        val allFinal = shown.isNotEmpty() && shown.all { it.state == GameState.FINAL }
        val title = Feed.dayTitle(day, nowMillis, zone)
        val date = Feed.dayLine(day)
        return FeedPage(
            sections = sections,
            title = title,
            date = date,
            subtitle = listOfNotNull(
                date.takeIf { it != title },
                Feed.weekLabel(shown),
                "ALL FINAL".takeIf { allFinal },
                Feed.recordLine(shown, prefs.follows).takeIf { dayOffset != 0 || allFinal },
            ).joinToString(" · ").takeIf { it.isNotEmpty() },
        )
    }

    fun loadSeason(league: League, teamId: String) {
        val key = "${league.id}:$teamId"
        viewModelScope.launch {
            val season = withContext(Dispatchers.IO) { repo.teamSeason(league, teamId) } ?: return@launch
            _seasons.value = _seasons.value + (key to season)
        }
    }

    /**
     * Start the ticker off the back of a refresh, if there is something to tick for.
     *
     * Opening the app during a game is both the commonest way to find out one is on and
     * the one moment a foreground service is unconditionally allowed to start — from the
     * background, Android 12 onwards refuses outside a short list of exemptions. So the
     * screen the user is already looking at does the honours, and the alarm chain's
     * attempt is the fallback rather than the other way round.
     */
    private fun syncTicker(games: List<Game> = _feed.value.games) {
        val app = getApplication<Application>()
        val watched = games.filter { it.involves(prefs.notifyKeys) }
        val wanted = prefs.notificationsEnabled && prefs.liveUpdatesEnabled &&
            TickerPlan.shouldRun(watched, System.currentTimeMillis(), ScoreWatcher.LEAD)
        // Only ever started from here, never stopped: a refresh that came back empty
        // because the train went into a tunnel looks identical to a game having ended,
        // and pulling the service down on the strength of that would leave the rest of
        // the game on the nine-minute floor. The ticker's own poll ends it, and the
        // settings below stop it outright when the user actually says so.
        if (wanted) LiveTicker.start(app)
    }

    fun loadTeams(league: League) {
        if (_teams.value.containsKey(league.id)) return
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { repo.teams(league) }
            _teams.value = _teams.value + (league.id to list)
            _logos.value = _logos.value + list.mapNotNull { team ->
                team.logoUrl?.let { team.key to it }
            }
        }
    }

    fun loadStandings(league: League) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val groups = withContext(Dispatchers.IO) {
                repo.standings(league, now, ZoneId.systemDefault())
            }
            _standings.value = _standings.value + (league.id to groups)
        }
    }

    fun toggleFollow(key: String) {
        prefs.toggleFollow(key)
        _follows.value = prefs.follows
        // Unfollowing drops any silence with it, so the two flows move together.
        _muted.value = prefs.muted
        // Following the first team is what turns the background poll on at all.
        ScoreWatcher.ensureArmed(getApplication())
    }

    fun toggleMute(key: String) {
        prefs.toggleMute(key)
        _muted.value = prefs.muted
        // Silencing the only live team should take its card down with it; unsilencing
        // one mid-game should put it up.
        if (prefs.muted.isEmpty() || _feed.value.games.any { it.involves(prefs.notifyKeys) }) {
            syncTicker()
        } else {
            LiveTicker.stop(getApplication())
        }
    }

    fun gameById(id: String): Game? = _feed.value.games.firstOrNull { it.id == id }

    /**
     * Re-fetch one game for the screen that has it open, and return the fresh copy.
     *
     * Only that game's league is fetched — a screen on a Mets game has no business
     * pulling four soccer scoreboards every fifteen seconds. The result is folded back
     * into the feed in place so the list behind the screen agrees with it; a change of
     * state (the final whistle) triggers a full refresh, since the game belongs in a
     * different section now and the ticker may have work to do.
     */
    suspend fun track(game: Game): Game? {
        val league = Leagues.byId(game.leagueId) ?: return null
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val fresh = withContext(Dispatchers.IO) { repo.games(league, now, zone) }
        val updated = fresh.firstOrNull { it.id == game.id } ?: return null
        // The play-by-play rides along with every live refresh; it is what the field and
        // the LAST PLAYS list are drawn from.
        if (updated.state == GameState.LIVE) loadPlays(league, updated.id)
        // Done whether or not the game moved: the card in the shade may be older than the
        // copy this screen was already holding, and a fetch that changed nothing here can
        // still be news there.
        pushCards(listOf(updated))
        if (updated == game) return updated
        val state = _feed.value
        _feed.value = state.withGame(updated).copy(updatedAt = now)
        if (updated.state != game.state) refresh()
        return updated
    }

    /**
     * Re-fetch the games in progress on the open page and fold them in where they sit.
     *
     * Deliberately not [refresh]: that fetches every followed league, rebuilds the sections
     * and re-sorts them, which is the right thing on the way in and the wrong thing every
     * fifteen seconds under a thumb halfway down the list. This fetches only the leagues
     * with something live on the page and swaps those rows in place, so the list does not
     * move and there is no spinner.
     *
     * A game changing state is the one case that does need the full rebuild — a final
     * belongs in a different section now — so it hands over to [refresh] and stops.
     *
     * @return how many rows actually changed, for the caller that wants to flash the header.
     */
    suspend fun trackFeed(): Int {
        val live = _feed.value.page.liveGames
        if (live.isEmpty()) return 0
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val leagues = live.mapNotNull { Leagues.byId(it.leagueId) }.distinctBy { it.id }
        val fresh = withContext(Dispatchers.IO) {
            leagues.flatMap { league ->
                runCatching { repo.games(league, now, zone) }.getOrDefault(emptyList())
            }
        }
        if (fresh.isEmpty()) return 0
        val byId = fresh.associateBy { it.id }
        var state = _feed.value
        var changed = 0
        var settled = false
        for (game in live) {
            val updated = byId[game.id] ?: continue
            if (updated == game) continue
            state = state.withGame(updated)
            changed++
            if (updated.state != game.state) settled = true
        }
        if (changed > 0) _feed.value = state.copy(updatedAt = now)
        pushCards(live.mapNotNull { byId[it.id] })
        if (settled) refresh()
        return changed
    }

    /**
     * Put what the app just fetched into whatever cards are already in the shade.
     *
     * The screen and the notification are two views of one score and they were fed by two
     * different clocks — the screen by its own fifteen seconds, the card by the ticker's
     * minute. Watching a game with the app open therefore meant watching the lock screen fall
     * a minute behind the panel above it.
     *
     * Nothing here decides *whether* a game is notified about: [Notifier.refreshShowing]
     * redraws cards that exist and posts none that do not, and a silenced or unfollowed team
     * has no card to redraw. [ScoreWatcher.liveCards] applies the spoiler hold, so a screen
     * the user chose to look at still cannot walk a delayed score onto their lock screen.
     */
    private fun pushCards(games: List<Game>) {
        if (!prefs.notificationsEnabled) return
        val notify = prefs.notifyKeys
        val live = games.filter { it.state == GameState.LIVE && it.involves(notify) }
        if (live.isEmpty()) return
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            // The hold writes a file and the cards are built off it; neither belongs on the
            // frame the score just landed on.
            runCatching {
                Notifier.refreshShowing(
                    app,
                    ScoreWatcher.liveCards(app, live, System.currentTimeMillis()),
                )
            }
        }
    }

    /** Fetch the last plays of one game and publish them. Cheap enough to call every poll. */
    fun loadPlays(league: League, gameId: String) {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { repo.plays(league, gameId) }
            if (list.isNotEmpty()) _plays.value = _plays.value + (gameId to list)
        }
    }

    /**
     * Fetch the scoring summary for a game, once per score. The key is the score line, so a
     * finished game is fetched once and a live one only after somebody scores.
     */
    fun loadScoring(game: Game) {
        val league = Leagues.byId(game.leagueId) ?: return
        val key = "${game.away.score}-${game.home.score}-${game.state}"
        if (_scoring.value[game.id]?.first == key) return
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) {
                repo.scoring(league, game.id, final = game.state == GameState.FINAL)
            }
            _scoring.value = _scoring.value + (game.id to (key to list))
        }
    }

    /**
     * Fetch the recap story, once per game. Only the reader opening the recap calls this:
     * the story rides along with a summary that runs to a megabyte in baseball, so it is
     * fetched on the tap rather than on the way in.
     */
    fun loadRecap(game: Game) {
        val league = Leagues.byId(game.leagueId) ?: return
        if (_recap.value.containsKey(game.id)) return
        viewModelScope.launch {
            val story = withContext(Dispatchers.IO) { repo.recap(league, game.id) }
            _recap.value = _recap.value + (game.id to story)
        }
    }

    /**
     * Leagues with at least one followed team, for the standings picker.
     *
     * Golf is left out. The PGA Tour keeps a season standing but ESPN serves it as five
     * and a half megabytes of season history, and the leaderboard of the tournament being
     * played is the standing anybody opens this app to read.
     */
    fun followedLeagues(): List<League> =
        prefs.followedLeagueIds().mapNotNull { Leagues.byId(it) }
            .filter { it.kind != SportKind.GOLF }
            .sortedBy { Leagues.all.indexOf(it) }

    fun setNotificationsEnabled(enabled: Boolean) {
        prefs.notificationsEnabled = enabled
        if (enabled) {
            ScoreWatcher.ensureArmed(getApplication())
            syncTicker()
        } else {
            ScoreWatcher.cancel(getApplication())
            LiveTicker.stop(getApplication())
        }
    }

    fun setLiveUpdatesEnabled(enabled: Boolean) {
        prefs.liveUpdatesEnabled = enabled
        // Turning it off should clear the card now, not at the end of the game.
        if (enabled) syncTicker() else {
            LiveTicker.stop(getApplication())
            LiveRelay.stop()
        }
    }

    fun setRelayEnabled(enabled: Boolean) {
        prefs.relayEnabled = enabled
        if (enabled) {
            runCatching { LiveRelay.sync(getApplication(), _feed.value.games.filter { it.involves(prefs.notifyKeys) }) }
        } else {
            LiveRelay.stop()
        }
    }

    fun setDelayEnabled(enabled: Boolean) {
        prefs.delayEnabled = enabled
    }

    fun setDelayMinutes(minutes: Int) {
        prefs.delayMinutes = minutes
    }

    fun setNotifyStarts(enabled: Boolean) {
        prefs.notifyStarts = enabled
    }

    fun setAlertBoxEnabled(enabled: Boolean) {
        prefs.alertBoxEnabled = enabled
    }

    fun setFootballLoudness(loudness: Loudness) {
        prefs.footballLoudness = loudness
    }

    fun setAlertRedZone(enabled: Boolean) {
        prefs.alertRedZone = enabled
    }

    fun setAlertClose(enabled: Boolean) {
        prefs.alertClose = enabled
    }

    fun setAlertBreaks(enabled: Boolean) {
        prefs.alertBreaks = enabled
    }

    fun setCelebrationEnabled(enabled: Boolean) {
        prefs.celebrationEnabled = enabled
    }

    /** Picking a style turns the whole thing on: choosing one is how you ask for it. */
    fun setCelebration(style: Celebration) {
        prefs.celebration = style
        prefs.celebrationEnabled = true
    }

    fun setCelebrationBuzz(enabled: Boolean) {
        prefs.celebrationBuzz = enabled
    }
}
