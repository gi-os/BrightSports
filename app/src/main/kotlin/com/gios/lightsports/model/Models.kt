package com.gios.lightsports.model

/**
 * What kind of game this is. Drives two things only: how a period is spelled
 * ("Bot 7th" vs "Q3" vs "P2"), and how loud the notifications are allowed to be.
 */
enum class SportKind { BASEBALL, FOOTBALL, BASKETBALL, HOCKEY, SOCCER, RACING }

enum class GameState { PRE, LIVE, FINAL, OFF }

/** Which provider a league's data comes from. */
enum class Provider { ESPN, STATSAPI, HOCKEYTECH }

/**
 * How often a league is allowed to interrupt.
 *
 * Basketball scores forty times a night, so a notification per bucket would be a
 * pager going off all evening — those leagues report at period boundaries only.
 * Everything else notifies on every change of score, which for baseball, hockey,
 * soccer and football is a handful of events per game.
 */
enum class Loudness { EVERY_SCORE, PERIOD_END, FINAL_ONLY }

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
) {
    fun involves(teamKeys: Set<String>): Boolean =
        "$leagueId:${home.teamId}" in teamKeys || "$leagueId:${away.teamId}" in teamKeys
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
