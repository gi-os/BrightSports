package com.gios.lightsports

import com.gios.lightsports.model.GameState
import com.gios.lightsports.model.Loudness
import com.gios.lightsports.notify.ScoreDiff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoreDiffTest {

    private fun snap(
        state: GameState,
        home: Int? = null,
        away: Int? = null,
        period: Int = 0,
    ) = ScoreDiff.Snapshot("g1", "mlb", state, home, away, period)

    private fun kinds(
        prev: ScoreDiff.Snapshot?,
        now: ScoreDiff.Snapshot,
        loudness: Loudness = Loudness.EVERY_SCORE,
        notifyStarts: Boolean = true,
        markPeriods: Boolean = false,
    ) = ScoreDiff.alerts(
        prev, now, loudness, notifyStarts, markPeriods = markPeriods,
    ).map { it.kind }

    @Test
    fun `a game seen for the first time never alerts`() {
        // Installing mid-Sunday would otherwise fire for every game already running.
        assertTrue(kinds(null, snap(GameState.LIVE, 3, 1, period = 5)).isEmpty())
        assertTrue(kinds(null, snap(GameState.FINAL, 5, 4)).isEmpty())
    }

    // ------------------------------------------------------------ starting soon

    private val LEAD = 15 * 60_000L
    private val NOW = 1_785_000_000_000L

    private fun pre(startsInMinutes: Long, soonSent: Boolean = false) = ScoreDiff.Snapshot(
        "g1", "mlb", GameState.PRE, 0, 0, 0,
        startMillis = NOW + startsInMinutes * 60_000L,
        soonSent = soonSent,
    )

    @Test
    fun `a game inside the lead window is announced`() {
        val alerts = ScoreDiff.alerts(
            prev = pre(12), now = pre(12), loudness = Loudness.EVERY_SCORE,
            notifyStarts = true, nowMillis = NOW, leadMillis = LEAD,
        )
        assertEquals(listOf(ScoreDiff.Kind.SOON), alerts.map { it.kind })
        // The stored snapshot records it, which is what stops the next poll repeating it.
        assertTrue(alerts.single().snapshot.soonSent)
    }

    @Test
    fun `it is announced once, not on every poll inside the window`() {
        // Two minutes later, already sent: the lead window spans seven or eight polls.
        assertTrue(
            ScoreDiff.alerts(
                prev = pre(10, soonSent = true), now = pre(10, soonSent = true),
                loudness = Loudness.EVERY_SCORE, notifyStarts = true,
                nowMillis = NOW, leadMillis = LEAD,
            ).isEmpty(),
        )
    }

    @Test
    fun `a game outside the lead window says nothing yet`() {
        assertTrue(
            ScoreDiff.alerts(
                prev = pre(90), now = pre(90), loudness = Loudness.EVERY_SCORE,
                notifyStarts = true, nowMillis = NOW, leadMillis = LEAD,
            ).isEmpty(),
        )
    }

    @Test
    fun `a start time already past is not announced as upcoming`() {
        // A game late to flip to LIVE must not produce "starts in 1 min" forever.
        assertTrue(
            ScoreDiff.alerts(
                prev = pre(-5), now = pre(-5), loudness = Loudness.EVERY_SCORE,
                notifyStarts = true, nowMillis = NOW, leadMillis = LEAD,
            ).isEmpty(),
        )
    }

    @Test
    fun `the reminder honours the same setting as the kickoff alert`() {
        assertTrue(
            ScoreDiff.alerts(
                prev = pre(12), now = pre(12), loudness = Loudness.EVERY_SCORE,
                notifyStarts = false, nowMillis = NOW, leadMillis = LEAD,
            ).isEmpty(),
        )
    }

    @Test
    fun `with no lead configured nothing is announced early`() {
        assertTrue(
            ScoreDiff.alerts(
                prev = pre(12), now = pre(12), loudness = Loudness.EVERY_SCORE,
                notifyStarts = true, nowMillis = NOW, leadMillis = 0L,
            ).isEmpty(),
        )
    }

    @Test
    fun `going live reports a start`() {
        assertEquals(
            listOf(ScoreDiff.Kind.START),
            kinds(snap(GameState.PRE, 0, 0), snap(GameState.LIVE, 0, 0, period = 1)),
        )
    }

    @Test
    fun `start alert is suppressed when the user turned it off`() {
        assertTrue(
            kinds(
                snap(GameState.PRE, 0, 0),
                snap(GameState.LIVE, 0, 0, period = 1),
                notifyStarts = false,
            ).isEmpty(),
        )
    }

    @Test
    fun `every score change alerts in a low scoring sport`() {
        assertEquals(
            listOf(ScoreDiff.Kind.SCORE),
            kinds(
                snap(GameState.LIVE, 1, 0, period = 3),
                snap(GameState.LIVE, 1, 1, period = 3),
            ),
        )
    }

    @Test
    fun `an unchanged score is silent`() {
        assertTrue(
            kinds(
                snap(GameState.LIVE, 2, 1, period = 4),
                snap(GameState.LIVE, 2, 1, period = 5),
            ).isEmpty(),
        )
    }

    @Test
    fun `basketball stays quiet inside a quarter`() {
        // Twenty baskets in a quarter must produce nothing at all.
        assertTrue(
            kinds(
                snap(GameState.LIVE, 24, 22, period = 2),
                snap(GameState.LIVE, 48, 41, period = 2),
                loudness = Loudness.PERIOD_ONLY,
                markPeriods = true,
            ).isEmpty(),
        )
    }

    @Test
    fun `basketball reports at the quarter boundary`() {
        assertEquals(
            listOf(ScoreDiff.Kind.PERIOD),
            kinds(
                snap(GameState.LIVE, 48, 41, period = 2),
                snap(GameState.LIVE, 52, 45, period = 3),
                loudness = Loudness.PERIOD_ONLY,
                markPeriods = true,
            ),
        )
    }

    // ------------------------------------------------------------ period marks

    private fun live(
        period: Int,
        home: Int = 0,
        away: Int = 0,
        statusName: String? = null,
        statusDetail: String = "",
        markedPeriod: Int = 0,
    ) = ScoreDiff.Snapshot(
        "g1", "mlb", GameState.LIVE, home, away, period,
        statusName = statusName, statusDetail = statusDetail, markedPeriod = markedPeriod,
    )

    @Test
    fun `baseball never marks an inning`() {
        // Nine innings and eighteen half-innings is not a notification schedule.
        assertTrue(
            kinds(live(6, 3, 1), live(7, 3, 1), markPeriods = false).isEmpty(),
        )
    }

    @Test
    fun `a soccer halftime is marked even at nil-nil`() {
        // The old rule required a score change, so most halftimes went unreported.
        assertEquals(
            listOf(ScoreDiff.Kind.PERIOD),
            kinds(
                live(1, 0, 0, statusName = "STATUS_FIRST_HALF"),
                live(1, 0, 0, statusName = "STATUS_HALFTIME", statusDetail = "HT"),
                markPeriods = true,
            ),
        )
    }

    @Test
    fun `a period end is reported once, not for every poll of the interval`() {
        // Fifteen minutes of halftime is seven or eight polls.
        val at = live(1, 0, 0, statusName = "STATUS_HALFTIME", statusDetail = "HT")
        val alerts = ScoreDiff.alerts(
            prev = live(1, 0, 0, statusName = "STATUS_FIRST_HALF"), now = at,
            loudness = Loudness.EVERY_SCORE, notifyStarts = true, markPeriods = true,
        )
        assertEquals(listOf(ScoreDiff.Kind.PERIOD), alerts.map { it.kind })
        assertEquals(1, alerts.single().snapshot.markedPeriod)
        // Same status again, now carrying the marker: silent.
        assertTrue(
            kinds(
                at.copy(markedPeriod = 1), at.copy(markedPeriod = 1), markPeriods = true,
            ).isEmpty(),
        )
    }

    @Test
    fun `the human status text is enough on its own`() {
        // Basketball and football say it here rather than in the status enum.
        assertEquals(
            listOf(ScoreDiff.Kind.PERIOD),
            kinds(
                live(1, 24, 22, statusDetail = "8:31 - 1st"),
                live(1, 30, 28, statusDetail = "End of 1st Quarter"),
                loudness = Loudness.PERIOD_ONLY,
                markPeriods = true,
            ),
        )
    }

    @Test
    fun `the period number alone is enough when neither field says anything`() {
        assertEquals(1, ScoreDiff.endedPeriod(live(1), live(2)))
        assertEquals(null, ScoreDiff.endedPeriod(live(2), live(2)))
        // A provider correcting itself downward is not a boundary.
        assertEquals(null, ScoreDiff.endedPeriod(live(3), live(2)))
    }

    @Test
    fun `a score and a period end in the same poll both fire`() {
        // A buzzer beater: the queue collapses them into one notification later, but the
        // diff should not be the thing deciding that.
        assertEquals(
            listOf(ScoreDiff.Kind.SCORE, ScoreDiff.Kind.PERIOD),
            kinds(
                live(1, 7, 3),
                live(1, 10, 3, statusDetail = "End of 1st"),
                markPeriods = true,
            ),
        )
    }

    @Test
    fun `an intermission counts, which is how the pwhl reports it`() {
        assertEquals(
            listOf(ScoreDiff.Kind.PERIOD),
            kinds(
                live(2, 1, 1, statusDetail = "12:00 2nd"),
                live(2, 1, 1, statusName = "INTERMISSION", statusDetail = "INT 2"),
                markPeriods = true,
            ),
        )
    }

    @Test
    fun `racing is silent until the result`() {
        assertTrue(
            kinds(
                snap(GameState.LIVE, null, null, period = 30),
                snap(GameState.LIVE, null, null, period = 44),
                loudness = Loudness.FINAL_ONLY,
            ).isEmpty(),
        )
        assertEquals(
            listOf(ScoreDiff.Kind.FINAL),
            kinds(
                snap(GameState.LIVE, null, null, period = 44),
                snap(GameState.FINAL, null, null, period = 57),
                loudness = Loudness.FINAL_ONLY,
            ),
        )
    }

    @Test
    fun `the final whistle alerts once and only once`() {
        assertEquals(
            listOf(ScoreDiff.Kind.FINAL),
            kinds(snap(GameState.LIVE, 4, 3, period = 9), snap(GameState.FINAL, 4, 3, period = 9)),
        )
        assertTrue(
            kinds(snap(GameState.FINAL, 4, 3), snap(GameState.FINAL, 4, 3)).isEmpty(),
        )
    }

    @Test
    fun `a walk off is one notification, not a run and then a final`() {
        // The final carries the score, so the winning run needs no separate alert.
        assertEquals(
            listOf(ScoreDiff.Kind.FINAL),
            kinds(
                snap(GameState.LIVE, 3, 4, period = 9),
                snap(GameState.FINAL, 5, 4, period = 9),
            ),
        )
    }

    @Test
    fun `a missing score is not treated as zero`() {
        // A provider that starts publishing numbers mid-game would otherwise look
        // like an eight run inning.
        assertTrue(
            kinds(
                snap(GameState.LIVE, null, null, period = 2),
                snap(GameState.LIVE, 0, 0, period = 2),
            ).isEmpty(),
        )
    }

    @Test
    fun `a postponement is reported`() {
        assertEquals(
            listOf(ScoreDiff.Kind.OFF),
            kinds(snap(GameState.PRE, 0, 0), snap(GameState.OFF, 0, 0)),
        )
    }

    @Test
    fun `a delay is reported once and stays silent while it drags on`() {
        // The exact complaint this covers: a game stuck in a rain delay for forty
        // minutes must not repeat the same "delayed" notification every poll.
        assertEquals(
            listOf(ScoreDiff.Kind.OFF),
            kinds(snap(GameState.LIVE, 2, 1, period = 5), snap(GameState.OFF, 2, 1, period = 5)),
        )
        assertTrue(
            kinds(snap(GameState.OFF, 2, 1, period = 5), snap(GameState.OFF, 2, 1, period = 5))
                .isEmpty(),
        )
    }

    @Test
    fun `play resuming after a delay is its own one-time alert`() {
        assertEquals(
            listOf(ScoreDiff.Kind.RESUMED),
            kinds(snap(GameState.OFF, 2, 1, period = 5), snap(GameState.LIVE, 2, 1, period = 5)),
        )
    }

    @Test
    fun `a postponed game rescheduled and postponed again alerts each time, not once`() {
        // What actually produced repeats before the state() fix: MiLB reports a
        // postponed game as abstractGameState "Final", and a reschedule can flip the
        // feed back to "Preview" before the next rainout lands. Each of those two OFF
        // entries is a genuinely new event and both should be told about — it's a
        // stable, unchanging OFF that must stay silent, not a returning one.
        val off = snap(GameState.OFF, 0, 0)
        val pre = snap(GameState.PRE, 0, 0)
        assertEquals(listOf(ScoreDiff.Kind.OFF), kinds(pre, off))
        assertTrue(kinds(off, off).isEmpty())
    }
}
