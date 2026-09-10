package com.gios.lightsports.data

import com.gios.lightsports.model.Game
import com.gios.lightsports.model.GameState
import com.gios.lightsports.model.Side
import com.gios.lightsports.model.Situation
import org.json.JSONObject

/**
 * What the BasilNet relay says about one game, and how it lands on the [Game] the phone
 * already holds.
 *
 * The relay (see `relay/main.py` in the BrightSports repo) publishes a compact snapshot to
 * the ntfy topic `bs-<eventId>` every time a field the phone diffs changes — score, state,
 * period, clock, possession, the count. It carries ids and abbreviations, never names or
 * venues: the phone has those from its own scoreboard fetch, and this only overlays the
 * parts that move. A message for a game the phone does not have is dropped.
 *
 * Kept pure so the wire format is unit-tested without a socket.
 */
object RelaySnapshot {

    const val TOPIC_PREFIX = "bs-"
    const val HEARTBEAT_TOPIC = "bs-relay"

    /** The ntfy topic the relay publishes a game on. */
    fun topicFor(gameId: String): String = TOPIC_PREFIX + gameId

    /** The event id inside a relay message, or null if it is not a game message. */
    fun gameId(message: JSONObject): String? =
        message.optString("id").takeIf { it.isNotEmpty() }

    /**
     * Overlay a relay message onto the phone's copy of the game. Fields the message does
     * not carry keep their values. Returns the same instance when nothing changed, so the
     * caller can skip a redraw.
     */
    fun apply(game: Game, message: JSONObject): Game {
        if (gameId(message) != game.id) return game
        val state = when (message.optString("st")) {
            "in" -> GameState.LIVE
            "post" -> if (message.optBoolean("done", true)) GameState.FINAL else GameState.OFF
            "pre" -> GameState.PRE
            else -> game.state
        }
        val name = message.optString("nm").takeIf { it.isNotEmpty() } ?: game.statusName
        // The same delay-rides-on-top-of-state rule as the ESPN parser.
        val upper = name.orEmpty().uppercase()
        val offBecauseNamed = listOf("DELAY", "SUSPEND", "POSTPON", "CANCEL").any { it in upper }
        val situation = message.optJSONObject("sit")?.let { situation(it) }
            ?: if (state == GameState.LIVE) game.situation else null
        val updated = game.copy(
            state = if (offBecauseNamed) GameState.OFF else state,
            statusName = name,
            statusDetail = message.optString("dt").takeIf { it.isNotEmpty() } ?: game.statusDetail,
            period = if (message.has("p") && !message.isNull("p")) message.optInt("p") else game.period,
            clock = message.optString("ck").takeIf { it.isNotEmpty() && it != "0:00" }
                ?: if (message.has("ck")) null else game.clock,
            home = side(game.home, message.optJSONObject("home")),
            away = side(game.away, message.optJSONObject("away")),
            situation = situation,
        )
        return if (updated == game) game else updated
    }

    private fun side(current: Side, m: JSONObject?): Side {
        if (m == null) return current
        // Ids must agree; a relay message is never allowed to swap the sides.
        val id = m.optString("id")
        if (id.isNotEmpty() && id != current.teamId) return current
        val ls = m.optJSONArray("ls")
        return current.copy(
            score = if (m.has("sc") && !m.isNull("sc")) m.optInt("sc") else current.score,
            lineScore = if (ls != null) (0 until ls.length()).map { ls.optString(it) } else current.lineScore,
            hits = if (m.has("h")) m.optInt("h") else current.hits,
            errors = if (m.has("e")) m.optInt("e") else current.errors,
        )
    }

    private fun situation(s: JSONObject): Situation = Situation(
        possession = s.optString("poss").takeIf { it.isNotEmpty() },
        downDistance = s.optString("dd").takeIf { it.isNotEmpty() },
        shortDownDistance = s.optString("sdd").takeIf { it.isNotEmpty() },
        spot = s.optString("spot").takeIf { it.isNotEmpty() },
        down = s.optInt("down", -1).takeIf { it > 0 },
        distance = s.optInt("dist", -1).takeIf { it >= 0 },
        isRedZone = s.optBoolean("rz", false),
        homeTimeouts = s.optInt("hto", -1).takeIf { it >= 0 },
        awayTimeouts = s.optInt("ato", -1).takeIf { it >= 0 },
        lastPlay = s.optString("lp").takeIf { it.isNotEmpty() },
        drive = s.optString("drive").takeIf { it.isNotEmpty() },
        balls = s.optInt("b", -1).takeIf { it >= 0 },
        strikes = s.optInt("s", -1).takeIf { it >= 0 },
        outs = s.optInt("o", -1).takeIf { it >= 0 },
        onFirst = s.optBoolean("on1", false),
        onSecond = s.optBoolean("on2", false),
        onThird = s.optBoolean("on3", false),
        batter = s.optString("bat").takeIf { it.isNotEmpty() },
        pitcher = s.optString("pit").takeIf { it.isNotEmpty() },
        batterSummary = s.optString("bats").takeIf { it.isNotEmpty() },
        pitcherSummary = s.optString("pits").takeIf { it.isNotEmpty() },
    )

    /**
     * The ntfy stream wraps each publish: `{"event":"message","topic":"bs-401…",
     * "message":"<the relay's JSON as a string>"}`. Anything that is not a message — the
     * `open` and `keepalive` events — comes back null.
     */
    fun unwrap(line: String): Pair<String, JSONObject>? {
        val outer = runCatching { JSONObject(line) }.getOrNull() ?: return null
        if (outer.optString("event") != "message") return null
        val topic = outer.optString("topic").takeIf { it.isNotEmpty() } ?: return null
        val inner = runCatching { JSONObject(outer.optString("message")) }.getOrNull() ?: return null
        return topic to inner
    }
}
