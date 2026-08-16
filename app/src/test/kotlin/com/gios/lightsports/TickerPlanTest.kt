package com.gios.lightsports

import com.gios.lightsports.model.Game
import com.gios.lightsports.model.GameState
import com.gios.lightsports.model.Side
import com.gios.lightsports.model.SportKind
import com.gios.lightsports.notify.TickerPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cadence arithmetic. This decides both how late a score can be and what an
 * afternoon costs in battery, and it is the half of the live ticker that can be proved
 * without a phone.
 */
class TickerPlanTest {

    private val now = 1_785_000_000_000L
    private val lead = 15 * 60_000L

    private fun game(
        state: GameState,
        startsInMinutes: Long = 0,
        period: Int = 0,
        home: Int? = null,
        away: Int? = null,
    ) = Game(
        id = "g${state}_${period}_$home",
        leagueId = "mlb",
        state = state,
        startMillis = now + startsInMinutes * 60_000L,
        statusDetail = "",
        period = period,
        home = Side("1", "Home Team", "HOM", "HOM", home),
        away = Side("2", "Away Team", "AWY", "AWY", away),
    )

    private fun interval(games: List<Game>, kind: SportKind = SportKind.BASEBALL) =
        TickerPlan.intervalMillis(games, now) { kind }

    // ------------------------------------------------------------- running at all

    @Test
    fun `nothing to watch, nothing to run`() {
        assertFalse(TickerPlan.shouldRun(emptyList(), now, lead))
        assertFalse(TickerPlan.shouldRun(listOf(game(GameState.FINAL, -120)), now, lead))
        // Tonight's game is not a reason to hold a wakelock this afternoon.
        assertFalse(TickerPlan.shouldRun(listOf(game(GameState.PRE, 240)), now, lead))
    }

    @Test
    fun `a live game runs it, and so does one about to start`() {
        assertTrue(TickerPlan.shouldRun(listOf(game(GameState.LIVE, -30, period = 3)), now, lead))
        assertTrue(TickerPlan.shouldRun(listOf(game(GameState.PRE, 10)), now, lead))
    }

    // ------------------------------------------------------------------- cadence

    @Test
    fun `an ordinary live game polls on the minute`() {
        val games = listOf(game(GameState.LIVE, -40, period = 4, home = 5, away = 1))
        assertEquals(TickerPlan.LIVE_INTERVAL, interval(games))
    }

    @Test
    fun `a close ninth polls twice as fast`() {
        val games = listOf(game(GameState.LIVE, -150, period = 9, home = 3, away = 2))
        assertEquals(TickerPlan.FAST_INTERVAL, interval(games))
    }

    @Test
    fun `a blowout in the ninth does not`() {
        // Late, but decided. The point of the tier is that it costs something.
        val games = listOf(game(GameState.LIVE, -150, period = 9, home = 11, away = 2))
        assertEquals(TickerPlan.LIVE_INTERVAL, interval(games))
    }

    @Test
    fun `extra innings are always crunch, whatever the score says`() {
        val extras = game(GameState.LIVE, -180, period = 11, home = 7, away = 1)
        assertTrue(TickerPlan.isCrunch(extras, SportKind.BASEBALL))
    }

    @Test
    fun `the margin that counts as close is per sport`() {
        val late = { h: Int, a: Int, period: Int -> game(GameState.LIVE, -60, period, h, a) }
        // Six points in the fourth quarter is two possessions; six runs in the ninth is over.
        assertTrue(TickerPlan.isCrunch(late(90, 84, 4), SportKind.BASKETBALL))
        assertFalse(TickerPlan.isCrunch(late(9, 3, 9), SportKind.BASEBALL))
        // One goal in the third is the whole sport.
        assertTrue(TickerPlan.isCrunch(late(2, 1, 3), SportKind.HOCKEY))
        assertFalse(TickerPlan.isCrunch(late(4, 1, 3), SportKind.HOCKEY))
    }

    @Test
    fun `a score the provider has not published yet is treated as close`() {
        // Guessing "not close" here would slow the poll down at exactly the moment the
        // provider is about to start publishing. Null is not zero.
        val game = game(GameState.LIVE, -150, period = 9, home = null, away = null)
        assertTrue(TickerPlan.isCrunch(game, SportKind.BASEBALL))
    }

    @Test
    fun `one fast game sets the pace for the rest`() {
        // A single poll fetches every followed league, so the second game is free.
        val games = listOf(
            game(GameState.LIVE, -40, period = 3, home = 8, away = 0),
            game(GameState.LIVE, -150, period = 9, home = 3, away = 2),
        )
        assertEquals(TickerPlan.FAST_INTERVAL, interval(games))
    }

    @Test
    fun `before first pitch it dozes most of the way there`() {
        assertEquals(TickerPlan.WARMUP_INTERVAL, interval(listOf(game(GameState.PRE, 12))))
        // Inside the last few minutes it closes in, rather than overshooting the start.
        assertEquals(90_000L, interval(listOf(game(GameState.PRE, startsInMinutes = 0).copy(
            startMillis = now + 90_000L,
        ))))
    }

    @Test
    fun `a start time that has passed with no first pitch is watched closely`() {
        // Scheduled times are a plan. The flip to LIVE is the event worth catching.
        assertEquals(TickerPlan.LIVE_INTERVAL, interval(listOf(game(GameState.PRE, -3))))
    }

    // -------------------------------------------------------------------- the cap

    @Test
    fun `it gives up after six hours`() {
        assertFalse(TickerPlan.expired(now, now + 5 * 60 * 60 * 1000L))
        assertTrue(TickerPlan.expired(now, now + 7 * 60 * 60 * 1000L))
        // Never started is not expired.
        assertFalse(TickerPlan.expired(0L, now))
    }

    // ---------------------------------------------------------------- the card

    @Test
    fun `the card carries the score, unless the spoiler delay says otherwise`() {
        val g = game(GameState.LIVE, -60, period = 7, home = 3, away = 2)
        assertEquals("AWY 2 · HOM 3 · 7th", TickerPlan.line(g, SportKind.BASEBALL, true))
        // The whole point of the delay is that the phone must not get ahead of the
        // stream. A card in the shade with the score on it walks straight through it.
        assertEquals("AWY at HOM · 7th", TickerPlan.line(g, SportKind.BASEBALL, false))
    }
}
