package com.gios.lightsports.notify

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import com.gios.lightsports.data.Prefs

/**
 * The alert side of a score: a buzz, and a box over whatever the phone is showing.
 *
 * This is LightChat's notifier design, carried over wholesale. The shade notification
 * [Notifier.post] raises is the *record* — it stays in LightOS's list and drives
 * LightGlance's dot — so this is purely additive and degrades to notification-only.
 *
 * Two ways of showing it, chosen on whether the phone is already awake and unlocked:
 *
 * - **Awake and unlocked → [ScoreAlertOverlay]**, a real overlay window. Nothing else is
 *   interrupted: the app underneath keeps running and every touch outside the box still
 *   reaches it. An activity can't do that — anything on top pauses what's below.
 * - **Screen off, or locked → [ScoreAlertActivity]**. An overlay window sits below the
 *   keyguard and can't wake the panel, so for the case that matters most here — a
 *   walk-off home run while the phone is face-down on a desk — only an activity with
 *   `showWhenLocked` + `turnScreenOn` will do, and the interruption is moot because there
 *   was nothing on screen to interrupt.
 *
 * Both paths need the `SYSTEM_ALERT_WINDOW` appop: for the overlay it's the obvious
 * reason, and for the activity it's because on Android 14 that appop is what exempts an
 * app from background-activity-start restrictions. LightOS has no Settings screen for it,
 * so it's adb-only and one-time:
 *
 *     adb shell appops set com.gios.lightsports SYSTEM_ALERT_WINDOW allow
 *
 * Without it the buzz still happens and the notification is still posted; only the box is
 * missing.
 */
object ScoreAlert {

    private const val TAG = "ScoreAlert"

    /**
     * One buzz per burst. Two goals inside a minute of each other, or a score and the
     * final whistle together, shouldn't feel like two separate events.
     */
    private const val BUZZ_RATE_LIMIT_MS = 1_500L

    @Volatile
    private var lastBuzz = 0L

    fun show(context: Context, entry: PendingQueue.Entry) {
        buzz(context)
        val app = context.applicationContext
        // The box is optional (Settings); the buzz above and the notification the
        // caller already posted are not. One SharedPreferences read per alert.
        if (!Prefs(app).alertBoxEnabled) {
            Log.d(TAG, "on-screen alert off; notification only")
            return
        }
        // BrightControl draws this box for every app now, off the notification posted a moment
        // ago. Drawing ours as well is the same score twice, one box on top of the other.
        if (AlertOwner.ownedElsewhere(app)) {
            Log.d(TAG, "BrightControl owns the box; notification only")
            return
        }
        if (!Settings.canDrawOverlays(app)) {
            // Expected on a phone that was never plugged into a computer; the
            // notification already went out, so this is not an error.
            Log.d(TAG, "SYSTEM_ALERT_WINDOW not granted; notification only")
            return
        }
        // Awake and unlocked: the window, so nothing the user is doing stops.
        if (awakeAndUnlocked(app)) {
            ScoreAlertOverlay.show(app, entry)
            return
        }
        val intent = Intent(app, ScoreAlertActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // Replace the box that's already up rather than stacking a second one:
            // singleTop plus this flag means a burst re-uses one activity via onNewIntent.
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            .putExtra(EXTRA_TITLE, entry.title)
            .putExtra(EXTRA_TEXT, entry.body)
            .putExtra(EXTRA_GAME_ID, entry.gameId)
            .putExtra(EXTRA_LEAGUE_ID, entry.leagueId)
        runCatching { app.startActivity(intent) }
            .onFailure { Log.w(TAG, "background activity start refused: $it") }
    }

    /**
     * Screen on *and* past the lock screen. Locked-but-on still takes the activity path:
     * an overlay window is below the keyguard, so it would be perfectly invisible.
     */
    private fun awakeAndUnlocked(context: Context): Boolean {
        val power = context.getSystemService(PowerManager::class.java) ?: return false
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        return power.isInteractive && keyguard?.isKeyguardLocked != true
    }

    /**
     * A double tick. Both notification channels have vibration disabled, so this is the
     * only buzz — one place to tune, and it still fires when the box can't be shown.
     */
    fun buzz(context: Context) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBuzz < BUZZ_RATE_LIMIT_MS) return
        lastBuzz = now
        val vibrator = context.getSystemService(VibratorManager::class.java)
            ?.defaultVibrator ?: return
        if (!vibrator.hasVibrator()) return
        // tick, gap, tick. Short enough to read as one event.
        val effect = VibrationEffect.createWaveform(
            longArrayOf(0, 30, 80, 30),
            intArrayOf(0, 180, 0, 180),
            -1,
        )
        runCatching { vibrator.vibrate(effect) }
    }

    const val EXTRA_TITLE = "alertTitle"
    const val EXTRA_TEXT = "alertText"
    const val EXTRA_GAME_ID = "alertGameId"
    const val EXTRA_LEAGUE_ID = "alertLeagueId"
}
