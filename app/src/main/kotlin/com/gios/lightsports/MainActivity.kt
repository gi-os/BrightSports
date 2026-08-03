package com.gios.lightsports

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
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
import androidx.compose.runtime.CompositionLocalProvider
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
import com.gios.lightsports.hw.LightKey
import com.gios.lightsports.hw.LightKeys
import com.gios.lightsports.hw.LocalWheelBus
import com.gios.lightsports.hw.WheelBus
import com.gios.lightsports.model.Game
import com.gios.lightsports.model.League
import com.gios.lightsports.model.StandingsRow
import com.gios.lightsports.notify.Notifier
import com.gios.lightsports.notify.ScoreWatcher
import com.gios.light.common.report.LightReport
import com.gios.light.common.report.ReportOverlay
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
import com.gios.lightsports.ui.TeamStatsScreen
import com.gios.lightsports.ui.theme.LightSportsTheme

class MainActivity : ComponentActivity() {

    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** Wheel notches on their way to whichever screen is up. */
    private val wheel = WheelBus()

    /**
     * Every hardware key arrives here first — `DecorView` hands the event to the window
     * callback before it walks the view hierarchy — so a turn of the wheel reaches the
     * list even when the team-search field holds focus.
     *
     * Both halves of the pair are consumed: one notch is a complete DOWN+UP, and letting
     * the UP through would let a text field take it as a keypress.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (LightKeys.of(event)) {
            LightKey.WheelUp -> {
                if (event.action == KeyEvent.ACTION_DOWN) wheel.send(1)
                return true
            }
            LightKey.WheelDown -> {
                if (event.action == KeyEvent.ACTION_DOWN) wheel.send(-1)
                return true
            }
            else -> Unit
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // First thing, before anything else can throw: the handler chains onto whatever is
        // already installed and only writes a file, so it is safe this early.
        LightReport.install(
            context = this,
            appName = "LightSports",
            label = "sports",
            token = BuildConfig.REPORT_TOKEN,
        )
        Notifier.ensureChannels(this)
        requestNotificationsIfNeeded()

        // A force-stop cancels every alarm the app owns, so launching is the reliable
        // moment to put the polling chain back.
        ScoreWatcher.ensureArmed(this)

        val openGameId = intent?.getStringExtra(EXTRA_GAME_ID)

        setContent {
            LightSportsTheme {
                Surface(Modifier.fillMaxSize(), color = Color.Black) {
                    // Every screen below can reach the wheel; which one answers a notch is
                    // decided down there, by whichever scroller is on screen.
                    CompositionLocalProvider(LocalWheelBus provides wheel) {
                        App(openGameId)
                        // Shake to report, the crash offer on next launch, and the app's own
                        // noticed failures. A sibling, not a wrapper — the sheet is its own
                        // window, so it covers this whether or not it contains it.
                        ReportOverlay()
                    }
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

/**
 * Three places, because editing your teams is a thing you do twice a season and the
 * action bar should not carry it for the rest of the year. It lives under settings.
 */
private const val TAB_SCORES = 0
private const val TAB_TABLE = 1
private const val TAB_MORE = 2

@Composable
private fun App(openGameId: String?) {
    val vm: SportsViewModel = viewModel()
    val feed by vm.feed.collectAsState()
    val follows by vm.follows.collectAsState()
    val muted by vm.muted.collectAsState()
    val teams by vm.teams.collectAsState()
    val standings by vm.standings.collectAsState()
    val logos by vm.logos.collectAsState()

    var tab by remember { mutableIntStateOf(TAB_SCORES) }
    var openGame by remember { mutableStateOf<Game?>(null) }
    var openLeague by remember { mutableStateOf<League?>(null) }
    var teamsOpen by remember { mutableStateOf(false) }
    var openStanding by remember { mutableStateOf<Pair<StandingsRow, League>?>(null) }

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
    val canPop = openGame != null || openLeague != null || teamsOpen || openStanding != null
    BackHandler(enabled = canPop) {
        when {
            openGame != null -> openGame = null
            openStanding != null -> openStanding = null
            openLeague != null -> openLeague = null
            else -> teamsOpen = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        val game = openGame
        val league = openLeague
        val standing = openStanding
        when {
            game != null -> LightTopBar(
                left = BarItem.Icon(R.drawable.ic_back_white, { openGame = null }, "Back"),
                title = Leagues.byId(game.leagueId)?.short,
            )
            standing != null -> LightTopBar(
                left = BarItem.Icon(R.drawable.ic_back_white, { openStanding = null }, "Back"),
                title = standing.second.short,
            )
            league != null -> LightTopBar(
                left = BarItem.Icon(R.drawable.ic_back_white, { openLeague = null }, "Back"),
                title = league.short,
            )
            teamsOpen -> LightTopBar(
                left = BarItem.Icon(R.drawable.ic_back_white, { teamsOpen = false }, "Back"),
                title = "MY TEAMS",
            )
            else -> LightTopBar(
                title = when (tab) {
                    TAB_SCORES -> "SPORTS"
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
                standing != null -> TeamStatsScreen(standing.first, standing.second)
                teamsOpen -> FollowScreen(
                    openLeague = openLeague,
                    teamsByLeague = teams,
                    follows = follows,
                    muted = muted,
                    onOpenLeague = {
                        openLeague = it
                        vm.loadTeams(it)
                    },
                    onToggle = {
                        vm.toggleFollow(it)
                        vm.refresh()
                    },
                    onToggleMute = { vm.toggleMute(it) },
                )
                tab == TAB_SCORES -> FeedScreen(
                    state = feed,
                    hasFollows = follows.isNotEmpty(),
                    logos = logos,
                    onGame = { openGame = it },
                    onEditTeams = { teamsOpen = true },
                )
                tab == TAB_TABLE -> StandingsScreen(
                    leagues = vm.followedLeagues(),
                    groups = standings,
                    followedTeamIds = follows,
                    onLeagueSelected = { vm.loadStandings(it) },
                    onTeamHeld = { row, l -> openStanding = row to l },
                )
                else -> SettingsScreen(
                    prefs = vm.prefs,
                    vm = vm,
                    version = BuildConfig.VERSION_NAME,
                    followCount = follows.size,
                    mutedCount = muted.size,
                    onOpenTeams = { teamsOpen = true },
                )
            }
        }

        // A game fills the screen on its own; the action bar would only offer places to
        // go while you are reading a line score.
        if (game == null) {
            Rule()
            fun go(target: Int) {
                tab = target
                teamsOpen = false
                openLeague = null
                openStanding = null
            }
            LightBottomBar(
                listOf(
                    // Filled star, not the outline: at two grid units on a matte
                    // greyscale panel the outline reads as a smudge.
                    BarItem.Icon(
                        R.drawable.ic_star_white,
                        { go(TAB_SCORES) },
                        "Scores",
                        selected = tab == TAB_SCORES && !teamsOpen,
                    ),
                    BarItem.Icon(
                        R.drawable.ic_large_list_white,
                        { go(TAB_TABLE) },
                        "Standings",
                        selected = tab == TAB_TABLE && !teamsOpen,
                    ),
                    BarItem.Icon(
                        R.drawable.ic_settings_white,
                        { go(TAB_MORE) },
                        "Settings",
                        selected = tab == TAB_MORE || teamsOpen,
                    ),
                ),
            )
        }
    }
}
