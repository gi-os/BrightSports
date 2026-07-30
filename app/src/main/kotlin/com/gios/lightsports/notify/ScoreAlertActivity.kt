package com.gios.lightsports.notify

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.gios.lightsports.MainActivity
import com.gios.lightsports.ui.ScoreBox
import com.gios.lightsports.ui.theme.LightSportsTheme

/**
 * The score box for the screen-off and locked cases.
 *
 * Deliberately a *floating* window sized to its content and pinned to the top, not a
 * full-screen translucent one. A full-screen window would swallow every touch on the
 * phone for as long as the box was up; this one only occupies the strip it draws, so the
 * app underneath stays tappable. That app is paused while the box is up — any activity on
 * top does that, floating or not — but remains visible.
 *
 * `showWhenLocked` + `turnScreenOn` are set both in the manifest and here: the manifest
 * attributes cover a cold start, the calls cover a re-use through [onNewIntent] where the
 * window is already built.
 *
 * No enter or exit animation (`windowAnimationStyle` is null in the theme): the box
 * appearing is the event, and animating it away only keeps the panel lit for a few more
 * frames.
 */
class ScoreAlertActivity : ComponentActivity() {

    // Named headline/detail rather than title/text: `title` collides with Activity's own
    // getTitle/setTitle and Kotlin treats that as an accidental override.
    private var headline by mutableStateOf("")
    private var detail by mutableStateOf("")
    private var gameId: String? = null
    private var leagueId: String? = null

    private val handler = Handler(Looper.getMainLooper())
    private val dismiss = Runnable { finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.apply {
            addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
            setGravity(Gravity.TOP)
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
            )
        }
        // Only one box at a time: if the screen went off while a window was up, this
        // activity is now the one showing it.
        ScoreAlertOverlay.hide()
        live = this
        read(intent)
        if (isFinishing) return
        setContent {
            LightSportsTheme {
                ScoreBox(
                    title = headline,
                    text = detail,
                    onClick = { openGame() },
                    onDismiss = { finish() },
                )
            }
        }
    }

    /** A second score while the box is up: swap the content, restart the timer. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        read(intent)
    }

    private fun read(intent: Intent?) {
        headline = intent?.getStringExtra(ScoreAlert.EXTRA_TITLE).orEmpty()
        detail = intent?.getStringExtra(ScoreAlert.EXTRA_TEXT).orEmpty().trim().take(200)
        gameId = intent?.getStringExtra(ScoreAlert.EXTRA_GAME_ID)
        leagueId = intent?.getStringExtra(ScoreAlert.EXTRA_LEAGUE_ID)
        if (headline.isBlank() && detail.isBlank()) {
            finish()
            return
        }
        handler.removeCallbacks(dismiss)
        handler.postDelayed(dismiss, VISIBLE_MS)
    }

    /** Tapping the box opens that game, exactly like tapping the notification. */
    private fun openGame() {
        val open = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        gameId?.let { open.putExtra(MainActivity.EXTRA_GAME_ID, it) }
        leagueId?.let { open.putExtra(MainActivity.EXTRA_LEAGUE_ID, it) }
        startActivity(open)
        finish()
    }

    override fun onDestroy() {
        if (live === this) live = null
        handler.removeCallbacks(dismiss)
        super.onDestroy()
    }

    companion object {
        /** Long enough to read a score, short enough not to sit in front of anything. */
        private const val VISIBLE_MS = 4_500L

        /**
         * The instance currently on screen, if any. Held so [ScoreAlertOverlay] can
         * replace it: the activity is only used when the screen was off, and once the user
         * has unlocked, a second score should arrive as a window that pauses nothing
         * rather than stack on top of this. Cleared in [onDestroy], so it isn't a leak.
         */
        @Volatile
        private var live: ScoreAlertActivity? = null

        fun dismissLive() {
            val current = live ?: return
            current.runOnUiThread { current.finish() }
        }
    }
}
