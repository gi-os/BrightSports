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

    /**
     * @param prev the snapshot the alert was diffed against. Football uses it to say what
     * the score *was* — which side scored and by how much — since "NE 7 · SEA 14" alone
     * does not say whether that was a touchdown or a field goal.
     */
    fun title(
        game: Game,
        kind: ScoreDiff.Kind,
        prev: ScoreDiff.Snapshot? = null,
        sport: SportKind? = null,
    ): String {
        val score = "${game.away.short} ${game.away.score ?: 0} · ${game.home.short} ${game.home.score ?: 0}"
        return when (kind) {
            ScoreDiff.Kind.SOON, ScoreDiff.Kind.START, ScoreDiff.Kind.OFF ->
                "${game.away.short} at ${game.home.short}"
            ScoreDiff.Kind.REDZONE -> "RED ZONE · ${game.offense?.abbrev ?: game.home.abbrev}"
            ScoreDiff.Kind.CLOSE -> "ONE-SCORE GAME · $score"
            ScoreDiff.Kind.SCORE -> if (sport == SportKind.FOOTBALL && prev != null) {
                val now = ScoreDiff.snapshot(game)
                val delta = ScoreDiff.scoreDelta(prev, now)
                val scorer = if (delta > 0) game.home else game.away
                val label = footballScoreLabel(kotlin.math.abs(delta), prev.tdAt > 0L)
                "$label ${scorer.abbrev} · $score"
            } else score
            else -> score
        }
    }

    /**
     * What a step in the score is called. Six or seven is a touchdown (the kick usually
     * lands in the same step); eight is a touchdown and a two-point try; three a field
     * goal; two a safety — unless a touchdown is waiting for its point-after, in which
     * case one or two is that kick or that try, and the alert is still about the touchdown.
     */
    fun footballScoreLabel(step: Int, patOpen: Boolean): String = when {
        step >= 8 -> "TD +2"
        step >= 6 -> "TD"
        step == 3 -> "FG"
        patOpen && step in 1..2 -> "TD"
        step == 2 -> "SAFETY"
        step == 1 -> "PAT"
        else -> "SCORE"
    }

    fun body(
        game: Game,
        league: League,
        kind: ScoreDiff.Kind,
        zone: ZoneId,
        prev: ScoreDiff.Snapshot? = null,
    ): String {
        val prefix = game.competition ?: league.short
        val football = league.kind == SportKind.FOOTBALL
        val where = listOfNotNull(
            periodLabel(league.kind, game.period).takeIf { it.isNotEmpty() },
            game.clock,
        ).joinToString(" ")
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
            // The one-time "it's back on" alert that pairs with OFF. The current score
            // is already in the title, so the body just needs to say play resumed.
            ScoreDiff.Kind.RESUMED -> "Resuming"
            // A tennis score is the sets, written out: "6-3 1-6 1-0 · 3rd". The title
            // carries sets won, which on its own says nothing about how the set went.
            ScoreDiff.Kind.FINAL -> if (league.kind == SportKind.TENNIS) {
                listOf(setLine(game), game.statusDetail.ifEmpty { "Final" })
                    .filter { it.isNotEmpty() }.joinToString(" · ")
            } else {
                listOfNotNull(game.headline, game.statusDetail.ifEmpty { "Final" })
                    .joinToString(" · ")
            }
            ScoreDiff.Kind.PERIOD -> boundaryLabel(league.kind, game)
            ScoreDiff.Kind.SCORE -> when {
                league.kind == SportKind.TENNIS ->
                    listOf(setLine(game), game.statusDetail).filter { it.isNotEmpty() }
                        .joinToString(" · ")
                // The play, then the clock: "K.Walker III run for 12 yds for a TD · Q2 3:24".
                football -> return listOfNotNull(footballPlay(game, prev), where.ifEmpty { null })
                    .joinToString(" · ").ifEmpty { prefix }
                else -> game.statusDetail.ifEmpty { periodLabel(league.kind, game.period) }
            }
            // "1st & 10 at NE 16 · SEA up 14–7 · Q2 3:24"
            ScoreDiff.Kind.REDZONE -> return listOfNotNull(
                game.situation?.downDistance,
                standing(game),
                where.ifEmpty { null },
            ).joinToString(" · ").ifEmpty { prefix }
            // "SEA leads by 4 · 4:58 left · SEA ball 3rd & 4"
            ScoreDiff.Kind.CLOSE -> return listOfNotNull(
                standing(game),
                game.clock?.let { "$it left" },
                game.offense?.let { off ->
                    listOfNotNull("${off.abbrev} ball", game.situation?.shortDownDistance)
                        .joinToString(" ")
                },
            ).joinToString(" · ").ifEmpty { prefix }
        }
        return if (detail.isEmpty()) prefix else "$prefix · $detail"
    }

    private val kindPrefix = Regex("^(TD \\+2|TD|FG|SAFETY|PAT|SCORE) ([A-Z0-9&]{2,5}) · (.*)$")

    /**
     * Pull the kind label off the front of a title, for the box that draws it large:
     * "TD SEA · NE 7 · SEA 14" -> ("TD", "SEA", "NE 7 · SEA 14"); "RED ZONE · SEA" ->
     * ("RED ZONE", "SEA", ""). A title with no label comes back as (null, null, title).
     */
    fun splitKind(title: String): Triple<String?, String?, String> {
        kindPrefix.matchEntire(title)?.let { m ->
            return Triple(m.groupValues[1], m.groupValues[2], m.groupValues[3])
        }
        if (title.startsWith("RED ZONE · ")) {
            return Triple("RED ZONE", title.removePrefix("RED ZONE · "), "")
        }
        if (title.startsWith("ONE-SCORE GAME · ")) {
            return Triple("ONE-SCORE GAME", null, title.removePrefix("ONE-SCORE GAME · "))
        }
        return Triple(null, null, title)
    }

    /** "SEA up 14–7", "tied 7–7". */
    fun standing(game: Game): String? {
        val h = game.home.score ?: return null
        val a = game.away.score ?: return null
        return when {
            h > a -> "${game.home.abbrev} up $h–$a"
            a > h -> "${game.away.abbrev} up $a–$h"
            else -> "tied $h–$a"
        }
    }

    /**
     * The scoring play in the provider's words, cleaned for a shade line. A point-after
     * that arrived on its own poll is written as the end of the touchdown it belongs to.
     */
    fun footballPlay(game: Game, prev: ScoreDiff.Snapshot?): String? {
        val now = ScoreDiff.snapshot(game)
        val step = if (prev == null) 0 else kotlin.math.abs(ScoreDiff.scoreDelta(prev, now))
        if (prev != null && prev.tdAt > 0L && step in 1..2) {
            val td = cleanPlay(prev.tdText) ?: "Touchdown"
            return "$td · " + if (step == 1) "PAT good" else "2-pt good"
        }
        return cleanPlay(game.situation?.lastPlay)
    }

    /**
     * ESPN's play text is written for a box score: "(Shotgun) G.Smith pass short left to
     * J.Woods for 13 yards, TOUCHDOWN. J.Sanders extra point is GOOD, Center-T.Hennessy,
     * Holder-A.McNamara." The formation prefix and the snap crew go; the rest stays.
     */
    fun cleanPlay(text: String?): String? {
        var t = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        // Leading parentheticals: "(Shotgun) ", "(No Huddle, Shotgun) ".
        while (t.startsWith("(")) {
            val close = t.indexOf(')')
            if (close < 0) break
            t = t.substring(close + 1).trim()
        }
        // The snap crew, and anything after it.
        val crew = t.indexOf(", Center-")
        if (crew > 0) t = t.substring(0, crew)
        // Tackler credits at the end of a run or catch: "for 2 yards (J.Sherwood)".
        t = t.replace(Regex("""\s*\([A-Z][^()]*\)\.?$"""), "")
        return t.trimEnd('.', ' ').takeIf { it.isNotEmpty() }
    }

    /** Games per set, away first to match the title: "6-3 1-6 1-0". */
    fun setLine(game: Game): String {
        val sets = maxOf(game.away.lineScore.size, game.home.lineScore.size)
        return (0 until sets).joinToString(" ") { i ->
            "${game.away.lineScore.getOrNull(i) ?: "0"}-${game.home.lineScore.getOrNull(i) ?: "0"}"
        }
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
            SportKind.TENNIS -> "Set $period"
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
