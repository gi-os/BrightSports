package com.gios.lightsports

import com.gios.lightsports.data.EspnParser
import com.gios.lightsports.data.Leagues
import com.gios.lightsports.model.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StripsTest {

    private val soccer = """
    {"events":[{"id":"1","date":"2026-09-05T14:00Z",
      "competitions":[{"id":"1","status":{"type":{"name":"STATUS_FULL_TIME","state":"post","completed":true,"shortDetail":"FT"},"period":2},
        "competitors":[
          {"homeAway":"home","team":{"id":"359","displayName":"Arsenal","shortDisplayName":"Arsenal","abbreviation":"ARS"},"score":"2",
           "statistics":[{"name":"possessionPct","displayValue":"54.0"},{"name":"shotsOnTarget","displayValue":"5"},{"name":"totalShots","displayValue":"11"}]},
          {"homeAway":"away","team":{"id":"364","displayName":"Liverpool","shortDisplayName":"Liverpool","abbreviation":"LIV"},"score":"2",
           "statistics":[{"name":"possessionPct","displayValue":"46.0"},{"name":"shotsOnTarget","displayValue":"4"},{"name":"totalShots","displayValue":"9"}]}
        ],
        "details":[
          {"type":{"id":"70","text":"Goal"},"clock":{"displayValue":"38'"},"team":{"id":"364"},"scoreValue":1,"scoringPlay":true,"redCard":false,"yellowCard":false,"penaltyKick":false,"ownGoal":false,"shootout":false,"athletesInvolved":[{"shortName":"C. Gakpo"}]},
          {"type":{"id":"70","text":"Goal"},"clock":{"displayValue":"45'+1'"},"team":{"id":"359"},"scoreValue":1,"scoringPlay":true,"ownGoal":false,"athletesInvolved":[{"shortName":"K. Havertz"}]},
          {"type":{"id":"94","text":"Yellow Card"},"clock":{"displayValue":"61'"},"team":{"id":"364"},"scoreValue":0,"scoringPlay":false,"yellowCard":true,"athletesInvolved":[{"shortName":"R. Gravenberch"}]},
          {"type":{"id":"137","text":"Penalty - Scored"},"clock":{"displayValue":"55'"},"team":{"id":"364"},"scoreValue":1,"scoringPlay":true,"penaltyKick":true,"athletesInvolved":[{"shortName":"M. Salah"}]},
          {"type":{"id":"70","text":"Goal"},"clock":{"displayValue":"74'"},"team":{"id":"359"},"scoreValue":1,"scoringPlay":true,"athletesInvolved":[{"shortName":"B. Saka"}]},
          {"type":{"id":"1","text":"Shootout"},"clock":{"displayValue":"90'"},"team":{"id":"359"},"scoreValue":1,"scoringPlay":true,"shootout":true}
        ]}]}]}
    """

    @Test
    fun `the soccer timeline carries goals, cards and the running score`() {
        val g = EspnParser.parseScoreboard(Leagues.EPL, soccer).single()
        val t = g.timeline
        // The shootout row is dropped.
        assertEquals(5, t.size)
        assertEquals("C. Gakpo", t[0].player)
        assertEquals(1, t[0].awayScore); assertEquals(0, t[0].homeScore)
        assertEquals(1, t[1].awayScore); assertEquals(1, t[1].homeScore)
        assertTrue(t[2].yellowCard); assertNull(t[2].awayScore)
        assertTrue(t[3].penalty); assertEquals(2, t[3].awayScore)
        assertEquals(2, t[4].homeScore)
        assertEquals("54.0", g.home.stats["possessionPct"])
        assertEquals("9", g.away.stats["totalShots"])
    }

    @Test
    fun `an own goal counts for the other side`() {
        val body = soccer.replace(
            """"team":{"id":"364"},"scoreValue":1,"scoringPlay":true,"redCard":false,"yellowCard":false,"penaltyKick":false,"ownGoal":false""",
            """"team":{"id":"364"},"scoreValue":1,"scoringPlay":true,"redCard":false,"yellowCard":false,"penaltyKick":false,"ownGoal":true""",
        )
        val t = EspnParser.parseScoreboard(Leagues.EPL, body).single().timeline
        assertTrue(t[0].ownGoal)
        assertEquals(0, t[0].awayScore); assertEquals(1, t[0].homeScore)
    }

    @Test
    fun `leaders and shots on goal`() {
        val body = """
        {"events":[{"id":"1","date":"2026-06-10T00:00Z","competitions":[{"id":"1",
          "status":{"type":{"name":"STATUS_FINAL","state":"post","completed":true}},
          "competitors":[
            {"homeAway":"home","team":{"id":"7","displayName":"Carolina Hurricanes","shortDisplayName":"Hurricanes","abbreviation":"CAR"},"score":"4",
             "statistics":[{"name":"saves","displayValue":"18"},{"name":"savePct","displayValue":".783"},{"name":"goals","displayValue":"4"}],
             "leaders":[{"name":"goals","leaders":[{"displayValue":"2","athlete":{"shortName":"N. Ehlers"}}]}]},
            {"homeAway":"away","team":{"id":"37","displayName":"Vegas Golden Knights","shortDisplayName":"Golden Knights","abbreviation":"VGK"},"score":"5",
             "statistics":[{"name":"saves","displayValue":"27"},{"name":"savePct","displayValue":".871"}]}
          ]}]}]}
        """
        val g = EspnParser.parseScoreboard(Leagues.NHL, body).single()
        assertEquals(listOf("goals" to "N. Ehlers 2"), g.home.leaders)
        // Home shots = away saves (27) + home goals (4).
        assertEquals(31, g.home.shotsOnGoal(g.away))
        assertEquals(23, g.away.shotsOnGoal(g.home))
        assertNull(Side("1", "x", "x", "X", 1).shotsOnGoal(Side("2", "y", "y", "Y", 0)))
    }

    @Test
    fun `a baseball situation keeps both summaries`() {
        val s = EspnParser.situation(org.json.JSONObject("""
            {"balls":1,"strikes":2,"outs":2,"onFirst":true,
             "batter":{"athlete":{"shortName":"B. Callahan"},"summary":"1-2, 2B, RBI"},
             "pitcher":{"athlete":{"shortName":"T. Adams"},"summary":"2.1 IP, 0 ER, H, 2 K, 2 BB"}}
        """))
        assertEquals("1-2, 2B, RBI", s.batterSummary)
        assertEquals("2.1 IP, 0 ER, H, 2 K, 2 BB", s.pitcherSummary)
    }
}
