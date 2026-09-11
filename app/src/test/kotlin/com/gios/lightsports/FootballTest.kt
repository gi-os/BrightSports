package com.gios.lightsports

import com.gios.lightsports.data.EspnParser
import com.gios.lightsports.data.Leagues
import com.gios.lightsports.model.Game
import com.gios.lightsports.model.GameState
import com.gios.lightsports.model.Loudness
import com.gios.lightsports.model.Side
import com.gios.lightsports.model.Situation
import com.gios.lightsports.model.SportKind
import com.gios.lightsports.notify.AlertText
import com.gios.lightsports.notify.PendingQueue
import com.gios.lightsports.notify.ScoreDiff
import com.gios.lightsports.notify.ScoreHold
import com.gios.lightsports.notify.TickerPlan
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId

class FootballTest {

    private val now = 1_800_000_000_000L

    private fun live(
        home: Int, away: Int, period: Int = 2, clock: String? = "3:24",
        possession: String? = "26", redZone: Boolean = false,
        lastPlay: String? = null, tdAt: Long = 0L, tdText: String? = null,
        closeSaid: Boolean = false,
    ) = ScoreDiff.Snapshot(
        "g", "nfl", GameState.LIVE, home, away, period,
        clock = clock, possession = possession, redZone = redZone, lastPlay = lastPlay,
        tdAt = tdAt, tdText = tdText, closeSaid = closeSaid,
    )

    private fun alerts(
        prev: ScoreDiff.Snapshot, next: ScoreDiff.Snapshot,
        loudness: Loudness = Loudness.TOUCHDOWNS,
        at: Long = now, redZone: Boolean = false, close: Boolean = false,
    ) = ScoreDiff.alerts(
        prev, next, loudness, notifyStarts = true, nowMillis = at, markPeriods = true,
        redZoneWanted = redZone, closeWanted = close, sport = SportKind.FOOTBALL,
    )

    // --------------------------------------------------------------- loudness

    @Test
    fun `touchdowns mode announces a seven point step once`() {
        val out = alerts(live(0, 0), live(7, 0))
        assertEquals(listOf(ScoreDiff.Kind.SCORE), out.map { it.kind })
        // Kick already counted: nothing left to wait for.
        assertEquals(0L, out.single().snapshot.tdAt)
    }

    @Test
    fun `a six point step opens the point after window`() {
        val out = alerts(live(0, 0), live(6, 0, lastPlay = "G.Smith pass to J.Woods for 13 yards, TOUCHDOWN"))
        assertEquals(listOf(ScoreDiff.Kind.SCORE), out.map { it.kind })
        assertEquals(now, out.single().snapshot.tdAt)
        assertEquals("G.Smith pass to J.Woods for 13 yards, TOUCHDOWN", out.single().snapshot.tdText)
    }

    @Test
    fun `the kick that follows is folded, not a second event`() {
        val afterTd = live(6, 0, tdAt = now, tdText = "the touchdown")
        val out = alerts(afterTd, live(7, 0), at = now + 40_000)
        // It still produces an alert -- carrying the seven -- but closes the window.
        assertEquals(listOf(ScoreDiff.Kind.SCORE), out.map { it.kind })
        assertEquals(0L, out.single().snapshot.tdAt)
    }

    @Test
    fun `a field goal is silent in touchdowns mode and loud in every score mode`() {
        assertTrue(alerts(live(7, 0), live(10, 0)).isEmpty())
        assertEquals(
            listOf(ScoreDiff.Kind.SCORE),
            alerts(live(7, 0), live(10, 0), loudness = Loudness.EVERY_SCORE).map { it.kind },
        )
    }

    @Test
    fun `a field goal ten minutes after a missed kick is not a point after`() {
        val stale = live(6, 0, tdAt = now - 10 * 60_000, tdText = "old td")
        // +3 is a field goal whatever came before; and the window is long gone anyway.
        assertTrue(alerts(stale, live(9, 0)).isEmpty())
        // A lone +1 after the window is not folded either.
        assertTrue(alerts(stale, live(7, 0)).isEmpty())
    }

    @Test
    fun `quarters mode still marks the quarter and the final`() {
        val out = alerts(live(7, 0, period = 1), live(7, 0, period = 2), loudness = Loudness.PERIOD_ONLY)
        assertEquals(listOf(ScoreDiff.Kind.PERIOD), out.map { it.kind })
    }

    // --------------------------------------------------------------- red zone

    @Test
    fun `crossing the twenty fires once per trip`() {
        val out = alerts(live(7, 0, redZone = false), live(7, 0, redZone = true), redZone = true)
        assertEquals(listOf(ScoreDiff.Kind.REDZONE), out.map { it.kind })
        // Still inside on the next poll: nothing.
        assertTrue(alerts(live(7, 0, redZone = true), live(7, 0, redZone = true), redZone = true).isEmpty())
    }

