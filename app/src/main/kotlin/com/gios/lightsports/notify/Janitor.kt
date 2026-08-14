package com.gios.lightsports.notify

import android.app.NotificationManager
import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Removes stale notifications from the shade.
 *
 * A final score is worth reading for a while, but a card that says the Knicks won
 * eighteen hours after they did is clutter. Every game observed going FINAL gets a
 * removal time an hour out; sweeps run on every poll, and the removal times feed
 * into the wake-up arithmetic so a removal doesn't have to wait for the next game
 * to start before it happens.
 *
 * The map is a file for the same reason the pending queue is: the process that
 * schedules a removal is a broadcast receiver that dies seconds later, and the
 * entry has to survive until a later wake-up acts on it.
 */
class Janitor(private val file: File) {

    private fun load(): MutableMap<String, Long> {
        val text = runCatching { file.readText() }.getOrNull() ?: return mutableMapOf()
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return mutableMapOf()
        val out = mutableMapOf<String, Long>()
        for (key in root.keys()) out[key] = root.optLong(key)
        return out
    }

    private fun save(map: Map<String, Long>) {
        val root = JSONObject()
        for ((key, at) in map) root.put(key, at)
        runCatching { file.writeText(root.toString()) }
    }

    /**
     * (Re)schedules the notification keyed by [key] to be removed at [cancelAt].
     * Scheduling the same key again moves its removal time — a FINAL that posts
     * late because of the spoiler delay pushes the removal out with it, so the
     * delayed card still gets its full hour in the shade.
     */
    fun schedule(key: String, cancelAt: Long) {
        val map = load()
        map[key] = cancelAt
        save(map)
    }

    /** Cancels every notification whose removal time has passed. */
    fun sweep(context: Context, nowMillis: Long) {
        val map = load()
        if (map.isEmpty()) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val due = map.filterValues { it <= nowMillis }.keys
        if (due.isEmpty()) return
        // Cancelling an id the user already swiped away is a no-op, so a card
        // dismissed by hand costs nothing here.
        for (key in due) manager.cancel(key.hashCode())
        save(map - due)
    }

    /** The soonest scheduled removal, for the wake-up arithmetic. Null when idle. */
    fun nextDue(): Long? = load().values.minOrNull()
}
