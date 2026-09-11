package com.gios.lightsports

import com.gios.lightsports.data.Leagues
import com.gios.lightsports.model.Game
import com.gios.lightsports.model.GameState
import com.gios.lightsports.model.Side
import com.gios.lightsports.model.Situation
import com.gios.lightsports.model.SportKind
import com.gios.lightsports.notify.AlertText
import com.gios.lightsports.notify.ScoreDiff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId

/**
 * The card, in the pieces the design draws: kind and team on the left, the figure on the
 * right, the play under them, the clock at the foot. These strings are what BrightControl's
 * lock face lays out, so they are pinned here rather than checked by eye on a phone.
 */
class CardTextTest {

    private val zone = ZoneId.of("America/New_York")
    private val sea = Side("26", "Seattle Seahawks", "Seahawks", "SEA", 14)
    private val ne = Side("17", "New England Patriots", "Patriots", "NE", 7)

    private fun game(situation: Situation? = null, clock: String? = "3:24", period: Int = 2) = Game(
        id = "g", leagueId = "nfl", state = GameState.LIVE, startMillis = 0L,
        statusDetail = "3:24 - 2nd", period = period, clock = clock,
        home = sea, away = ne, situation = situation,
    )

    private fun prev(home: Int, away: Int, tdAt: Long = 0L) =
        ScoreDiff.Snapshot("g", "nfl", GameState.LIVE, home, away, 2, tdAt = tdAt)

    @Test
    fun `a touchdown draws the kind, the team, the score, the play and the clock`() {
        val g = game(Situation(possession = "26", lastPlay = "K.Walker III run for 12 yards, TOUCHDOWN. J.Myers extra point is GOOD, Center-C.Stoll."))
        val card = AlertText.cardText(g, Leagues.NFL, ScoreDiff.Kind.SCORE, zone, prev(7, 7))
        assertEquals("TD", card.kind)
        assertEquals("SEA", card.team)
        assertEquals("NE 7 · SEA 14", card.value)
        assertEquals("K.Walker III run for 12 yards, TOUCHDOWN. J.Myers extra point is GOOD", card.detail)
        assertEquals("Q2 3:24", card.foot)
        // The crest is asked for by team id, so the card can draw the side that scored.
        assertEquals("26", card.crestTeamId)
        // And the shade still gets the plain two lines it always got.
        assertEquals("TD SEA · Patriots 7 · Seahawks 14", card.title)
    }

    @Test
    fun `the red zone puts the team on the right and the down under it`() {
        val g = game(Situation(possession = "26", downDistance = "1st & 10 at NE 16", isRedZone = true))
        val card = AlertText.cardText(g, Leagues.NFL, ScoreDiff.Kind.REDZONE, zone)
        assertEquals("RED ZONE", card.kind)
        assertNull(card.team)
        assertEquals("SEA", card.value)
        assertEquals("1st & 10 at NE 16 · SEA up 14–7", card.detail)
        assertEquals("Q2 3:24", card.foot)
    }

    @Test
    fun `a one-score game leads with the margin and says who has the ball`() {
        val g = game(
            Situation(possession = "26", shortDownDistance = "3rd & 4"),
            clock = "4:58", period = 4,
        ).copy(home = sea.copy(score = 24), away = ne.copy(score = 20))
        val card = AlertText.cardText(g, Leagues.NFL, ScoreDiff.Kind.CLOSE, zone)
        assertEquals("ONE-SCORE GAME", card.kind)
        assertEquals("24–20", card.value)
        assertEquals("SEA leads NE · 4:58 left", card.detail)
        assertEquals("Q4 · SEA ball 3rd & 4", card.foot)
    }

    @Test
    fun `a final leads with the word and carries the headline`() {
        val g = game(clock = null, period = 4).copy(
            state = GameState.FINAL, statusDetail = "Final",
            headline = "Walker's late run puts it away",
        )
        val card = AlertText.cardText(g, Leagues.NFL, ScoreDiff.Kind.FINAL, zone)
        assertEquals("FINAL", card.kind)
        assertEquals("NE 7 · SEA 14", card.value)
        assertEquals("Walker's late run puts it away", card.detail)
        assertEquals("NFL · Final", card.foot)
    }

    @Test
    fun `a kickoff reminder has no score on the right`() {
        val g = game(clock = null, period = 0).copy(
            state = GameState.PRE, statusDetail = "Sun 1:00 PM", broadcast = "CBS",
            home = sea.copy(score = null), away = ne.copy(score = null),
        )
        val card = AlertText.cardText(g, Leagues.NFL, ScoreDiff.Kind.SOON, zone)
        assertEquals("KICKOFF", card.kind)
        assertNull(card.value)
        assertEquals("NFL · CBS", card.foot)
    }

    @Test
    fun `the score line uses the abbreviations the right-hand column has room for`() {
        assertEquals("NE 7 · SEA 14", AlertText.scoreLine(game()))
        assertEquals("SEA leads NE", AlertText.leads(game()))
    }
}
