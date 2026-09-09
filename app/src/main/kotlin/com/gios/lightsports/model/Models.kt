package com.gios.lightsports.model

/**
 * What kind of game this is. Drives two things only: how a period is spelled
 * ("Bot 7th" vs "Q3" vs "P2"), and how loud the notifications are allowed to be.
 */
enum class SportKind { BASEBALL, FOOTBALL, BASKETBALL, HOCKEY, SOCCER, RACING, TENNIS }

enum class GameState { PRE, LIVE, FINAL, OFF }

/**
 * Whether a game is a one-off you might follow without following either side.
 * `SHOWCASE` is all-star weekends and neutral-site novelties; `CHAMPIONSHIP` is the
 * Super Bowl, the World Series, MLS Cup and their equivalents.
 */
enum class EventClass { NONE, SHOWCASE, CHAMPIONSHIP }

/** Which provider a league's data comes from. */
enum class Provider { ESPN, STATSAPI, HOCKEYTECH, WPBL }

/**
 * How often a league is allowed to interrupt.
 *
 * Basketball scores forty times a night, so a notification per bucket would be a
 * pager going off all evening — those leagues say nothing until a quarter ends, and the
 * quarter mark carries the score. Everything else notifies on every change of score,
 * which for baseball, hockey, soccer and football is a handful of events per game.
 *
 * This covers *scores* only. Whether a league also marks the end of each period is
 * [League.markPeriods], and the two are independent: baseball wants every run and no
 * inning marks at all.
 */
enum class Loudness {
    EVERY_SCORE,
    /**
     * Football only. A touchdown and its point-after arrive as one alert; a field goal
     * or a safety is not announced on its own but rides along in the next quarter mark.
     */
    TOUCHDOWNS,
    PERIOD_ONLY,
    FINAL_ONLY,
}

/**
 * A knockout competition a league's clubs also play in — the Leagues Cup, the U.S. Open
 * Cup. ESPN serves these as separate leagues, but they reuse the parent league's team
 * ids, so a followed club is matched in them without any extra bookkeeping.
 */
data class Cup(
    /** ESPN `sports/soccer/<path>`, e.g. `concacaf.leagues.cup`. */
    val path: String,
    /** Shown in place of the league name on the row: "LEAGUES CUP". */
    val name: String,
)

data class League(
    val id: String,
    val name: String,
    val short: String,
    val kind: SportKind,
    val provider: Provider,
    /** ESPN `sports/<path>` fragment, e.g. `baseball/mlb`. */
    val espnPath: String? = null,
    /**
     * A second ESPN path whose games are folded into the same league. Tennis is the one
     * user: ESPN keeps the men's and women's tours at `tennis/atp` and `tennis/wta`, and a
     * Grand Slam appears under both with its own half of the draw. One league in the
     * picker, two fetches underneath.
     */
    val espnAltPath: String? = null,
    /**
     * ESPN's `groups` filter, e.g. `"80"` for FBS college football. Confirmed live:
     * the scoreboard and standings endpoints both honor it (`groups=` on the former,
     * singular `group=` on the latter — no relation between the two spellings), but the
     * plain `teams` endpoint silently ignores it, returning the alphabetically-first
     * slice of every division from FBS through D3. When this is set, the team roster is
     * sourced from the standings tree instead, which carries a full team object at every
     * leaf and does respect the filter.
     */
    val espnGroup: String? = null,
    /** MLB StatsAPI `sportId`, e.g. 11 for Triple-A. */
    val statsApiSportId: Int? = null,
    /** HockeyTech `client_code`, e.g. `pwhl`. */
    val hockeyTechClient: String? = null,
    val loudness: Loudness = Loudness.EVERY_SCORE,
    /** Racing has no home/away pair; the feed renders those rows differently. */
    val isRacing: Boolean = false,
    /**
     * Whether this league's feed carries recognisable one-off events. Only the ESPN
     * leagues do — MiLB's StatsAPI and the PWHL's HockeyTech feed publish neither the
     * headline nor the season slug the classifier reads.
     */
    val hasEvents: Boolean = false,
    /** Shown under the toggles so the choice isn't abstract. */
    val championshipExample: String? = null,
    val specialExample: String? = null,
    /**
     * What the two category toggles are called. "Championship games" and "Special games"
     * fit every team league; tennis has no all-star weekend and its championship is just
     * the final, so it names its rounds instead.
     */
    val championshipLabel: String = "Championship games",
    val specialLabel: String = "Special games",
    /** What one followable thing is called in the picker: a team, or a player. */
    val followNoun: String = "team",
    /** Knockout competitions whose games are folded into this league's feed. */
    val cups: List<Cup> = emptyList(),
    /**
     * Announce the end of each period — halftime, the end of a quarter, an intermission.
     *
     * False for baseball: nine innings, eighteen half-innings, and none of them is an
     * event anybody wants a buzz for. Every other sport has two to four of them a game
     * and they're the natural moments to glance at the phone.
     */
    val markPeriods: Boolean = false,
)

