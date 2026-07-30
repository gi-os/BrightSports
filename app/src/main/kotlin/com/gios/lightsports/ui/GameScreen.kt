package com.gios.lightsports.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gios.lightsports.data.Leagues
import com.gios.lightsports.hw.WheelScroll
import com.gios.lightsports.model.Game
import com.gios.lightsports.model.GameState
import com.gios.lightsports.model.Side
import com.gios.lightsports.model.SportKind
import com.gios.lightsports.notify.AlertText
import com.gios.lightsports.ui.theme.Dim
import com.gios.lightsports.ui.theme.Faint
import com.gios.lightsports.util.Fmt
import java.time.ZoneId

/**
 * One game in full: the score, the line score by inning or quarter or period, and the
 * handful of facts worth knowing before watching it.
 */
@Composable
fun GameScreen(game: Game) {
    val zone = ZoneId.systemDefault()
    val league = Leagues.byId(game.leagueId)
    val kind = league?.kind ?: SportKind.BASEBALL
    val scroll = rememberScrollState()
    WheelScroll(scroll)

    Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
        Text(
            listOfNotNull(
                league?.short,
                when (game.state) {
                    GameState.PRE -> Fmt.dayTime(game.startMillis, zone)
                    else -> game.statusDetail.ifEmpty { game.state.name.lowercase() }
                },
            ).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = Dim,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
        )
        Spacer(Modifier.height(14.dp))
        BigScore(game.away, game.home, game.state != GameState.PRE)
        Spacer(Modifier.height(18.dp))
        Rule()

        if (game.away.lineScore.isNotEmpty() || game.home.lineScore.isNotEmpty()) {
            SectionHeader(lineScoreTitle(kind))
            LineScoreTable(game, kind)
            Rule()
        }

        SectionHeader("DETAILS")
        game.note?.let { MenuRow("Series", detail = null, sub = it) }
        MenuRow("First pitch".takeIf { kind == SportKind.BASEBALL } ?: "Start",
            detail = Fmt.time(game.startMillis, zone))
        game.venue?.let { MenuRow("Venue", sub = it) }
        game.broadcast?.let { MenuRow("TV", detail = it) }
        game.away.record?.let { MenuRow(game.away.short, detail = it) }
        game.home.record?.let { MenuRow(game.home.short, detail = it) }
        Spacer(Modifier.height(32.dp))
    }
}

private fun lineScoreTitle(kind: SportKind) = when (kind) {
    SportKind.BASEBALL -> "BY INNING"
    SportKind.HOCKEY -> "BY PERIOD"
    SportKind.SOCCER -> "BY HALF"
    else -> "BY QUARTER"
}

@Composable
private fun BigScore(away: Side, home: Side, showScore: Boolean) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        for (side in listOf(away, home)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        side.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val extras = listOfNotNull(
                        side.record,
                        side.hits?.let { "$it H" },
                        side.errors?.let { "$it E" },
                    )
                    if (extras.isNotEmpty()) {
                        Text(
                            extras.joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = Faint,
                        )
                    }
                }
                if (showScore) {
                    Text(
                        side.score?.toString() ?: "-",
                        style = MaterialTheme.typography.displaySmall,
                        color = Color.White,
                    )
                }
            }
        }
    }
}

/**
 * Scrolls sideways rather than shrinking: an eleven-inning game will not fit across
 * 3.9 inches, and a five-point font is no use to anybody.
 */
@Composable
private fun LineScoreTable(game: Game, kind: SportKind) {
    val periods = maxOf(game.away.lineScore.size, game.home.lineScore.size)
    if (periods == 0) return
    val labels = (1..periods).map { periodHeader(kind, it) }

    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column {
            CellText("", header = true)
            CellText(game.away.abbrev, header = true)
            CellText(game.home.abbrev, header = true)
        }
        Spacer(Modifier.width(10.dp))
        for (i in 0 until periods) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CellText(labels[i], header = true)
                CellText(game.away.lineScore.getOrNull(i) ?: "")
                CellText(game.home.lineScore.getOrNull(i) ?: "")
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CellText(totalLabel(kind), header = true)
            CellText(game.away.score?.toString() ?: "")
            CellText(game.home.score?.toString() ?: "")
        }
    }
}

private fun totalLabel(kind: SportKind) = if (kind == SportKind.BASEBALL) "R" else "T"

private fun periodHeader(kind: SportKind, period: Int): String = when (kind) {
    SportKind.BASEBALL -> period.toString()
    else -> AlertText.periodLabel(kind, period)
}

@Composable
private fun CellText(text: String, header: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (header) Dim else Color.White,
        textAlign = TextAlign.Center,
        maxLines = 1,
        modifier = Modifier.width(28.dp).padding(vertical = 4.dp),
    )
}
