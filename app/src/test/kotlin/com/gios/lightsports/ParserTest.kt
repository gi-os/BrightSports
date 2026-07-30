package com.gios.lightsports

import com.gios.lightsports.data.EspnParser
import com.gios.lightsports.data.HockeyTechParser
import com.gios.lightsports.data.Leagues
import com.gios.lightsports.data.StatsApiParser
import com.gios.lightsports.model.EventClass
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

    /** 2026-07-29, so the 2026 F1 calendar splits into raced and unraced either side. */
    private val NOW_JULY_2026 = 1_785_000_000_000L

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
    fun `a category star matches an event with neither team followed`() {
        // The MLS All-Star fixture, exactly as it comes down: no headline, competitors
        // that are absent from the 30-club roster.
        val body = """
        {"events":[{"id":"1","date":"2026-07-30T00:00Z","name":"Liga MX All-Stars at MLS All-Stars",
          "season":{"year":2026,"type":13846,"slug":"regular-season"},
          "competitions":[{"notes":[],
            "status":{"period":0,"type":{"state":"pre","shortDetail":"7:00 PM"}},
            "competitors":[
              {"homeAway":"home","team":{"id":"9817","displayName":"MLS All-Stars",
               "shortDisplayName":"MLS","abbreviation":"MLS"}},
              {"homeAway":"away","team":{"id":"20279","displayName":"Liga MX All-Stars",
               "shortDisplayName":"Liga MX","abbreviation":"LIGA MX"}}]}]}]}
        """.trimIndent()
        val roster = setOf("17606", "190")
        val game = EspnParser.parseScoreboard(Leagues.MLS, body, roster).single()
        assertEquals(EventClass.SHOWCASE, game.eventClass)
        // An all-star game lives in `regular-season`, so there is no title to derive —
        // and "Regular Season" would be a worse label than the league's own name.
        assertNull(game.eventTitle)
        assertTrue(game.involves(setOf("mls:special")))
        assertTrue(!game.involves(setOf("mls:championship")))
        assertTrue(!game.involves(setOf("mls:17606")))
    }

    @Test
    fun `without a roster the same fixture falls back to the headline`() {
        // A cold cache means no roster to compare against. The classifier must not
        // invent an event, and must not crash.
        val body = """
        {"events":[{"id":"1","date":"2026-07-30T00:00Z",
          "season":{"slug":"regular-season"},
          "competitions":[{"notes":[],
            "status":{"period":0,"type":{"state":"pre"}},
            "competitors":[
              {"homeAway":"home","team":{"id":"9817","displayName":"MLS All-Stars",
               "shortDisplayName":"MLS","abbreviation":"MLS"}},
              {"homeAway":"away","team":{"id":"20279","displayName":"Liga MX All-Stars",
               "shortDisplayName":"Liga MX","abbreviation":"LIGA"}}]}]}]}
        """.trimIndent()
        val game = EspnParser.parseScoreboard(Leagues.MLS, body).single()
        assertEquals(EventClass.NONE, game.eventClass)
    }

    @Test
    fun `mls cup is titled from the season slug`() {
        val body = """
        {"events":[{"id":"1","date":"2025-12-06T20:30Z",
          "season":{"year":2025,"type":13119,"slug":"mls-cup"},
          "competitions":[{"notes":[],
            "status":{"period":0,"type":{"state":"post","completed":true,"shortDetail":"FT"}},
            "competitors":[
              {"homeAway":"home","score":"3","team":{"id":"20232","displayName":"Inter Miami",
               "shortDisplayName":"Miami","abbreviation":"MIA"}},
              {"homeAway":"away","score":"1","team":{"id":"9727","displayName":"Vancouver",
               "shortDisplayName":"Vancouver","abbreviation":"VAN"}}]}]}]}
        """.trimIndent()
        val game = EspnParser.parseScoreboard(Leagues.MLS, body, setOf("20232", "9727")).single()
        assertEquals(EventClass.CHAMPIONSHIP, game.eventClass)
        assertEquals("MLS Cup", game.eventTitle)
        assertTrue(game.involves(setOf("mls:championship")))
    }

    @Test
    fun `the super bowl keeps its own name and is followable either way`() {
        val body = """
        {"events":[{"id":"1","date":"2026-02-08T23:30Z",
          "season":{"year":2025,"type":3,"slug":"post-season"},
          "competitions":[{"notes":[{"headline":"Super Bowl LX"}],
            "status":{"period":4,"type":{"state":"post","completed":true,"shortDetail":"Final"}},
            "competitors":[
              {"homeAway":"home","score":"24","team":{"id":"26","displayName":"Seattle Seahawks",
               "shortDisplayName":"Seahawks","abbreviation":"SEA"}},
              {"homeAway":"away","score":"21","team":{"id":"17","displayName":"New England Patriots",
               "shortDisplayName":"Patriots","abbreviation":"NE"}}]}]}]}
        """.trimIndent()
        val game = EspnParser.parseScoreboard(Leagues.NFL, body, setOf("26", "17")).single()
        assertEquals(EventClass.CHAMPIONSHIP, game.eventClass)
        assertEquals("Super Bowl LX", game.eventTitle)
        assertTrue(game.involves(setOf("nfl:championship")))
        // It is two real teams, so following either still works.
        assertTrue(game.involves(setOf("nfl:26")))
    }

    @Test
    fun `a cup tie is filed under its league but named after the competition`() {
        // Leagues Cup, exactly as it comes down: MLS ids alongside a Liga MX club that
        // is nowhere in the MLS team list.
        val body = """
        {"events":[{"id":"7","date":"2025-08-01T23:30Z",
          "season":{"year":2025,"slug":"league-phase"},
          "competitions":[{"notes":[],
            "status":{"period":2,"type":{"state":"post","completed":true,"shortDetail":"FT"}},
            "competitors":[
              {"homeAway":"home","score":"2","team":{"id":"17606",
               "displayName":"New York City FC","shortDisplayName":"NYCFC","abbreviation":"NYC"}},
              {"homeAway":"away","score":"1","team":{"id":"228",
               "displayName":"Leon","shortDisplayName":"Leon","abbreviation":"LEO"}}]}]}]}
        """.trimIndent()
        val game = EspnParser.parseScoreboard(
            Leagues.MLS, body, competition = "Leagues Cup",
        ).single()
        assertEquals("mls", game.leagueId)
        assertEquals("Leagues Cup", game.competition)
        // A league-phase tie is an ordinary game you get by following the club — the
        // roster check is deliberately not applied to cups, or the Liga MX side would
        // make every tie look like an all-star fixture.
        assertEquals(EventClass.NONE, game.eventClass)
        assertTrue(game.involves(setOf("mls:17606")))
        assertTrue(!game.involves(setOf("mls:special")))
    }

    @Test
    fun `a cup final counts as a championship`() {
        val body = """
        {"events":[{"id":"8","date":"2026-08-31T23:30Z",
          "season":{"year":2026,"slug":"final"},
          "competitions":[{"notes":[],
            "status":{"period":0,"type":{"state":"pre","shortDetail":"8:00 PM"}},
            "competitors":[
              {"homeAway":"home","team":{"id":"17606","displayName":"New York City FC",
               "shortDisplayName":"NYCFC","abbreviation":"NYC"}},
              {"homeAway":"away","team":{"id":"228","displayName":"Leon",
               "shortDisplayName":"Leon","abbreviation":"LEO"}}]}]}]}
        """.trimIndent()
        val game = EspnParser.parseScoreboard(
            Leagues.MLS, body, competition = "Leagues Cup",
        ).single()
        assertEquals(EventClass.CHAMPIONSHIP, game.eventClass)
        assertTrue(game.involves(setOf("mls:championship")))
    }

    @Test
    fun `mls carries its two cups`() {
        assertEquals(
            listOf("Leagues Cup", "U.S. Open Cup"),
            Leagues.MLS.cups.map { it.name },
        )
        // Paths are full ESPN sport paths, since the scoreboard URL takes them verbatim.
        assertTrue(Leagues.MLS.cups.all { it.path.startsWith("soccer/") })
        assertTrue(Leagues.MLB.cups.isEmpty())
    }

    @Test
    fun `a standings row carries every stat, not just the visible columns`() {
        val body = """
        {"name":"MLS","children":[{"name":"Eastern Conference","standings":{"entries":[
          {"team":{"id":"17606","displayName":"New York City FC","abbreviation":"NYC"},
           "stats":[{"name":"wins","displayValue":"12"},
                    {"name":"losses","displayValue":"7"},
                    {"name":"ties","displayValue":"5"},
                    {"name":"points","shortDisplayName":"PTS","displayValue":"41"},
                    {"name":"avgPointsAgainst","displayValue":"1.2"},
                    {"name":"pointDifferential","shortDisplayName":"DIFF","displayValue":"+9"}]}]}}]}
        """.trimIndent()
        val row = EspnParser.parseStandings(Leagues.MLS, body).single().rows.single()
        val stats = row.allStats.toMap()
        assertEquals("41", stats["PTS"])
        assertEquals("+9", stats["DIFF"])
        // No label from ESPN, so the camelCase key is made readable.
        assertEquals("1.2", stats["Avg points against"])
        // The table itself only shows five soccer columns.
        assertEquals(5, row.values.size)
        assertEquals(6, row.allStats.size)
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

    @Test
    fun `a finished weekend that espn never marked completed is not live`() {
        // Bahrain and Saudi Arabia 2026 both come back with every session at
        // state "post" and completed:false. Keying off the flag pinned them to LIVE at
        // the top of the feed for the rest of the season.
        val body = """
        {"events":[{"id":"1","date":"2026-04-10T11:30Z","endDate":"2026-04-12T15:00Z",
          "name":"Gulf Air Bahrain GP","shortName":"Bahrain GP",
          "competitions":[
            {"date":"2026-04-10T11:30Z","type":{"abbreviation":"FP1"},
             "status":{"type":{"state":"post","completed":false}},"competitors":[]},
            {"date":"2026-04-12T15:00Z","type":{"abbreviation":"Race"},
             "status":{"type":{"state":"post","completed":false}},
             "competitors":[{"order":1,"athlete":{"shortName":"M. Verstappen"}}]}]}]}
        """.trimIndent()
        val race = EspnParser.parseRaces(Leagues.F1, body, nowMillis = NOW_JULY_2026).single()
        assertEquals(GameState.FINAL, race.state)
        assertEquals(listOf("M. Verstappen"), race.podium)
    }

    @Test
    fun `a weekend under way is live only while a session is running`() {
        fun weekend(raceState: String) = """
        {"events":[{"id":"1","date":"2026-07-24T11:30Z","endDate":"2026-07-26T13:00Z",
          "name":"AWS Hungarian GP","shortName":"Hungarian GP",
          "competitions":[
            {"date":"2026-07-24T11:30Z","type":{"abbreviation":"FP1"},
             "status":{"type":{"state":"post","completed":true}},"competitors":[]},
            {"date":"2026-07-26T13:00Z","type":{"abbreviation":"Race"},
             "status":{"type":{"state":"$raceState","completed":false}},"competitors":[]}]}]}
        """.trimIndent()

        // Mid-weekend, race under way.
        val duringRace = 1_784_000_000_000L // 2026-07-25
        assertEquals(
            GameState.LIVE,
            EspnParser.parseRaces(Leagues.F1, weekend("in"), duringRace).single().state,
        )
        // Same weekend, sitting between sessions: upcoming, not live.
        assertEquals(
            GameState.PRE,
            EspnParser.parseRaces(Leagues.F1, weekend("pre"), duringRace).single().state,
        )
    }

    @Test
    fun `a future grand prix stays upcoming`() {
        val body = """
        {"events":[{"id":"1","date":"2026-12-04T09:30Z","endDate":"2026-12-06T13:00Z",
          "name":"Etihad Airways Abu Dhabi GP","shortName":"Abu Dhabi GP",
          "competitions":[
            {"date":"2026-12-04T09:30Z","type":{"abbreviation":"FP1"},
             "status":{"type":{"state":"pre","completed":false}},"competitors":[]}]}]}
        """.trimIndent()
        val race = EspnParser.parseRaces(Leagues.F1, body, NOW_JULY_2026).single()
        assertEquals(GameState.PRE, race.state)
        assertEquals("FP1", race.sessionLabel)
        assertTrue(race.podium.isEmpty())
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
