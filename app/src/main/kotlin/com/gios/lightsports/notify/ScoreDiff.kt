package com.gios.lightsports.notify

import com.gios.lightsports.model.Game
import com.gios.lightsports.model.GameState
import com.gios.lightsports.model.Loudness

/**
 * What changed between two polls of the same game, and whether it is worth a
 * notification. Pure, and unit tested — getting this wrong means either a silent
 * app or a phone that buzzes forty times during a basketball game.
 */
object ScoreDiff {

    enum class Kind { SOON, START, SCORE, PERIOD, FINAL, OFF, RESUMED }

    /** The minimum of a game needed to tell what changed since last time. */
    data class Snapshot(
        val gameId: String,
        val leagueId: String,
        val state: GameState,
        val home: Int?,
        val away: Int?,
        val period: Int,
        val startMillis: Long = 0L,
        /** The provider's status enum, for spotting halftime and the end of a period. */
        val statusName: String? = null,
        /** The provider's own words, the second signal for the same thing. */
        val statusDetail: String = "",
        /**
         * The last period whose end was announced. Halftime lasts fifteen minutes and the
         * poll runs every two, so without this the same interval is reported seven times.
         */
        val markedPeriod: Int = 0,
        /**
         * Whether the "starting soon" alert has already gone out for this game. Without
         * it, every poll inside the lead window would fire another one — seven or eight
         * reminders for one kickoff.
         */
        val soonSent: Boolean = false,
        /**
         * How many polls in a row this game has read as [GameState.OFF].
         *
         * The provider's delay and suspension names ride on top of an ordinary live game — see
         * `EspnParser.state` — and baseball produces them constantly: a replay review, a pitching
         * change, a groundskeeper on the tarp for ninety seconds. Every one of those is a delay for
         * exactly as long as it takes to clear, and announcing on the first sighting turned each of
         * them into two buzzes with no run scored between them.
         *
         * Counted rather than timed because a poll is the only clock this has. See [OFF_CONFIRM].
         */
        val offPolls: Int = 0,
        /**
         * Whether the delay was actually announced.
         *
         * The other half of the debounce: "back on" is only news if "off" was, so a blip that
         * cleared before it was ever mentioned must not produce a [Kind.RESUMED] either.
         */
        val offAnnounced: Boolean = false,
    )

    /**
     * How many consecutive polls a delay has to survive before it is worth interrupting for.
     *
     * Two, which the live ticker turns into roughly one to two minutes — long enough that a review
     * or a pitching change is gone before it counts, short enough that a real rain delay is still
     * news while it matters. A genuine suspension lasts an hour and does not care about the wait.
     *
     * The alarm chain polls far slower, so on a build the ticker cannot run on this is a longer wait
     * for the same certainty. That is the right way round: the fault being fixed is a phone buzzing
     * about nothing, and the cost of it is being told about a real rain delay one poll late.
     */
    const val OFF_CONFIRM = 2

    /**
     * The period that has just ended, or null if none has.
     *
     * Three signals, because no one of them is available everywhere:
     *
     * 1. **The status enum.** ESPN names the phase for soccer — `STATUS_HALFTIME`,
     *    `STATUS_END_PERIOD` — and falls back to a flat `STATUS_IN_PROGRESS` for the US
     *    leagues, so this catches some sports and not others.
     * 2. **The human status text**, which is where basketball and football actually say
     *    it: "End of 1st Quarter", "Halftime", "End 3rd".
     * 3. **The period number going up.** The fallback that needs no vocabulary at all:
     *    if the game is in period 3 and was in period 2, period 2 ended. It reads one
     *    poll late, which for a fifteen-minute interval is immaterial.
     *
     * The first two report the period that ended as the *current* one ("End of 1st" while
     * `period` is 1); the third reports the previous one. Both are deduplicated by number
     * against [Snapshot.markedPeriod].
     */
    fun endedPeriod(prev: Snapshot, now: Snapshot): Int? {
        if (explicitBoundary(now.statusName, now.statusDetail) && now.period > 0) {
            return now.period
        }

        // Period numbers only move forward within a game; a provider correcting itself
        // downward is not a boundary.
        if (now.period > prev.period && prev.period > 0) return prev.period
        return null
    }

    /**
     * Whether the provider is saying, in either field, that a period has just ended
     * rather than that one is under way.
     *
     * Shared with [AlertText] so the wording and the detection can't drift apart: if this
     * is what fired the alert, this is also what decides whether to call it halftime.
     */
    fun explicitBoundary(statusName: String?, statusDetail: String): Boolean {
        val name = statusName.orEmpty().uppercase()
        if ("HALFTIME" in name || "END_PERIOD" in name || "END_OF_PERIOD" in name ||
            "INTERMISSION" in name
        ) return true
        val text = statusDetail.lowercase().trim()
        return text.startsWith("end of") || text.startsWith("end ") ||
            text == "ht" || text == "half" || text == "halftime" || text.startsWith("int")
    }

    /** Whether the boundary is specifically the midpoint, which has its own name. */
    fun isHalftime(statusName: String?, statusDetail: String): Boolean {
        val name = statusName.orEmpty().uppercase()
        val text = statusDetail.lowercase().trim()
        return "HALFTIME" in name || text == "ht" || text == "half" || text == "halftime"
    }

