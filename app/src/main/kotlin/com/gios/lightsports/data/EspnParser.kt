package com.gios.lightsports.data

import com.gios.lightsports.model.Game
import com.gios.lightsports.model.GameState
import com.gios.lightsports.model.League
import com.gios.lightsports.model.RaceEvent
import com.gios.lightsports.model.Side
import com.gios.lightsports.model.StandingsGroup
import com.gios.lightsports.model.StandingsRow
import com.gios.lightsports.model.TeamRef
import org.json.JSONArray
import org.json.JSONObject

/**
 * ESPN's site API. Undocumented but stable for a decade, keyless, and identical in
 * shape across every team sport, which is the only reason one parser covers eight
 * leagues. Racing is the exception and gets its own function below.
 */
object EspnParser {

    private const val SITE = "https://site.api.espn.com/apis/site/v2/sports"
    private const val CORE = "https://site.api.espn.com/apis/v2/sports"

    fun scoreboardUrl(league: League, startYmd: String, endYmd: String): String =
        "$SITE/${league.espnPath}/scoreboard?limit=200&dates=$startYmd-$endYmd"

    fun teamsUrl(league: League): String = "$SITE/${league.espnPath}/teams?limit=400"

    /**
     * level=3 asks for divisions rather than conferences. Leagues without divisions
     * ignore it, so it is safe to send everywhere.
     */
    fun standingsUrl(league: League): String = "$CORE/${league.espnPath}/standings?level=3"

    fun raceUrl(league: League, year: Int): String = "$SITE/${league.espnPath}/scoreboard?dates=$year"

    // ---------------------------------------------------------------- teams

    fun parseTeams(leagueId: String, body: String): List<TeamRef> {
        val leagues = JSONObject(body).optJSONArray("sports")
            ?.optJSONObject(0)?.optJSONArray("leagues") ?: return emptyList()
        val out = mutableListOf<TeamRef>()
        for (l in leagues.objects()) {
            for (entry in (l.optJSONArray("teams") ?: JSONArray()).objects()) {
                val t = entry.optJSONObject("team") ?: continue
                // ESPN keeps relocated and defunct clubs in the list; they'd only pad
                // the picker with teams that will never appear on a scoreboard.
                if (t.has("isActive") && !t.optBoolean("isActive", true)) continue
                out += TeamRef(
                    leagueId = leagueId,
                    teamId = t.optString("id"),
                    displayName = t.optString("displayName"),
                    short = t.optString("shortDisplayName").ifEmpty { t.optString("name") },
                    abbrev = t.optString("abbreviation").ifEmpty {
                        t.optString("shortDisplayName").take(3).uppercase()
                    },
                )
            }
        }
        return out.distinctBy { it.teamId }.sortedBy { it.displayName }
    }

    // ---------------------------------------------------------------- games

    fun parseScoreboard(league: League, body: String): List<Game> {
        val events = JSONObject(body).optJSONArray("events") ?: return emptyList()
        val out = mutableListOf<Game>()
        for (e in events.objects()) {
            val comp = e.optJSONArray("competitions")?.optJSONObject(0) ?: continue
            val competitors = comp.optJSONArray("competitors") ?: continue
            var home: Side? = null
            var away: Side? = null
            for (c in competitors.objects()) {
                val side = side(c) ?: continue
                if (c.optString("homeAway") == "home") home = side else away = side
            }
            if (home == null || away == null) continue

            val status = comp.optJSONObject("status") ?: e.optJSONObject("status")
            val type = status?.optJSONObject("type")
            out += Game(
                id = e.optString("id"),
                leagueId = league.id,
                state = state(type),
                startMillis = Iso.millis(e.optString("date")),
                statusDetail = type?.optString("shortDetail")
                    ?.ifEmpty { type.optString("detail") }.orEmpty(),
                period = status?.optInt("period") ?: 0,
                clock = status?.optString("displayClock")?.takeIf { it.isNotEmpty() && it != "0:00" },
                home = home,
                away = away,
                venue = comp.optJSONObject("venue")?.optString("fullName")?.takeIf { it.isNotEmpty() },
                broadcast = broadcast(comp),
                note = comp.optJSONArray("notes")?.optJSONObject(0)
                    ?.optString("headline")?.takeIf { it.isNotEmpty() },
            )
        }
        return out
    }

