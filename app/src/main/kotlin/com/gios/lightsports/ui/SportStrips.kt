package com.gios.lightsports.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gios.lightsports.model.Game
import com.gios.lightsports.model.Moment
import com.gios.lightsports.model.Side
import com.gios.lightsports.model.Situation
import com.gios.lightsports.model.SportKind
import com.gios.lightsports.ui.theme.Dim
import com.gios.lightsports.ui.theme.Faint
import com.gios.lightsports.ui.theme.FieldLine
import com.gios.lightsports.ui.theme.Marks
import com.gios.lightsports.ui.theme.Soft

/**
 * The strip under the score that changes per sport. Football's (the field) lives in
 * GameScreen; these are the other four. Each one draws only what the provider actually
 * sends, so a quiet feed gives a short strip rather than a row of dashes.
 */
@Composable
fun SportStrip(game: Game, kind: SportKind) {
    when (kind) {
        SportKind.BASEBALL -> game.situation?.let { BaseballStrip(it) }
        SportKind.SOCCER -> SoccerStrip(game)
        SportKind.BASKETBALL -> BasketballStrip(game)
        SportKind.HOCKEY -> HockeyStrip(game)
        else -> Unit
    }
}

// ------------------------------------------------------------------ baseball

/**
 * The diamond, the count, the outs, and who is up. Runners are the filled bases;
 * the batter and pitcher lines come with their day so far.
 */