    @Test
    fun `a turnover inside the twenty is a new trip`() {
        val out = alerts(
            live(7, 0, redZone = true, possession = "26"),
            live(7, 0, redZone = true, possession = "17"),
            redZone = true,
        )
        assertEquals(listOf(ScoreDiff.Kind.REDZONE), out.map { it.kind })
    }

    @Test
    fun `the red zone is not mentioned on the poll that scores`() {
        val out = alerts(live(7, 0, redZone = false), live(14, 0, redZone = true), redZone = true)
        assertEquals(listOf(ScoreDiff.Kind.SCORE), out.map { it.kind })
    }

    @Test
    fun `red zone is off unless asked for`() {
        assertTrue(alerts(live(7, 0, redZone = false), live(7, 0, redZone = true)).isEmpty())
    }

    // ------------------------------------------------------------ one-score game

    @Test
    fun `the late and close nudge fires once`() {
        val out = alerts(live(20, 24, period = 4, clock = "5:12"), live(20, 24, period = 4, clock = "4:58"), close = true)
        assertEquals(listOf(ScoreDiff.Kind.CLOSE), out.map { it.kind })
        assertTrue(out.single().snapshot.closeSaid)
        val again = alerts(out.single().snapshot, live(20, 24, period = 4, clock = "4:30", closeSaid = true), close = true)
        assertTrue(again.isEmpty())
    }

    @Test
    fun `nine points is not one score and the third quarter is not late`() {
        assertTrue(alerts(live(15, 24, period = 4, clock = "4:00"), live(15, 24, period = 4, clock = "3:30"), close = true).isEmpty())
        assertTrue(alerts(live(20, 24, period = 3, clock = "4:00"), live(20, 24, period = 3, clock = "3:30"), close = true).isEmpty())
    }

    @Test
    fun `clock parsing`() {
        assertEquals(204, ScoreDiff.clockSeconds("3:24"))
        assertEquals(900, ScoreDiff.clockSeconds("15:00"))
        assertNull(ScoreDiff.clockSeconds(null))
        assertNull(ScoreDiff.clockSeconds("Halftime"))
    }

    // ---------------------------------------------------------------- wording

    private val sea = Side("26", "Seattle Seahawks", "Seahawks", "SEA", 14, record = "1-0")
    private val ne = Side("17", "New England Patriots", "Patriots", "NE", 7, record = "0-1")

    private fun game(home: Side = sea, away: Side = ne, situation: Situation? = null, clock: String? = "3:24") = Game(
        id = "g", leagueId = "nfl", state = GameState.LIVE, startMillis = now,
        statusDetail = "3:24 - 2nd", period = 2, clock = clock, home = home, away = away,
        situation = situation,
    )

    @Test
    fun `a touchdown is titled as one`() {
        val prev = live(7, 7)
        val g = game(situation = Situation(possession = "26", lastPlay = "(Shotgun) S.Darnold pass deep right to J.Smith-Njigba for 31 yards, TOUCHDOWN. J.Myers extra point is GOOD, Center-C.Stoll, Holder-M.Dickson."))
        assertEquals("TD SEA · Patriots 7 · Seahawks 14", AlertText.title(g, ScoreDiff.Kind.SCORE, prev, SportKind.FOOTBALL))
        assertEquals(
            "S.Darnold pass deep right to J.Smith-Njigba for 31 yards, TOUCHDOWN. J.Myers extra point is GOOD · Q2 3:24",
            AlertText.body(g, Leagues.NFL, ScoreDiff.Kind.SCORE, ZoneId.of("America/New_York"), prev),
        )
    }

    @Test
    fun `a folded point after says touchdown and kick`() {
        val prev = live(13, 7, tdAt = now, tdText = "K.Walker III run for 12 yards, TOUCHDOWN")
        val g = game(situation = Situation(lastPlay = "J.Myers extra point is GOOD, Center-C.Stoll, Holder-M.Dickson."))
        assertEquals("TD SEA · Patriots 7 · Seahawks 14", AlertText.title(g, ScoreDiff.Kind.SCORE, prev, SportKind.FOOTBALL))
        assertEquals(
            "K.Walker III run for 12 yards, TOUCHDOWN · PAT good · Q2 3:24",
            AlertText.body(g, Leagues.NFL, ScoreDiff.Kind.SCORE, ZoneId.of("America/New_York"), prev),
        )
    }

    @Test
    fun `field goal and safety labels`() {
        assertEquals("FG", AlertText.footballScoreLabel(3, false))
        assertEquals("SAFETY", AlertText.footballScoreLabel(2, false))
        assertEquals("TD", AlertText.footballScoreLabel(2, true))
        assertEquals("TD +2", AlertText.footballScoreLabel(8, false))
        assertEquals("PAT", AlertText.footballScoreLabel(1, false))
    }

