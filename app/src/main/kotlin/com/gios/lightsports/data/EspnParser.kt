package com.gios.lightsports.data

import com.gios.lightsports.model.EventClass
import com.gios.lightsports.model.Game
import com.gios.lightsports.model.GameState
import com.gios.lightsports.model.League
import com.gios.lightsports.model.Play
import com.gios.lightsports.model.ScoringPlay
import com.gios.lightsports.model.RaceEvent
import com.gios.lightsports.model.Side
import com.gios.lightsports.model.Situation
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

    /**
     * `limit` has to clear the busiest window in the calendar. A fortnight of MLB is
     * north of 200 games league-wide, and at limit=200 the tail was being cut off
     * silently — no error, just a short list, which is indistinguishable from a quiet
     * fortnight until you count.
     */
    fun scoreboardUrl(league: League, startYmd: String, endYmd: String): String {
        val base = pathScoreboardUrl(league.espnPath.orEmpty(), startYmd, endYmd)
        // Plural, confirmed against college football: `groups=80` is what actually
        // narrows the scoreboard to FBS games.
        return league.espnGroup?.let { "$base&groups=$it" } ?: base
    }

    /** Any ESPN competition path, which is how the cups are reached. */
    fun pathScoreboardUrl(path: String, startYmd: String, endYmd: String): String =
        "$SITE/$path/scoreboard?limit=1000&dates=$startYmd-$endYmd"

    fun teamsUrl(league: League): String = "$SITE/${league.espnPath}/teams?limit=400"

    /**
     * level=3 asks for divisions rather than conferences. Leagues without divisions
     * ignore it, so it is safe to send everywhere.
     */
    fun standingsUrl(league: League): String {
        val base = "$CORE/${league.espnPath}/standings?level=3"
        // Singular here, unlike the scoreboard's `groups=` — verified live against
        // college football's FBS/FCS split, which is the only league that needs it.
        return league.espnGroup?.let { "$base&group=$it" } ?: base
    }

    fun raceUrl(league: League, year: Int): String = "$SITE/${league.espnPath}/scoreboard?dates=$year"

    /**
     * No date window on purpose. The tennis scoreboard answers with whole tournaments,
     * not days — a Grand Slam is one event holding every match of the fortnight — and a
     * `dates=` range only returns events whose first or last day falls inside it.
     * Confirmed live: `20260904-20260906`, mid-US Open, came back empty, while the bare
     * URL returned the tournament in progress. Gzipped it is about 150 KB, less than an
     * FBS Saturday.
     */
    fun tennisScoreboardUrl(path: String): String = "$SITE/$path/scoreboard"

    /** Top 150 of a tour. `tennis/atp` gives the men, `tennis/wta` the women. */
    fun rankingsUrl(path: String): String = "$SITE/$path/rankings"

    private const val CORE_API = "https://sports.core.api.espn.com/v2/sports"

    /**
     * The last few plays, newest first. `sort=desc` is what makes this one request: the
     * default order is oldest first across twenty-odd pages, and the last page is the
     * only one worth having. Confirmed live: `sort=desc` answers with the final plays of
     * a finished game on page 1. About 4.5 KB a play, so eight is ~36 KB — fine every
     * fifteen seconds while the screen is open, and never fetched otherwise.
     */
    fun playsUrl(league: League, eventId: String, limit: Int = 8): String {
        val path = league.espnPath.orEmpty()
        val sport = path.substringBefore('/')
        val slug = path.substringAfter('/')
        return "$CORE_API/$sport/leagues/$slug/events/$eventId/competitions/$eventId/plays?limit=$limit&sort=desc"
    }

    /**
     * The game summary: box score, drives, scoring plays, win probability, news. Around
     * 580 KB for a finished game, which is why it is fetched once per game on request
     * rather than polled.
     */
    fun summaryUrl(league: League, eventId: String): String =
        "$SITE/${league.espnPath}/summary?event=$eventId"

    fun parsePlays(body: String): List<Play> {
        val items = JSONObject(body).optJSONArray("items") ?: return emptyList()
        return items.objects().mapNotNull { p ->
            val text = p.optString("text").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            // The team is a reference, not an object: ".../teams/23?lang=en".
            val teamRef = p.optJSONObject("team")?.optString("\$ref").orEmpty()
            Play(
                id = p.optString("id"),
                text = text,
                shortText = p.optString("shortText").takeIf { it.isNotEmpty() },
                type = p.optJSONObject("type")?.optString("text")?.takeIf { it.isNotEmpty() },
                period = p.optJSONObject("period")?.optInt("number") ?: 0,
                clock = p.optJSONObject("clock")?.optString("displayValue")?.takeIf { it.isNotEmpty() },
                teamId = teamRef.substringAfterLast("/teams/", "").substringBefore('?')
                    .takeIf { it.isNotEmpty() },
                scoring = p.optBoolean("scoringPlay", false),
                scoreValue = p.optInt("scoreValue", 0),
                awayScore = if (p.has("awayScore")) p.optInt("awayScore") else null,
                homeScore = if (p.has("homeScore")) p.optInt("homeScore") else null,
                downDistance = p.optJSONObject("start")?.optString("downDistanceText")
                    ?.takeIf { it.isNotEmpty() },
            )
        }
    }

    /** The summary's `scoringPlays`, in game order. */
    fun parseScoringPlays(body: String): List<ScoringPlay> {
        val plays = JSONObject(body).optJSONArray("scoringPlays") ?: return emptyList()
        return plays.objects().mapNotNull { p ->
            val text = p.optString("text").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val team = p.optJSONObject("team")
            ScoringPlay(
                id = p.optString("id"),
                text = text,
                kind = p.optJSONObject("scoringType")?.optString("abbreviation")
                    ?.takeIf { it.isNotEmpty() } ?: p.optJSONObject("type")
                    ?.optString("abbreviation").orEmpty(),
                period = p.optJSONObject("period")?.optInt("number") ?: 0,
                clock = p.optJSONObject("clock")?.optString("displayValue")?.takeIf { it.isNotEmpty() },
                teamId = team?.optString("id")?.takeIf { it.isNotEmpty() },
                teamAbbrev = team?.optString("abbreviation")?.takeIf { it.isNotEmpty() },
                awayScore = p.optInt("awayScore", 0),
                homeScore = p.optInt("homeScore", 0),
            )
        }
    }

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
                    logoUrl = logoFor(t),
                )
            }
        }
        return out.distinctBy { it.teamId }.sortedBy { it.displayName }
    }

    /**
     * The roster source for any league with [League.espnGroup] set, since the plain
     * `teams` endpoint silently ignores that filter (see the field's doc comment).
     * The standings tree does honor it, and every leaf entry carries a full team
     * object — id, name, crest — so it doubles as a roster without a second request.
     */
    fun parseTeamsFromStandings(leagueId: String, body: String): List<TeamRef> {
        val out = mutableListOf<TeamRef>()
        fun walk(node: JSONObject) {
            for (entry in (node.optJSONObject("standings")
                ?.optJSONArray("entries") ?: JSONArray()).objects()) {
                val t = entry.optJSONObject("team") ?: continue
                if (t.has("isActive") && !t.optBoolean("isActive", true)) continue
                out += TeamRef(
                    leagueId = leagueId,
                    teamId = t.optString("id"),
                    displayName = t.optString("displayName"),
                    short = t.optString("shortDisplayName").ifEmpty { t.optString("name") },
                    abbrev = t.optString("abbreviation").ifEmpty {
                        t.optString("shortDisplayName").take(3).uppercase()
                    },
                    logoUrl = logoFor(t),
                )
            }
            for (child in (node.optJSONArray("children") ?: JSONArray()).objects()) {
                walk(child)
            }
        }
        walk(JSONObject(body))
        return out.distinctBy { it.teamId }.sortedBy { it.displayName }
    }

    /**
     * ESPN ships several crests per club. Take the one marked `dark`, which is the
     * variant drawn for a dark background — the default has white outlines that
     * disappear against this app's black.
     */
    private fun logoFor(team: JSONObject): String? {
        val logos = team.optJSONArray("logos")?.objects()
            ?: return team.optString("logo").takeIf { it.isNotEmpty() }
        fun href(rel: String) = logos.firstOrNull { logo ->
            (logo.optJSONArray("rel") ?: JSONArray()).let { rels ->
                (0 until rels.length()).any { rels.optString(it) == rel }
            }
        }?.optString("href")?.takeIf { it.isNotEmpty() }
        return href("dark") ?: href("default") ?: logos.firstOrNull()
            ?.optString("href")?.takeIf { it.isNotEmpty() }
    }

    // ---------------------------------------------------------------- games

    /**
     * @param rosterIds the league's own team ids, from the cached team list. An event
     *   with a competitor outside this set is an all-star or exhibition fixture — for
     *   several of them that is the only signal, since they carry no headline. Pass an
     *   empty set to skip that check; classification then falls back to the headline.
     */
    fun parseScoreboard(
        league: League,
        body: String,
        rosterIds: Set<String> = emptySet(),
        competition: String? = null,
    ): List<Game> {
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

            val headline = comp.optJSONArray("notes")?.optJSONObject(0)
                ?.optString("headline")?.takeIf { it.isNotEmpty() }
            val seasonSlug = e.optJSONObject("season")?.optString("slug")
            val offRoster = rosterIds.isNotEmpty() &&
                listOf(home, away).any { it.teamId !in rosterIds }
            val eventClass = SpecialEvents.classify(headline, seasonSlug, offRoster)

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
                statusName = type?.optString("name")?.takeIf { it.isNotEmpty() },
                clock = status?.optString("displayClock")?.takeIf { it.isNotEmpty() && it != "0:00" },
                home = home,
                away = away,
                venue = comp.optJSONObject("venue")?.optString("fullName")?.takeIf { it.isNotEmpty() },
                broadcast = broadcast(comp),
                note = headline,
                // Soccer names its finals only in the slug, so fall back to that.
                eventTitle = headline ?: slugTitle(seasonSlug).takeIf {
                    eventClass != EventClass.NONE
                },
                eventClass = eventClass,
                competition = competition,
                situation = comp.optJSONObject("situation")?.let { situation(it) },
                odds = comp.optJSONArray("odds")?.optJSONObject(0)?.optString("details")
                    ?.takeIf { it.isNotEmpty() },
                overUnder = comp.optJSONArray("odds")?.optJSONObject(0)?.let {
                    val ou = it.optDouble("overUnder", Double.NaN)
                    if (ou.isNaN()) null else fmtNum(ou)
                },
                weather = weather(e.optJSONObject("weather")),
                week = e.optJSONObject("week")?.optInt("number", 0)?.takeIf { it > 0 },
                headline = comp.optJSONArray("headlines")?.optJSONObject(0)
                    ?.optString("shortLinkText")?.takeIf { it.isNotEmpty() },
                neutralSite = comp.optBoolean("neutralSite", false),
            )
        }
        return out
    }

    /**
     * ESPN's live-game block. Every key is optional in practice — football fills the
     * down-and-distance half, baseball the count — so nothing here is required, and a
     * block with none of the fields still comes back as an (empty) situation rather
     * than a null one, because its presence is itself the signal that play is under way.
     */
    internal fun situation(s: JSONObject): Situation {
        val last = s.optJSONObject("lastPlay")
        return Situation(
            possession = s.optString("possession").takeIf { it.isNotEmpty() },
            downDistance = s.optString("downDistanceText").takeIf { it.isNotEmpty() },
            shortDownDistance = s.optString("shortDownDistanceText").takeIf { it.isNotEmpty() },
            spot = s.optString("possessionText").takeIf { it.isNotEmpty() },
            down = s.optInt("down", -1).takeIf { it > 0 },
            distance = s.optInt("distance", -1).takeIf { it >= 0 },
            isRedZone = s.optBoolean("isRedZone", false),
            homeTimeouts = s.optInt("homeTimeouts", -1).takeIf { it >= 0 },
            awayTimeouts = s.optInt("awayTimeouts", -1).takeIf { it >= 0 },
            lastPlay = last?.optString("text")?.takeIf { it.isNotEmpty() },
            drive = last?.optJSONObject("drive")?.optString("description")
                ?.takeIf { it.isNotEmpty() },
            balls = s.optInt("balls", -1).takeIf { it >= 0 },
            strikes = s.optInt("strikes", -1).takeIf { it >= 0 },
            outs = s.optInt("outs", -1).takeIf { it >= 0 },
            onFirst = s.optBoolean("onFirst", false),
            onSecond = s.optBoolean("onSecond", false),
            onThird = s.optBoolean("onThird", false),
            batter = s.optJSONObject("batter")?.optJSONObject("athlete")
                ?.optString("shortName")?.takeIf { it.isNotEmpty() },
            pitcher = s.optJSONObject("pitcher")?.optJSONObject("athlete")
                ?.optString("shortName")?.takeIf { it.isNotEmpty() },
        )
    }

    /** "75° Mostly sunny". Indoor games carry no block and get null. */
    private fun weather(w: JSONObject?): String? {
        if (w == null) return null
        val temp = w.optInt("temperature", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
        val text = w.optString("displayValue").takeIf { it.isNotEmpty() }
        return listOfNotNull(temp?.let { "$it°" }, text).joinToString(" ").takeIf { it.isNotEmpty() }
    }

    /**
     * `mls-cup` -> `MLS Cup`, `playoffs---championship` -> `Championship`.
     *
     * Generic slugs give nothing back: an all-star game sits in `regular-season`, and
     * titling it "Regular Season" is worse than leaving it to the league name.
     */
    private fun slugTitle(slug: String?): String? {
        if (slug.isNullOrEmpty()) return null
        if (slug.lowercase().replace("-", "") in
            setOf("regularseason", "postseason", "preseason", "offseason")
        ) return null
        val tail = slug.substringAfterLast("---").replace('-', ' ').trim()
        if (tail.isEmpty()) return null
        return tail.split(' ').joinToString(" ") { word ->
            // Acronyms stay upper: mls, nwsl.
            if (word.length <= 4 && word !in setOf("cup", "final", "semi")) word.uppercase()
            else word.replaceFirstChar { it.uppercase() }
        }
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
            // 99 is ESPN for "not in the poll".
            rank = c.optJSONObject("curatedRank")?.optInt("current", 0)
                ?.takeIf { it in 1..25 },
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

    /**
     * The delay/suspension names ride on top of an ordinary `state` value rather than
     * replacing it — confirmed live: a rain delay reports `state: "in"`, `completed:
     * false`, `name: "STATUS_RAIN_DELAY"`, which is indistinguishable from a normal
     * live game unless the name is checked first. Without this a delay carries no
     * state change at all: no alert when it starts, and nothing to tell "still
     * delayed" apart from "back and nobody said so."
     */
    private fun state(type: JSONObject?): GameState {
        val name = type?.optString("name").orEmpty().uppercase()
        if (listOf("DELAY", "SUSPEND", "POSTPON", "CANCEL").any { it in name }) {
            return GameState.OFF
        }
        return when (type?.optString("state")) {
            "in" -> GameState.LIVE
            "post" -> if (type.optBoolean("completed", true)) GameState.FINAL else GameState.OFF
            "pre" -> GameState.PRE
            else -> GameState.PRE
        }
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

            fun sessionState(c: JSONObject): String? =
                c.optJSONObject("status")?.optJSONObject("type")?.optString("state")
                    ?.takeIf { it.isNotEmpty() }

            // State comes from the session `state` strings, never from `completed`.
            // ESPN leaves `completed:false` on whole finished weekends — Bahrain and
            // Saudi Arabia 2026 both do — and keying off that flag left those races
            // pinned to LIVE in the feed for the rest of the season.
            val anyLive = comps.any { sessionState(it) == "in" }
            val allPost = comps.isNotEmpty() && comps.all { sessionState(it) == "post" }
            val startMillis = Iso.millis(e.optString("date"))
            val endMillis = Iso.millis(e.optString("endDate")).takeIf { it > 0 } ?: startMillis
            val finished = allPost || endMillis < nowMillis

            val podium = if (race != null && finished) {
                race.optJSONArray("competitors")?.objects()
                    ?.sortedBy { it.optInt("order", 99) }
                    ?.take(3)
                    ?.mapNotNull { it.optJSONObject("athlete")?.optString("shortName") }
                    .orEmpty()
            } else emptyList()

            out += RaceEvent(
                id = e.optString("id"),
                leagueId = league.id,
                name = e.optString("name"),
                shortName = e.optString("shortName").ifEmpty { e.optString("name") },
                state = when {
                    finished -> GameState.FINAL
                    anyLive -> GameState.LIVE
                    // Includes mid-weekend gaps: practice is over, the race has not
                    // started, so the card counts down to the next session.
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

    // ---------------------------------------------------------------- tennis

    /**
     * Every match of every major in the scoreboard, as games.
     *
     * A competitor is an `athlete` in singles and a `roster` of two in doubles, and in
     * both cases the score that decides the match is sets won, which ESPN does not total
     * — it marks each set in `linescores` with `winner`, and the count of those is the
     * score. Games per set become the line score, so the detail screen reads 6-3 1-6 1-0
     * the way a tennis score is written. Qualifying rounds are dropped: they run the week
     * before, against names nobody follows, and would triple the pre-tournament feed.
     */
    fun parseTennis(league: League, body: String): List<Game> {
        val events = JSONObject(body).optJSONArray("events") ?: return emptyList()
        val out = mutableListOf<Game>()
        for (e in events.objects()) {
            if (!e.optBoolean("major", false)) continue
            val tournament = e.optString("shortName").ifEmpty { e.optString("name") }
            for (grouping in (e.optJSONArray("groupings") ?: JSONArray()).objects()) {
                val draw = grouping.optJSONObject("grouping")?.optString("displayName").orEmpty()
                for (comp in (grouping.optJSONArray("competitions") ?: JSONArray()).objects()) {
                    val roundName = comp.optJSONObject("round")?.optString("displayName").orEmpty()
                    if ("qualifying" in roundName.lowercase()) continue
                    var home: Side? = null
                    var away: Side? = null
                    for (c in (comp.optJSONArray("competitors") ?: JSONArray()).objects()) {
                        val side = tennisSide(c) ?: continue
                        if (c.optString("homeAway") == "home") home = side else away = side
                    }
                    if (home == null || away == null) continue

                    val status = comp.optJSONObject("status")
                    val type = status?.optJSONObject("type")
                    val eventClass = when (roundName.lowercase()) {
                        "final" -> EventClass.CHAMPIONSHIP
                        "quarterfinal", "semifinal" -> EventClass.SHOWCASE
                        else -> EventClass.NONE
                    }
                    val venue = comp.optJSONObject("venue")
                    out += Game(
                        id = "t-" + comp.optString("id"),
                        leagueId = league.id,
                        state = state(type),
                        startMillis = Iso.millis(comp.optString("date")),
                        statusDetail = type?.optString("shortDetail")
                            ?.ifEmpty { type.optString("detail") }.orEmpty(),
                        period = status?.optInt("period") ?: 0,
                        statusName = type?.optString("name")?.takeIf { it.isNotEmpty() },
                        home = home,
                        away = away,
                        // The court is the venue that matters; "New York, USA" is the
                        // tournament's, and the tournament is already on the line.
                        venue = venue?.optString("court")?.takeIf { it.isNotEmpty() }
                            ?: venue?.optString("fullName")?.takeIf { it.isNotEmpty() },
                        broadcast = broadcast(comp),
                        note = listOf(draw, roundName).filter { it.isNotEmpty() }
                            .joinToString(" · ").takeIf { it.isNotEmpty() },
                        // "US Open · WS · QF": the tournament earns the league's slot on
                        // the row, and the draw and round have to fit beside the clock.
                        eventTitle = listOfNotNull(
                            tournament,
                            drawCode(draw),
                            roundCode(roundName),
                        ).joinToString(" · "),
                        eventClass = eventClass,
                        competition = tournament,
                    )
                }
            }
        }
        return out
    }

    /** "Women's Singles" -> "WS", "Mixed Doubles" -> "XD". */
    internal fun drawCode(draw: String): String? {
        val d = draw.lowercase()
        if (d.isEmpty()) return null
        val gender = when {
            d.startsWith("men") -> "M"
            d.startsWith("women") -> "W"
            d.startsWith("mixed") -> "X"
            else -> return draw
        }
        val form = when {
            "doubles" in d -> "D"
            "singles" in d -> "S"
            else -> return draw
        }
        return gender + form
    }

    /** "Round 3" -> "R3", "Quarterfinal" -> "QF", "Final" -> "FINAL". */
    internal fun roundCode(round: String): String? {
        val r = round.lowercase().trim()
        if (r.isEmpty()) return null
        return when {
            r == "final" -> "FINAL"
            r.startsWith("semi") -> "SF"
            r.startsWith("quarter") -> "QF"
            r.startsWith("round of ") -> "R" + r.removePrefix("round of ")
            r.startsWith("round ") -> "R" + r.removePrefix("round ")
            else -> round
        }
    }

    private fun tennisSide(c: JSONObject): Side? {
        val athlete = c.optJSONObject("athlete")
        val roster = c.optJSONObject("roster")
        val pair = roster?.optJSONArray("athletes")?.objects().orEmpty()
        val displayName: String
        val short: String
        val members: List<String>
        when {
            athlete != null -> {
                displayName = athlete.optString("displayName")
                short = athlete.optString("shortName").ifEmpty { displayName }
                members = emptyList()
            }
            roster != null && pair.isNotEmpty() -> {
                displayName = roster.optString("displayName")
                    .ifEmpty { pair.joinToString(" / ") { it.optString("displayName") } }
                short = roster.optString("shortDisplayName")
                    .ifEmpty { pair.joinToString(" / ") { it.optString("shortName") } }
                // A pair's id is the two athlete ids joined, "1013-2319"; the roster
                // entries carry them only in the player-card link.
                members = c.optString("id").split('-').filter { it.isNotEmpty() }
            }
            else -> return null
        }
        if (displayName.isEmpty()) return null
        val sets = c.optJSONArray("linescores")?.objects().orEmpty()
        val started = sets.isNotEmpty()
        val seed = c.optJSONObject("curatedRank")?.optInt("current", 0)?.takeIf { it > 0 }
        return Side(
            teamId = c.optString("id"),
            displayName = displayName,
            short = short,
            abbrev = tennisAbbrev(displayName, pair.isNotEmpty()),
            // Sets won. Null before the first set, so a match that hasn't started shows
            // no score rather than 0-0.
            score = if (started) sets.count { it.optBoolean("winner", false) } else null,
            record = seed?.let { "($it)" },
            lineScore = sets.map { set ->
                val games = fmtNum(set.optDouble("value", 0.0))
                // 7(7) — the tiebreak points, the way a scoreline prints them.
                val tiebreak = set.optInt("tiebreak", -1).takeIf { it >= 0 }
                if (tiebreak != null) "$games($tiebreak)" else games
            },
            memberIds = members,
        )
    }

    /**
     * Three letters of the surname, the way a club has a three-letter code: the line
     * score header is 28dp wide and "C. Gauff" does not fit in it. A pair takes the first
     * letter of each surname plus a slash.
     */
    private fun tennisAbbrev(displayName: String, doubles: Boolean): String {
        if (doubles) {
            return displayName.split('/').map { it.trim().substringAfterLast(' ').take(1) }
                .filter { it.isNotEmpty() }.joinToString("/").uppercase()
        }
        return displayName.trim().substringAfterLast(' ').take(3).uppercase()
    }

    /**
     * The players in the scoreboard's draws, singles and doubles, as followables. The
     * flag stands in for the crest. Deduplicated with the rankings by the repository.
     */
    fun parseTennisPlayers(leagueId: String, body: String): List<TeamRef> {
        val events = JSONObject(body).optJSONArray("events") ?: return emptyList()
        val out = mutableListOf<TeamRef>()
        for (e in events.objects()) {
            for (grouping in (e.optJSONArray("groupings") ?: JSONArray()).objects()) {
                for (comp in (grouping.optJSONArray("competitions") ?: JSONArray()).objects()) {
                    for (c in (comp.optJSONArray("competitors") ?: JSONArray()).objects()) {
                        c.optJSONObject("athlete")?.let { a ->
                            playerRef(leagueId, c.optString("id"), a)?.let { out += it }
                        }
                        val pair = c.optJSONObject("roster")?.optJSONArray("athletes")?.objects()
                            .orEmpty()
                        val ids = c.optString("id").split('-')
                        if (pair.size == ids.size) {
                            pair.zip(ids).forEach { (a, id) ->
                                playerRef(leagueId, id, a)?.let { out += it }
                            }
                        }
                    }
                }
            }
        }
        return out.distinctBy { it.teamId }.sortedBy { it.displayName }
    }

    /** The top 150 of one tour as followables, in ranking order. */
    fun parseRankedPlayers(leagueId: String, body: String): List<TeamRef> {
        val out = mutableListOf<TeamRef>()
        for (ranking in (JSONObject(body).optJSONArray("rankings") ?: JSONArray()).objects()) {
            for (rank in (ranking.optJSONArray("ranks") ?: JSONArray()).objects()) {
                val a = rank.optJSONObject("athlete") ?: continue
                playerRef(leagueId, a.optString("id"), a)?.let { out += it }
            }
        }
        return out.distinctBy { it.teamId }
    }

    private fun playerRef(leagueId: String, id: String, a: JSONObject): TeamRef? {
        if (id.isEmpty()) return null
        val name = a.optString("displayName").takeIf { it.isNotEmpty() } ?: return null
        // The rankings feed spells it `shortname`; the scoreboard `shortName`.
        val short = a.optString("shortName").ifEmpty { a.optString("shortname") }.ifEmpty { name }
        return TeamRef(
            leagueId = leagueId,
            teamId = id,
            displayName = name,
            short = short,
            abbrev = tennisAbbrev(name, doubles = false),
            // The scoreboard wraps the flag in an object with an `href`; the rankings
            // feed sends the bare URL under the same key.
            logoUrl = (a.optJSONObject("flag")?.optString("href") ?: a.optString("flag"))
                .takeIf { it.isNotEmpty() },
        )
    }

    /**
     * One tour's ranking as a standings table. Rank, name, points, and the move since
     * last week in the long-press sheet.
     */
    fun parseRankings(body: String): List<StandingsGroup> {
        val out = mutableListOf<StandingsGroup>()
        for (ranking in (JSONObject(body).optJSONArray("rankings") ?: JSONArray()).objects()) {
            val rows = (ranking.optJSONArray("ranks") ?: JSONArray()).objects().mapNotNull { r ->
                val a = r.optJSONObject("athlete") ?: return@mapNotNull null
                val current = r.optInt("current", 0)
                val previous = r.optInt("previous", 0)
                val points = fmtNum(r.optDouble("points", 0.0))
                StandingsRow(
                    rank = current.toString(),
                    name = a.optString("displayName"),
                    abbrev = tennisAbbrev(a.optString("displayName"), doubles = false),
                    values = listOf(points),
                    teamId = a.optString("id"),
                    allStats = listOfNotNull(
                        "Rank" to current.toString(),
                        "Points" to points,
                        if (previous > 0) "Last week" to previous.toString() else null,
                        a.optString("citizenshipCountry").takeIf { it.isNotEmpty() }
                            ?.let { "Country" to it },
                        a.optInt("age", 0).takeIf { it > 0 }?.let { "Age" to it.toString() },
                    ),
                )
            }
            if (rows.isNotEmpty()) {
                out += StandingsGroup(ranking.optString("name"), listOf("PTS"), rows)
            }
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
                    allStats = stats.mapNotNull { s ->
                        val value = s.optString("displayValue").takeIf { it.isNotEmpty() }
                            ?: return@mapNotNull null
                        // ESPN ships a readable label for most stats and only a
                        // camelCase key for the rest.
                        val name = s.optString("shortDisplayName")
                            .ifEmpty { s.optString("displayName") }
                            .ifEmpty { prettify(s.optString("name")) }
                        if (name.isEmpty()) null else name to value
                    },
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
                    // For a driver this is the points scored at every round of the
                    // season, which is the whole story of their year.
                    allStats = stats.mapNotNull { s ->
                        val value = s.optString("displayValue").takeIf { it.isNotBlank() }
                            ?: return@mapNotNull null
                        val name = s.optString("shortDisplayName")
                            .ifEmpty { prettify(s.optString("name")) }
                        if (name.isEmpty()) null else name to value
                    },
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

/** `avgPointsAgainst` -> `Avg points against`, for the stats ESPN doesn't label. */
internal fun prettify(key: String): String {
    if (key.isEmpty()) return key
    val spaced = key.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
    return spaced.first().uppercase() + spaced.drop(1).lowercase()
}

internal fun fmtNum(d: Double): String =
    if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
