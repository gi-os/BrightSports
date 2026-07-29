package com.gios.lightsports

import com.gios.lightsports.data.EspnParser
import com.gios.lightsports.data.HockeyTechParser
import com.gios.lightsports.data.Leagues
import com.gios.lightsports.data.StatsApiParser
import com.gios.lightsports.model.GameState
import com.gios.lightsports.notify.AlertText
import com.gios.lightsports.model.SportKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixtures are trimmed copies of real responses — the field names and the quirks
 * (scores as strings, half-innings that omit "runs", parenthesis-wrapped JSON) are
 * exactly as the providers send them.
 */
class ParserTest {

    // ------------------------------------------------------------------- ESPN

    private val espnScoreboard = """
    {"events":[{"id":"401816311","date":"2026-07-29T16:10Z",
      "name":"Philadelphia Phillies at Miami Marlins","shortName":"PHI @ MIA",
      "competitions":[{
        "venue":{"fullName":"loanDepot park"},
        "status":{"clock":0.0,"displayClock":"0:00","period":6,
          "type":{"state":"in","completed":false,"detail":"Middle 6th","shortDetail":"Mid 6th"}},
        "broadcasts":[{"market":"home","names":["Marlins.TV"]},
                      {"market":"national","names":["MLB.TV"]}],
        "competitors":[
          {"homeAway":"home","score":"3","hits":5,"errors":0,
           "team":{"id":"28","displayName":"Miami Marlins","shortDisplayName":"Marlins",
                   "abbreviation":"MIA"},
           "linescores":[{"value":0.0,"displayValue":"0","period":1},
                         {"value":3.0,"displayValue":"3","period":5}],
           "records":[{"type":"total","summary":"54-54"},{"type":"home","summary":"33-23"}]},
          {"homeAway":"away","score":"1",
           "team":{"id":"22","displayName":"Philadelphia Phillies","shortDisplayName":"Phillies",
                   "abbreviation":"PHI"},
           "linescores":[{"value":1.0,"displayValue":"1","period":1}],
           "records":[{"type":"total","summary":"60-48"}]}]}]}]}
    """.trimIndent()

    @Test
    fun `espn scoreboard parses a live game`() {
        val game = EspnParser.parseScoreboard(Leagues.MLB, espnScoreboard).single()
        assertEquals(GameState.LIVE, game.state)
        assertEquals("Mid 6th", game.statusDetail)
        assertEquals(6, game.period)
        assertEquals("Marlins", game.home.short)
        assertEquals(3, game.home.score)
        assertEquals(1, game.away.score)
        assertEquals("54-54", game.home.record)
        assertEquals("loanDepot park", game.venue)
        assertEquals(listOf("0", "3"), game.home.lineScore)
        assertEquals(5, game.home.hits)
    }

    @Test
    fun `the national broadcast wins over the regional feeds`() {
        // Either regional feed is the wrong answer for one of the two fan bases.
        val game = EspnParser.parseScoreboard(Leagues.MLB, espnScoreboard).single()
        assertEquals("MLB.TV", game.broadcast)
    }

    @Test
    fun `a followed team is matched by league and id together`() {
        val game = EspnParser.parseScoreboard(Leagues.MLB, espnScoreboard).single()
        assertTrue(game.involves(setOf("mlb:28")))
        // Team id 28 also exists in the NBA; it must not match there.
        assertTrue(!game.involves(setOf("nba:28")))
    }

    @Test
    fun `espn team lists drop inactive clubs`() {
        val body = """
        {"sports":[{"leagues":[{"teams":[
          {"team":{"id":"1","displayName":"Atlanta Dream","shortDisplayName":"Dream",
                   "abbreviation":"ATL","isActive":true}},
          {"team":{"id":"99","displayName":"Defunct Franchise","shortDisplayName":"Defunct",
                   "abbreviation":"DEF","isActive":false}}]}]}]}
        """.trimIndent()
        val teams = EspnParser.parseTeams("wnba", body)
        assertEquals(listOf("Atlanta Dream"), teams.map { it.displayName })
        assertEquals("wnba:1", teams.single().key)
    }

    @Test
    fun `a postponed game is neither upcoming nor final`() {
        val body = """
        {"events":[{"id":"1","date":"2026-07-29T16:10Z","competitions":[{
          "status":{"period":0,"type":{"state":"pre","name":"STATUS_POSTPONED",
                    "shortDetail":"Postponed"}},
          "competitors":[
            {"homeAway":"home","team":{"id":"1","displayName":"A","shortDisplayName":"A",
             "abbreviation":"A"}},
            {"homeAway":"away","team":{"id":"2","displayName":"B","shortDisplayName":"B",
             "abbreviation":"B"}}]}]}]}
        """.trimIndent()
        assertEquals(
            GameState.OFF,
            EspnParser.parseScoreboard(Leagues.MLB, body).single().state,
        )
    }

