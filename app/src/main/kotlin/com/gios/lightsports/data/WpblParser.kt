package com.gios.lightsports.data

import com.gios.lightsports.model.Game
import com.gios.lightsports.model.GameState
import com.gios.lightsports.model.League
import com.gios.lightsports.model.Side
import com.gios.lightsports.model.StandingsGroup
import com.gios.lightsports.model.StandingsRow
import com.gios.lightsports.model.TeamRef
import org.json.JSONObject
import java.util.Locale

/**
 * The WPBL's own stats service, `stats.womensprobaseballleague.com`.
 *
 * The league is on neither of the sources the rest of this app leans on. ESPN streams
 * every WPBL game and still publishes no scoreboard for it — `sports.core.api.espn.com`
 * lists twelve baseball leagues and the WPBL is not among them — and MLB's StatsAPI
 * `/v1/sports` has no entry for it either, only Women's Professional *Softball*. So the
 * league's own service is the source of record, the way HockeyTech is for the PWHL.
 *
 * It is keyless public JSON, same-origin behind the public game centre, in two endpoints:
 * `/v1/games` returns the entire season in one response — thirty-two rows, about 85 KB —
 * and `/v1/teams` returns the four clubs. There is no per-date query, which is why the
 * window is applied here rather than in the request.
 *
 * Two things the feed does not give us:
 *
 *  - **No standings endpoint.** `/v1/teams` carries `wins`/`losses`/`streak` fields and
 *    leaves them all at zero; the league's own site computes the table in the browser
 *    from finished games. [standings] does the same thing, over the same
 *    `counts_in_standings` flag, so the app's table and the league's agree by
 *    construction.
 *  - **No line score.** Runs by inning, hits and errors live in
 *    `/v1/games/<id>/boxscore`, one request and ~35 KB per game. The notification poll
 *    runs every two minutes while a game is live and shares a code path with the feed,
 *    so fetching those would cost megabytes an evening to fill in a strip of numbers
 *    nobody is looking at. WPBL games show a score and a status and no inning strip.
 */
object WpblParser {

    private const val BASE = "https://stats.womensprobaseballleague.com/v1"

    fun gamesUrl(): String = "$BASE/games"

    fun teamsUrl(): String = "$BASE/teams"

    // ---------------------------------------------------------------- teams

    fun parseTeams(leagueId: String, body: String): List<TeamRef> {
        val teams = JSONObject(body).optJSONArray("teams") ?: return emptyList()
        return teams.objects().mapNotNull {
            val name = it.optString("team_name").takeIf { n -> n.isNotEmpty() }
                ?: return@mapNotNull null
            TeamRef(
                leagueId = leagueId,
                teamId = it.optString("team_id"),
                displayName = name,
                short = nickname(name),
                abbrev = abbrev(name),
                logoUrl = it.optString("logo_url").takeIf { url -> url.isNotEmpty() },
            )
        }.sortedBy { it.displayName }
    }

    // ---------------------------------------------------------------- games

    /**
     * @param roster the cached team list, used to name the sides of a fixture the
     *   schedule has not filled in yet — an unannounced game arrives with both
     *   `home_team_name` and `away_team_name` empty but its team ids already set, and
     *   would otherwise render the way the league's own site renders it, as
     *   "Away team at Home team".
     * @param fromMillis the start of the same window the other providers get by querying
     *   a date range. This feed has no such parameter and always answers with the whole
     *   season, so a game outside the window has to be dropped here — left in, every
     *   September fixture would count as an upcoming game the moment the app opened in
     *   August, and the pre-game nudge would arm against the wrong one.
     * @param toMillis the end of that window.
     * @param standingsOnly keep only the games the league counts towards its table.
     */
    fun parseGames(
        league: League,
        body: String,
        roster: List<TeamRef> = emptyList(),
        fromMillis: Long = Long.MIN_VALUE,
        toMillis: Long = Long.MAX_VALUE,
        standingsOnly: Boolean = false,
    ): List<Game> {
        val games = JSONObject(body).optJSONArray("games") ?: return emptyList()
        val byId = roster.associateBy { it.teamId }
        return games.objects().mapNotNull { g ->
            val start = Iso.millis(g.optString("scheduled_start"))
            if (start < fromMillis || start > toMillis) return@mapNotNull null
            if (standingsOnly && !g.optBoolean("counts_in_standings", true)) return@mapNotNull null

            val presto = g.optJSONObject("presto_data")
            val live = g.optJSONObject("state")
            val status = g.optString("status").ifEmpty { presto?.optString("status").orEmpty() }

            Game(
                id = g.optString("game_id"),
                leagueId = league.id,
                state = state(status, presto?.optInt("statusCode", MISSING) ?: MISSING),
                startMillis = start,
                statusDetail = status,
                // `state.inning` is populated late and reads 0 through the first innings
                // of a game whose status already says "Top of 3rd", so the ordinal in
                // the status string is the reliable one.
                period = inning(status) ?: live?.optInt("inning", 0) ?: 0,
                home = side(g, presto, live, byId, home = true),
                away = side(g, presto, live, byId, home = false),
                venue = g.optString("venue").takeIf { it.isNotEmpty() },
            )
        }
    }

