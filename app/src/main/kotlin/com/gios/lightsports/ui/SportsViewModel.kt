package com.gios.lightsports.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gios.lightsports.data.Feed
import com.gios.lightsports.data.Leagues
import com.gios.lightsports.data.Prefs
import com.gios.lightsports.data.SportsRepository
import com.gios.lightsports.model.Game
import com.gios.lightsports.model.League
import com.gios.lightsports.model.StandingsGroup
import com.gios.lightsports.model.TeamRef
import com.gios.lightsports.notify.ScoreWatcher
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
    )

    private val _feed = MutableStateFlow(FeedState())
    val feed: StateFlow<FeedState> = _feed.asStateFlow()

    private val _follows = MutableStateFlow(prefs.follows)
    val follows: StateFlow<Set<String>> = _follows.asStateFlow()

    private val _teams = MutableStateFlow<Map<String, List<TeamRef>>>(emptyMap())
    val teams: StateFlow<Map<String, List<TeamRef>>> = _teams.asStateFlow()

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
            _feed.value = FeedState(
                loading = false,
                sections = sections,
                games = games,
                updatedAt = now,
                // Followed teams but nothing came back: almost always the network,
                // and worth saying so rather than showing a bare "no games".
                offline = prefs.follows.isNotEmpty() && games.isEmpty() && races.isEmpty(),
            )
        }
    }

    fun loadTeams(league: League) {
        if (_teams.value.containsKey(league.id)) return
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { repo.teams(league) }
            _teams.value = _teams.value + (league.id to list)
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
        // Following the first team is what turns the background poll on at all.
        ScoreWatcher.ensureArmed(getApplication())
    }

    fun gameById(id: String): Game? = _feed.value.games.firstOrNull { it.id == id }

    /** Leagues with at least one followed team, for the standings picker. */
    fun followedLeagues(): List<League> =
        prefs.followedLeagueIds().mapNotNull { Leagues.byId(it) }
            .sortedBy { Leagues.all.indexOf(it) }

    fun setNotificationsEnabled(enabled: Boolean) {
        prefs.notificationsEnabled = enabled
        if (enabled) ScoreWatcher.ensureArmed(getApplication())
        else ScoreWatcher.cancel(getApplication())
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
}
