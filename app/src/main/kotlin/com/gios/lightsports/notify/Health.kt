package com.gios.lightsports.notify

import android.app.AlarmManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.gios.lightsports.data.Prefs

/**
 * Which of the two delivery paths actually ran, and how long ago.
 *
 * This file exists because of a whole class of bug the app had no way to show. There
 * are two ways a score reaches the phone -- the alarm chain, which survives Doze and is
 * throttled to roughly nine minutes, and [LiveTicker], which is not throttled and polls
 * every thirty to sixty seconds. The second one can be refused by the system at the
 * moment it is needed most, from the background, on a sleeping phone. When that
 * happened the app behaved exactly as it had before the ticker was written: no crash,
 * no report, no visible difference, and alerts arriving a quarter of an hour late.
 *
 * Everything here is a fact about the phone, not a preference. Nothing in it changes
 * behaviour; it changes what the settings screen can tell you when a goal shows up
 * after the highlights do.
 */
object Health {

    const val SOURCE_ALARM = "alarm"
    const val SOURCE_TICKER = "ticker"

    private const val KEY_POLL_AT = "health_poll_at"
    private const val KEY_POLL_SOURCE = "health_poll_source"
    private const val KEY_TICKER_STATE = "health_ticker_state"
    private const val KEY_TICKER_AT = "health_ticker_at"

    private const val STATE_RUNNING = "running"
    private const val STATE_REFUSED = "refused"

    /** How long a recorded ticker refusal is still worth mentioning. */
    private const val STALE = 6L * 60 * 60 * 1000

    fun recordPoll(context: Context, source: String) {
        val prefs = Prefs(context)
        prefs.putLong(KEY_POLL_AT, System.currentTimeMillis())
        prefs.putString(KEY_POLL_SOURCE, source)
    }

    /**
     * @param allowed whether the system let the foreground service up. False is the
     * interesting case and the reason this is written down at all.
     */
    fun recordTicker(context: Context, allowed: Boolean) {
        val prefs = Prefs(context)
        prefs.putString(KEY_TICKER_STATE, if (allowed) STATE_RUNNING else STATE_REFUSED)
        prefs.putLong(KEY_TICKER_AT, System.currentTimeMillis())
    }

    /**
     * Whether this app may schedule an exact alarm.
     *
     * Not about precision. An exact alarm's broadcast is exempt from the background
     * foreground-service start restriction, which is the only thing that lets the alarm
     * chain hand over to the ticker while the screen is off. See [ScoreWatcher.armAt].
     */
    fun exactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val manager = context.getSystemService(AlarmManager::class.java) ?: return false
        // try/catch rather than runCatching: lint's version-check analysis does not
        // follow the guard above through a lambda, and reports canScheduleExactAlarms
        // as an unguarded API 31 call.
        return try {
            manager.canScheduleExactAlarms()
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * Whether the user has taken this app out of battery optimisation.
     *
     * An exempt app is not in Doze, so the nine-minute alarm floor stops existing and
     * the chain runs at its stated two minutes even if the ticker never comes up. It is
     * the belt to the ticker's braces, and on a phone whose launcher hides the Settings
     * screen for it, the only way to grant it may be adb.
     */
    fun dozeExempt(context: Context): Boolean = runCatching {
        context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true
    }.getOrDefault(false)

    /**
     * One line for the settings screen, in the order the user cares about: how fast
     * scores can arrive right now, then when the last check actually happened.
     */
    fun summary(context: Context): String {
        val prefs = Prefs(context)
        val now = System.currentTimeMillis()
        val at = prefs.getLong(KEY_POLL_AT)
        val source = prefs.getString(KEY_POLL_SOURCE)

        val cadence = when {
            LiveTicker.running || (source == SOURCE_TICKER && now - at < 5L * 60_000) ->
                "Live — every 30–60s"
            dozeExempt(context) -> "Alarms — every 2 min"
            else -> "Alarms — up to 9 min"
        }
        val last = if (at <= 0L) "no check yet" else "last check ${ago(now - at)}"
        return "$cadence · $last"
    }

    /**
     * The line under it: what is standing between the phone and a thirty-second poll.
     * Empty when nothing is, which is the answer most of the time and should read that
     * way rather than as a fourth thing to worry about.
     */
    fun advice(context: Context): String {
        val prefs = Prefs(context)
        val refusedAt = prefs.getLong(KEY_TICKER_AT)
        val refused = prefs.getString(KEY_TICKER_STATE) == STATE_REFUSED &&
            System.currentTimeMillis() - refusedAt < STALE
        return when {
            !exactAlarms(context) ->
                "Exact alarms are off, so the phone cannot start the live check while " +
                    "asleep. Everything falls back to the nine-minute floor."
            refused ->
                "The system refused the live check the last time a game started. " +
                    "Taking this app out of battery optimisation fixes it for good."
            !dozeExempt(context) ->
                "Still in battery optimisation. That is fine while the live check runs, " +
                    "and it is the nine-minute floor whenever it cannot."
            else -> "Nothing is holding it back."
        }
    }

    private fun ago(millis: Long): String {
        val seconds = millis / 1000
        return when {
            seconds < 90 -> "${seconds}s ago"
            seconds < 90 * 60 -> "${seconds / 60} min ago"
            else -> "${seconds / 3600}h ago"
        }
    }
}
