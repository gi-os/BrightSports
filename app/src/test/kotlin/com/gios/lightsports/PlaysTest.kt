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
    fun `baseball has no scoringPlays array, so the runs come out of the play-by-play`() {
        // The shape MLB actually sends: every play in one list, the runs flagged, the
        // play named under `alternativeType` and the inning split into halves.
        val list = EspnParser.parseScoringPlays("""
            {"plays":[
              {"id":"a","type":{"id":"59","text":"Start Inning"},"text":"Top of the 1st inning",
               "awayScore":0,"homeScore":0,"period":{"type":"Top","number":1},"scoringPlay":false,"team":{"id":"27"}},
              {"id":"b","type":{"id":"57","text":"Play Result"},
               "alternativeType":{"id":"28","text":"Home Run","abbreviation":"HR"},
               "text":"Goodman homered to left (362 feet), McCarthy scored and Norby scored.",
               "awayScore":3,"homeScore":0,"period":{"type":"Top","number":3},"scoringPlay":true,
               "scoreValue":3,"team":{"id":"27","abbreviation":"COL"}},
              {"id":"c","type":{"id":"57","text":"Play Result"},
               "alternativeType":{"id":"2","text":"Single","abbreviation":"1B"},
               "text":"Clark singled to right, Greene scored.",
               "awayScore":3,"homeScore":2,"period":{"type":"Bottom","number":8},"scoringPlay":true,
               "scoreValue":1,"team":{"id":"6","abbreviation":"DET"}}
            ]}
        """)
        assertEquals(2, list.size)
        assertEquals("HR", list[0].kind)
        assertEquals("COL", list[0].teamAbbrev)
        assertEquals(3, list[0].awayScore)
        assertNull(list[0].clock)
        // A run in the 3rd is a different moment top and bottom, so the half is in the label.
        assertEquals("Top 3rd", list[0].periodLabel)
        assertEquals("Bot 8th", list[1].periodLabel)
        assertEquals("1B", list[1].kind)
    }

    @Test
    fun `a sport with the scoringPlays array does not get a half-inning label`() {
        val list = EspnParser.parseScoringPlays("""
            {"scoringPlays":[
              {"id":"x","type":{"text":"Field Goal Good","abbreviation":"FG"},"text":"Chris Boswell 44 Yd Field Goal",
               "awayScore":7,"homeScore":3,"period":{"number":2},"clock":{"displayValue":"0:48"},
               "team":{"id":"23","abbreviation":"PIT"}}
            ]}
        """)
        assertNull(list[0].periodLabel)
    }

    @Test
    fun `the recap story comes out of the article as plain paragraphs`() {
        val story = EspnParser.parseRecapStory("""
            {"article":{"headline":"Tigers rally past Rockies",
             "story":"DETROIT -- &#8212; <a href=\"http://espn.com/a\">Hao-Yu Lee</a> doubled and tripled.\n\n   Detroit&#39;s streak reached three.\n\n   <hl2>Up next</hl2>\r\n   They finish the series Sunday.\n\n   ------\n\n   See AP&#8217;s full MLB coverage here"}}
        """)
        // The dateline, the em dash after it and the links are gone; the section head is
        // its own line; nothing under the rule of dashes is part of the game.
        assertEquals(listOf(
            "Hao-Yu Lee doubled and tripled.",
            "Detroit's streak reached three.",
            "Up next",
            "They finish the series Sunday.",
        ), story)
    }

    @Test
    fun `a summary with no article has no recap`() {
        assertTrue(EspnParser.parseRecapStory("{}").isEmpty())
        assertTrue(EspnParser.parseRecapStory("""{"article":{"headline":"x"}}""").isEmpty())
    }

    @Test
    fun `an empty or broken body is an empty list`() {
        assertTrue(EspnParser.parsePlays("{}").isEmpty())
        assertTrue(EspnParser.parseScoringPlays("{\"scoringPlays\":[]}").isEmpty())
        assertTrue(EspnParser.parseScoringPlays("{\"plays\":[]}").isEmpty())
    }
}