    @Test
    fun `red zone and one score wording`() {
        val g = game(situation = Situation(possession = "26", downDistance = "1st & 10 at NE 16", shortDownDistance = "1st & 10", isRedZone = true))
        assertEquals("RED ZONE · SEA", AlertText.title(g, ScoreDiff.Kind.REDZONE, null, SportKind.FOOTBALL))
        assertEquals(
            "1st & 10 at NE 16 · SEA up 14–7 · Q2 3:24",
            AlertText.body(g, Leagues.NFL, ScoreDiff.Kind.REDZONE, ZoneId.of("UTC")),
        )
        val late = game(situation = Situation(possession = "26", shortDownDistance = "3rd & 4"), clock = "4:58")
            .copy(period = 4, home = sea.copy(score = 24), away = ne.copy(score = 20))
        assertEquals("ONE-SCORE GAME · Patriots 20 · Seahawks 24", AlertText.title(late, ScoreDiff.Kind.CLOSE, null, SportKind.FOOTBALL))
        assertEquals(
            "SEA up 24–20 · 4:58 left · SEA ball 3rd & 4",
            AlertText.body(late, Leagues.NFL, ScoreDiff.Kind.CLOSE, ZoneId.of("UTC")),
        )
    }

    @Test
    fun `the box can pull the kind label off a title`() {
        assertEquals(Triple("TD", "SEA", "Patriots 7 · Seahawks 14"), AlertText.splitKind("TD SEA · Patriots 7 · Seahawks 14"))
        assertEquals(Triple("TD +2", "SEA", "NE 7 · SEA 15"), AlertText.splitKind("TD +2 SEA · NE 7 · SEA 15"))
        assertEquals(Triple("RED ZONE", "SEA", ""), AlertText.splitKind("RED ZONE · SEA"))
        assertEquals(Triple("ONE-SCORE GAME", null, "NE 20 · SEA 24"), AlertText.splitKind("ONE-SCORE GAME · NE 20 · SEA 24"))
        assertEquals(Triple(null, null, "Mets 3 · Yankees 2"), AlertText.splitKind("Mets 3 · Yankees 2"))
    }

    @Test
    fun `play text is cleaned of the box score noise`() {
        assertEquals(
            "J.Warren right end to PIT 21 for 2 yards",
            AlertText.cleanPlay("J.Warren right end to PIT 21 for 2 yards (J.Sherwood)."),
        )
        assertEquals(
            "W.Howard pass incomplete deep middle to R.Wilson",
            AlertText.cleanPlay("(Shotgun) W.Howard pass incomplete deep middle to R.Wilson."),
        )
        assertNull(AlertText.cleanPlay("  "))
    }

    // ---------------------------------------------------------------- parsing

    @Test
    fun `the situation block is read in full`() {
        val s = EspnParser.situation(JSONObject("""
            {"possession":"26","down":2,"distance":7,"yardLine":84,"isRedZone":true,
             "homeTimeouts":3,"awayTimeouts":2,"downDistanceText":"2nd & 7 at NE 16",
             "shortDownDistanceText":"2nd & 7","possessionText":"NE 16",
             "lastPlay":{"text":"K.Walker III run for 9 yards","drive":{"description":"7 plays, 58 yards, 3:41"}}}
        """))
        assertEquals("26", s.possession)
        assertEquals("2nd & 7 at NE 16", s.downDistance)
        assertEquals("NE 16", s.spot)
        assertTrue(s.isRedZone)
        assertEquals(3, s.homeTimeouts)
        assertEquals(2, s.awayTimeouts)
        assertEquals("7 plays, 58 yards, 3:41", s.drive)
        // SEA has it at the NE 16: sixteen yards out. NE at its own 16 would be 84.
        assertEquals(16, s.yardsToGoal("SEA"))
        assertEquals(84, s.yardsToGoal("NE"))
    }

    @Test
    fun `a baseball situation reads the count`() {
        val s = EspnParser.situation(JSONObject("""
            {"balls":2,"strikes":1,"outs":1,"onFirst":true,"onSecond":true,"onThird":false,
             "batter":{"athlete":{"shortName":"B. Harper"}},"pitcher":{"athlete":{"shortName":"K. Finnegan"}}}
        """))
        assertEquals(2, s.balls); assertEquals(1, s.strikes); assertEquals(1, s.outs)
        assertTrue(s.onFirst); assertTrue(s.onSecond); assertFalse(s.onThird)
        assertEquals("B. Harper", s.batter)
        assertNull(s.possession)
    }

    @Test
    fun `an empty block is a situation with nothing in it, not null`() {
        val s = EspnParser.situation(JSONObject("{}"))
        assertNull(s.downDistance)
        assertFalse(s.isRedZone)
    }

