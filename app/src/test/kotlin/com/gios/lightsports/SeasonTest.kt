package com.gios.lightsports

import com.gios.lightsports.data.EspnParser
import com.gios.lightsports.data.Feed
import com.gios.lightsports.data.Iso
import com.gios.lightsports.data.Leagues
import com.gios.lightsports.model.Game
import com.gios.lightsports.model.GameState
import com.gios.lightsports.model.Side
import com.gios.lightsports.model.TeamSeason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId

class SeasonTest {

    private val zone = ZoneId.of("America/New_York")

    private fun game(id: String, state: GameState, iso: String, week: Int?, league: String = "nfl", home: Side, away: Side) = Game(
        id = id, leagueId = league, state = state, startMillis = Iso.millis(iso), statusDetail = "",
        home = home, away = away, week = week,
    )
    private val sea = Side("26", "Seattle Seahawks", "Seahawks", "SEA", null)
    private val ne = Side("17", "New England Patriots", "Patriots", "NE", null)

    @Test
    fun `the schedule shape parses through the scoreboard parser`() {
        // A finished game on a team schedule: score is an object, record is `record[]`,
        // the network is under media.shortName.
        val body = """
        {"byeWeek":11,"events":[{"id":"1","date":"2026-09-06T20:25Z","week":{"number":1},
          "competitions":[{"id":"1","neutralSite":false,
            "status":{"type":{"name":"STATUS_FINAL","state":"post","completed":true,"shortDetail":"Final"},"period":4},
            "broadcasts":[{"market":{"type":"National"},"media":{"shortName":"FOX"}}],
            "competitors":[
              {"homeAway":"home","team":{"id":"26","displayName":"Seattle Seahawks","shortDisplayName":"Seahawks","abbreviation":"SEA"},
               "score":{"value":24.0,"displayValue":"24"},"winner":true,"record":[{"type":"total","displayValue":"1-0"}]},
              {"homeAway":"away","team":{"id":"25","displayName":"San Francisco 49ers","shortDisplayName":"49ers","abbreviation":"SF"},
               "score":{"value":17.0,"displayValue":"17"},"winner":false,"record":[{"type":"total","displayValue":"0-1"}]}
            ]}]}]}
        """
        val games = EspnParser.parseScoreboard(Leagues.NFL, body)
        assertEquals(1, games.size)
        val g = games.single()
        assertEquals(24, g.home.score)
        assertEquals(17, g.away.score)
        assertEquals("1-0", g.home.record)
        assertEquals("FOX", g.broadcast)
        assertEquals(1, g.week)
        assertEquals(GameState.FINAL, g.state)
        assertEquals(11, EspnParser.parseByeWeek(body))
        assertNull(EspnParser.parseByeWeek("{\"events\":[]}"))
    }

    @Test
    fun `a season knows its record and its next game`() {
        val now = Iso.millis("2026-09-09T19:00:00Z")
        val season = TeamSeason("nfl", "26", listOf(
            game("1", GameState.FINAL, "2026-09-06T20:25Z", 1, home = sea.copy(score = 24), away = ne.copy(score = 17)),
            game("2", GameState.PRE, "2026-09-13T20:25Z", 2, home = ne, away = sea),
            game("3", GameState.PRE, "2026-09-20T17:00Z", 3, home = sea, away = ne),
        ), byeWeek = 11)
        assertEquals(1 to 0, season.record())
        assertEquals("2", season.next(now)?.id)
    }

    @Test
    fun `the week title prefers the NFL week and falls back to dates`() {
        val nfl = game("1", GameState.PRE, "2026-09-13T17:00Z", 2, home = sea, away = ne)
        val cfb = game("2", GameState.PRE, "2026-09-12T19:30Z", 3, league = "cfb", home = sea, away = ne)
        val from = Iso.millis("2026-09-10T12:00Z"); val to = Iso.millis("2026-09-15T12:00Z")
        assertEquals("WEEK 2", Feed.weekTitle(listOf(nfl, cfb), from, to, zone))
        // No week numbers: the date range.
        val mlb = game("3", GameState.PRE, "2026-09-12T23:05Z", null, league = "mlb", home = sea, away = ne)
        assertEquals("SEP 10 – 15", Feed.weekTitle(listOf(mlb), from, to, zone))
        // Mostly baseball with one football game: still dates.
        assertEquals("SEP 10 – 15", Feed.weekTitle(listOf(mlb, mlb, mlb, nfl), from, to, zone))
        assertEquals("SEP 28 – OCT 3", Feed.weekTitle(emptyList(), Iso.millis("2026-09-28T12:00Z"), Iso.millis("2026-10-03T12:00Z"), zone))
    }

    @Test
    fun `the record line counts followed teams' finals only`() {
        val follows = setOf("nfl:26")
        val games = listOf(
            game("1", GameState.FINAL, "2026-09-06T20:25Z", 1, home = sea.copy(score = 24), away = ne.copy(score = 17)),
            game("2", GameState.FINAL, "2026-09-07T20:25Z", 1, home = ne.copy(score = 30), away = sea.copy(score = 10)),
            game("3", GameState.PRE, "2026-09-13T20:25Z", 2, home = ne, away = sea),
        )
        assertEquals("1–1 FOR YOUR TEAMS", Feed.recordLine(games, follows))
        assertNull(Feed.recordLine(games.drop(2), follows))
        // Both sides followed: the game says nothing about "your" record.
        assertNull(Feed.recordLine(games.take(1), setOf("nfl:26", "nfl:17")))
    }
}