    private fun side(
        g: JSONObject,
        presto: JSONObject?,
        live: JSONObject?,
        byId: Map<String, TeamRef>,
        home: Boolean,
    ): Side {
        val which = if (home) "home" else "away"
        val id = g.optString("${which}_team_id")
        val name = g.optString("${which}_team_name").takeIf { it.isNotEmpty() }
            ?: byId[id]?.displayName
            ?: ""
        // The live state block counts in integers and the Presto block in strings.
        // The former is the one that updates mid-inning, so it wins when present.
        val score = live?.optInt("${which}_score", MISSING)?.takeIf { it != MISSING }
            ?: presto?.optJSONObject("score")?.optString(which)?.toIntOrNull()
        return Side(
            teamId = id,
            displayName = name,
            short = nickname(name),
            abbrev = abbrev(name),
            score = score,
        )
    }

    /**
     * The status string says what happened in words — "Not Started", "In Progress -
     * Top of 3rd", "Final - Weather Delay" — and `statusCode` says it in a number that
     * has only been seen take three values. The words are read first and the code is the
     * fallback, so a status Presto spells a new way still lands somewhere sane.
     *
     * "Final - Weather Delay" is why final is tested before delay: a game stopped for
     * rain and then played out carries both words, and it is over. "Delay" is tested
     * before "in progress" for the same reason in the other direction — a game still
     * paused mid-innings carries both words too ("In Progress - Weather Delay"), and
     * that one is not over, or live, or anything to keep quiet about: it is OFF, same
     * as the ESPN and StatsAPI providers once their own delay wording was fixed to
     * stop reading as an ordinary live game (see the endpoint-traps list above).
     */
    private fun state(status: String, code: Int): GameState {
        val s = status.lowercase(Locale.US)
        return when {
            s.startsWith("final") || s.contains("complete") -> GameState.FINAL
            s.contains("postponed") || s.contains("cancel") || s.contains("suspended") ||
                s.contains("delay")
            -> GameState.OFF
            s.contains("in progress") -> GameState.LIVE
            s.contains("not started") || s.contains("scheduled") || s.contains("pregame") ->
                GameState.PRE
            code == -2 -> GameState.PRE
            code == -1 -> GameState.LIVE
            code == 0 -> GameState.FINAL
            else -> GameState.OFF
        }
    }

    /** "In Progress - Top of 3rd" -> 3. Null when the status names no inning. */
    private fun inning(status: String): Int? =
        Regex("(\\d+)\\s*(?:st|nd|rd|th)\\b", RegexOption.IGNORE_CASE)
            .find(status)?.groupValues?.get(1)?.toIntOrNull()

    // ------------------------------------------------------------- standings

