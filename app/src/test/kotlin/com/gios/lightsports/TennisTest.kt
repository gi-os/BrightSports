package com.gios.lightsports

import com.gios.lightsports.data.EspnParser
import com.gios.lightsports.data.Leagues
import com.gios.lightsports.model.EventClass
import com.gios.lightsports.model.GameState
import com.gios.lightsports.model.SportKind
import com.gios.lightsports.notify.AlertText
import com.gios.lightsports.notify.TickerPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Trimmed from the 2026 US Open as ESPN served it on 2026-09-05: one live singles match
 * in the third set, one finished with a tiebreak, one doubles pair, one qualifying
 * match, and a tour stop that is not a major and must be ignored.
 */
class TennisTest {

    private val scoreboard = """
    {"events":[
      {"id":"189-2026","name":"US Open","shortName":"US Open","major":true,
       "date":"2026-08-24T04:00Z","endDate":"2026-09-14T03:59Z",
       "groupings":[
        {"grouping":{"displayName":"Men's Singles","slug":"mens-singles"},
         "competitions":[
          {"id":"182728","date":"2026-09-06T02:05Z",
           "status":{"period":3,"type":{"id":"2","name":"STATUS_IN_PROGRESS","state":"in",
                     "completed":false,"detail":"3rd Set","shortDetail":"3rd"}},
           "venue":{"fullName":"New York, USA","court":"Louis Armstrong Stadium"},
           "broadcasts":[{"market":"national","names":["ESPN2"]}],
           "competitors":[
            {"id":"10386","type":"athlete","homeAway":"away","curatedRank":{"current":14},
             "linescores":[{"value":6.0,"winner":true},{"value":1.0,"winner":false},{"value":1.0}],
             "athlete":{"displayName":"Learner Tien","shortName":"L. Tien",
                        "flag":{"href":"https://a.espncdn.com/i/teamlogos/countries/500/usa.png"}}},
            {"id":"10319","type":"athlete","homeAway":"home","curatedRank":{"current":17},
             "linescores":[{"value":3.0,"winner":false},{"value":6.0,"winner":true},{"value":0.0}],
             "athlete":{"displayName":"Jakub Mensik","shortName":"J. Mensik",
                        "flag":{"href":"https://a.espncdn.com/i/teamlogos/countries/500/cze.png"}}}],
           "type":{"text":"Men's Singles","slug":"mens-singles"},
           "round":{"id":"3","displayName":"Round 3"}},
          {"id":"182600","date":"2026-08-25T15:00Z",
           "status":{"period":2,"type":{"id":"3","name":"STATUS_FINAL","state":"post",
                     "completed":true,"detail":"Final","shortDetail":"Final"}},
           "competitors":[
            {"id":"3056","type":"athlete","homeAway":"home","winner":false,
             "linescores":[{"value":6.0,"tiebreak":3,"winner":false},{"value":3.0,"winner":false}],
             "athlete":{"displayName":"Roberto Carballes Baena","shortName":"R. Carballes Baena"}},
            {"id":"5128","type":"athlete","homeAway":"away","winner":true,
             "linescores":[{"value":7.0,"tiebreak":7,"winner":true},{"value":6.0,"winner":true}],
             "athlete":{"displayName":"Jacob Fearnley","shortName":"J. Fearnley"}}],
           "round":{"id":"1","displayName":"Round 1"}},
          {"id":"182900","date":"2026-09-13T20:00Z",
           "status":{"period":0,"type":{"id":"1","name":"STATUS_SCHEDULED","state":"pre",
                     "completed":false,"detail":"9/13 - 4:00 PM EDT","shortDetail":"9/13 - 4:00 PM EDT"}},
           "competitors":[
            {"id":"3623","type":"athlete","homeAway":"home","athlete":{"displayName":"Jannik Sinner","shortName":"J. Sinner"}},
            {"id":"4593","type":"athlete","homeAway":"away","athlete":{"displayName":"Carlos Alcaraz","shortName":"C. Alcaraz"}}],
           "round":{"id":"7","displayName":"Final"}},
          {"id":"182100","date":"2026-08-20T15:00Z",
           "status":{"period":2,"type":{"id":"3","name":"STATUS_FINAL","state":"post","completed":true,"detail":"Final","shortDetail":"Final"}},
           "competitors":[
            {"id":"1","type":"athlete","homeAway":"home","linescores":[{"value":6.0,"winner":true},{"value":6.0,"winner":true}],"athlete":{"displayName":"A Qualifier","shortName":"A. Qualifier"}},
            {"id":"2","type":"athlete","homeAway":"away","linescores":[{"value":2.0,"winner":false},{"value":3.0,"winner":false}],"athlete":{"displayName":"B Qualifier","shortName":"B. Qualifier"}}],
           "round":{"id":"11","displayName":"Qualifying 1st Round"}}
         ]},
        {"grouping":{"displayName":"Men's Doubles","slug":"mens-doubles"},
         "competitions":[
          {"id":"183001","date":"2026-09-09T16:00Z",
           "status":{"period":0,"type":{"id":"1","name":"STATUS_SCHEDULED","state":"pre","completed":false,"detail":"TBD","shortDetail":"TBD"}},
           "competitors":[
            {"id":"1013-2319","type":"team","homeAway":"away",
             "roster":{"displayName":"Marcelo Melo / John Peers","shortDisplayName":"M. Melo / J. Peers",
                       "athletes":[{"displayName":"Marcelo Melo","shortName":"M. Melo"},
                                   {"displayName":"John Peers","shortName":"J. Peers"}]}},
            {"id":"4444-5555","type":"team","homeAway":"home",
             "roster":{"displayName":"Some Body / Other Body","shortDisplayName":"S. Body / O. Body",
                       "athletes":[{"displayName":"Some Body","shortName":"S. Body"},
                                   {"displayName":"Other Body","shortName":"O. Body"}]}}],
           "round":{"id":"5","displayName":"Quarterfinal"}}
         ]}
       ]},
      {"id":"306-2026","name":"Nordea Open","shortName":"Nordea Open","major":false,
       "groupings":[{"grouping":{"displayName":"Men's Singles"},
         "competitions":[{"id":"9","date":"2026-09-06T10:00Z",
           "status":{"period":1,"type":{"state":"in","detail":"1st Set","shortDetail":"1st"}},
           "competitors":[
            {"id":"77","type":"athlete","homeAway":"home","linescores":[{"value":2.0}],"athlete":{"displayName":"Tour Stop","shortName":"T. Stop"}},
            {"id":"78","type":"athlete","homeAway":"away","linescores":[{"value":1.0}],"athlete":{"displayName":"Also Tour","shortName":"A. Tour"}}],
           "round":{"displayName":"Round 1"}}]}]}
    ]}
    """.trimIndent()

