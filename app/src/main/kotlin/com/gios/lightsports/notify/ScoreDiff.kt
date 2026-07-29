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

    enum class Kind { START, SCORE, PERIOD, FINAL, OFF }

    /** The minimum of a game needed to tell what changed since last time. */
    data class Snapshot(
        val gameId: String,
        val leagueId: String,
        val state: GameState,
        val home: Int?,
        val away: Int?,
        val period: Int,
    )

    fun snapshot(game: Game) = Snapshot(
        gameId = game.id,
        leagueId = game.leagueId,
        state = game.state,
        home = game.home.score,
        away = game.away.score,
        period = game.period,
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
    ): List<Alert> {
        if (prev == null) return emptyList()
        if (prev.state == now.state && prev.state != GameState.LIVE) return emptyList()

        val out = mutableListOf<Kind>()

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
        return out.map { Alert(it, now) }
    }

    private fun scoreChanged(prev: Snapshot, now: Snapshot): Boolean {
        // A null score is "not reported yet", not zero — treating it as zero invents
        // a scoring play the instant a provider starts publishing numbers.
        if (now.home == null || now.away == null) return false
        if (prev.home == null || prev.away == null) return false
        return now.home != prev.home || now.away != prev.away
    }
}
