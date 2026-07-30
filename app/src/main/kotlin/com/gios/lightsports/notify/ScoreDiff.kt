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

    enum class Kind { SOON, START, SCORE, PERIOD, FINAL, OFF }

    /** The minimum of a game needed to tell what changed since last time. */
    data class Snapshot(
        val gameId: String,
        val leagueId: String,
        val state: GameState,
        val home: Int?,
        val away: Int?,
        val period: Int,
        val startMillis: Long = 0L,
        /**
         * Whether the "starting soon" alert has already gone out for this game. Without
         * it, every poll inside the lead window would fire another one — seven or eight
         * reminders for one kickoff.
         */
        val soonSent: Boolean = false,
    )

    fun snapshot(game: Game, soonSent: Boolean = false) = Snapshot(
        gameId = game.id,
        leagueId = game.leagueId,
        state = game.state,
        home = game.home.score,
        away = game.away.score,
        period = game.period,
        startMillis = game.startMillis,
        soonSent = soonSent,
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

        if (prev.state == now.state && prev.state != GameState.LIVE) {
            return if (soon) listOf(Alert(Kind.SOON, now.copy(soonSent = true))) else emptyList()
        }

        val out = mutableListOf<Kind>()

        if (soon) out += Kind.SOON
        if (prev.state == GameState.PRE && now.state == GameState.LIVE && notifyStarts) {
            out += Kind.START
        }
        if (now.state == GameState.OFF && prev.state != GameState.OFF) {
            out += Kind.OFF
        }
        if (prev.state == GameState.LIVE && now.state == GameState.LIVE) {
            when (loudness) {
                Loudness.EVERY_SCORE ->
                    if (scoreChanged(prev, now)) out += Kind.SCORE

                // A period boundary is the only interruption basketball gets. The
                // score is read at the moment the period ticks over, which is the
                // end-of-quarter score by definition.
                Loudness.PERIOD_END ->
                    if (now.period > prev.period && scoreChanged(prev, now)) out += Kind.PERIOD

                Loudness.FINAL_ONLY -> Unit
            }
        }
        if (now.state == GameState.FINAL && prev.state != GameState.FINAL) {
            out += Kind.FINAL
        }
        // Carry soonSent forward on the snapshot the caller will store, so a game that
        // was nudged and then kicked off isn't nudged again if it somehow reverts to PRE.
        val stored = if (soon) now.copy(soonSent = true) else now
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
