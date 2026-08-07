package com.gios.lightsports.data

import android.content.Context
import com.gios.lightsports.model.Game
import com.gios.lightsports.model.League
import com.gios.lightsports.model.Provider
import com.gios.lightsports.model.RaceEvent
import com.gios.lightsports.model.StandingsGroup
import com.gios.lightsports.model.TeamRef
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

    /** Racing has no followable clubs; the series itself is the thing to follow. */
    fun isFollowableAsWhole(league: League): Boolean = league.isRacing

    // ---------------------------------------------------------------- games

    /**
     * Games for one league across a date window. Not cached — a scoreboard is stale
     * the moment it lands.
     */
    fun games(league: League, nowMillis: Long, zone: ZoneId): List<Game> {
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val from = today.minusDays(BACK_DAYS)
        val to = today.plusDays(AHEAD_DAYS)
        val body = when (league.provider) {
            Provider.ESPN -> Http.get(
                EspnParser.scoreboardUrl(league, from.format(ymd), to.format(ymd)),
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
     * The knockout competitions a league's clubs also play in. Fetched on every poll:
     * measured against the live endpoints, the two MLS cups add about 260ms to a 430ms
     * total, and the out-of-season one answers in under a kilobyte — cheap enough not to
     * need a separate cadence.
     */
    private fun cupGames(league: League, fromYmd: String, toYmd: String): List<Game> {
        if (league.cups.isEmpty()) return emptyList()
        val out = mutableListOf<Game>()
        for (cup in league.cups) {
            val body = Http.get(EspnParser.pathScoreboardUrl(cup.path, fromYmd, toYmd))
                ?: continue
            out += runCatching {
                // No roster check here. A cup field is full of clubs from other leagues,
                // so every game would look like an all-star fixture; in a cup the round
                // in `season.slug` is the only signal that matters.
                EspnParser.parseScoreboard(league, body, competition = cup.name)
            }.getOrDefault(emptyList())
        }
        return out
    }

    fun races(league: League, nowMillis: Long, zone: ZoneId): List<RaceEvent> {
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
    fun followedGames(nowMillis: Long, zone: ZoneId): Pair<List<Game>, List<RaceEvent>> {
        val follows = prefs.follows
        if (follows.isEmpty()) return emptyList<Game>() to emptyList()
        val gameOut = mutableListOf<Game>()
        val raceOut = mutableListOf<RaceEvent>()
        for (leagueId in prefs.followedLeagueIds()) {
            val league = Leagues.byId(leagueId) ?: continue
            if (league.isRacing) {
                raceOut += races(league, nowMillis, zone)
            } else {
                gameOut += games(league, nowMillis, zone).filter { it.involves(follows) }
            }
        }
        return gameOut to raceOut
    }

    // ------------------------------------------------------------- standings

    fun standings(league: League, nowMillis: Long, zone: ZoneId): List<StandingsGroup> {
        val season = Instant.ofEpochMilli(nowMillis).atZone(zone).year
        return runCatching {
            when (league.provider) {
                Provider.ESPN -> {
                    val body = Http.get(EspnParser.standingsUrl(league)) ?: return emptyList()
                    if (league.isRacing) EspnParser.parseRacingStandings(body)
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

        /** Enough history for "RECENT", enough future for a week of schedule. */
        const val BACK_DAYS = 4L
        const val AHEAD_DAYS = 11L
    }
}
