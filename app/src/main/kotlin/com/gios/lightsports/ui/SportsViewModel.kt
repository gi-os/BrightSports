package com.gios.lightsports.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gios.lightsports.data.Feed
import com.gios.lightsports.data.Leagues
import com.gios.lightsports.data.Prefs
import com.gios.lightsports.data.SpecialEvents
import com.gios.lightsports.data.SportsRepository
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
import com.gios.lightsports.notify.ScoreWatcher
import com.gios.lightsports.notify.TickerPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId

class SportsViewModel(app: Application) : AndroidViewModel(app) {

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 450L
    }

    private val repo = SportsRepository(app)
    val prefs = Prefs(app)

    /** A followed team with nothing in the window: its name, and where it is instead. */
    data class IdleTeam(val key: String, val label: String, val note: String? = null)

    data class FeedState(
        val loading: Boolean = false,
        val sections: List<Feed.Section> = emptyList(),
        val games: List<Game> = emptyList(),
        val updatedAt: Long = 0L,
        val offline: Boolean = false,
        /** Followed teams with no fixture in the window, for the feed's last rows. */
        val idle: List<IdleTeam> = emptyList(),
        /** Weeks away from this one: 0 is now, -1 last week, 1 next week. */
        val weekOffset: Int = 0,
        /** "WEEK 2", or "SEP 10 – 15" when the games in view carry no week number. */
        val title: String = "SPORTS",
        /** "SEP 10 – 15", plus "ALL FINAL" and "3–1 FOR YOUR TEAMS" on a week gone by. */
        val subtitle: String? = null,
    )

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
            _feed.value = state.copy(
                games = state.games.map { if (it.id == updated.id) updated else it },
                sections = state.sections.map { section ->
                    section.copy(items = section.items.map { item ->
                        if (item is Feed.Item.GameItem && item.game.id == updated.id) {
                            Feed.Item.GameItem(updated)
                        } else item
                    })
                },
                updatedAt = System.currentTimeMillis(),
            )
        }
        // A game whose plays are on screen gets its play-by-play refreshed with the score.
        if (updated.state == GameState.LIVE && _plays.value.containsKey(updated.id)) {
            Leagues.byId(updated.leagueId)?.let { loadPlays(it, updated.id) }
        }
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

    fun refresh() = refresh(_feed.value.weekOffset)

    /** Page the feed a week either way. Zero is this week. */
    fun shiftWeek(delta: Int) {
        val next = (_feed.value.weekOffset + delta).coerceIn(-4, 4)
        if (next != _feed.value.weekOffset) refresh(next)
    }

    private fun refresh(weekOffset: Int) {
        if (_feed.value.loading) return
        _feed.value = _feed.value.copy(loading = true, weekOffset = weekOffset)
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val zone = ZoneId.systemDefault()
            val shift = weekOffset * 7L
            val (games, races) = withContext(Dispatchers.IO) { repo.followedGames(now, zone, shift) }
            // A paged week widens the bucket window in that direction so the whole slate
            // is kept; the other direction stays at its usual reach.
            val back = SportsRepository.BACK_DAYS + 7L * maxOf(0, -weekOffset)
            val ahead = (SportsRepository.AHEAD_DAYS - 1) + 7L * maxOf(0, weekOffset)
            val sections = Feed.build(games, races, now, zone, backDays = back, aheadDays = ahead)
            val idleKeys = Feed.idleFollows(prefs.follows, games, races) { key -> key }
            val idle = idleKeys.mapNotNull { key -> teamLabel(key)?.let { IdleTeam(key, it) } }
            val dayMillis = 24L * 60 * 60 * 1000
            val fromMillis = now - SportsRepository.BACK_DAYS * dayMillis + shift * dayMillis
            val toMillis = now + (SportsRepository.AHEAD_DAYS - 1) * dayMillis + shift * dayMillis
            val range = Feed.weekTitle(emptyList(), fromMillis, toMillis, zone)
            val title = if (games.isEmpty()) range else Feed.weekTitle(games, fromMillis, toMillis, zone)
            val allFinal = games.isNotEmpty() && games.all { it.state == GameState.FINAL }
            val subtitle = listOfNotNull(
                range.takeIf { it != title },
                "ALL FINAL".takeIf { allFinal },
                Feed.recordLine(games, prefs.follows).takeIf { weekOffset != 0 || allFinal },
            ).joinToString(" · ").takeIf { it.isNotEmpty() }
            _feed.value = FeedState(
                loading = false,
                sections = sections,
                games = games,
                idle = idle,
                updatedAt = now,
                // Followed teams but nothing came back: almost always the network,
                // and worth saying so rather than showing a bare "no games".
                offline = prefs.follows.isNotEmpty() && games.isEmpty() && races.isEmpty(),
                weekOffset = weekOffset,
                title = title,
                subtitle = subtitle,
            )
            if (weekOffset == 0) {
                syncTicker(games)
                // The relay socket follows the feed too, so opening the app during a game
                // connects it even before the ticker's next poll does.
                runCatching { LiveRelay.sync(getApplication(), games.filter { it.involves(prefs.notifyKeys) }, now) }
            }
            // A followed football team with no game this week is on its bye, or between
            // a Monday night and the next Sunday. Its schedule says which, and what's next.
            noteIdle(idle, now, zone)
        }
    }

    /** Fill in "BYE · next vs BAL · Sun 9/20 4:25" for idle teams whose league has a schedule. */
    private suspend fun noteIdle(idle: List<IdleTeam>, now: Long, zone: ZoneId) {
        if (idle.isEmpty()) return
        val noted = idle.map { team ->
            val leagueId = team.key.substringBefore(':')
            val teamId = team.key.substringAfter(':')
            val league = Leagues.byId(leagueId) ?: return@map team
            if (league.kind != SportKind.FOOTBALL) return@map team
            val season = withContext(Dispatchers.IO) { repo.teamSeason(league, teamId) }
                ?: return@map team
            _seasons.value = _seasons.value + (team.key to season)
            val next = season.next(now)
            val onBye = season.byeWeek != null && next?.week?.let { it == season.byeWeek + 1 } == true
            val nextText = next?.let { g ->
                val home = g.home.teamId == teamId
                val other = if (home) g.away else g.home
                "next ${if (home) "vs" else "@"} ${other.abbrev} · ${Fmt.dayDate(g.startMillis, zone)} ${Fmt.time(g.startMillis, zone)}"
            }
            team.copy(note = listOfNotNull("BYE".takeIf { onBye }, nextText).joinToString(" · ").takeIf { it.isNotEmpty() })
        }
        // Only if the feed hasn't moved on under us.
        if (_feed.value.idle.map { it.key } == idle.map { it.key }) {
            _feed.value = _feed.value.copy(idle = noted)
        }
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
        if (updated == game) return updated
        val state = _feed.value
        _feed.value = state.copy(
            games = state.games.map { if (it.id == updated.id) updated else it },
            sections = state.sections.map { section ->
                section.copy(items = section.items.map { item ->
                    if (item is Feed.Item.GameItem && item.game.id == updated.id) {
                        Feed.Item.GameItem(updated)
                    } else item
                })
            },
            updatedAt = now,
        )
        if (updated.state != game.state) refresh()
        return updated
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
     * A follow key as a human name. Falls back to the league and the raw id when the
     * team list hasn't loaded — better a rough label than a team that seems to vanish.
     */
    private fun teamLabel(key: String): String? {
        val leagueId = key.substringBefore(':')
        val teamId = key.substringAfter(':')
        val league = Leagues.byId(leagueId)
        // A category is not a team, so "no game scheduled" would be nonsense for it —
        // there is no fixture list to be absent from.
        if (teamId == SpecialEvents.SUFFIX_SPECIAL || teamId == SpecialEvents.SUFFIX_CHAMPIONSHIP) {
            return null
        }
        if (teamId == "series") return league?.name ?: leagueId.uppercase()
        val name = _teams.value[leagueId]?.firstOrNull { it.teamId == teamId }?.displayName
        return name ?: "${league?.short ?: leagueId.uppercase()} $teamId"
    }

    /** Leagues with at least one followed team, for the standings picker. */
    fun followedLeagues(): List<League> =
        prefs.followedLeagueIds().mapNotNull { Leagues.byId(it) }
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
}
