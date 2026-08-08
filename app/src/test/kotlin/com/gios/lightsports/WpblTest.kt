package com.gios.lightsports

import com.gios.lightsports.data.Leagues
import com.gios.lightsports.data.WpblParser
import com.gios.lightsports.model.GameState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixtures are trimmed copies of real `/v1/games` and `/v1/teams` responses from the
 * opening weekend, with the quirks left in: scores in two places and two types, a
 * fixture whose teams have not been named, and the one status Presto sends that
 * contains the words for two different states at once.
 */
class WpblTest {

    private val league = Leagues.WPBL

    /** 2026-08-02 12:00Z, the middle of the second day of the league's first season. */
    private val NOW = 1_785_672_000_000L
    private val DAY = 24L * 60 * 60 * 1000

    private val teamsBody = """
    {"count":4,"teams":[
      {"team_id":"9f08or2mffx81409","team_name":"Boston Hunters","logo_url":""},
      {"team_id":"v4gisr4rbgmn67b0","team_name":"Los Angeles Queens",
       "logo_url":"https://static.prestosports.com/action/sports/logos?rpi=WPBL002&sport=bsb"},
      {"team_id":"fttth861nft1j2s7","team_name":"New York Heights","logo_url":""},
      {"team_id":"vhubhz8li07tmgq8","team_name":"San Francisco Firebells","logo_url":""}]}
    """.trimIndent()

    private val gamesBody = """
    {"count":4,"games":[
      {"game_id":"8alsgvzc90ypwphl","home_team_id":"fttth861nft1j2s7",
       "away_team_id":"v4gisr4rbgmn67b0","home_team_name":"New York Heights",
       "away_team_name":"Los Angeles Queens","counts_in_standings":true,
       "status":"Final - Weather Delay","scheduled_start":"2026-08-01T21:00:00Z",
       "venue":"","state":{"home_score":8,"away_score":10,"inning":0,"half":""},
       "presto_data":{"statusCode":0,"score":{"away":"10","home":"8"}}},
      {"game_id":"qk2oug9ikob2a1hl","home_team_id":"9f08or2mffx81409",
       "away_team_id":"vhubhz8li07tmgq8","home_team_name":"Boston Hunters",
       "away_team_name":"San Francisco Firebells","counts_in_standings":true,
       "status":"In Progress - Top of 3rd","scheduled_start":"2026-08-02T23:30:00Z",
       "venue":"Robin Roberts","state":{"home_score":1,"away_score":9,"inning":0,"half":""},
       "presto_data":{"statusCode":-1,"score":{"away":"9","home":"1"}}},
      {"game_id":"gnja1cz7onrn3lmt","home_team_id":"9f08or2mffx81409",
       "away_team_id":"vhubhz8li07tmgq8","home_team_name":"","away_team_name":"",
       "counts_in_standings":true,"status":"Not Started",
       "scheduled_start":"2026-08-02T23:30:00Z","venue":"",
       "presto_data":{"statusCode":-2,"score":{"away":"0","home":"0"},"teams":{}}},
      {"game_id":"yw5gv1ay5saolsy6","home_team_id":"vhubhz8li07tmgq8",
       "away_team_id":"9f08or2mffx81409","home_team_name":"San Francisco WPBL",
       "away_team_name":"Boston WPBL","counts_in_standings":true,"status":"Not Started",
       "scheduled_start":"2026-09-06T22:30:00Z","venue":"",
       "presto_data":{"statusCode":-2,"score":{"away":"0","home":"0"}}}]}
    """.trimIndent()

    // -------------------------------------------------------------------- teams

    @Test
    fun `teams carry a derived abbreviation, not the Presto RPI key`() {
        val teams = WpblParser.parseTeams(league.id, teamsBody)
        assertEquals(4, teams.size)
        assertEquals(listOf("BOS", "LA", "NY", "SF"), teams.map { it.abbrev })
        assertEquals(listOf("Hunters", "Queens", "Heights", "Firebells"), teams.map { it.short })
        assertNull(teams.first { it.abbrev == "BOS" }.logoUrl)
        assertTrue(teams.first { it.abbrev == "LA" }.logoUrl!!.startsWith("https://"))
    }

    // -------------------------------------------------------------------- games

    private fun window(backDays: Long = 4, aheadDays: Long = 11) =
        WpblParser.parseGames(
            league, gamesBody,
            roster = WpblParser.parseTeams(league.id, teamsBody),
            fromMillis = NOW - backDays * DAY,
            toMillis = NOW + aheadDays * DAY,
        )