    private fun side(c: JSONObject): Side? {
        val t = c.optJSONObject("team") ?: return null
        return Side(
            teamId = t.optString("id"),
            displayName = t.optString("displayName"),
            short = t.optString("shortDisplayName").ifEmpty { t.optString("name") },
            abbrev = t.optString("abbreviation").ifEmpty { t.optString("shortDisplayName").take(3) },
            score = c.optString("score").toIntOrNull(),
            record = c.optJSONArray("records")?.objects()
                ?.firstOrNull { it.optString("type") == "total" }
                ?.optString("summary")?.takeIf { it.isNotEmpty() },
            lineScore = (c.optJSONArray("linescores") ?: JSONArray()).objects().map {
                val display = it.optString("displayValue")
                if (display.isNotEmpty()) display else fmtNum(it.optDouble("value", 0.0))
            },
            hits = c.optInt("hits", -1).takeIf { it >= 0 },
            errors = c.optInt("errors", -1).takeIf { it >= 0 },
        )
    }

    /**
     * Prefer whatever is on nationally; the home and away regional feeds are the
     * wrong answer for at least one of the two fan bases.
     */
    private fun broadcast(comp: JSONObject): String? {
        val casts = comp.optJSONArray("broadcasts")?.objects() ?: return null
        val pick = casts.firstOrNull { it.optString("market") == "national" } ?: casts.firstOrNull()
        val names = pick?.optJSONArray("names") ?: return null
        return (0 until names.length()).map { names.optString(it) }
            .filter { it.isNotEmpty() }.joinToString("/").takeIf { it.isNotEmpty() }
    }

    private fun state(type: JSONObject?): GameState = when (type?.optString("state")) {
        "in" -> GameState.LIVE
        "post" -> if (type.optBoolean("completed", true)) GameState.FINAL else GameState.OFF
        "pre" -> if (type.optString("name").contains("POSTPONED")) GameState.OFF else GameState.PRE
        else -> GameState.PRE
    }

    // ---------------------------------------------------------------- racing

    /**
     * ESPN models a Grand Prix as one event holding five competitions — the practice
     * sessions, qualifying and the race. The feed wants one card per weekend showing
     * the next session, so the sessions are collapsed here rather than in the UI.
     */
    fun parseRaces(league: League, body: String, nowMillis: Long): List<RaceEvent> {
        val events = JSONObject(body).optJSONArray("events") ?: return emptyList()
        val out = mutableListOf<RaceEvent>()
        for (e in events.objects()) {
            val comps = e.optJSONArray("competitions")?.objects() ?: emptyList()
            val race = comps.lastOrNull()
            val next = comps.firstOrNull { Iso.millis(it.optString("date")) > nowMillis }
            val weekendDone = comps.isNotEmpty() && comps.all {
                it.optJSONObject("status")?.optJSONObject("type")?.optBoolean("completed") == true
            }
            val podium = if (race != null && weekendDone) {
                race.optJSONArray("competitors")?.objects()
                    ?.sortedBy { it.optInt("order", 99) }
                    ?.take(3)
                    ?.mapNotNull { it.optJSONObject("athlete")?.optString("shortName") }
                    .orEmpty()
            } else emptyList()

            val startMillis = Iso.millis(e.optString("date"))
            out += RaceEvent(
                id = e.optString("id"),
                leagueId = league.id,
                name = e.optString("name"),
                shortName = e.optString("shortName").ifEmpty { e.optString("name") },
                state = when {
                    weekendDone -> GameState.FINAL
                    next == null && startMillis <= nowMillis -> GameState.LIVE
                    startMillis <= nowMillis -> GameState.LIVE
                    else -> GameState.PRE
                },
                startMillis = startMillis,
                sessionLabel = (next ?: race)?.optJSONObject("type")
                    ?.optString("abbreviation")?.takeIf { it.isNotEmpty() },
                sessionMillis = (next ?: race)?.let { Iso.millis(it.optString("date")) },
                podium = podium,
                circuit = e.optJSONObject("circuit")?.optString("fullName")?.takeIf { it.isNotEmpty() },
            )
        }
        return out
    }

    // ------------------------------------------------------------- standings

