package com.gios.lightsports.notify

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.gios.lightsports.anim.Buzz

/**
 * The one motor, and the one thing allowed to be running it.
 *
 * There are two callers now and they can arrive within a second of each other: the alert's
 * double tick, raised from the poll or the relay, and a celebration, raised from the screen
 * the user is looking at. Fired independently they overlap into a mush that is neither, and
 * on a phone the buzz is often the *only* signal — the panel may be dark and in a pocket.
 *
 * So both go through here, and here keeps one clock. A pattern in flight owns the motor for
 * its own length; anything arriving underneath it is dropped rather than queued, because a
 * celebration that buzzes two seconds after the goal is worse than one that does not buzz.
 *
 * Priority decides what happens when two want it at once, and the rule is that the more
 * specific thing wins: a celebration knows which team scored and what it looked like on the
 * screen; the alert tick is the same two taps for everything. So a celebration takes the
 * motor off a tick, and a tick never takes it off a celebration.
 */
object Buzzer {

    /**
     * One buzz per burst. Two goals inside a minute of each other, or a score and the final
     * whistle together, should not feel like two separate events.
     */
    private const val RATE_LIMIT_MS = 1_500L

    /** Lower wins nothing; higher takes the motor. */
    const val PRIORITY_TICK = 0
    const val PRIORITY_CELEBRATION = 1

    @Volatile private var busyUntil = 0L
    @Volatile private var busyPriority = PRIORITY_TICK

    /**
     * Run a waveform, unless something at least as important is already running one.
     *
     * @param holdMillis how long to hold the motor after this pattern ends. The pattern's
     *   own length is not enough on its own: Mortar's beats stop a second before the sparks
     *   do, and a tick landing in that second would read as part of the celebration.
     * @return whether it actually played, which is what the caller needs to know before
     *   claiming it has told the user anything.
     */
    fun play(
        context: Context,
        timings: LongArray,
        amplitudes: IntArray,
        priority: Int,
        holdMillis: Long,
    ): Boolean {
        if (timings.isEmpty()) return false
        val now = SystemClock.elapsedRealtime()
        // Already running something at least as important. Dropped, never queued.
        if (now < busyUntil && priority <= busyPriority) return false
        // Silent means silent. Vibrate mode does not: that is the mode that asks for
        // exactly this.
        val audio = context.getSystemService(AudioManager::class.java)
        if (audio?.ringerMode == AudioManager.RINGER_MODE_SILENT) return false
        val vibrator = vibrator(context) ?: return false
        if (!vibrator.hasVibrator()) return false

        val effect = if (vibrator.hasAmplitudeControl()) {
            VibrationEffect.createWaveform(timings, amplitudes, -1)
        } else {
            // The same rhythm at one strength. The array already alternates off and on
            // starting with off, which is what this overload means by a waveform, so the
            // beats land in the right places and only the shape is lost.
            VibrationEffect.createWaveform(timings, -1)
        }
        val played = runCatching { vibrator.vibrate(effect) }.isSuccess
        if (played) {
            busyUntil = now + maxOf(RATE_LIMIT_MS, timings.sum() + holdMillis)
            busyPriority = priority
        }
        return played
    }

    /** A celebration's waveform, held for the length of the picture it belongs to. */
    fun play(context: Context, pattern: Buzz.Pattern, holdMillis: Long): Boolean = play(
        context,
        pattern.timings,
        pattern.amplitudes,
        PRIORITY_CELEBRATION,
        (holdMillis - pattern.totalMillis).coerceAtLeast(0L),
    )

    private fun vibrator(context: Context): Vibrator? =
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
}
