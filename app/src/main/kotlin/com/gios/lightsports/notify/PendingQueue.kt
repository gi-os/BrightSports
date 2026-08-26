package com.gios.lightsports.notify

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Alerts waiting out the delay before they're posted.
 *
 * A score notification that beats the broadcast spoils the game, so every alert is
 * held for the configured delay and only posted once due. The queue is a file, not
 * memory: the process that detects a score is usually a broadcast receiver that dies
 * seconds later, and the alert has to survive until a later wake-up posts it.
 */
class PendingQueue(private val file: File) {

    data class Entry(
        val dueAt: Long,
        val gameId: String,
        val leagueId: String,
        val kind: ScoreDiff.Kind,
        val title: String,
        val body: String,
        /**
         * When this stops being true, or 0 for "never".
         *
         * Separate from [dueAt], and it exists because *due* and *still true* are not the
         * same question. "Starts in 15 minutes" is due immediately and stops being true at
         * kickoff — and the gap between those two is every way a phone can fail to run a
         * poll: Doze, no signal, a screen that stayed off. The entry sat in the queue and
         * posted on the next wake-up, which announced that a game was about to start while
         * it was in the second inning, next to the card carrying its score.
         */
        val expiresAt: Long = 0L,
    )

    fun load(): List<Entry> {
        val text = runCatching { file.readText() }.getOrNull() ?: return emptyList()
        val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val kind = runCatching { ScoreDiff.Kind.valueOf(o.optString("kind")) }.getOrNull()
                ?: return@mapNotNull null
            Entry(
                dueAt = o.optLong("dueAt"),
                gameId = o.optString("gameId"),
                leagueId = o.optString("leagueId"),
                kind = kind,
                title = o.optString("title"),
                body = o.optString("body"),
                expiresAt = o.optLong("expiresAt"),
            )
        }
    }

    fun save(entries: List<Entry>) {
        val array = JSONArray()
        for (e in entries) {
            array.put(
                JSONObject()
                    .put("dueAt", e.dueAt)
                    .put("gameId", e.gameId)
                    .put("leagueId", e.leagueId)
                    .put("kind", e.kind.name)
                    .put("title", e.title)
                    .put("body", e.body)
                    .put("expiresAt", e.expiresAt),
            )
        }
        runCatching { file.writeText(array.toString()) }
    }

    fun add(entries: List<Entry>) {
        if (entries.isEmpty()) return
        save(prune(load() + entries))
    }

    /**
     * Split into what should be posted now and what keeps waiting.
     *
     * Supersedes earlier alerts for the same game: if two goals go in during a five
     * minute delay, one notification carrying the current score is the useful thing,
     * not two stale ones. A final always wins over a score from the same game.
     *
     * Anything past its [Entry.expiresAt] is dropped rather than posted.
     */
    fun takeDue(nowMillis: Long): Pair<List<Entry>, List<Entry>> {
        val all = prune(load())
        val (due, waiting) = all.partition { it.dueAt <= nowMillis }
        val collapsed = due
            // Dropped rather than posted late. See [Entry.expiresAt] -- a reminder about a
            // game that has already started is worse than no reminder, because it is wrong.
            .filter { it.expiresAt == 0L || nowMillis < it.expiresAt }
            .groupBy { it.gameId }
            .values
            .map { forGame ->
                forGame.firstOrNull { it.kind == ScoreDiff.Kind.FINAL } ?: forGame.last()
            }
            .sortedBy { it.dueAt }
        save(waiting)
        return collapsed to waiting
    }

    /** Anything older than an hour past due is no longer news. */
    private fun prune(entries: List<Entry>): List<Entry> {
        val cutoff = System.currentTimeMillis() - STALE_MILLIS
        return entries.filter { it.dueAt > cutoff }.takeLast(MAX_ENTRIES)
    }

    fun earliestDueAt(): Long? = load().minOfOrNull { it.dueAt }

    fun clear() = save(emptyList())

    companion object {
        private const val STALE_MILLIS = 60L * 60 * 1000
        private const val MAX_ENTRIES = 100
    }
}
