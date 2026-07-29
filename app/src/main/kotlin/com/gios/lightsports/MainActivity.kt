package com.gios.lightsports

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gios.lightsports.data.Leagues
import com.gios.lightsports.model.Game
import com.gios.lightsports.model.League
import com.gios.lightsports.notify.Notifier
import com.gios.lightsports.notify.ScoreWatcher
import com.gios.lightsports.ui.BarItem
import com.gios.lightsports.ui.FeedScreen
import com.gios.lightsports.ui.FollowScreen
import com.gios.lightsports.ui.GameScreen
import com.gios.lightsports.ui.LightBottomBar
import com.gios.lightsports.ui.LightTopBar
import com.gios.lightsports.ui.Rule
import com.gios.lightsports.ui.SettingsScreen
import com.gios.lightsports.ui.SportsViewModel
import com.gios.lightsports.ui.StandingsScreen
import com.gios.lightsports.ui.theme.LightSportsTheme

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

private const val TAB_SCORES = 0
private const val TAB_TEAMS = 1
private const val TAB_TABLE = 2
private const val TAB_MORE = 3

@Composable
private fun App(openGameId: String?) {
    val vm: SportsViewModel = viewModel()
    val feed by vm.feed.collectAsState()
    val follows by vm.follows.collectAsState()
    val teams by vm.teams.collectAsState()
    val standings by vm.standings.collectAsState()

    var tab by remember { mutableIntStateOf(TAB_SCORES) }
    var openGame by remember { mutableStateOf<Game?>(null) }
    var openLeague by remember { mutableStateOf<League?>(null) }

    LaunchedEffect(Unit) {
        vm.refresh()
        // The picker needs at least the abbreviations to render the "following" chips.
        for (id in vm.prefs.followedLeagueIds()) {
            Leagues.byId(id)?.let { vm.loadTeams(it) }
        }
    }

    // Tapping a score notification lands on that game, but only once the feed it came
    // from has actually loaded.
    LaunchedEffect(openGameId, feed.games.size) {
        if (openGameId != null && openGame == null) {
            openGame = vm.gameById(openGameId)
        }
    }

    // LightOS supplies the back gesture; the SDK's own screens expect it to unwind the
    // stack rather than leave the app, so it is handled wherever there is a level to
    // pop and left alone at the root.
    val canPop = openGame != null || openLeague != null
    BackHandler(enabled = canPop) {
        if (openGame != null) openGame = null else openLeague = null
    }

    Column(Modifier.fillMaxSize()) {
        val game = openGame
        val league = openLeague
        when {
            game != null -> LightTopBar(
                left = BarItem.Icon(R.drawable.ic_back_white, { openGame = null }, "Back"),
                title = Leagues.byId(game.leagueId)?.short,
            )
            league != null -> LightTopBar(
                left = BarItem.Icon(R.drawable.ic_back_white, { openLeague = null }, "Back"),
                title = league.short,
            )
            else -> LightTopBar(
                title = when (tab) {
                    TAB_SCORES -> "SPORTS"
                    TAB_TEAMS -> "MY TEAMS"
                    TAB_TABLE -> "STANDINGS"
                    else -> "SETTINGS"
                },
                right = if (tab == TAB_SCORES) {
                    BarItem.Icon(R.drawable.ic_refresh_white, { vm.refresh() }, "Refresh")
                } else null,
            )
        }
        Rule()

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                game != null -> GameScreen(game)
                tab == TAB_SCORES -> FeedScreen(
                    state = feed,
                    hasFollows = follows.isNotEmpty(),
                    onGame = { openGame = it },
                    onEditTeams = { tab = TAB_TEAMS },
                )
                tab == TAB_TEAMS -> FollowScreen(
                    openLeague = openLeague,
                    teamsByLeague = teams,
                    follows = follows,
                    onOpenLeague = {
                        openLeague = it
                        vm.loadTeams(it)
                    },
                    onToggle = {
                        vm.toggleFollow(it)
                        vm.refresh()
                    },
                )
                tab == TAB_TABLE -> StandingsScreen(
                    leagues = vm.followedLeagues(),
                    groups = standings,
                    followedTeamIds = follows,
                    onLeagueSelected = { vm.loadStandings(it) },
                )
                else -> SettingsScreen(vm.prefs, vm, BuildConfig.VERSION_NAME)
            }
        }

        // A game fills the screen on its own; the action bar would only offer places to
        // go while you are reading a line score.
        if (game == null) {
            Rule()
            LightBottomBar(
                listOf(
                    BarItem.Icon(
                        R.drawable.ic_list_white,
                        { tab = TAB_SCORES; openLeague = null },
                        "Scores",
                        selected = tab == TAB_SCORES,
                    ),
                    BarItem.Icon(
                        R.drawable.ic_star_white,
                        { tab = TAB_TEAMS },
                        "My teams",
                        selected = tab == TAB_TEAMS,
                    ),
                    BarItem.Icon(
                        R.drawable.ic_large_list_white,
                        { tab = TAB_TABLE; openLeague = null },
                        "Standings",
                        selected = tab == TAB_TABLE,
                    ),
                    BarItem.Icon(
                        R.drawable.ic_settings_white,
                        { tab = TAB_MORE; openLeague = null },
                        "Settings",
                        selected = tab == TAB_MORE,
                    ),
                ),
            )
        }
    }
}