    /**
     * The table the league publishes, recomputed from finished games. Feed it the list
     * [parseGames] returns with `standingsOnly = true`, over a window wide enough to
     * cover the season — anything narrower and the record is only of the games still on
     * screen.
     *
     * Ties get their own column: a WPBL game is seven innings and the league has not
     * said it will play them out.
     */
    fun standings(games: List<Game>, title: String = "WPBL"): List<StandingsGroup> {
        val finals = games
            .filter { it.state == GameState.FINAL && it.home.score != null && it.away.score != null }
            .sortedBy { it.startMillis }
        if (finals.isEmpty()) return emptyList()

        val records = linkedMapOf<String, Record>()
        fun record(side: Side) = records.getOrPut(side.teamId) {
            Record(side.teamId, side.displayName, side.abbrev)
        }

        for (game in finals) {
            val h = record(game.home)
            val a = record(game.away)
            val hs = game.home.score ?: continue
            val away = game.away.score ?: continue
            h.runsFor += hs; h.runsAgainst += away
            a.runsFor += away; a.runsAgainst += hs
            when {
                hs > away -> { h.win(); a.loss() }
                away > hs -> { a.win(); h.loss() }
                else -> { h.tie(); a.tie() }
            }
        }

        val table = records.values.sortedWith(
            compareByDescending<Record> { it.pct }.thenByDescending { it.runsFor - it.runsAgainst },
        )
        val leader = table.first()

        return listOf(
            StandingsGroup(
                title = title,
                headers = listOf("W", "L", "PCT", "GB", "RF", "RA"),
                rows = table.mapIndexed { i, r ->
                    val diff = r.runsFor - r.runsAgainst
                    StandingsRow(
                        rank = (i + 1).toString(),
                        name = r.name,
                        abbrev = r.abbrev,
                        teamId = r.teamId,
                        values = listOf(
                            r.wins.toString(), r.losses.toString(), pct(r.pct),
                            gamesBack(leader, r), r.runsFor.toString(), r.runsAgainst.toString(),
                        ),
                        allStats = listOf(
                            "GP" to (r.wins + r.losses + r.ties).toString(),
                            "W" to r.wins.toString(),
                            "L" to r.losses.toString(),
                            "T" to r.ties.toString(),
                            "PCT" to pct(r.pct),
                            "GB" to gamesBack(leader, r),
                            "Runs for" to r.runsFor.toString(),
                            "Runs against" to r.runsAgainst.toString(),
                            "Run diff" to (if (diff > 0) "+$diff" else diff.toString()),
                            "Streak" to r.streak(),
                        ),
                    )
                },
            ),
        )
    }

    private class Record(val teamId: String, val name: String, val abbrev: String) {
        var wins = 0
        var losses = 0
        var ties = 0
        var runsFor = 0
        var runsAgainst = 0
        private var last = ' '
        private var run = 0

        fun win() { wins++; mark('W') }
        fun loss() { losses++; mark('L') }
        fun tie() { ties++; mark('T') }

        private fun mark(c: Char) {
            run = if (c == last) run + 1 else 1
            last = c
        }

        /** Ties are neutral in the record and still break a run. */
        fun streak(): String = if (run == 0) "—" else "$last$run"

        /** Decided games only, the way baseball has always counted it. */
        val pct: Double get() = if (wins + losses == 0) 0.0 else wins.toDouble() / (wins + losses)
    }

    /** ".667", "1.000" — baseball drops the leading zero. */
    private fun pct(p: Double): String =
        String.format(Locale.US, "%.3f", p).removePrefix("0")

    private fun gamesBack(leader: Record, r: Record): String {
        val back = ((leader.wins - r.wins) + (r.losses - leader.losses)) / 2.0
        return if (back <= 0.0) "—" else fmtNum(back)
    }

    // ------------------------------------------------------------- team names

    /** "New York Heights" -> "Heights". The feed sends no nickname of its own. */
    internal fun nickname(name: String): String =
        name.trim().split(" ").lastOrNull().orEmpty().ifEmpty { name }

    /**
     * "Boston Hunters" -> BOS, "New York Heights" -> NY, "San Francisco Firebells" -> SF.
     *
     * Derived rather than taken from the feed, whose `code` field is the Presto RPI key —
     * "WPBL004" — which is unreadable on a score row and says nothing about which club it
     * is. Everything before the nickname is the place, so a one-word place gives its
     * first three letters and a two-word place gives its initials.
     */
    internal fun abbrev(name: String): String {
        val words = name.trim().split(" ").filter { it.isNotEmpty() }
        if (words.size <= 1) return words.firstOrNull().orEmpty().take(3).uppercase(Locale.US)
        val place = words.dropLast(1)
        return if (place.size == 1) place[0].take(3).uppercase(Locale.US)
        else place.joinToString("") { it.take(1) }.take(3).uppercase(Locale.US)
    }

    /** `optInt` has no null, so an out-of-range sentinel stands in for "absent". */
    private const val MISSING = Int.MIN_VALUE
}
