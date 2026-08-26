package com.gios.lightsports.notify

import android.app.NotificationManager
import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Removes stale notifications from the shade.
 *
 * A final score is worth reading for a while, but a card that says the Knicks won
 * eighteen hours after they did is clutter. Sweeps run on every poll and on every app
 * launch, and the removal times feed into the wake-up arithmetic so a removal doesn't
 * have to wait for the next game to start before it happens.
 *
 * ### Every card is tracked when it posts, not when a game is seen ending
 *
 * The removal used to be scheduled on the FINAL *transition* — the one poll where the
 * game went from LIVE to FINAL. Miss that poll and the card was invisible to this class
 * forever: nothing else ever wrote an entry for it, and once the game rolled out of the
 * scoreboard window its snapshot went too, so the transition could never be observed
 * again. A phone that was asleep, in Doze, or simply out of signal at full time kept
 * yesterday's score on the lock screen indefinitely. Reported from a real phone as a
 * Phillies game from the day before still sitting there.
 *
 * So the clock starts when a card is *posted*, which needs no transition to be caught,
 * and every later post for the same game pushes it out. A game still in the feed and not
 * yet finished pushes it out too, so a quiet second half never loses its card. What is
 * left is exactly the intended rule: **an hour after the last thing worth saying.**
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

    /** Cancels every notification whose removal time has passed, and every orphan. */
    fun sweep(context: Context, nowMillis: Long) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val map = load()
        val due = map.filterValues { it <= nowMillis }.keys
        if (due.isNotEmpty()) {
            // Cancelling an id the user already swiped away is a no-op, so a card
            // dismissed by hand costs nothing here.
            for (key in due) manager.cancel(key.hashCode())
            save(map - due)
        }
        sweepOrphans(manager, map.keys - due, nowMillis)
    }

    /**
     * Cards in the shade that nothing is tracking.
     *
     * The tracking above can only ever clear a card it was told about, and there are two
     * ways for one to exist without that: it was posted by a build that only tracked
     * FINALs, or the file was lost. Either way nothing in this class would ever look at it
     * again, and the symptom is a score from yesterday that will not leave — which is
     * exactly how this was reported.
     *
     * So the shade itself is the second source of truth. `getActiveNotifications` returns
     * only this app's own cards and needs no permission, and every one carries the time it
     * was posted. Anything older than an hour that nothing is tracking is by definition
     * something whose hour ran out unobserved.
     *
     * Tracked ids are skipped rather than aged out, because a tracked card is one whose
     * clock is being kept topped up on purpose: a game still in progress an hour after its
     * last goal should keep the score on screen.
     *
     * The live ticker's own card is excluded by channel. It is the receipt for a foreground
     * service and is allowed to outlive anything; cancelling it would leave a service
     * running with nothing to show for it.
     */
    private fun sweepOrphans(manager: NotificationManager, tracked: Set<String>, nowMillis: Long) {
        val active = runCatching { manager.activeNotifications }.getOrNull() ?: return
        val trackedIds = tracked.mapTo(mutableSetOf()) { it.hashCode() }
        for (sbn in active) {
            if (sbn == null) continue
            if (sbn.id in trackedIds) continue
            if (sbn.notification?.channelId == Notifier.CHANNEL_LIVE) continue
            if (nowMillis - sbn.postTime < STALE_MILLIS) continue
            runCatching { manager.cancel(sbn.id) }
        }
    }

    /** Whether a card is being tracked for [key] — i.e. one was posted and not yet swept. */
    fun has(key: String): Boolean = load().containsKey(key)

    /**
     * Cancel [key]'s card now and stop tracking it.
     *
     * For a card that has stopped being true rather than one that has gone stale: the
     * "starting soon" row the moment the game starts.
     */
    fun drop(context: Context, key: String) {
        val map = load()
        if (map.remove(key) == null) return
        context.getSystemService(NotificationManager::class.java)?.cancel(key.hashCode())
        save(map)
    }

    private companion object {
        /** Matches ScoreWatcher's CLEANUP_DELAY. An orphan is a card whose hour ran out
         *  with nobody watching, so it is the same hour. */
        const val STALE_MILLIS = 60L * 60 * 1000
    }

    /** The soonest scheduled removal, for the wake-up arithmetic. Null when idle. */
    fun nextDue(): Long? = load().values.minOrNull()
}
