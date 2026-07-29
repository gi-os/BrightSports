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
        return added
    }

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

    val effectiveDelayMillis: Long
        get() = if (delayEnabled) delayMinutes * 60_000L else 0L

    // --------------------------------------------------------------- cache

    fun getString(key: String): String? = sp.getString(key, null)

    fun putString(key: String, value: String) = sp.edit().putString(key, value).apply()

    fun getLong(key: String, default: Long = 0L): Long = sp.getLong(key, default)

    fun putLong(key: String, value: Long) = sp.edit().putLong(key, value).apply()

    companion object {
        private const val KEY_FOLLOWS = "follows"
        private const val KEY_NOTIFY = "notify"
        private const val KEY_DELAY = "delay_minutes"
        private const val KEY_DELAY_ON = "delay_enabled"
        private const val KEY_NOTIFY_STARTS = "notify_starts"
    }
}