    /** Which columns to show, per sport, and what to call them in six characters. */
    private fun columnsFor(league: League): List<Pair<String, String>> = when (league.kind) {
        com.gios.lightsports.model.SportKind.SOCCER -> listOf(
            "gamesPlayed" to "GP", "points" to "PTS", "wins" to "W",
            "ties" to "D", "losses" to "L",
        )
        com.gios.lightsports.model.SportKind.HOCKEY -> listOf(
            "gamesPlayed" to "GP", "wins" to "W", "losses" to "L",
            "otLosses" to "OTL", "points" to "PTS",
        )
        com.gios.lightsports.model.SportKind.BASEBALL -> listOf(
            "wins" to "W", "losses" to "L", "winPercent" to "PCT", "gamesBehind" to "GB",
        )
        else -> listOf(
            "wins" to "W", "losses" to "L", "winPercent" to "PCT", "gamesBehind" to "GB",
        )
    }

    fun parseStandings(league: League, body: String): List<StandingsGroup> {
        val root = JSONObject(body)
        val cols = columnsFor(league)
        val groups = mutableListOf<StandingsGroup>()
        walkStandings(root, cols, groups, prefix = null)
        return groups
    }

    /**
     * The standings tree nests differently per league — conference then division for
     * the NBA, league then division for MLB, one flat table for the NWSL — so it is
     * walked rather than indexed.
     */
    private fun walkStandings(
        node: JSONObject,
        cols: List<Pair<String, String>>,
        out: MutableList<StandingsGroup>,
        prefix: String?,
    ) {
        val name = node.optString("name").takeIf { it.isNotEmpty() && it != "null" }
        val title = listOfNotNull(prefix, name).lastOrNull()

        node.optJSONObject("standings")?.optJSONArray("entries")?.let { entries ->
            val rows = entries.objects().mapIndexedNotNull { i, entry ->
                val stats = entry.optJSONArray("stats")?.objects().orEmpty()
                fun stat(key: String) = stats.firstOrNull { it.optString("name") == key }
                    ?.optString("displayValue")?.takeIf { it.isNotEmpty() } ?: "-"
                val team = entry.optJSONObject("team")
                val athlete = entry.optJSONObject("athlete")
                val label = team?.optString("displayName")
                    ?: athlete?.optString("displayName")
                    ?: return@mapIndexedNotNull null
                StandingsRow(
                    rank = stat("playoffSeed").takeIf { it != "-" } ?: (i + 1).toString(),
                    name = label,
                    abbrev = (team?.optString("abbreviation")
                        ?: athlete?.optString("abbreviation")).orEmpty(),
                    values = cols.map { stat(it.first) },
                    teamId = team?.optString("id"),
                )
            }
            if (rows.isNotEmpty()) {
                out += StandingsGroup(
                    title = title ?: "Standings",
                    headers = cols.map { it.second },
                    rows = rows,
                )
            }
        }

        for (child in (node.optJSONArray("children") ?: JSONArray()).objects()) {
            walkStandings(child, cols, out, prefix = name)
        }
    }

    /** F1 keeps two tables under one endpoint and scores them in championship points. */
    fun parseRacingStandings(body: String): List<StandingsGroup> {
        val root = JSONObject(body)
        val out = mutableListOf<StandingsGroup>()
        for (child in (root.optJSONArray("children") ?: JSONArray()).objects()) {
            val entries = child.optJSONObject("standings")?.optJSONArray("entries") ?: continue
            val rows = entries.objects().mapIndexedNotNull { i, entry ->
                val stats = entry.optJSONArray("stats")?.objects().orEmpty()
                fun stat(key: String) = stats.firstOrNull { it.optString("name") == key }
                    ?.optString("displayValue")?.takeIf { it.isNotEmpty() } ?: "-"
                val who = entry.optJSONObject("athlete") ?: entry.optJSONObject("team")
                    ?: return@mapIndexedNotNull null
                StandingsRow(
                    rank = stat("rank").takeIf { it != "-" } ?: (i + 1).toString(),
                    name = who.optString("displayName"),
                    abbrev = who.optString("abbreviation"),
                    values = listOf(stat("championshipPts")),
                )
            }
            if (rows.isNotEmpty()) {
                out += StandingsGroup(child.optString("name"), listOf("PTS"), rows)
            }
        }
        return out
    }
}

// -------------------------------------------------------------------- helpers

internal fun JSONArray.objects(): List<JSONObject> =
    (0 until length()).mapNotNull { optJSONObject(it) }

internal fun fmtNum(d: Double): String =
    if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
