package com.gios.lightsports

import com.gios.lightsports.data.RelaySnapshot
import com.gios.lightsports.model.Game
import com.gios.lightsports.model.GameState
import com.gios.lightsports.model.Side
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RelaySnapshotTest {

    private val game = Game(
        id = "401872661", leagueId = "nfl", state = GameState.PRE, startMillis = 1L,
        statusDetail = "Sun 1:00 PM", period = 0, clock = null,
        home = Side("26", "Seattle Seahawks", "Seahawks", "SEA", null, record = "1-0"),
        away = Side("17", "New England Patriots", "Patriots", "NE", null, record = "0-1"),
        venue = "Lumen Field", broadcast = "NBC", week = 2,
    )

    private val live = JSONObject("""
        {"v":1,"id":"401872661","st":"in","nm":"STATUS_IN_PROGRESS","dt":"3:24 - 2nd","done":false,
         "p":2,"ck":"3:24","home":{"id":"26","ab":"SEA","sc":14,"ls":["7","7"]},"away":{"id":"17","ab":"NE","sc":7,"ls":["0","7"]},
         "sit":{"poss":"26","dd":"2nd & 7 at NE 16","sdd":"2nd & 7","spot":"NE 16","down":2,"dist":7,"rz":true,"hto":3,"ato":2,
                "lp":"K.Walker III run for 9 yards","drive":"7 plays, 58 yards, 3:41"},"ts":1}
    """)

    @Test
    fun `a live message overlays the moving parts and keeps the rest`() {
        val g = RelaySnapshot.apply(game, live)
        assertEquals(GameState.LIVE, g.state)
        assertEquals(14, g.home.score); assertEquals(7, g.away.score)
        assertEquals(listOf("7", "7"), g.home.lineScore)
        assertEquals(2, g.period); assertEquals("3:24", g.clock)
        assertEquals("3:24 - 2nd", g.statusDetail)
        assertEquals("26", g.situation?.possession)
        assertTrue(g.situation?.isRedZone == true)
        assertEquals(16, g.situation?.yardsToGoal("SEA"))
        // Untouched: names, venue, network, week, records.
        assertEquals("Seattle Seahawks", g.home.displayName)
        assertEquals("Lumen Field", g.venue); assertEquals("NBC", g.broadcast); assertEquals(2, g.week)
        assertEquals("1-0", g.home.record)
    }

    @Test
    fun `the same message twice is the same instance`() {
        val once = RelaySnapshot.apply(game, live)
        assertSame(once, RelaySnapshot.apply(once, live))
    }

    @Test
    fun `a message for another game or a swapped side is ignored`() {
        val other = JSONObject(live.toString()).put("id", "999")
        assertSame(game, RelaySnapshot.apply(game, other))
        val swapped = JSONObject(live.toString())
        swapped.getJSONObject("home").put("id", "17")
        val g = RelaySnapshot.apply(game, swapped)
        // Home keeps its own score (the message's home block named the wrong team).
        assertNull(g.home.score); assertEquals(7, g.away.score)
    }

    @Test
    fun `a final and a delay read as the parser reads them`() {
        val final = JSONObject("""{"id":"401872661","st":"post","nm":"STATUS_FINAL","dt":"Final","done":true,"p":4,"ck":"0:00",
            "home":{"id":"26","sc":24},"away":{"id":"17","sc":17}}""")
        val g = RelaySnapshot.apply(game, final)
        assertEquals(GameState.FINAL, g.state); assertNull(g.clock); assertNull(g.situation)
        val delayed = JSONObject(live.toString()).put("nm", "STATUS_RAIN_DELAY")
        assertEquals(GameState.OFF, RelaySnapshot.apply(game, delayed).state)
    }

    @Test
    fun `the ntfy envelope is unwrapped and keepalives are dropped`() {
        val line = """{"id":"oKOF2r6aYnIY","time":1789056342,"event":"message","topic":"bs-401872661","message":"{\"id\":\"401872661\",\"st\":\"in\"}"}"""
        val (topic, body) = RelaySnapshot.unwrap(line)!!
        assertEquals("bs-401872661", topic)
        assertEquals("401872661", RelaySnapshot.gameId(body))
        assertNull(RelaySnapshot.unwrap("""{"id":"x","event":"keepalive","topic":"bs-401872661"}"""))
        assertNull(RelaySnapshot.unwrap("not json"))
        assertEquals("bs-401872661", RelaySnapshot.topicFor("401872661"))
    }
}