    private val rankings = """
    {"rankings":[{"id":"1","name":"ATP","shortName":"ATP","ranks":[
      {"current":1,"previous":1,"points":12800.0,"athlete":{"id":"3623","displayName":"Jannik Sinner",
        "shortname":"J. Sinner","flag":"https://a.espncdn.com/i/teamlogos/countries/500/ita.png",
        "citizenshipCountry":"ITA","age":25}},
      {"current":2,"previous":3,"points":8000.0,"athlete":{"id":"4593","displayName":"Carlos Alcaraz",
        "shortname":"C. Alcaraz","flag":"https://a.espncdn.com/i/teamlogos/countries/500/esp.png"}}
    ]}]}
    """.trimIndent()

    private val games by lazy { EspnParser.parseTennis(Leagues.TENNIS, scoreboard) }

    @Test
    fun `only majors are kept, and qualifying is dropped`() {
        assertEquals(4, games.size)
        assertTrue(games.none { "Qualif" in (it.note ?: "") })
        assertTrue(games.none { it.competition == "Nordea Open" })
    }

    @Test
    fun `a live match scores in sets with games per set as the line`() {
        val g = games.single { it.id == "t-182728" }
        assertEquals(GameState.LIVE, g.state)
        assertEquals(3, g.period)
        assertEquals("3rd", g.statusDetail)
        assertEquals("L. Tien", g.away.short)
        assertEquals(1, g.away.score)
        assertEquals(1, g.home.score)
        assertEquals(listOf("6", "1", "1"), g.away.lineScore)
        assertEquals(listOf("3", "6", "0"), g.home.lineScore)
        assertEquals("(14)", g.away.record)
        assertEquals("TIE", g.away.abbrev)
        assertEquals("Louis Armstrong Stadium", g.venue)
        assertEquals("ESPN2", g.broadcast)
        assertEquals("US Open · MS · R3", g.eventTitle)
        assertEquals("Men's Singles · Round 3", g.note)
        assertEquals("US Open", g.competition)
        assertEquals(EventClass.NONE, g.eventClass)
    }

    @Test
    fun `a tiebreak prints beside the games`() {
        val g = games.single { it.id == "t-182600" }
        assertEquals(GameState.FINAL, g.state)
        assertEquals(listOf("7(7)", "6"), g.away.lineScore)
        assertEquals(listOf("6(3)", "3"), g.home.lineScore)
        assertEquals(2, g.away.score)
        assertEquals(0, g.home.score)
        assertEquals("7(7)-6(3) 6-3", AlertText.setLine(g))
    }

