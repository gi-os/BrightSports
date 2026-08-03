package com.gios.lightsports.model

/**
 * What kind of game this is. Drives two things only: how a period is spelled
 * ("Bot 7th" vs "Q3" vs "P2"), and how loud the notifications are allowed to be.
 */
enum class SportKind { BASEBALL, FOOTBALL, BASKETBALL, HOCKEY, SOCCER, RACING }

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
enum class Loudness { EVERY_SCORE, PERIOD_ONLY, FINAL_ONLY }

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
)

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
) {
    /**
     * True when the user follows either side, or follows the category this game belongs
     * to. Everything downstream — the feed filter, the notification poll, the standings
     * highlight — is expressed in terms of this one predicate.
     */
    fun involves(teamKeys: Set<String>): Boolean {
        if ("$leagueId:${home.teamId}" in teamKeys) return true
        if ("$leagueId:${away.teamId}" in teamKeys) return true
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
