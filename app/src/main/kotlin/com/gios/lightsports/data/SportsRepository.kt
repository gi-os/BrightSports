package com.gios.lightsports.data

import android.content.Context
import com.gios.lightsports.model.Game
import com.gios.lightsports.model.League
import com.gios.lightsports.model.Play
import com.gios.lightsports.model.ScoringPlay
import com.gios.lightsports.model.Provider
import com.gios.lightsports.model.SportKind
import com.gios.lightsports.model.FieldEvent
import com.gios.lightsports.model.StandingsGroup
import com.gios.lightsports.model.TeamRef
import com.gios.lightsports.model.TeamSeason
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The one place that knows which provider serves which league. Everything above this
 * line works in [Game] and [TeamRef] and never sees a URL.
 */
class SportsRepository(context: Context) {

    private val cacheDir: File = File(context.filesDir, "cache").apply { mkdirs() }
    private val prefs = Prefs(context)

    private val ymd = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val dashed = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    // ---------------------------------------------------------------- teams

    /**
     * Team lists change once a year at most, so they are cached for a week and served
     * stale on a network failure. Without that the follow picker would be empty in a
     * subway, which is exactly where someone edits their teams.
     */
    fun teams(league: League): List<TeamRef> {
        if (league.kind == SportKind.TENNIS) return players(league)
        if (league.kind == SportKind.GOLF) return golfers(league)
        // A league with a `groups` filter (college football) needs the standings
        // tree instead of the plain teams endpoint, which ignores that filter — see
        // League.espnGroup.
        val body = when (league.provider) {
            Provider.ESPN -> Http.cached(
                cacheDir, "teams-${league.id}.json",
                if (league.espnGroup != null) EspnParser.standingsUrl(league)
                else EspnParser.teamsUrl(league),
                TEAM_CACHE_MILLIS,
            )
            Provider.STATSAPI -> Http.cached(
                cacheDir, "teams-${league.id}.json",
                StatsApiParser.teamsUrl(league), TEAM_CACHE_MILLIS,
            )
            Provider.HOCKEYTECH -> Http.cached(
                cacheDir, "teams-${league.id}.json",
                HockeyTechParser.teamsUrl(league), TEAM_CACHE_MILLIS,
            )
            Provider.WPBL -> Http.cached(
                cacheDir, "teams-${league.id}.json",
                WpblParser.teamsUrl(), TEAM_CACHE_MILLIS,
            )
        } ?: return emptyList()

        return runCatching {
            when (league.provider) {
                Provider.ESPN -> if (league.espnGroup != null) {
                    EspnParser.parseTeamsFromStandings(league.id, body)
                } else {
                    EspnParser.parseTeams(league.id, body)
                }
                Provider.STATSAPI -> StatsApiParser.parseTeams(league.id, body)
                Provider.HOCKEYTECH -> HockeyTechParser.parseTeams(league.id, body)
                Provider.WPBL -> WpblParser.parseTeams(league.id, body)
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Tennis has no roster endpoint, so the followable list is assembled: the top 150 of
     * each tour from the rankings, cached a week like a team list, plus everyone in the
     * draws on the scoreboard right now, cached a day so a qualifier who reaches the
     * second week is in the picker while it matters. Ranked players come first so the
     * names people actually look for are at the top.
     */
    private fun players(league: League): List<TeamRef> {
        val paths = listOfNotNull(league.espnPath, league.espnAltPath)
        val ranked = paths.flatMap { path ->
            Http.cached(
                cacheDir, "rankings-${path.substringAfterLast('/')}.json",
                EspnParser.rankingsUrl(path), TEAM_CACHE_MILLIS,
            )?.let { body ->
                runCatching { EspnParser.parseRankedPlayers(league.id, body) }
                    .getOrDefault(emptyList())
            }.orEmpty()
        }
        val drawn = Http.cached(
            cacheDir, "draws-${league.id}.json",
            EspnParser.tennisScoreboardUrl(league.espnPath.orEmpty()), SEASON_CACHE_MILLIS,
        )?.let { body ->
            runCatching { EspnParser.parseTennisPlayers(league.id, body) }
                .getOrDefault(emptyList())
        }.orEmpty()
        return (ranked + drawn).distinctBy { it.teamId }
    }

    /** Racing has no followable clubs; the series itself is the thing to follow. */
    fun isFollowableAsWhole(league: League): Boolean = league.isField

    // ---------------------------------------------------------------- games

    /**
     * ESPN's scoreboard for a date window, with a fallback for the range query.
     *
     * As of 2026-09-15 ESPN answers *every* `dates=start-end` scoreboard request with
     * HTTP 400 `{"code":400,"message":"Failed to get events endpoint."}` — the same
     * endpoint serves a single day, a year, or no window at all. Since the feed and the
     * live poll both ask per league per poll, that one 400 blanked every score in the
     * app: nothing came back, so every followed team fell into the feed's idle list.
     *
     * So a window that answers with nothing is asked again with no window. ESPN's default
     * is its own notion of "now" (today plus the last few days), which is enough to keep
     * today's slate, the scores, and the ticker alive.
     *
     * ponytail: the fallback drops the days either side of today and the week paging.
     * Remove it once ESPN's range query answers again.
     */
    private fun espnScoreboard(path: String, group: String?, fromYmd: String, toYmd: String): String? {
        val windowed = Http.get(EspnParser.pathScoreboardUrl(path, fromYmd, toYmd, group))
        if (windowed != null && windowed.contains("\"events\"")) return windowed
        return Http.get(EspnParser.pathScoreboardUrl(path, fromYmd, toYmd, group, windowed = false))
    }

    /**
     * Games for one league across a date window. Not cached — a scoreboard is stale
     * the moment it lands.
     */
    fun games(league: League, nowMillis: Long, zone: ZoneId, shiftDays: Long = 0L): List<Game> {
        if (league.kind == SportKind.TENNIS) return matches(league)
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        // The window slides by whole weeks: last week's results, next week's slate.
        val from = today.minusDays(BACK_DAYS).plusDays(shiftDays)
        val to = today.plusDays(AHEAD_DAYS).plusDays(shiftDays)
        val body = when (league.provider) {
            Provider.ESPN -> espnScoreboard(
                league.espnPath.orEmpty(), league.espnGroup, from.format(ymd), to.format(ymd),
            )

            Provider.STATSAPI -> Http.get(
                StatsApiParser.scheduleUrl(league, from.format(dashed), to.format(dashed)),
            )
            Provider.HOCKEYTECH -> Http.get(
                HockeyTechParser.scorebarUrl(league, BACK_DAYS.toInt(), AHEAD_DAYS.toInt()),
            )
            // No date parameter on this one: it answers with the whole season and the
            // window is applied after parsing.
            Provider.WPBL -> Http.get(WpblParser.gamesUrl())
        }

        // A failed league fetch must not take the cups down with it: in August the
        // Leagues Cup is where the games actually are.
        val leagueGames = if (body == null) emptyList() else runCatching {
            when (league.provider) {
                // The team list is already cached for a week, so handing its ids to the
                // parser costs nothing and is what makes an all-star fixture — which
                // carries no headline at all in MLS and MLB — recognisable. Gated on
                // hasEvents: college football's off-conference games (an FBS team
                // hosting an FCS team) would otherwise read as an off-roster showcase
                // every single week, since that mismatch is routine there rather than
                // the exception it is everywhere else this runs.
                Provider.ESPN -> EspnParser.parseScoreboard(
                    league, body,
                    rosterIds = if (league.hasEvents) {
                        teams(league).map { it.teamId }.toSet()
                    } else {
                        emptySet()
                    },
                )
                Provider.STATSAPI -> StatsApiParser.parseSchedule(league, body)
                Provider.HOCKEYTECH -> HockeyTechParser.parseScorebar(league, body)
                // The cached team list fills in the sides of a fixture the schedule
                // has not named yet, which is otherwise a row reading "Away team".
                Provider.WPBL -> WpblParser.parseGames(
                    league, body,
                    roster = teams(league),
                    fromMillis = from.atStartOfDay(zone).toInstant().toEpochMilli(),
                    toMillis = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
                )
            }
        }.getOrDefault(emptyList())

        return leagueGames + cupGames(league, from.format(ymd), to.format(ymd))
    }

    /**
     * A Grand Slam is one event on both tour scoreboards, identical down to the match
     * ids — confirmed against the 2026 US Open, 625 matches on each side, all shared. So
     * one fetch covers men, women and mixed, and the second path is only consulted for
     * the rankings. The window is applied by the feed: the tournament comes back whole.
     */
    private fun matches(league: League): List<Game> {
        val body = Http.get(EspnParser.tennisScoreboardUrl(league.espnPath.orEmpty()))
            ?: return emptyList()
        return runCatching { EspnParser.parseTennis(league, body) }.getOrDefault(emptyList())
    }

    /**
     * The knockout competitions a league's clubs also play in. Fetched on every poll:
     * measured against the live endpoints, the two MLS cups add about 260ms to a 430ms
     * total, and the out-of-season one answers in under a kilobyte — cheap enough not to
     * need a separate cadence.
     */
    private fun cupGames(league: League, fromYmd: String, toYmd: String): List<Game> {
        if (league.cups.isEmpty()) return emptyList()
        val out = mutableListOf<Game>()
        for (cup in league.cups) {
            val body = espnScoreboard(cup.path, null, fromYmd, toYmd) ?: continue
            out += runCatching {
                // No roster check here. A cup field is full of clubs from other leagues,
                // so every game would look like an all-star fixture; in a cup the round
                // in `season.slug` is the only signal that matters.
                EspnParser.parseScoreboard(league, body, competition = cup.name)
            }.getOrDefault(emptyList())
        }
        return out
    }

    /**
     * The last few plays of one game, newest first. ESPN only, and only for the sports
     * that have a play-by-play; everything else gets an empty list and the screen shows
     * nothing under the score.
     */
    fun plays(league: League, gameId: String): List<Play> {
        if (league.provider != Provider.ESPN || league.isField) return emptyList()
        if (league.kind == SportKind.TENNIS) return emptyList()
        val body = Http.get(EspnParser.playsUrl(league, gameId)) ?: return emptyList()
        return runCatching { EspnParser.parsePlays(body) }.getOrDefault(emptyList())
    }

    /**
     * The scoring plays of one game. Half a megabyte a fetch, so a finished game's list
     * is written to disk and never fetched again; a live game's is re-fetched only when
     * the caller says the score has moved.
     */
    fun scoring(league: League, gameId: String, final: Boolean): List<ScoringPlay> {
        if (league.provider != Provider.ESPN || league.isField) return emptyList()
        if (league.kind == SportKind.TENNIS) return emptyList()
        val url = EspnParser.summaryUrl(league, gameId)
        val body = if (final) {
            Http.cached(cacheDir, "scoring-$gameId.json", url, Long.MAX_VALUE)
        } else {
            Http.get(url)
        } ?: return emptyList()
        return runCatching { EspnParser.parseScoringPlays(body) }.getOrDefault(emptyList())
    }

    /**
     * The recap story of a finished game, one entry per paragraph, or empty when the
     * provider has not written one.
     *
     * Same body as [scoring], and for a final game that body is cached forever, so the
     * two together cost one request. Asked for only on a final game and only when the
     * reader opens the recap: baseball's summary is a megabyte of play-by-play, which is
     * not a thing to download on the chance somebody wants the story.
     */
    fun recap(league: League, gameId: String): List<String> {
        if (league.provider != Provider.ESPN || league.isField) return emptyList()
        val url = EspnParser.summaryUrl(league, gameId)
        val body = Http.cached(cacheDir, "scoring-$gameId.json", url, Long.MAX_VALUE)
            ?: return emptyList()
        return runCatching { EspnParser.parseRecapStory(body) }.getOrDefault(emptyList())
    }

    /**
     * PGA tournaments across a narrow window: the one being played, the one just
     * finished, and the one coming.
     *
     * Cached for five minutes, which no other scoreboard here is. A golf scoreboard is
     * the whole leaderboard — a hundred and fifty players, each carrying four rounds of
     * eighteen holes — and one tournament is about half a megabyte. The score watcher
     * asks for this every thirty seconds while anything is live, so without the cache a
     * Sunday afternoon would cost sixty megabytes. Five minutes is also about the
     * resolution golf has: a group plays a hole in fifteen.
     */
    fun golf(league: League, nowMillis: Long, zone: ZoneId, shiftDays: Long = 0L): List<FieldEvent> {
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val from = today.minusDays(GOLF_BACK_DAYS).plusDays(shiftDays)
        val to = today.plusDays(GOLF_AHEAD_DAYS).plusDays(shiftDays)
        val body = Http.cached(
            cacheDir, "golf-${league.id}-$shiftDays.json",
            EspnParser.golfUrl(league, from.format(ymd), to.format(ymd)),
            LEADERBOARD_CACHE_MILLIS,
        ) ?: return emptyList()
        return runCatching { EspnParser.parseGolf(league, body, nowMillis) }
            .getOrDefault(emptyList())
    }

    /**
     * The followable golfers: everyone in a field this week.
     *
     * There is no roster endpoint for golf worth using. `athletes` answers with four
     * thousand `$ref` links and nothing else, a page each, and the season's scoreboard is
     * eleven megabytes. The field in front of the app is the list that matters anyway —
     * these are the players whose scores will move today — and it comes from the body
     * already fetched for the feed, so the picker costs nothing.
     */
    private fun golfers(league: League): List<TeamRef> {
        val zone = ZoneId.systemDefault()
        return golf(league, System.currentTimeMillis(), zone)
            .flatMap { it.entries }
            .distinctBy { it.athleteId ?: it.name }
            .mapNotNull { entry ->
                TeamRef(
                    leagueId = league.id,
                    teamId = entry.athleteId ?: return@mapNotNull null,
                    displayName = entry.name,
                    short = entry.shortName,
                    abbrev = entry.shortName,
                )
            }
            .sortedBy { it.displayName }
    }

    fun races(league: League, nowMillis: Long, zone: ZoneId): List<FieldEvent> {
        val year = Instant.ofEpochMilli(nowMillis).atZone(zone).year
        val body = Http.get(EspnParser.raceUrl(league, year)) ?: return emptyList()
        return runCatching { EspnParser.parseRaces(league, body, nowMillis) }
            .getOrDefault(emptyList())
    }

    /**
     * Everything the followed teams are involved in, across every league they span.
     * Leagues are fetched one at a time on purpose: a followed set usually touches two
     * or three leagues, and serialising them keeps the Doze allowlist window short.
     */
    fun followedGames(nowMillis: Long, zone: ZoneId, shiftDays: Long = 0L): Pair<List<Game>, List<FieldEvent>> {
        val follows = prefs.follows
        if (follows.isEmpty()) return emptyList<Game>() to emptyList()
        val gameOut = mutableListOf<Game>()
        val raceOut = mutableListOf<FieldEvent>()
        for (leagueId in prefs.followedLeagueIds()) {
            val league = Leagues.byId(leagueId) ?: continue
            if (league.isField) {
                raceOut += if (league.kind == SportKind.GOLF) {
                    golf(league, nowMillis, zone, shiftDays)
                } else {
                    races(league, nowMillis, zone)
                }
            } else {
                gameOut += games(league, nowMillis, zone, shiftDays).filter { it.involves(follows) }
            }
        }
        return gameOut to raceOut
    }

    /**
     * Games matching a typed query, across every league the app knows, not only the
     * followed ones. A league whose name matches ("nfl", "premier") gives its whole slate;
     * otherwise the team lists (cached a week each) are searched and only the leagues
     * with a matching club are fetched. Racing and tennis are left out: a race has no
     * opponent to look up and a tennis draw is already one screen.
     */
    fun search(query: String, nowMillis: Long, zone: ZoneId): List<Game> {
        val q = query.trim().lowercase()
        if (q.length < 2) return emptyList()
        val leagueHits = Leagues.all.filter { l ->
            !l.isField && l.kind != SportKind.TENNIS &&
                (l.short.lowercase().contains(q) || l.name.lowercase().contains(q) || l.id == q)
        }
        val teamHits = mutableMapOf<League, Set<String>>()
        for (league in Leagues.all) {
            if (league.isField || league.kind == SportKind.TENNIS || league in leagueHits) continue
            val ids = teams(league).filter { t ->
                t.displayName.lowercase().contains(q) || t.short.lowercase().contains(q) ||
                    t.abbrev.lowercase() == q
            }.map { it.teamId }.toSet()
            if (ids.isNotEmpty()) teamHits[league] = ids
        }
        val out = mutableListOf<Game>()
        for (league in leagueHits) out += games(league, nowMillis, zone)
        for ((league, ids) in teamHits) {
            out += games(league, nowMillis, zone).filter { g ->
                g.home.teamId in ids || g.away.teamId in ids
            }
        }
        return out.distinctBy { "${it.leagueId}:${it.id}" }
    }

    /**
     * One team's season. Cached six hours: the list changes when a game finishes, and a
     * finished game is already in the feed, so the schedule can lag.
     */
    fun teamSeason(league: League, teamId: String): TeamSeason? {
        if (league.provider != Provider.ESPN || league.isField) return null
        if (league.kind == SportKind.TENNIS) return null
        val body = Http.cached(
            cacheDir, "season-${league.id}-$teamId.json",
            EspnParser.scheduleUrl(league, teamId), SCHEDULE_CACHE_MILLIS,
        ) ?: return null
        return runCatching {
            TeamSeason(
                leagueId = league.id,
                teamId = teamId,
                games = EspnParser.parseScoreboard(league, body),
                byeWeek = EspnParser.parseByeWeek(body),
                fetchedAt = System.currentTimeMillis(),
            )
        }.getOrNull()
    }

    // ------------------------------------------------------------- standings

    fun standings(league: League, nowMillis: Long, zone: ZoneId): List<StandingsGroup> {
        // See SportsViewModel.followedLeagues: golf has no table worth the download.
        if (league.kind == SportKind.GOLF) return emptyList()
        val season = Instant.ofEpochMilli(nowMillis).atZone(zone).year
        return runCatching {
            when (league.provider) {
                Provider.ESPN -> {
                    // Tennis has no table; the rankings are the nearest thing, one per
                    // tour, and they turn over weekly so they are not cached.
                    if (league.kind == SportKind.TENNIS) {
                        return listOfNotNull(league.espnPath, league.espnAltPath).flatMap { path ->
                            Http.get(EspnParser.rankingsUrl(path))
                                ?.let { EspnParser.parseRankings(it) }.orEmpty()
                        }
                    }
                    val body = Http.get(EspnParser.standingsUrl(league)) ?: return emptyList()
                    if (league.isField) EspnParser.parseRacingStandings(body)
                    else EspnParser.parseStandings(league, body)
                }
                Provider.STATSAPI -> {
                    val leagueIds = Http.cached(
                        cacheDir, "leagues-${league.id}.json",
                        StatsApiParser.leaguesUrl(league), TEAM_CACHE_MILLIS,
                    )?.let { StatsApiParser.parseLeagueIds(it) }.orEmpty()
                    if (leagueIds.isEmpty()) return emptyList()
                    val divisions = Http.cached(
                        cacheDir, "divisions-${league.id}.json",
                        StatsApiParser.divisionsUrl(league), TEAM_CACHE_MILLIS,
                    )?.let { StatsApiParser.parseDivisions(it) }.orEmpty()
                    val body = Http.get(StatsApiParser.standingsUrl(leagueIds, season))
                        ?: return emptyList()
                    StatsApiParser.parseStandings(body, divisions)
                }
                // The league publishes no standings endpoint — its own site computes
                // the table in the browser from finished games, and so does this.
                Provider.WPBL -> {
                    val body = Http.get(WpblParser.gamesUrl()) ?: return emptyList()
                    WpblParser.standings(
                        WpblParser.parseGames(
                            league, body,
                            roster = teams(league),
                            standingsOnly = true,
                        ),
                        title = league.short,
                    )
                }
                Provider.HOCKEYTECH -> {
                    val seasonId = Http.cached(
                        cacheDir, "seasons-${league.id}.json",
                        HockeyTechParser.seasonsUrl(league), SEASON_CACHE_MILLIS,
                    )?.let { HockeyTechParser.parseLatestSeasonId(it) } ?: return emptyList()
                    val body = Http.get(HockeyTechParser.standingsUrl(league, seasonId))
                        ?: return emptyList()
                    HockeyTechParser.parseStandings(body)
                }
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        private const val TEAM_CACHE_MILLIS = 7L * 24 * 60 * 60 * 1000
        private const val SEASON_CACHE_MILLIS = 24L * 60 * 60 * 1000
        private const val SCHEDULE_CACHE_MILLIS = 6L * 60 * 60 * 1000

        /** See [golf]: half a megabyte a fetch is worth five minutes of staleness. */
        private const val LEADERBOARD_CACHE_MILLIS = 5L * 60 * 1000

        /** Enough history for "RECENT", enough future for a week of schedule. */
        const val BACK_DAYS = 4L
        const val AHEAD_DAYS = 11L

        /** Tighter than the games window on purpose: every extra tournament in range is
         *  another half-megabyte of leaderboard. */
        private const val GOLF_BACK_DAYS = 3L
        private const val GOLF_AHEAD_DAYS = 8L
    }
}