    // ------------------------------------------------------------------ queue

    @Test
    fun `a posted alert retires the older ones still waiting for the same game`() {
        val file = File.createTempFile("pending-queue", ".json").also { it.deleteOnExit() }
        val q = PendingQueue(file)
        val t0 = System.currentTimeMillis()
        // The six-point touchdown, held for its kick, and the seven-point fold, due now.
        q.add(listOf(
            PendingQueue.Entry(dueAt = t0 + 75_000, gameId = "g", leagueId = "nfl", kind = ScoreDiff.Kind.SCORE, title = "TD 6", body = "", createdAt = t0),
            PendingQueue.Entry(dueAt = t0, gameId = "g", leagueId = "nfl", kind = ScoreDiff.Kind.SCORE, title = "TD 7", body = "", createdAt = t0 + 40_000),
        ))
        val (due, waiting) = q.takeDue(t0 + 1)
        assertEquals(listOf("TD 7"), due.map { it.title })
        assertTrue(waiting.isEmpty())
    }
}

/** The ongoing card: what the shade and BrightControl's lock face are handed. */
class TickerCardTest {

    private val sea = Side("26", "Seattle Seahawks", "Seahawks", "SEA", 14)
    private val ne = Side("17", "New England Patriots", "Patriots", "NE", 7)

    private fun football(situation: Situation?) = Game(
        id = "g1", leagueId = "nfl", state = GameState.LIVE, startMillis = 0L,
        statusDetail = "3:24 - 2nd", period = 2, clock = "3:24",
        home = sea, away = ne, situation = situation,
    )

    @Test
    fun `one live game gives a score line, a situation line and the game to open`() {
        val g = football(Situation(possession = "26", downDistance = "2nd & 7 at NE 16", isRedZone = true))
        val card = TickerPlan.cards(listOf(g), showScores = true) { SportKind.FOOTBALL }.single()
        assertEquals("Patriots 7 · Seahawks 14 · Q2", card.text.title)
        assertEquals("SEA ball · 2nd & 7 at NE 16 · RED ZONE", card.text.detail)
        assertEquals("g1", card.gameId)
        assertEquals("nfl", card.leagueId)
        // The card's own design: the matchup on the left, the score on the right.
        assertEquals("NE @ SEA", card.text.kind)
        assertEquals("7–14", card.text.value)
        assertEquals("Q2 3:24", card.text.foot)
    }

    @Test
    fun `a card with nothing released has no score and no situation`() {
        val g = football(Situation(possession = "26", downDistance = "2nd & 7 at NE 16"))
        val card = TickerPlan.cards(listOf(g), showScores = false) { SportKind.FOOTBALL }.single()
        assertEquals("Patriots at Seahawks · Q2", card.text.title)
        assertNull(card.text.detail)
        assertNull(card.text.value)
    }

    @Test
    fun `the spoiler delay shows the older score and drops the situation`() {
        // What the delay does now: the card carries a score, five minutes behind. The down and
        // distance describes this second, so it waits with the score it belongs to.
        val g = football(Situation(possession = "26", downDistance = "2nd & 7 at NE 16"))
        val card = TickerPlan.cards(listOf(g), { SportKind.FOOTBALL }) {
            ScoreHold.Shown(away = 7, home = 7, current = false)
        }.single()
        assertEquals("Patriots 7 · Seahawks 7 · Q2", card.text.title)
        assertEquals("7–7", card.text.value)
        assertNull(card.text.detail)
    }

    @Test
    fun `two live games get a card each`() {
        // One card per game, so a Sunday afternoon is one row per game rather than one row
        // listing them and a second one for whichever scored.
        val g = football(null)
        val cards = TickerPlan.cards(listOf(g, g.copy(id = "g2")), showScores = true) { SportKind.FOOTBALL }
        assertEquals(listOf("g1", "g2"), cards.map { it.gameId })
    }

    @Test
    fun `baseball counts the count, the outs and the runners`() {
        val g = football(Situation(balls = 2, strikes = 1, outs = 1, onFirst = true, onSecond = true))
            .copy(leagueId = "mlb", period = 7, clock = null, statusDetail = "Top 7th")
        val card = TickerPlan.cards(listOf(g), showScores = true) { SportKind.BASEBALL }.single()
        assertEquals("2-1 · 1 out · Runners on 1st and 2nd", card.text.detail)
    }

    @Test
    fun `a game with nothing to add says nothing twice`() {
        // No situation and no clock: the first line already carries the period.
        val g = football(null).copy(clock = null)
        assertNull(TickerPlan.detail(g, SportKind.FOOTBALL))
        // Not live: never a detail line.
        assertNull(TickerPlan.detail(g.copy(state = GameState.FINAL), SportKind.FOOTBALL))
    }
}