    @Test
    fun `the whole-season response is cut down to the window`() {
        // The 6 September fixture is a month out; the app's window is eleven days.
        assertEquals(
            listOf("8alsgvzc90ypwphl", "qk2oug9ikob2a1hl", "gnja1cz7onrn3lmt"),
            window().map { it.id },
        )
    }

    @Test
    fun `a game that finished after a rain delay is final, not live`() {
        val g = window().first { it.id == "8alsgvzc90ypwphl" }
        assertEquals(GameState.FINAL, g.state)
        assertEquals(10, g.away.score)
        assertEquals(8, g.home.score)
    }

    @Test
    fun `an active weather delay is off, not an ordinary live game`() {
        // "In Progress - Weather Delay" carries both words the way "Final - Weather
        // Delay" does, and the order the branches are checked in matters the same way
        // in both directions: final wins when the game is actually over, and delay
        // wins over "in progress" when it is not. Getting this backwards is what let a
        // paused game sit in LIVE with no state change to hang a notification off —
        // none when it stopped, and nothing to say when it started back up either.
        val body = """
        {"games":[{"game_id":"1","home_team_id":"a","away_team_id":"b",
          "home_team_name":"A","away_team_name":"B","counts_in_standings":true,
          "status":"In Progress - Weather Delay","scheduled_start":"2026-08-02T23:30:00Z",
          "state":{"home_score":2,"away_score":1},
          "presto_data":{"statusCode":-1,"score":{"away":"1","home":"2"}}}]}
        """.trimIndent()
        val g = WpblParser.parseGames(league, body, fromMillis = NOW - DAY, toMillis = NOW + DAY)
            .single()
        assertEquals(GameState.OFF, g.state)
    }

    @Test
    fun `the inning comes from the status string, which leads the state block`() {
        val g = window().first { it.id == "qk2oug9ikob2a1hl" }
        assertEquals(GameState.LIVE, g.state)
        // state.inning is still 0 here.
        assertEquals(3, g.period)
        assertEquals("In Progress - Top of 3rd", g.statusDetail)
        assertEquals("Robin Roberts", g.venue)
    }

    @Test
    fun `an unnamed fixture is named from the cached team list`() {
        val g = window().first { it.id == "gnja1cz7onrn3lmt" }
        assertEquals(GameState.PRE, g.state)
        assertEquals("Boston Hunters", g.home.displayName)
        assertEquals("San Francisco Firebells", g.away.displayName)
        assertNull(g.venue)
    }

    @Test
    fun `without a roster an unnamed fixture degrades instead of throwing`() {
        val g = WpblParser.parseGames(league, gamesBody).first { it.id == "gnja1cz7onrn3lmt" }
        assertEquals("", g.home.displayName)
        assertEquals("", g.home.abbrev)
    }

    // ---------------------------------------------------------------- standings

    @Test
    fun `standings are computed from finished games only`() {
        val season = WpblParser.parseGames(
            league, gamesBody,
            roster = WpblParser.parseTeams(league.id, teamsBody),
            standingsOnly = true,
        )
        val groups = WpblParser.standings(season, title = league.short)
        assertEquals(1, groups.size)
        assertEquals(listOf("W", "L", "PCT", "GB", "RF", "RA"), groups[0].headers)

        // One decided game, so two teams have a record and the live one is not in it.
        val rows = groups[0].rows
        assertEquals(listOf("Los Angeles Queens", "New York Heights"), rows.map { it.name })
        assertEquals(listOf("1", "0", "1.000", "—", "10", "8"), rows[0].values)
        assertEquals(listOf("0", "1", ".000", "1", "8", "10"), rows[1].values)
        assertEquals("W1", rows[0].allStats.toMap()["Streak"])
        assertEquals("-2", rows[1].allStats.toMap()["Run diff"])
    }

    @Test
    fun `an empty table is empty rather than a header with no rows`() {
        assertTrue(WpblParser.standings(emptyList()).isEmpty())
    }

    // ------------------------------------------------------------ abbreviations

    @Test
    fun `abbreviations take the place, not the nickname`() {
        assertEquals("BOS", WpblParser.abbrev("Boston Hunters"))
        assertEquals("NY", WpblParser.abbrev("New York Heights"))
        assertEquals("SF", WpblParser.abbrev("San Francisco Firebells"))
        assertEquals("LA", WpblParser.abbrev("Los Angeles Queens"))
        // Placeholder rows the schedule ships before a club is announced.
        assertEquals("BOS", WpblParser.abbrev("Boston WPBL"))
        assertEquals("", WpblParser.abbrev(""))
    }
}