/** A team the user can follow. Cached per league so the picker works offline. */
data class TeamRef(
    val leagueId: String,
    val teamId: String,
    val displayName: String,
    val short: String,
    val abbrev: String,
    /** Crest, drawn beside the name in the feed. Null when the provider has none. */
    val logoUrl: String? = null,
) {
    /** Stable key for the follow set. Team ids are only unique within a league. */
    val key: String get() = "$leagueId:$teamId"
}

data class Side(
    val teamId: String,
    val displayName: String,
    val short: String,
    val abbrev: String,
    val score: Int?,
    val record: String? = null,
    /** Runs by inning, points by quarter, goals by period. */
    val lineScore: List<String> = emptyList(),
    val hits: Int? = null,
    val errors: Int? = null,
    /**
     * The people making up this side when it isn't a club: a doubles pair. A follow on
     * either player matches the pair, so following Gauff gets her doubles too.
     */
    val memberIds: List<String> = emptyList(),
    /**
     * Poll rank, college football only. ESPN sends `curatedRank.current` for every FBS
     * side and spells "unranked" as 99, which is dropped here.
     */
    val rank: Int? = null,
)

/**
 * Where a live game stands right now, beyond the score. ESPN attaches this to the
 * scoreboard entry while a game is in progress and drops it at the final, so every field
 * is optional and the whole thing is null for a game that hasn't started.
 *
 * Football fills the top half; baseball the bottom. Other sports get the shared fields
 * ([lastPlay] mostly) and nothing else.
 */
data class Situation(
    /** Team id of the side with the ball. */
    val possession: String? = null,
    /** "2nd & 7 at NE 16" — ESPN's own wording. */
    val downDistance: String? = null,
    /** "2nd & 7", the same without the spot. */
    val shortDownDistance: String? = null,
    /** "NE 16": which team's yard line the ball sits on, and which one. */
    val spot: String? = null,
    val down: Int? = null,
    val distance: Int? = null,
    val isRedZone: Boolean = false,
    val homeTimeouts: Int? = null,
    val awayTimeouts: Int? = null,
    /** The last play as a sentence: "K.Walker III run for 12 yds for a TD". */
    val lastPlay: String? = null,
    /** "7 plays, 58 yards, 3:41" — the drive the last play belongs to. */
    val drive: String? = null,
    // ---- baseball
    val balls: Int? = null,
    val strikes: Int? = null,
    val outs: Int? = null,
    val onFirst: Boolean = false,
    val onSecond: Boolean = false,
    val onThird: Boolean = false,
    val batter: String? = null,
    val pitcher: String? = null,
) {
    /**
     * How far the offense is from the goal line, 1..99, read off [spot] rather than
     * ESPN's absolute `yardLine`, whose direction is undocumented. "NE 16" with SEA in
     * possession is 16 yards out; "SEA 40" with SEA in possession is 60.
     */
    fun yardsToGoal(offenseAbbrev: String?): Int? {
        val s = spot?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val parts = s.split(' ')
        if (parts.size < 2) return null
        val yards = parts.last().toIntOrNull() ?: return null
        val side = parts.dropLast(1).joinToString(" ")
        if (offenseAbbrev == null) return null
        return if (side.equals(offenseAbbrev, ignoreCase = true)) 100 - yards else yards
    }
}