    @Test
    fun `espn standings walk down to divisions`() {
        val body = """
        {"name":"MLB","children":[
          {"name":"American League","children":[
            {"name":"AL East","standings":{"entries":[
              {"team":{"id":"30","displayName":"Tampa Bay Rays","abbreviation":"TB"},
               "stats":[{"name":"wins","displayValue":"62"},
                        {"name":"losses","displayValue":"44"},
                        {"name":"winPercent","displayValue":".585"},
                        {"name":"gamesBehind","displayValue":"-"},
                        {"name":"playoffSeed","displayValue":"1"}]}]}}]}]}
        """.trimIndent()
        val groups = EspnParser.parseStandings(Leagues.MLB, body)
        assertEquals("AL East", groups.single().title)
        assertEquals(listOf("W", "L", "PCT", "GB"), groups.single().headers)
        val row = groups.single().rows.single()
        assertEquals("1", row.rank)
        assertEquals(listOf("62", "44", ".585", "-"), row.values)
    }

    @Test
    fun `f1 collapses a weekend of sessions into one card`() {
        val body = """
        {"events":[{"id":"600057440","date":"2026-07-24T11:30Z",
          "name":"AWS Hungarian Grand Prix","shortName":"AWS Hungarian GP",
          "competitions":[
            {"date":"2026-07-24T11:30Z","type":{"abbreviation":"FP1"},
             "status":{"type":{"state":"post","completed":true}},"competitors":[]},
            {"date":"2026-07-26T13:00Z","type":{"abbreviation":"Race"},
             "status":{"type":{"state":"post","completed":true}},
             "competitors":[
               {"order":1,"athlete":{"shortName":"L. Norris"}},
               {"order":2,"athlete":{"shortName":"C. Leclerc"}},
               {"order":3,"athlete":{"shortName":"K. Antonelli"}},
               {"order":4,"athlete":{"shortName":"G. Russell"}}]}]}]}
        """.trimIndent()
        val races = EspnParser.parseRaces(Leagues.F1, body, nowMillis = 1_785_000_000_000)
        val race = races.single()
        assertEquals(GameState.FINAL, race.state)
        assertEquals(listOf("L. Norris", "C. Leclerc", "K. Antonelli"), race.podium)
    }

    // -------------------------------------------------------------- StatsAPI

    private val statsApiSchedule = """
    {"dates":[{"games":[{
      "gamePk":814751,"gameDate":"2026-07-28T19:05:00Z",
      "status":{"abstractGameState":"Live","detailedState":"In Progress"},
      "venue":{"name":"Cheney Stadium"},
      "seriesDescription":"Regular Season",
      "teams":{
        "away":{"score":4,"leagueRecord":{"wins":56,"losses":44},
                "team":{"id":105,"name":"Sacramento River Cats","teamName":"River Cats",
                        "abbreviation":"SAC","parentOrgName":"San Francisco Giants"}},
        "home":{"score":5,"leagueRecord":{"wins":50,"losses":50},
                "team":{"id":529,"name":"Tacoma Rainiers","teamName":"Rainiers",
                        "abbreviation":"TAC","parentOrgName":"Seattle Mariners"}}},
      "linescore":{"currentInning":9,"currentInningOrdinal":"9th","inningState":"Top",
        "teams":{"home":{"hits":9,"errors":0},"away":{"hits":7,"errors":1}},
        "innings":[
          {"num":1,"home":{"runs":0},"away":{"runs":0}},
          {"num":9,"away":{"runs":1}}]},
      "broadcasts":[{"type":"TV","name":"MiLB.TV"}]}]}]}
    """.trimIndent()

    @Test
    fun `statsapi schedule parses a minor league game`() {
        val game = StatsApiParser.parseSchedule(Leagues.AAA, statsApiSchedule).single()
        assertEquals(GameState.LIVE, game.state)
        assertEquals("Top 9th", game.statusDetail)
        assertEquals(5, game.home.score)
        assertEquals("56-44", game.away.record)
        assertEquals("MiLB.TV", game.broadcast)
    }

    @Test
    fun `a half inning that has not been played shows blank, not zero`() {
        // The bottom of the ninth in a walk-off has no "runs" key at all.
        val game = StatsApiParser.parseSchedule(Leagues.AAA, statsApiSchedule).single()
        assertEquals(listOf("0", "-"), game.home.lineScore)
        assertEquals(listOf("0", "1"), game.away.lineScore)
    }

