package com.gios.lightsports.data

import com.gios.lightsports.model.Game
import com.gios.lightsports.model.GameState
import com.gios.lightsports.model.League
import com.gios.lightsports.model.Side
import com.gios.lightsports.model.StandingsGroup
import com.gios.lightsports.model.StandingsRow
import com.gios.lightsports.model.TeamRef
import org.json.JSONArray
import org.json.JSONObject

/**
 * MLB's StatsAPI, used here only for the minor leagues — ESPN publishes no MiLB
 * scoreboard, and this is the same feed MiLB.com itself runs on. Keyless.
 *
 * `sportId` selects the level: 11 Triple-A, 12 Double-A, 13 High-A, 14 Single-A.
 */
object StatsApiParser {

    private const val BASE = "https://statsapi.mlb.com/api/v1"

    fun scheduleUrl(league: League, startDate: String, endDate: String): String =
        "$BASE/schedule?sportId=${league.statsApiSportId}" +
            "&startDate=$startDate&endDate=$endDate" +
            "&hydrate=team,linescore,venue,broadcasts(all)"

    fun teamsUrl(league: League): String =
        "$BASE/teams?sportId=${league.statsApiSportId}&activeStatus=Y"

    /**
     * Standings are the one endpoint that will not take a `sportId` — it answers with
     * an empty record set and no error. Every level has to be expanded into its own
     * leagues first (Triple-A is the International League plus the Pacific Coast
     * League, and so on) and asked for by `leagueId`.
     */
    fun leaguesUrl(league: League): String =
        "$BASE/leagues?sportId=${league.statsApiSportId}"

    fun parseLeagueIds(body: String): List<String> =
        (JSONObject(body).optJSONArray("leagues") ?: JSONArray()).objects()
            .map { it.optInt("id").toString() }
            .filter { it != "0" }
            .distinct()

    fun standingsUrl(leagueIds: List<String>, season: Int): String =
        "$BASE/standings?leagueId=${leagueIds.joinToString(",")}" +
            "&season=$season&standingsTypes=regularSeason&hydrate=team"

    // ---------------------------------------------------------------- teams

    fun parseTeams(leagueId: String, body: String): List<TeamRef> {
        val teams = JSONObject(body).optJSONArray("teams") ?: return emptyList()
        return teams.objects().map { t ->
            val parent = t.optString("parentOrgName").takeIf { it.isNotEmpty() }
            TeamRef(
                leagueId = leagueId,
                teamId = t.optInt("id").toString(),
                // The affiliate is the reason to follow a farm club at all, so it
                // rides along in the display name: "Reno Aces (ARI)".
                displayName = t.optString("name") +
                    (parent?.let { " (${orgAbbrev(it)})" } ?: ""),
                short = t.optString("teamName").ifEmpty { t.optString("name") },
                abbrev = t.optString("abbreviation").ifEmpty {
                    t.optString("teamCode").uppercase()
                },
            )
        }.sortedBy { it.displayName }
    }

    /** "San Francisco Giants" -> "SFG"-ish. Only used as a parenthetical hint. */
    private fun orgAbbrev(org: String): String {
        val words = org.split(' ').filter { it.isNotEmpty() }
        return if (words.size >= 2) {
            words.dropLast(1).joinToString("") { it.take(1).uppercase() } +
                words.last().take(1).uppercase()
        } else org.take(3).uppercase()
    }

    // ---------------------------------------------------------------- games

    fun parseSchedule(league: League, body: String): List<Game> {
        val dates = JSONObject(body).optJSONArray("dates") ?: return emptyList()
        val out = mutableListOf<Game>()
        for (d in dates.objects()) {
            for (g in (d.optJSONArray("games") ?: JSONArray()).objects()) {
                val teams = g.optJSONObject("teams") ?: continue
                val line = g.optJSONObject("linescore")
                val home = side(teams.optJSONObject("home"), line, home = true) ?: continue
                val away = side(teams.optJSONObject("away"), line, home = false) ?: continue
                val status = g.optJSONObject("status")
                out += Game(
                    id = g.optInt("gamePk").toString(),
                    leagueId = league.id,
                    state = state(status),
                    startMillis = Iso.millis(g.optString("gameDate")),
                    statusDetail = statusDetail(status, line),
                    period = line?.optInt("currentInning") ?: 0,
                    clock = null,
                    home = home,
                    away = away,
                    venue = g.optJSONObject("venue")?.optString("name")?.takeIf { it.isNotEmpty() },
                    broadcast = broadcast(g),
                    note = g.optString("seriesDescription").takeIf {
                        it.isNotEmpty() && it != "Regular Season"
                    },
                )
            }
        }
        return out
    }