data class Game(
    val id: String,
    val leagueId: String,
    val state: GameState,
    val startMillis: Long,
    /** Provider's own words: "Bot 7th", "Final/OT", "Postponed", "3:24 - 2nd". */
    val statusDetail: String,
    val period: Int = 0,
    val clock: String? = null,
    val home: Side,
    val away: Side,
    val venue: String? = null,
    val broadcast: String? = null,
    /** Series or session context: "Game 3 of 7", "Practice 2", "Leg 2". */
    val note: String? = null,
    /**
     * The provider's status enum: `STATUS_HALFTIME`, `STATUS_END_PERIOD`,
     * `STATUS_SECOND_HALF`. ESPN names the phase for soccer and falls back to a flat
     * `STATUS_IN_PROGRESS` elsewhere, so it is one signal of three for spotting the end
     * of a period, not the whole answer.
     */
    val statusName: String? = null,
    /** "Super Bowl LX", "NHL Winter Classic", "MLS Cup" — what to call this one. */
    val eventTitle: String? = null,
    val eventClass: EventClass = EventClass.NONE,
    /**
     * The competition, when it isn't the league's own: "Leagues Cup". Cup games are
     * filed under the parent league so they land in the same feed as the league fixtures.
     */
    val competition: String? = null,
    /** Live only: possession, down and distance, the count, the last play. */
    val situation: Situation? = null,
    /** The pre-game line as the book writes it: "SEA -3". */
    val odds: String? = null,
    /** The total, "44.5". */
    val overUnder: String? = null,
    /** "75° Mostly sunny" — ESPN sends a forecast for outdoor games. */
    val weather: String? = null,
    /** Football's week number; the season is scheduled in weeks, not dates. */
    val week: Int? = null,
    /** ESPN's one-line recap of a finished game: "Walker's late TD lifts Seahawks". */
    val headline: String? = null,
    val neutralSite: Boolean = false,
) {
    /** The side in possession, or null when nobody is or the provider doesn't say. */
    val offense: Side? get() = when (situation?.possession) {
        null -> null
        home.teamId -> home
        away.teamId -> away
        else -> null
    }

    /** The other one. */
    val defense: Side? get() = when (offense) {
        null -> null
        home -> away
        else -> home
    }

    /**
     * True when the user follows either side, or follows the category this game belongs
     * to. Everything downstream — the feed filter, the notification poll, the standings
     * highlight — is expressed in terms of this one predicate.
     */
    fun involves(teamKeys: Set<String>): Boolean {
        if ("$leagueId:${home.teamId}" in teamKeys) return true
        if ("$leagueId:${away.teamId}" in teamKeys) return true
        if (home.memberIds.any { "$leagueId:$it" in teamKeys }) return true
        if (away.memberIds.any { "$leagueId:$it" in teamKeys }) return true
        return when (eventClass) {
            EventClass.SHOWCASE -> "$leagueId:special" in teamKeys
            EventClass.CHAMPIONSHIP -> "$leagueId:championship" in teamKeys
            EventClass.NONE -> false
        }
    }
}

/**
 * A racing weekend. One row in the feed, not one row per session — nobody wants
 * five identical Hungarian Grand Prix cards.
 */
data class RaceEvent(
    val id: String,
    val leagueId: String,
    val name: String,
    val shortName: String,
    val state: GameState,
    val startMillis: Long,
    /** Next session if the weekend hasn't finished, else the race session. */
    val sessionLabel: String?,
    val sessionMillis: Long?,
    val podium: List<String> = emptyList(),
    val circuit: String? = null,
)

data class StandingsRow(
    val rank: String,
    val name: String,
    val abbrev: String,
    val values: List<String>,
    val teamId: String? = null,
    /**
     * Every stat the provider sent for this team, label to value, in its own order.
     * The table shows four or five columns; a long press opens the rest.
     */
    val allStats: List<Pair<String, String>> = emptyList(),
)

data class StandingsGroup(
    val title: String,
    val headers: List<String>,
    val rows: List<StandingsRow>,
)

/**
 * One play from ESPN's play-by-play. Newest first as fetched; the game screen shows the
 * last handful under the field.
 */
data class Play(
    val id: String,
    /** The provider's sentence: "J.Warren right end to PIT 21 for 2 yards (J.Sherwood)." */
    val text: String,
    /** "Jaylen Warren 2 Yd Rush" — the box-score wording, shorter. */
    val shortText: String? = null,
    /** "Rush", "Pass Incompletion", "Passing Touchdown", "Punt". */
    val type: String? = null,
    val period: Int = 0,
    /** Game clock when the play started, "7:49". */
    val clock: String? = null,
    /** Team id credited with the play. */
    val teamId: String? = null,
    val scoring: Boolean = false,
    val scoreValue: Int = 0,
    val awayScore: Int? = null,
    val homeScore: Int? = null,
    /** "3rd & 9 at PIT 20" before the snap. */
    val downDistance: String? = null,
)

/** A scoring play from the game summary: what, when, and the score after it. */
data class ScoringPlay(
    val id: String,
    /** "Jelani Woods 13 Yd pass from Geno Smith (Jason Sanders Kick)". */
    val text: String,
    /** "TD", "FG", "SF", "2PT" — the provider's abbreviation. */
    val kind: String,
    val period: Int,
    val clock: String?,
    val teamId: String?,
    val teamAbbrev: String?,
    val awayScore: Int,
    val homeScore: Int,
)
