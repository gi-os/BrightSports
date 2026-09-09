package com.gios.lightsports

import com.gios.lightsports.data.EspnParser
import com.gios.lightsports.data.Leagues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaysTest {

    @Test
    fun `the plays url asks for the newest first in one page`() {
        assertEquals(
            "https://sports.core.api.espn.com/v2/sports/football/leagues/nfl/events/401873288/competitions/401873288/plays?limit=8&sort=desc",
            EspnParser.playsUrl(Leagues.NFL, "401873288"),
        )
        assertTrue(EspnParser.playsUrl(Leagues.CFB, "1").contains("/football/leagues/college-football/"))
    }

    @Test
    fun `a play carries its clock, down and team`() {
        // Trimmed from the live response: the team is a reference, not an object.
        val plays = EspnParser.parsePlays("""
            {"count":176,"pageIndex":1,"pageCount":22,"items":[
              {"id":"4018732884111","type":{"id":"5","text":"Rush","abbreviation":"RUSH"},
               "text":"J.Warren right end to PIT 21 for 2 yards (J.Sherwood).",
               "shortText":"Jaylen Warren 2 Yd Rush","period":{"number":1},
               "clock":{"value":540.0,"displayValue":"9:00"},"scoringPlay":false,"scoreValue":0,
               "team":{"${'$'}ref":"http://sports.core.api.espn.com/v2/sports/football/leagues/nfl/seasons/2026/teams/23?lang=en&region=us"},
               "start":{"down":1,"distance":10,"yardLine":19,"downDistanceText":"1st & 10 at PIT 19","possessionText":"PIT 19"},
               "awayScore":7,"homeScore":0},
              {"id":"4018732884112","type":{"text":"Passing Touchdown"},
               "text":"G.Smith pass short left to J.Woods for 13 yards, TOUCHDOWN.",
               "period":{"number":1},"clock":{"displayValue":"9:04"},"scoringPlay":true,"scoreValue":6,
               "team":{"${'$'}ref":"http://sports.core.api.espn.com/v2/sports/football/leagues/nfl/seasons/2026/teams/20"}}
            ]}
        """)
        assertEquals(2, plays.size)
        val p = plays[0]
        assertEquals("9:00", p.clock)
        assertEquals(1, p.period)
        assertEquals("23", p.teamId)
        assertEquals("Rush", p.type)
        assertEquals("1st & 10 at PIT 19", p.downDistance)
        assertEquals(7, p.awayScore)
        val td = plays[1]
        assertTrue(td.scoring)
        assertEquals(6, td.scoreValue)
        assertEquals("20", td.teamId)
        assertNull(td.downDistance)
    }

    @Test
    fun `the scoring summary reads kind, team, moment and running score`() {
        val list = EspnParser.parseScoringPlays("""
            {"scoringPlays":[
              {"id":"401873288311","type":{"id":"67","text":"Passing Touchdown","abbreviation":"TD"},
               "text":"Jelani Woods 13 Yd pass from Geno Smith (Jason Sanders Kick)",
               "awayScore":7,"homeScore":0,"period":{"number":1},"clock":{"value":544.0,"displayValue":"9:04"},
               "team":{"id":"20","abbreviation":"NYJ"},"scoringType":{"name":"touchdown","displayName":"Touchdown","abbreviation":"TD"}},
              {"id":"x","type":{"text":"Field Goal Good","abbreviation":"FG"},"text":"Chris Boswell 44 Yd Field Goal",
               "awayScore":7,"homeScore":3,"period":{"number":2},"clock":{"displayValue":"0:48"},"team":{"id":"23","abbreviation":"PIT"}}
            ]}
        """)
        assertEquals(2, list.size)
        assertEquals("TD", list[0].kind)
        assertEquals("NYJ", list[0].teamAbbrev)
        assertEquals(7, list[0].awayScore)
        assertEquals("9:04", list[0].clock)
        // No scoringType block: falls back to the type abbreviation.
        assertEquals("FG", list[1].kind)
        assertEquals(3, list[1].homeScore)
    }

    @Test
    fun `an empty or broken body is an empty list`() {
        assertTrue(EspnParser.parsePlays("{}").isEmpty())
        assertTrue(EspnParser.parseScoringPlays("{\"scoringPlays\":[]}").isEmpty())
    }
}