    private fun side(node: JSONObject?, line: JSONObject?, home: Boolean): Side? {
        val t = node?.optJSONObject("team") ?: return null
        val key = if (home) "home" else "away"
        val innings = (line?.optJSONArray("innings") ?: JSONArray()).objects()
        val rec = node.optJSONObject("leagueRecord")
        return Side(
            teamId = t.optInt("id").toString(),
            displayName = t.optString("name"),
            short = t.optString("teamName").ifEmpty { t.optString("name") },
            abbrev = t.optString("abbreviation").ifEmpty { t.optString("teamCode").uppercase() },
            score = if (node.has("score")) node.optInt("score") else null,
            record = rec?.let { "${it.optInt("wins")}-${it.optInt("losses")}" },
            lineScore = innings.map { inning ->
                val half = inning.optJSONObject(key)
                // A half-inning that hasn't been played yet has no "runs" key at all,
                // which is how the bottom of the ninth stays blank in a walk-off.
                if (half != null && half.has("runs")) half.optInt("runs").toString() else "-"
            },
            hits = line?.optJSONObject("teams")?.optJSONObject(key)?.optInt("hits", -1)
                ?.takeIf { it >= 0 },
            errors = line?.optJSONObject("teams")?.optJSONObject(key)?.optInt("errors", -1)
                ?.takeIf { it >= 0 },
        )
    }

    private fun state(status: JSONObject?): GameState =
        when (status?.optString("abstractGameState")) {
            "Live" -> GameState.LIVE
            "Final" -> GameState.FINAL
            "Preview" -> {
                val detailed = status.optString("detailedState")
                if (detailed.contains("Postponed") || detailed.contains("Cancelled") ||
                    detailed.contains("Suspended")
                ) GameState.OFF else GameState.PRE
            }
            else -> GameState.PRE
        }

    private fun statusDetail(status: JSONObject?, line: JSONObject?): String {
        val detailed = status?.optString("detailedState").orEmpty()
        if (status?.optString("abstractGameState") != "Live") return detailed
        val half = line?.optString("inningState")?.take(3).orEmpty()
        val ord = line?.optString("currentInningOrdinal").orEmpty()
        return if (ord.isEmpty()) detailed else "$half $ord".trim()
    }

    private fun broadcast(g: JSONObject): String? =
        (g.optJSONArray("broadcasts") ?: JSONArray()).objects()
            .firstOrNull { it.optString("type") == "TV" }
            ?.optString("name")?.takeIf { it.isNotEmpty() }

    // ------------------------------------------------------------- standings

    fun parseStandings(body: String, divisionNames: Map<String, String>): List<StandingsGroup> {
        val records = JSONObject(body).optJSONArray("records") ?: return emptyList()
        return records.objects().mapNotNull { rec ->
            val divId = rec.optJSONObject("division")?.optInt("id")?.toString()
            // The record itself carries only a division id. With `hydrate=team` the
            // name is on each team, which saves a second request.
            val hydratedName = (rec.optJSONArray("teamRecords") ?: JSONArray()).objects()
                .firstNotNullOfOrNull {
                    it.optJSONObject("team")?.optJSONObject("division")
                        ?.optString("name")?.takeIf { name -> name.isNotEmpty() }
                }
            val rows = (rec.optJSONArray("teamRecords") ?: JSONArray()).objects().map { tr ->
                val team = tr.optJSONObject("team")
                StandingsRow(
                    rank = tr.optString("divisionRank").ifEmpty { "-" },
                    name = team?.optString("name").orEmpty(),
                    abbrev = team?.optString("abbreviation").orEmpty(),
                    values = listOf(
                        tr.optInt("wins").toString(),
                        tr.optInt("losses").toString(),
                        tr.optString("winningPercentage").ifEmpty { "-" },
                        tr.optString("gamesBack").ifEmpty { "-" },
                    ),
                    teamId = team?.optInt("id")?.toString(),
                )
            }
            if (rows.isEmpty()) null
            else StandingsGroup(
                title = hydratedName
                    ?: divisionNames[divId]
                    ?: rec.optJSONObject("league")?.optString("name")
                    ?: "Division",
                headers = listOf("W", "L", "PCT", "GB"),
                rows = rows,
            )
        }
    }

    /**
     * The standings feed identifies divisions by id only, so the names come from a
     * separate lookup. Cached alongside the team list.
     */
    fun divisionsUrl(league: League): String =
        "$BASE/divisions?sportId=${league.statsApiSportId}"

    fun parseDivisions(body: String): Map<String, String> {
        val divisions = JSONObject(body).optJSONArray("divisions") ?: return emptyMap()
        return divisions.objects().associate {
            it.optInt("id").toString() to it.optString("name")
        }
    }
}
