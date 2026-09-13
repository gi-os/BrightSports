package com.gios.lightsports

import com.gios.lightsports.data.EspnParser
import com.gios.lightsports.data.Leagues
import com.gios.lightsports.model.GameState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The PGA leaderboard. The shape here is the one the live endpoint sends, trimmed:
 * `competitors` ordered by `order`, a total to par in `score`, one `linescores` entry per
 * round played.
 */
class GolfTest {

    private val body = """
        {"events":[{"id":"401811960","name":"Rocket Classic","shortName":"Rocket Classic",
          "date":"2026-07-30T04:00Z","endDate":"2026-08-02T04:00Z",
          "competitions":[{"id":"401811960","broadcast":"Golf Chnl",
            "status":{"period":4,"type":{"state":"post","detail":"Final"}},
            "competitors":[
              {"id":"1","order":1,"athlete":{"displayName":"Michael Thorbjornsen","shortName":"M. Thorbjornsen",
                "flag":{"alt":"USA"}},"score":"-18",
                "linescores":[{"value":67.0},{"value":65.0},{"value":68.0},{"value":66.0}]},
              {"id":"2","order":2,"athlete":{"displayName":"Xander Schauffele","shortName":"X. Schauffele"},
                "score":"-13","linescores":[{"value":68.0},{"value":68.0},{"value":67.0},{"value":68.0}]},
              {"id":"3","order":3,"athlete":{"displayName":"Davis Riley","shortName":"D. Riley"},
                "score":"-13","linescores":[{"value":70.0},{"value":66.0},{"value":68.0},{"value":67.0}]},
              {"id":"4","order":4,"athlete":{"displayName":"Rasmus Hojgaard","shortName":"R. Hojgaard"},
                "score":"-11","linescores":[{"value":69.0},{"value":68.0},{"value":68.0},{"value":68.0}]},
              {"id":"5","order":5,"athlete":{"displayName":"Bud Dalke","shortName":"B. Dalke"},
                "score":"+16","linescores":[{"value":78.0},{"value":78.0}]}
            ]}]}]}
    """

    @Test
    fun `the leaderboard comes out in order, with the ties written as ties`() {
        val list = EspnParser.parseGolf(Leagues.PGA, body, 0L)
        assertEquals(1, list.size)
        val field = list[0].entries
        assertEquals(5, field.size)
        assertEquals("1", field[0].position)
        assertEquals("-18", field[0].total)
        assertEquals(-18, field[0].toPar)
        assertEquals(listOf("67", "65", "68", "66"), field[0].rounds)
        // Two players on -13 share second place, and the player behind them is fourth.
        assertEquals("T2", field[1].position)
        assertEquals("T2", field[2].position)
        assertEquals("4", field[3].position)
    }

    @Test
    fun `a short card in a finished tournament is a missed cut, not a position`() {
        val field = EspnParser.parseGolf(Leagues.PGA, body, 0L)[0].entries
        assertEquals("CUT", field[4].position)
        assertEquals(listOf("78", "78"), field[4].rounds)
    }

    @Test
    fun `the tournament reads as final, with the leaders and where to watch`() {
        val event = EspnParser.parseGolf(Leagues.PGA, body, 0L)[0]
        assertEquals(GameState.FINAL, event.state)
        assertEquals("Final", event.sessionLabel)
        assertEquals(listOf("M. Thorbjornsen", "X. Schauffele", "D. Riley"), event.podium)
        assertEquals("Golf Chnl", event.note)
    }

    @Test
    fun `a round in progress is named by its number`() {
        val live = body
            .replace("\"state\":\"post\"", "\"state\":\"in\"")
            .replace("\"period\":4", "\"period\":3")
        val event = EspnParser.parseGolf(Leagues.PGA, live, 0L)[0]
        assertEquals(GameState.LIVE, event.state)
        assertEquals("R3", event.sessionLabel)
        // Nobody is cut while the tournament is still being played.
        assertTrue(event.entries.none { it.position == "CUT" })
    }

    @Test
    fun `a tournament with no field yet parses to an empty leaderboard`() {
        val pre = """
            {"events":[{"id":"9","name":"Biltmore Championship","shortName":"Biltmore",
              "date":"2026-09-17T04:00Z","competitions":[{"id":"9",
              "status":{"period":0,"type":{"state":"pre"}}}]}]}
        """
        val event = EspnParser.parseGolf(Leagues.PGA, pre, 0L)[0]
        assertEquals(GameState.PRE, event.state)
        assertTrue(event.entries.isEmpty())
        assertNull(event.sessionLabel)
    }

    @Test
    fun `an empty or broken body is an empty list`() {
        assertTrue(EspnParser.parseGolf(Leagues.PGA, "{}", 0L).isEmpty())
        assertTrue(EspnParser.parseGolf(Leagues.PGA, """{"events":[]}""", 0L).isEmpty())
    }

    @Test
    fun `the url asks for a date range, which golf needs and the others do not`() {
        assertEquals(
            "https://site.api.espn.com/apis/site/v2/sports/golf/pga/scoreboard?dates=20260910-20260921",
            EspnParser.golfUrl(Leagues.PGA, "20260910", "20260921"),
        )
    }
}
