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
import com.gios.lightsports.model.League
import com.gios.lightsports.model.StandingsGroup
import com.gios.lightsports.model.TeamRef
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

    private val repo = SportsRepository(app)
    val prefs = Prefs(app)

    data class FeedState(
        val loading: Boolean = false,
        val sections: List<Feed.Section> = emptyList(),
        val games: List<Game> = emptyList(),
        val updatedAt: Long = 0L,
        val offline: Boolean = false,
        /** Followed teams with no fixture in the window, named for the feed's last row. */
        val idle: List<String> = emptyList(),
    )

    private val _feed = MutableStateFlow(FeedState())
    val feed: StateFlow<FeedState> = _feed.asStateFlow()

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

    fun refresh() {
        if (_feed.value.loading) return
        _feed.value = _feed.value.copy(loading = true)
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val zone = ZoneId.systemDefault()
            val (games, races) = withContext(Dispatchers.IO) { repo.followedGames(now, zone) }
            val sections = Feed.build(games, races, now, zone)
            val idle = Feed.idleFollows(prefs.follows, games, races) { key -> teamLabel(key) }
            _feed.value = FeedState(
                loading = false,
                sections = sections,
                games = games,
                idle = idle,
                updatedAt = now,
                // Followed teams but nothing came back: almost always the network,
                // and worth saying so rather than showing a bare "no games".
                offline = prefs.follows.isNotEmpty() && games.isEmpty() && races.isEmpty(),
            )
            syncTicker(games)
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
        if (enabled) syncTicker() else LiveTicker.stop(getApplication())
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
}
