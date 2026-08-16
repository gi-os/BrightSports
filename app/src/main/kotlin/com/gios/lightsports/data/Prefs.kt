package com.gios.lightsports.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Followed teams and settings. SharedPreferences rather than Room: the whole state
 * is a set of strings and four scalars, and skipping Room keeps KSP out of the build.
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("lightsports", Context.MODE_PRIVATE)

    // ------------------------------------------------------------- follows

    /** Keys are `leagueId:teamId`; team ids repeat across leagues. */
    var follows: Set<String>
        get() = sp.getStringSet(KEY_FOLLOWS, emptySet()) ?: emptySet()
        set(value) = sp.edit().putStringSet(KEY_FOLLOWS, value).apply()

    fun isFollowing(key: String): Boolean = key in follows

    fun toggleFollow(key: String): Boolean {
        val next = follows.toMutableSet()
        val added = next.add(key)
        if (!added) next.remove(key)
        follows = next
        // Dropping a team drops its silence with it, so re-following later doesn't
        // silently inherit a decision made months ago.
        if (!added && key in muted) muted = muted - key
        return added
    }

    // --------------------------------------------------------------- silenced

    /**
     * Followed teams that shouldn't interrupt. A subset of [follows] — they stay in the
     * feed and the standings, they just don't buzz.
     */
    var muted: Set<String>
        get() = sp.getStringSet(KEY_MUTED, emptySet()) ?: emptySet()
        set(value) = sp.edit().putStringSet(KEY_MUTED, value).apply()

    fun isMuted(key: String): Boolean = key in muted

    fun toggleMute(key: String): Boolean {
        val next = muted.toMutableSet()
        val added = next.add(key)
        if (!added) next.remove(key)
        muted = next
        return added
    }

    /**
     * The keys notifications are allowed to fire for.
     *
     * Subtraction rather than a separate list, and it's why a derby still buzzes: a game
     * is checked against this set, so Yankees–Mets with the Mets silenced still matches
     * on the Yankees. Silencing one team never silences a game the other is in.
     */
    val notifyKeys: Set<String> get() = follows - muted

    /** League ids that have at least one followed team — the only ones worth fetching. */
    fun followedLeagueIds(): List<String> =
        follows.mapNotNull { it.substringBefore(':').takeIf { id -> id.isNotEmpty() } }.distinct()

    // ------------------------------------------------------------ settings

    var notificationsEnabled: Boolean
        get() = sp.getBoolean(KEY_NOTIFY, true)
        set(v) = sp.edit().putBoolean(KEY_NOTIFY, v).apply()

    /**
     * Score notifications are held back this long before they're posted, so the
     * phone doesn't spoil a stream running a minute or two behind live.
     */
    var delayMinutes: Int
        get() = sp.getInt(KEY_DELAY, 5)
        set(v) = sp.edit().putInt(KEY_DELAY, v).apply()

    var delayEnabled: Boolean
        get() = sp.getBoolean(KEY_DELAY_ON, true)
        set(v) = sp.edit().putBoolean(KEY_DELAY_ON, v).apply()

    /** Notify when a followed team's game is about to start. */
    var notifyStarts: Boolean
        get() = sp.getBoolean(KEY_NOTIFY_STARTS, true)
        set(v) = sp.edit().putBoolean(KEY_NOTIFY_STARTS, v).apply()

    /**
     * Whether to run the foreground ticker while a followed game is in progress.
     *
     * On, the app polls every 30–60 seconds during a game instead of waiting out Doze's
     * nine-minute floor, and shows a quiet ongoing card for as long as it does. Off is
     * the behaviour every release before this one had: alarms only, slower, and nothing
     * in the shade between alerts.
     */
    var liveUpdatesEnabled: Boolean
        get() = sp.getBoolean(KEY_LIVE_UPDATES, true)
        set(v) = sp.edit().putBoolean(KEY_LIVE_UPDATES, v).apply()

    /**
     * Whether a score puts the box up over whatever the phone is showing (see
     * `ScoreAlert`). Off keeps the buzz and the shade notification — the record — but
     * never draws over the screen or wakes it. On by default.
     */
    var alertBoxEnabled: Boolean
        get() = sp.getBoolean(KEY_ALERT_BOX, true)
        set(v) = sp.edit().putBoolean(KEY_ALERT_BOX, v).apply()

    val effectiveDelayMillis: Long
        get() = if (delayEnabled) delayMinutes * 60_000L else 0L

    // --------------------------------------------------------------- cache

    fun getString(key: String): String? = sp.getString(key, null)

    fun putString(key: String, value: String) = sp.edit().putString(key, value).apply()

    fun getLong(key: String, default: Long = 0L): Long = sp.getLong(key, default)

    fun putLong(key: String, value: Long) = sp.edit().putLong(key, value).apply()

    companion object {
        private const val KEY_FOLLOWS = "follows"
        private const val KEY_MUTED = "muted"
        private const val KEY_NOTIFY = "notify"
        private const val KEY_DELAY = "delay_minutes"
        private const val KEY_DELAY_ON = "delay_enabled"
        private const val KEY_NOTIFY_STARTS = "notify_starts"
        private const val KEY_ALERT_BOX = "alert_box"
        private const val KEY_LIVE_UPDATES = "live_updates"
    }
}