    @Test
    fun `an unplayed match has no score rather than nil-nil`() {
        val g = games.single { it.id == "t-182900" }
        assertNull(g.home.score)
        assertNull(g.away.score)
        assertEquals(EventClass.CHAMPIONSHIP, g.eventClass)
        assertEquals("US Open · MS · FINAL", g.eventTitle)
        assertTrue(g.involves(setOf("tennis:championship")))
        assertTrue(g.involves(setOf("tennis:3623")))
        assertFalse(g.involves(setOf("tennis:special")))
    }

    @Test
    fun `a doubles pair is matched by either player`() {
        val g = games.single { it.id == "t-183001" }
        assertEquals(EventClass.SHOWCASE, g.eventClass)
        assertEquals("M. Melo / J. Peers", g.away.short)
        assertEquals("M/P", g.away.abbrev)
        assertEquals(listOf("1013", "2319"), g.away.memberIds)
        assertTrue(g.involves(setOf("tennis:2319")))
        assertTrue(g.involves(setOf("tennis:special")))
        assertFalse(g.involves(setOf("tennis:9999")))
    }

    @Test
    fun `the draw roster names every player once, doubles included`() {
        val players = EspnParser.parseTennisPlayers("tennis", scoreboard)
        val ids = players.map { it.teamId }
        assertTrue("1013" in ids)
        assertTrue("2319" in ids)
        assertTrue("10386" in ids)
        assertEquals(ids.size, ids.distinct().size)
        val tien = players.single { it.teamId == "10386" }
        assertEquals("L. Tien", tien.short)
        assertEquals("https://a.espncdn.com/i/teamlogos/countries/500/usa.png", tien.logoUrl)
        // The non-major's players are followable too; a tour stop is where they are
        // between slams.
        assertTrue("77" in ids)
    }

    @Test
    fun `rankings give both a roster and a table`() {
        val players = EspnParser.parseRankedPlayers("tennis", rankings)
        assertEquals(listOf("3623", "4593"), players.map { it.teamId })
        assertEquals("J. Sinner", players[0].short)
        assertEquals("https://a.espncdn.com/i/teamlogos/countries/500/ita.png", players[0].logoUrl)

        val table = EspnParser.parseRankings(rankings).single()
        assertEquals("ATP", table.title)
        assertEquals(listOf("PTS"), table.headers)
        assertEquals("12800", table.rows[0].values[0])
        assertEquals("2", table.rows[1].rank)
        assertTrue(table.rows[1].allStats.contains("Last week" to "3"))
    }

    @Test
    fun `draw and round codes`() {
        assertEquals("WS", EspnParser.drawCode("Women's Singles"))
        assertEquals("XD", EspnParser.drawCode("Mixed Doubles"))
        assertEquals("R16", EspnParser.roundCode("Round of 16"))
        assertEquals("SF", EspnParser.roundCode("Semifinal"))
        assertEquals("Set 2", AlertText.periodLabel(SportKind.TENNIS, 2))
    }

    @Test
    fun `a third set that is level is crunch time`() {
        val live = games.single { it.id == "t-182728" }
        assertTrue(TickerPlan.isCrunch(live, SportKind.TENNIS))
        val early = live.copy(period = 1)
        assertFalse(TickerPlan.isCrunch(early, SportKind.TENNIS))
    }

    @Test
    fun `the open screen polls a live match and one about to start, not one tomorrow`() {
        val now = 1_000_000L
        val lead = 15 * 60_000L
        val live = games.single { it.id == "t-182728" }
        assertTrue(TickerPlan.screenShouldPoll(live, now, lead))
        val soon = live.copy(state = GameState.PRE, startMillis = now + 5 * 60_000L)
        assertTrue(TickerPlan.screenShouldPoll(soon, now, lead))
        val tomorrow = live.copy(state = GameState.PRE, startMillis = now + 24 * 3_600_000L)
        assertFalse(TickerPlan.screenShouldPoll(tomorrow, now, lead))
        assertFalse(TickerPlan.screenShouldPoll(live.copy(state = GameState.FINAL), now, lead))
    }

    @Test
    fun `fcs is its own league on the same feed`() {
        assertEquals("81", Leagues.FCS.espnGroup)
        assertTrue(EspnParser.scoreboardUrl(Leagues.FCS, "20260901", "20260910").endsWith("&groups=81"))
        assertTrue(EspnParser.standingsUrl(Leagues.FCS).endsWith("&group=81"))
        assertTrue(Leagues.sections.single { it.first == "COLLEGE FOOTBALL" }.second.contains(Leagues.FCS))
    }
}
