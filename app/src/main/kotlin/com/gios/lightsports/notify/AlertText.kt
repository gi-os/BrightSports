package com.gios.lightsports.notify

import com.gios.lightsports.model.Game
import com.gios.lightsports.model.League
import com.gios.lightsports.model.RaceEvent
import com.gios.lightsports.model.SportKind
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Notification wording. Kept separate from the posting code so it can be tested, and
 * because on a 3.9" greyscale panel the character count matters: a title that wraps
 * pushes the score off the shade.
 */
object AlertText {

    private val timeFormat = DateTimeFormatter.ofPattern("h:mm a")

    fun title(game: Game, kind: ScoreDiff.Kind): String = when (kind) {
        ScoreDiff.Kind.SOON, ScoreDiff.Kind.START, ScoreDiff.Kind.OFF ->
            "${game.away.short} at ${game.home.short}"
        else ->
            "${game.away.short} ${game.away.score ?: 0} · ${game.home.short} ${game.home.score ?: 0}"
    }

    fun body(game: Game, league: League, kind: ScoreDiff.Kind, zone: ZoneId): String {
        val prefix = game.competition ?: league.short
        val detail = when (kind) {
            // "in 15 min" rather than a clock time: the alert is the answer to "should I
            // put the TV on", and a time would need doing arithmetic on.
            ScoreDiff.Kind.SOON -> {
                val minutes = ((game.startMillis - System.currentTimeMillis()) / 60_000L)
                    .coerceAtLeast(1L)
                listOfNotNull(
                    if (minutes <= 1L) "Starts now" else "Starts in $minutes min",
                    game.broadcast,
                ).joinToString(" · ")
            }
            ScoreDiff.Kind.START -> {
                val at = Instant.ofEpochMilli(game.startMillis).atZone(zone).format(timeFormat)
                listOfNotNull(at, game.broadcast).joinToString(" · ")
            }
            ScoreDiff.Kind.OFF -> game.statusDetail.ifEmpty { "Postponed" }
            ScoreDiff.Kind.FINAL -> game.statusDetail.ifEmpty { "Final" }
            ScoreDiff.Kind.PERIOD -> boundaryLabel(league.kind, game)
            ScoreDiff.Kind.SCORE -> game.statusDetail.ifEmpty {
                periodLabel(league.kind, game.period)
            }
        }
        return if (detail.isEmpty()) prefix else "$prefix · $detail"
    }

    /**
     * What to call the break that has just started.
     *
     * The midpoint has its own name in the sports that have one — nobody says "end of the
     * second quarter", they say halftime. Which period ended is read the same way
     * [ScoreDiff.endedPeriod] reads it: the provider's own status when it spells the
     * boundary out, and the previous period when all we saw was the number move.
     */
    fun boundaryLabel(kind: SportKind, game: Game): String {
        if (ScoreDiff.isHalftime(game.statusName, game.statusDetail)) return "Halftime"
        val explicit = ScoreDiff.explicitBoundary(game.statusName, game.statusDetail)
        val ended = if (explicit) game.period else game.period - 1
        if (ended <= 0) return "End of period"
        // Football and basketball reach the midpoint at the end of the second quarter;
        // soccer at the end of the first half. Same word, different number.
        val midpoint = when (kind) {
            SportKind.FOOTBALL, SportKind.BASKETBALL -> 2
            SportKind.SOCCER -> 1
            else -> 0
        }
        if (ended == midpoint) return "Halftime"
        val label = periodLabel(kind, ended)
        return if (label.isEmpty()) "End of period" else "End of $label"
    }

    /**
     * A period is called something different in every sport, and the number that
     * matters is the one past regulation: overtime is "OT", not "Q5".
     */
    fun periodLabel(kind: SportKind, period: Int): String {
        if (period <= 0) return ""
        return when (kind) {
            SportKind.BASKETBALL -> if (period > 4) ot(period - 4) else "Q$period"
            SportKind.FOOTBALL -> if (period > 4) ot(period - 4) else "Q$period"
            SportKind.HOCKEY -> if (period > 3) ot(period - 3) else "P$period"
            SportKind.SOCCER -> if (period > 2) ot(period - 2) else "H$period"
            SportKind.BASEBALL -> ordinal(period)
            SportKind.RACING -> "Lap $period"
        }
    }

    private fun ot(n: Int) = if (n <= 1) "OT" else "${n}OT"

    fun ordinal(n: Int): String {
        val suffix = when {
            n % 100 in 11..13 -> "th"
            n % 10 == 1 -> "st"
            n % 10 == 2 -> "nd"
            n % 10 == 3 -> "rd"
            else -> "th"
        }
        return "$n$suffix"
    }

    fun raceTitle(race: RaceEvent): String = race.shortName

    fun raceBody(race: RaceEvent, league: League): String {
        val podium = race.podium.take(3)
        return if (podium.isEmpty()) "${league.short} · Final"
        else "${league.short} · " + podium.mapIndexed { i, name -> "${i + 1}. $name" }
            .joinToString("  ")
    }
}