    @Test
    fun `minor league teams carry their parent club`() {
        val body = """
        {"teams":[{"id":105,"name":"Sacramento River Cats","teamName":"River Cats",
                   "abbreviation":"SAC","parentOrgName":"San Francisco Giants"}]}
        """.trimIndent()
        val team = StatsApiParser.parseTeams("milb-aaa", body).single()
        assertEquals("Sacramento River Cats (SFG)", team.displayName)
    }

    // ------------------------------------------------------------ HockeyTech

    private val pwhlScorebar = """
    {"SiteKit":{"Scorebar":[{
      "ID":"343","GameDateISO8601":"2026-05-08T19:00:00-04:00",
      "HomeID":"5","HomeCode":"OTT","HomeNickname":"Charge","HomeLongName":"Ottawa Charge",
      "HomeGoals":"2","HomeWins":"4","HomeRegulationLosses":"4","HomeOTLosses":"0",
      "HomeShootoutLosses":"1",
      "VisitorID":"1","VisitorCode":"BOS","VisitorNickname":"Fleet",
      "VisitorLongName":"Boston Fleet","VisitorGoals":"1","VisitorWins":"1",
      "VisitorRegulationLosses":"3","VisitorOTLosses":"0","VisitorShootoutLosses":"0",
      "Period":"3","PeriodNameLong":"3rd","PeriodNameShort":"3","GameClock":"00:00",
      "GameStatus":"4","Intermission":"0","GameStatusStringLong":"Final",
      "venue_name":"Canadian Tire Centre | Ottawa"}]}}
    """.trimIndent()

    @Test
    fun `pwhl scorebar parses a finished game`() {
        val game = HockeyTechParser.parseScorebar(Leagues.PWHL, pwhlScorebar).single()
        assertEquals(GameState.FINAL, game.state)
        assertEquals("Final", game.statusDetail)
        assertEquals(2, game.home.score)
        assertEquals("Charge", game.home.short)
        // Shootout losses fold into the OT column, the way a hockey record is written.
        assertEquals("4-4-1", game.home.record)
        assertNull(game.clock)
    }

    @Test
    fun `pwhl game status is read from the code, not the string`() {
        val live = pwhlScorebar.replace("\"GameStatus\":\"4\"", "\"GameStatus\":\"2\"")
        assertEquals(
            GameState.LIVE,
            HockeyTechParser.parseScorebar(Leagues.PWHL, live).single().state,
        )
    }

    @Test
    fun `pwhl standings survive the parenthesis wrapper`() {
        val body = """
        ([{"sections":[{"title":"PWHL","data":[
          {"row":{"rank":"1","name":"Boston Fleet","team_code":"BOS","games_played":"30",
                  "regulation_wins":"18","losses":"8","non_reg_losses":"4","points":"58"}}]}]}])
        """.trimIndent()
        val group = HockeyTechParser.parseStandings(body).single()
        assertEquals("PWHL", group.title)
        assertEquals(listOf("GP", "W", "L", "OTL", "PTS"), group.headers)
        assertEquals(listOf("30", "18", "8", "4", "58"), group.rows.single().values)
    }

    @Test
    fun `the newest hockeytech season wins`() {
        val body = """
        {"SiteKit":{"Seasons":[{"season_id":"3"},{"season_id":"10"},{"season_id":"9"}]}}
        """.trimIndent()
        assertEquals("10", HockeyTechParser.parseLatestSeasonId(body))
    }

    // -------------------------------------------------------------- wording

    @Test
    fun `period labels follow the sport, and overtime is not a fifth quarter`() {
        assertEquals("Q3", AlertText.periodLabel(SportKind.BASKETBALL, 3))
        assertEquals("OT", AlertText.periodLabel(SportKind.BASKETBALL, 5))
        assertEquals("2OT", AlertText.periodLabel(SportKind.BASKETBALL, 6))
        assertEquals("P2", AlertText.periodLabel(SportKind.HOCKEY, 2))
        assertEquals("OT", AlertText.periodLabel(SportKind.HOCKEY, 4))
        assertEquals("H1", AlertText.periodLabel(SportKind.SOCCER, 1))
        assertEquals("7th", AlertText.periodLabel(SportKind.BASEBALL, 7))
        assertEquals("11th", AlertText.periodLabel(SportKind.BASEBALL, 11))
        assertEquals("21st", AlertText.periodLabel(SportKind.BASEBALL, 21))
        assertEquals("", AlertText.periodLabel(SportKind.BASEBALL, 0))
    }
}