    /**
     * The snapshot to store, whether or not anything was worth announcing.
     *
     * **Separate from [alerts] because the delay counter has to advance on a quiet poll.** Every
     * other "already said that" marker only ever changes on a poll that produced an alert, so
     * `ScoreWatcher` could store the alert's snapshot and be done. A count of consecutive OFF polls
     * is the opposite: the poll that increments it is precisely the one that stays silent, and a
     * counter reset by every quiet poll would never reach two.
     */
    fun advanced(prev: Snapshot?, now: Snapshot): Snapshot {
        if (prev == null) return now
        if (now.state != GameState.OFF) return now.copy(offPolls = 0, offAnnounced = false)
        val polls = prev.offPolls + 1
        return now.copy(
            offPolls = polls,
            offAnnounced = prev.offAnnounced || polls >= OFF_CONFIRM,
        )
    }

    fun snapshot(game: Game, soonSent: Boolean = false, markedPeriod: Int = 0) = Snapshot(
        gameId = game.id,
        leagueId = game.leagueId,
        state = game.state,
        home = game.home.score,
        away = game.away.score,
        period = game.period,
        startMillis = game.startMillis,
        soonSent = soonSent,
        statusName = game.statusName,
        statusDetail = game.statusDetail,
        markedPeriod = markedPeriod,
    )

    data class Alert(val kind: Kind, val snapshot: Snapshot)

    /**
     * @param prev the last snapshot stored for this game, or null if never seen.
     * @param notifyStarts whether the user wants a nudge when a game kicks off.
     *
     * A game seen for the first time never alerts. Otherwise installing the app
     * mid-Sunday would fire a notification for every game already in progress, and
     * a phone rebooting at 9pm would replay the evening.
     */
    fun alerts(
        prev: Snapshot?,
        now: Snapshot,
        loudness: Loudness,
        notifyStarts: Boolean,
        nowMillis: Long = 0L,
        leadMillis: Long = 0L,
        markPeriods: Boolean = false,
    ): List<Alert> {
        if (prev == null) return emptyList()

        // The pre-game nudge, which is the only alert that fires without anything having
        // changed: what changed is the clock. Guarded by soonSent rather than by a state
        // transition, since the game is still PRE on both sides of it.
        val soon = notifyStarts &&
            leadMillis > 0L &&
            now.state == GameState.PRE &&
            !prev.soonSent &&
            now.startMillis > 0L &&
            nowMillis >= now.startMillis - leadMillis &&
            nowMillis < now.startMillis

        // Advanced before anything can return early, because the poll that confirms a delay is by
        // definition one where the state has not changed — and that is the branch below that used
        // to return without looking at anything.
        val moved = advanced(prev, now)
        // Announced on the poll that reaches the threshold, and only that one.
        val announceOff = moved.state == GameState.OFF &&
            moved.offAnnounced &&
            !prev.offAnnounced

        if (prev.state == now.state && prev.state != GameState.LIVE) {
            val quiet = mutableListOf<Kind>()
            if (soon) quiet += Kind.SOON
            if (announceOff) quiet += Kind.OFF
            val held = moved.copy(soonSent = soon || moved.soonSent)
            return quiet.map { Alert(it, held) }
        }

        val out = mutableListOf<Kind>()

        if (soon) out += Kind.SOON
        if (prev.state == GameState.PRE && now.state == GameState.LIVE && notifyStarts) {
            out += Kind.START
        }
        if (announceOff) {
            out += Kind.OFF
        }
        // The other half of the pair above: a delay or suspension clearing, reported
        // exactly once. Without this the only signal a postponed-or-delayed game ever
        // gives again is silence — nothing distinguishes "still delayed" from "back on
        // and nobody said so."
        //
        // Keyed on whether the delay was ever *announced* rather than on the previous state, so a
        // blip that cleared before it was worth mentioning does not come back as "resumed" — which
        // would be a buzz about the end of something you were never told had started.
        if (prev.offAnnounced && now.state == GameState.LIVE) {
            out += Kind.RESUMED
        }
        var marked = prev.markedPeriod
        if (prev.state == GameState.LIVE && now.state == GameState.LIVE) {
            if (loudness == Loudness.EVERY_SCORE && scoreChanged(prev, now)) out += Kind.SCORE

            // Halftime, the end of a quarter, an intermission. Fires whether or not the
            // score moved — a 0-0 halftime is still halftime, and reporting it only when
            // somebody scored would have missed most of them in soccer.
            if (markPeriods) {
                val ended = endedPeriod(prev, now)
                if (ended != null && ended > prev.markedPeriod) {
                    out += Kind.PERIOD
                    marked = ended
                }
            }
        }
        if (now.state == GameState.FINAL && prev.state != GameState.FINAL) {
            out += Kind.FINAL
        }
        // The snapshot the caller stores carries every bit of "already said that" — the pre-game
        // nudge, the last period marked, and how long this delay has been going on.
        val stored = moved.copy(soonSent = soon || moved.soonSent, markedPeriod = marked)
        return out.map { Alert(it, stored) }
    }

    private fun scoreChanged(prev: Snapshot, now: Snapshot): Boolean {
        // A null score is "not reported yet", not zero — treating it as zero invents
        // a scoring play the instant a provider starts publishing numbers.
        if (now.home == null || now.away == null) return false
        if (prev.home == null || prev.away == null) return false
        return now.home != prev.home || now.away != prev.away
    }
}
