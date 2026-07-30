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
 * HockeyTech / LeagueStat, the platform behind thepwhl.com. The PWHL is on no
 * mainstream scores API, so this is the source of record for it. The feed key below
 * is the public one thepwhl.com ships in its own front end.
 */
object HockeyTechParser {

    private const val BASE = "https://lscluster.hockeytech.com/feed/index.php"
    private const val KEY = "694cfeed58c932ee"

    private fun modulekit(client: String, view: String, extra: String = "") =
        "$BASE?feed=modulekit&view=$view&key=$KEY&fmt=json&client_code=$client$extra"

    fun scorebarUrl(league: League, daysBack: Int, daysAhead: Int): String =
        modulekit(
            league.hockeyTechClient!!, "scorebar",
            "&numberofdaysback=$daysBack&numberofdaysahead=$daysAhead",
        )

    fun teamsUrl(league: League): String = modulekit(league.hockeyTechClient!!, "teamsbyseason")

    fun seasonsUrl(league: League): String = modulekit(league.hockeyTechClient!!, "seasons")

    fun standingsUrl(league: League, seasonId: String): String =
        "$BASE?feed=statviewfeed&view=teams&groupTeamsBy=division&context=overall" +
            "&site_id=0&season=$seasonId&special=false&key=$KEY" +
            "&client_code=${league.hockeyTechClient}&league_id=1&fmt=json"

    /** Newest season first in this feed, but sort by id rather than trust the order. */
    fun parseLatestSeasonId(body: String): String? =
        JSONObject(body).optJSONObject("SiteKit")?.optJSONArray("Seasons")
            ?.objects()
            ?.maxByOrNull { it.optString("season_id").toIntOrNull() ?: 0 }
            ?.optString("season_id")

    fun parseTeams(leagueId: String, body: String): List<TeamRef> {
        val teams = JSONObject(body).optJSONObject("SiteKit")
            ?.optJSONArray("Teamsbyseason") ?: return emptyList()
        return teams.objects().map {
            TeamRef(
                leagueId = leagueId,
                teamId = it.optString("id"),
                displayName = it.optString("name"),
                short = it.optString("nickname").ifEmpty { it.optString("name") },
                abbrev = it.optString("code"),
                logoUrl = it.optString("team_logo_url").takeIf { url -> url.isNotEmpty() },
            )
        }.sortedBy { it.displayName }
    }

    fun parseScorebar(league: League, body: String): List<Game> {
        val bar = JSONObject(body).optJSONObject("SiteKit")
            ?.optJSONArray("Scorebar") ?: return emptyList()
        return bar.objects().map { g ->
            val state = state(g.optString("GameStatus"))
            Game(
                id = g.optString("ID"),
                leagueId = league.id,
                state = state,
                startMillis = Iso.millis(g.optString("GameDateISO8601")),
                statusDetail = when (state) {
                    GameState.LIVE ->
                        if (g.optString("Intermission") == "1") "INT ${g.optString("PeriodNameShort")}"
                        else "${g.optString("GameClock")} ${g.optString("PeriodNameLong")}"
                    else -> g.optString("GameStatusStringLong")
                        .ifEmpty { g.optString("GameStatusString") }
                },
                period = g.optString("Period").toIntOrNull() ?: 0,
                // HockeyTech states the intermission outright, which is a better signal
                // than anything derivable from the clock.
                statusName = if (g.optString("Intermission") == "1") "INTERMISSION" else null,
                clock = g.optString("GameClock").takeIf {
                    state == GameState.LIVE && it.isNotEmpty() && it != "00:00"
                },
                home = Side(
                    teamId = g.optString("HomeID"),
                    displayName = g.optString("HomeLongName"),
                    short = g.optString("HomeNickname"),
                    abbrev = g.optString("HomeCode"),
                    score = g.optString("HomeGoals").toIntOrNull(),
                    record = record(g, "Home"),
                ),
                away = Side(
                    teamId = g.optString("VisitorID"),
                    displayName = g.optString("VisitorLongName"),
                    short = g.optString("VisitorNickname"),
                    abbrev = g.optString("VisitorCode"),
                    score = g.optString("VisitorGoals").toIntOrNull(),
                    record = record(g, "Visitor"),
                ),
                venue = g.optString("venue_name").takeIf { it.isNotEmpty() },
                broadcast = null,
            )
        }
    }

    /** Hockey records are W-L-OTL, and shootout losses count as overtime losses. */
    private fun record(g: JSONObject, side: String): String? {
        val w = g.optString("${side}Wins").toIntOrNull() ?: return null
        val l = g.optString("${side}RegulationLosses").toIntOrNull() ?: return null
        val otl = (g.optString("${side}OTLosses").toIntOrNull() ?: 0) +
            (g.optString("${side}ShootoutLosses").toIntOrNull() ?: 0)
        return "$w-$l-$otl"
    }

    /**
     * GameStatus is numeric: 1 scheduled, 2 and 3 in progress, 4 final.
     * The string forms drift between LeagueStat clients, the codes don't.
     */
    private fun state(code: String): GameState = when (code) {
        "1" -> GameState.PRE
        "2", "3" -> GameState.LIVE
        "4" -> GameState.FINAL
        else -> GameState.OFF
    }

    /**
     * The standings feed answers with JSON wrapped in a bare pair of parentheses —
     * a leftover from a JSONP-era callback — so the wrapper is peeled before parsing.
     */
    fun parseStandings(body: String): List<StandingsGroup> {
        val trimmed = body.trim().removePrefix("(").removeSuffix(")")
        val root = runCatching { JSONArray(trimmed) }.getOrNull() ?: return emptyList()
        val cols = listOf(
            "games_played" to "GP", "regulation_wins" to "W",
            "losses" to "L", "non_reg_losses" to "OTL", "points" to "PTS",
        )
        val out = mutableListOf<StandingsGroup>()
        for (group in root.objects()) {
            for (section in (group.optJSONArray("sections") ?: JSONArray()).objects()) {
                val rows = (section.optJSONArray("data") ?: JSONArray()).objects()
                    .mapNotNull { it.optJSONObject("row") }
                    .map { row ->
                        StandingsRow(
                            rank = row.optString("rank"),
                            name = row.optString("name"),
                            abbrev = row.optString("team_code"),
                            values = cols.map { row.optString(it.first).ifEmpty { "0" } },
                            allStats = row.keys().asSequence()
                                .filter { it !in setOf("name", "team_code", "rank", "overall_rank") }
                                .mapNotNull { key ->
                                    val v = row.optString(key).takeIf { it.isNotBlank() }
                                        ?: return@mapNotNull null
                                    prettify(key.replace('_', ' ')) to v
                                }
                                .toList(),
                        )
                    }
                if (rows.isNotEmpty()) {
                    out += StandingsGroup(
                        title = section.optString("title").ifEmpty { "PWHL" },
                        headers = cols.map { it.second },
                        rows = rows,
                    )
                }
            }
        }
        return out
    }
}
