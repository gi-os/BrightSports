package com.gios.lightsports

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gios.lightsports.data.Leagues
import com.gios.lightsports.model.Game
import com.gios.lightsports.notify.Notifier
import com.gios.lightsports.notify.ScoreWatcher
import com.gios.lightsports.ui.FeedScreen
import com.gios.lightsports.ui.FollowScreen
import com.gios.lightsports.ui.GameScreen
import com.gios.lightsports.ui.Rule
import com.gios.lightsports.ui.SettingsScreen
import com.gios.lightsports.ui.SportsViewModel
import com.gios.lightsports.ui.StandingsScreen
import com.gios.lightsports.ui.TabBar
import com.gios.lightsports.ui.theme.Dim
import com.gios.lightsports.ui.theme.LightSportsTheme
import com.gios.lightsports.util.Fmt

class MainActivity : ComponentActivity() {

    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notifier.ensureChannels(this)
        requestNotificationsIfNeeded()

        // A force-stop cancels every alarm the app owns, so launching is the reliable
        // moment to put the polling chain back.
        ScoreWatcher.ensureArmed(this)

        val openGameId = intent?.getStringExtra(EXTRA_GAME_ID)

        setContent {
            LightSportsTheme {
                Surface(Modifier.fillMaxSize(), color = Color.Black) {
                    App(openGameId)
                }
            }
        }
    }

    private fun requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    companion object {
        const val EXTRA_GAME_ID = "game_id"
        const val EXTRA_LEAGUE_ID = "league_id"
    }
}

@Composable
private fun App(openGameId: String?) {
    val vm: SportsViewModel = viewModel()
    val feed by vm.feed.collectAsState()
    val follows by vm.follows.collectAsState()
    val teams by vm.teams.collectAsState()
    val standings by vm.standings.collectAsState()

    var tab by remember { mutableIntStateOf(0) }
    var openGame by remember { mutableStateOf<Game?>(null) }

    LaunchedEffect(Unit) {
        vm.refresh()
        // The picker needs at least the abbreviations to render the "following" chips.
        for (id in vm.prefs.followedLeagueIds()) {
            Leagues.byId(id)?.let { vm.loadTeams(it) }
        }
    }

    // Tapping a score notification lands on that game, but only once the feed it
    // came from has actually loaded.
    LaunchedEffect(openGameId, feed.games.size) {
        if (openGameId != null && openGame == null) {
            openGame = vm.gameById(openGameId)
        }
    }

    val game = openGame
    if (game != null) {
        Column(Modifier.fillMaxSize()) {
            TopBar(title = "< BACK") { openGame = null }
            GameScreen(game)
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        Header(tab = tab, updatedAt = feed.updatedAt, loading = feed.loading) { vm.refresh() }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                0 -> FeedScreen(
                    state = feed,
                    hasFollows = follows.isNotEmpty(),
                    onGame = { openGame = it },
                    onEditTeams = { tab = 1 },
                )
                1 -> FollowScreen(
                    teamsByLeague = teams,
                    follows = follows,
                    onLeagueOpened = { vm.loadTeams(it) },
                    onToggle = {
                        vm.toggleFollow(it)
                        vm.refresh()
                    },
                )
                2 -> StandingsScreen(
                    leagues = vm.followedLeagues(),
                    groups = standings,
                    followedTeamIds = follows,
                    onLeagueSelected = { vm.loadStandings(it) },
                )
                else -> SettingsScreen(vm.prefs, vm, BuildConfig.VERSION_NAME)
            }
        }
        TabBar(tab, listOf("SCORES", "TEAMS", "TABLE", "MORE")) { tab = it }
    }
}

@Composable
private fun Header(tab: Int, updatedAt: Long, loading: Boolean, onRefresh: () -> Unit) {
    val title = when (tab) {
        0 -> "SPORTS"
        1 -> "MY TEAMS"
        2 -> "STANDINGS"
        else -> "SETTINGS"
    }
    Column {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                modifier = Modifier.weight(1f),
            )
            if (tab == 0) {
                Text(
                    if (loading) "…" else Fmt.ago(updatedAt, System.currentTimeMillis())
                        .ifEmpty { "REFRESH" },
                    style = MaterialTheme.typography.labelSmall,
                    color = Dim,
                    modifier = Modifier.clickable(onClick = onRefresh),
                )
            }
        }
        Rule()
    }
}

@Composable
private fun TopBar(title: String, onClick: () -> Unit) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
                .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 12.dp),
        )
        Rule()
    }
}