@Composable
private fun BaseballStrip(s: Situation) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Diamond(s.onFirst, s.onSecond, s.onThird)
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                val batting = listOfNotNull(s.batter?.let { "$it batting" }, s.batterSummary)
                    .joinToString(" · ")
                if (batting.isNotEmpty()) {
                    Text(
                        batting,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (s.balls != null && s.strikes != null) {
                        Text(
                            "${s.balls}–${s.strikes}",
                            style = Marks.score.copy(fontSize = 24.sp, lineHeight = 26.sp),
                            color = Color.White,
                        )
                        Spacer(Modifier.width(14.dp))
                    }
                    if (s.outs != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (i in 0 until 3) {
                                Box(
                                    Modifier.size(8.dp).let {
                                        if (i < s.outs) it.background(Color.White) else it.border(1.dp, Faint)
                                    },
                                )
                            }
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (s.outs == 1) "OUT" else "OUTS",
                            style = MaterialTheme.typography.labelSmall,
                            color = Faint,
                        )
                    }
                }
                runners(s)?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = Soft, maxLines = 1)
                }
            }
        }
        val pitching = listOfNotNull(s.pitcher?.let { "$it pitching" }, s.pitcherSummary)
            .joinToString(" · ")
        if (pitching.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                pitching,
                style = MaterialTheme.typography.labelSmall,
                color = Faint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Three bases as rotated squares, filled when occupied. Home plate is not drawn. */
@Composable
private fun Diamond(first: Boolean, second: Boolean, third: Boolean) {
    Canvas(Modifier.size(52.dp)) {
        val w = size.width
        val base = w * 0.24f
        fun square(cx: Float, cy: Float, filled: Boolean) {
            val p = Path().apply {
                moveTo(cx, cy - base / 2); lineTo(cx + base / 2, cy)
                lineTo(cx, cy + base / 2); lineTo(cx - base / 2, cy); close()
            }
            if (filled) drawPath(p, Color.White) else drawPath(p, FieldLine, style = Stroke(1.5f))
        }
        // Second at the top, first to the right, third to the left.
        square(w / 2, base * 0.7f, second)
        square(w - base * 0.7f, w / 2, first)
        square(base * 0.7f, w / 2, third)
        // The base paths, faint.
        val pts = listOf(
            Offset(w / 2, base * 0.7f), Offset(w - base * 0.7f, w / 2),
            Offset(w / 2, w - base * 0.7f), Offset(base * 0.7f, w / 2),
        )
        for (i in pts.indices) {
            drawLine(FieldLine, pts[i], pts[(i + 1) % 4], 1f)
        }
    }
}

// -------------------------------------------------------------------- soccer

/** Shots, on target and possession for each side, away on the left, home on the right. */
@Composable
private fun SoccerStrip(game: Game) {
    val a = game.away.stats
    val h = game.home.stats
    if (a.isEmpty() && h.isEmpty()) return
    fun line(m: Map<String, String>): String = listOfNotNull(
        m["totalShots"]?.let { "SHOTS $it" },
        m["shotsOnTarget"]?.let { "ON TARGET $it" },
        m["possessionPct"]?.toDoubleOrNull()?.let { "${it.toInt()}% POSS." },
    ).joinToString(" · ")
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(line(a), style = MaterialTheme.typography.labelSmall, color = Dim, maxLines = 2,
            modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(line(h), style = MaterialTheme.typography.labelSmall, color = Dim, maxLines = 2,
            textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
}

/**
 * Goals and cards, newest first, with the running score on each goal. Halftime is a row
 * of its own so the two halves read apart.
 */
@Composable
fun TimelineList(game: Game, moments: List<Moment>) {
    if (moments.isEmpty()) {
        Text(
            "No goals yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = Dim,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        return
    }
    Column(Modifier.padding(bottom = 8.dp)) {
        for (m in moments.asReversed()) {
            val team = when (m.teamId) {
                game.home.teamId -> game.home.abbrev
                game.away.teamId -> game.away.abbrev
                else -> null
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    m.clock ?: "",
                    style = MaterialTheme.typography.labelSmall,
                    color = Faint,
                    maxLines = 1,
                    modifier = Modifier.width(44.dp).padding(top = 3.dp),
                )
                Column(Modifier.weight(1f).padding(end = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (m.scoring) {
                            Tag(if (m.ownGoal) "OWN GOAL" else if (m.penalty) "PEN" else "GOAL")
                            Spacer(Modifier.width(8.dp))
                        } else if (m.redCard) {
                            Box(Modifier.size(10.dp, 14.dp).background(Color.White))
                            Spacer(Modifier.width(8.dp))
                        } else if (m.yellowCard) {
                            Box(Modifier.size(10.dp, 14.dp).border(1.dp, Dim))
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            listOfNotNull(
                                m.player,
                                m.type.takeIf { !m.scoring && !m.yellowCard && !m.redCard && it.isNotEmpty() },
                            ).joinToString(" · ").ifEmpty { m.type },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (m.scoring) Color.White else Soft,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (team != null) {
                        Text(team, style = MaterialTheme.typography.labelSmall, color = Faint, maxLines = 1)
                    }
                }
                if (m.scoring && m.awayScore != null && m.homeScore != null) {
                    Text(
                        "${m.awayScore}–${m.homeScore}",
                        style = Marks.score.copy(fontSize = 22.sp, lineHeight = 24.sp),
                        color = Dim,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- basketball

/** Each side's scoring leader and shooting line. */
@Composable
private fun BasketballStrip(game: Game) {
    fun leader(side: Side) = side.leaders.firstOrNull { it.first == "points" }?.second
        ?.let { "${it.uppercase()} PTS" }
    fun shooting(side: Side) = listOfNotNull(
        side.stats["fieldGoalPct"]?.toDoubleOrNull()?.let { "FG ${it.toInt()}%" },
        side.stats["threePointPct"]?.toDoubleOrNull()?.let { "3PT ${it.toInt()}%" },
        side.stats["rebounds"]?.let { "$it REB" },
    ).joinToString(" · ")
    val al = leader(game.away); val hl = leader(game.home)
    val ash = shooting(game.away); val hsh = shooting(game.home)
    if (al == null && hl == null && ash.isEmpty() && hsh.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            al?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Color.White, maxLines = 1) }
            if (ash.isNotEmpty()) Text(ash, style = MaterialTheme.typography.labelSmall, color = Faint, maxLines = 1)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
            hl?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Color.White, maxLines = 1, textAlign = TextAlign.End) }
            if (hsh.isNotEmpty()) Text(hsh, style = MaterialTheme.typography.labelSmall, color = Faint, maxLines = 1, textAlign = TextAlign.End)
        }
    }
}

// -------------------------------------------------------------------- hockey

/** Shots on goal (the other side's saves plus goals) and the goaltending line. */
@Composable
private fun HockeyStrip(game: Game) {
    fun line(side: Side, other: Side) = listOfNotNull(
        side.shotsOnGoal(other)?.let { "SOG $it" },
        side.stats["savePct"]?.let { "SV $it" },
    ).joinToString(" · ")
    val a = line(game.away, game.home); val h = line(game.home, game.away)
    if (a.isEmpty() && h.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(a, style = MaterialTheme.typography.labelSmall, color = Dim, maxLines = 1, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(h, style = MaterialTheme.typography.labelSmall, color = Dim, maxLines = 1, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
}
