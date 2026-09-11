package com.gios.lightsports.notify

import com.gios.lightsports.model.Game
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The score the live card is allowed to show, with the spoiler delay applied to it.
 *
 * The delay setting says "hold score **alerts**", and that is what it did to a buzz: an alert
 * waits its five minutes in [PendingQueue] and then goes off carrying the score. The ongoing
 * card read the same setting and did something else entirely — it drew no score at all, for the
 * whole game, with the delay on by default. A card that never says the score is not a delayed
 * card. It is a card with the one thing you wanted taken out of it.
 *
 * So the card shows the score **as it stood [delay] minutes ago**. A touchdown is on the lock
 * screen five minutes after it happened, which is the same moment the alert for it fires, and a
 * game nobody has scored in reads exactly as live.
 *
 * Kept as a short list of samples per game rather than one held value, because several scores
 * can land inside one window and the card has to walk through them in order rather than jump to
 * the newest. Pure, so the walk is tested without a phone; the file is the same
 * write-the-whole-thing JSON as every other small store here.
 */
object ScoreHold {

    /** A score, and the moment the phone first saw it. */
    data class Sample(val at: Long, val away: Int, val home: Int)

    /**
     * What the card draws. [current] is false while an unreleased score is waiting behind this
     * one, which is how the card knows to leave the situation line off: the down and distance
     * belong to now, and now is what the delay is holding back.
     */
    data class Shown(val away: Int, val home: Int, val current: Boolean)

    /** Enough to walk a scoring flurry through; anything older has long since been released. */
    const val MAX_SAMPLES = 12

    /**
     * The newest sample old enough to show.
     *
     * Falls back to the **oldest** sample held, which is the first score the phone ever saw for
     * this game. That is deliberate and it matches the alert rule: a game seen for the first
     * time never alerts, because nothing about it is news yet. Holding it back instead would
     * leave a blank card for the first five minutes of every game the ticker picks up.
     */
    fun visible(samples: List<Sample>, delayMs: Long, now: Long): Shown? {
        if (samples.isEmpty()) return null
        val released = samples.lastOrNull { it.at <= now - delayMs } ?: samples.first()
        return Shown(released.away, released.home, current = released === samples.last())
    }

    /**
     * Add what the game says now, and drop what nobody will need again.
     *
     * A score that has not changed keeps the timestamp it arrived with — the delay is measured
     * from the moment it became true, not from the last time the ticker looked. Everything
     * before the newest already-released sample is dropped: that sample is the fallback and the
     * ones behind it can never be drawn again.
     */
    fun record(samples: List<Sample>, sample: Sample, delayMs: Long): List<Sample> {
        val last = samples.lastOrNull()
        val next = if (last != null && last.away == sample.away && last.home == sample.home) {
            samples
        } else {
            samples + sample
        }
        val keepFrom = next.indexOfLast { it.at <= sample.at - delayMs }.coerceAtLeast(0)
        return next.drop(keepFrom).takeLast(MAX_SAMPLES)
    }

    /**
     * Roll the whole store forward and answer for every game at once.
     *
     * One read and one write per tick. Games that are no longer live fall out of the file with
     * it, so a Sunday does not leave a season behind.
     */
    fun release(file: File, games: List<Game>, delayMs: Long, now: Long): Map<String, Shown> {
        val stored = read(file)
        val next = mutableMapOf<String, List<Sample>>()
        val shown = mutableMapOf<String, Shown>()
        for (game in games) {
            val samples = record(
                stored[game.id].orEmpty(),
                Sample(now, game.away.score ?: 0, game.home.score ?: 0),
                delayMs,
            )
            next[game.id] = samples
            visible(samples, delayMs, now)?.let { shown[game.id] = it }
        }
        runCatching { write(file, next) }
        return shown
    }

    private fun read(file: File): Map<String, List<Sample>> = runCatching {
        if (!file.exists()) return emptyMap()
        val root = JSONObject(file.readText())
        root.keys().asSequence().associateWith { key ->
            val arr = root.optJSONArray(key) ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONArray(i)?.takeIf { it.length() >= 3 }?.let {
                    Sample(it.optLong(0), it.optInt(1), it.optInt(2))
                }
            }
        }
    }.getOrDefault(emptyMap())

    private fun write(file: File, samples: Map<String, List<Sample>>) {
        val root = JSONObject()
        for ((id, list) in samples) {
            val arr = JSONArray()
            for (s in list) {
                arr.put(JSONArray().put(s.at).put(s.away).put(s.home))
            }
            root.put(id, arr)
        }
        file.writeText(root.toString())
    }
}
