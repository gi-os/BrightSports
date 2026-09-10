package com.gios.lightsports.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gios.lightsports.hw.WheelScroll
import com.gios.lightsports.model.Game
import com.gios.lightsports.model.GameState
import com.gios.lightsports.model.League
import com.gios.lightsports.model.SportKind
import com.gios.lightsports.model.StandingsGroup
import com.gios.lightsports.model.TeamSeason
import com.gios.lightsports.notify.AlertText
import com.gios.lightsports.ui.theme.Dim
import com.gios.lightsports.ui.theme.Faint
import com.gios.lightsports.ui.theme.Marks
import com.gios.lightsports.util.Fmt
import java.time.ZoneId

/**
 * One team's season: the mark and the record up top, then a row per week — result,
 * live score, next kickoff, or BYE. Football is the design case (eighteen rows, one of
 * them empty); every other ESPN league gets the same list without the week numbers.
 */
@Composable
fun TeamScreen(
    league: League,
    teamId: String,
    displayName: String,
    abbrev: String,
    logoUrl: String?,
    season: TeamSeason?,
    standings: List<StandingsGroup>?,
    onGame: (Game) -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val now = System.currentTimeMillis()
    val listState = rememberLazyListState()
    WheelScroll(listState)
    val football = league.kind == SportKind.FOOTBALL

    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        item(key = "head") {
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 18.dp)) {
                Text(
                    displayName.substringAfterLast(' ').uppercase(),
                    style = Marks.big.copy(fontSize = 44.sp, lineHeight = 44.sp),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TeamLogo(logoUrl, size = 24.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(abbrev.uppercase(), style = Marks.team, color = Dim, maxLines = 1)
                    Spacer(Modifier.width(12.dp))
                    val record = season?.record()
                    // The provider's record when a game carries one, else counted from the finals.
                    val fromGames = season?.games?.mapNotNull { g ->
                        (if (g.home.teamId == teamId) g.home else g.away).record
                    }?.lastOrNull()
                    Text(
                        fromGames ?: record?.let { "${it.first}–${it.second}" } ?: "",
                        style = Marks.team,
                        color = Color.White,
                        maxLines = 1,
                    )
                }
                val place = standings?.let { placeIn(it, teamId) }
                if (place != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        place.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = Faint,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
            Rule()
        }
        val games = season?.games.orEmpty().sortedBy { it.startMillis }
        // Playing right now: one row above the season, so the game is one tap away.
        val live = games.firstOrNull { it.state == GameState.LIVE }
        if (live != null) {
            item(key = "now") {
                SectionHeader("NOW")
                SeasonRow(
                    label = live.week?.let { "W$it" } ?: "",
                    game = live, teamId = teamId, kind = league.kind, zone = zone, now = now,
                    onClick = { onGame(live) },
                )
                Rule()
            }
        }
        item(key = "season-h") { SectionHeader("SEASON") }
        if (season == null) {
            item(key = "loading") {
                Text(
                    "Loading…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Dim,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        } else if (games.isEmpty()) {
            item(key = "none") {
                Text(
                    "No schedule from the provider.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Dim,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        // Football lists every week including the bye; other sports list the games.
        val rows = if (football) weekRows(games, season?.byeWeek) else games.map { it.week to it }
        for ((i, row) in rows.withIndex()) {
            val (week, game) = row
            item(key = "w-$i") {
                SeasonRow(
                    label = week?.let { "W$it" } ?: (i + 1).toString(),
                    game = game,
                    teamId = teamId,
                    kind = league.kind,
                    zone = zone,
                    now = now,
                    onClick = { game?.let(onGame) },
                )
                Rule()
            }
        }
        item { Spacer(Modifier.height(28.dp)) }
    }
}

/**
 * Weeks 1..N with the bye filled in as an empty row. The count runs to the last week
 * the provider lists, so a season with a bye at week 11 shows eighteen rows.
 */
private fun weekRows(games: List<Game>, byeWeek: Int?): List<Pair<Int?, Game?>> {
    if (games.none { it.week != null }) return games.map { null to it }
    val byWeek = games.filter { it.week != null }.associateBy { it.week!! }
    val last = maxOf(byWeek.keys.max(), byeWeek ?: 0)
    val out = mutableListOf<Pair<Int?, Game?>>()
    for (w in 1..last) {
        out += w to byWeek[w]
        // A second game in one week (a postponement replayed) keeps its own row.
    }
    // Anything without a week number (a playoff game before the bracket is set) at the end.
    for (g in games.filter { it.week == null }) out += null to g
    return out
}

/** "NFC West · 1st" from the standings table the team sits in. */
private fun placeIn(groups: List<StandingsGroup>, teamId: String): String? {
    for (g in groups) {
        val i = g.rows.indexOfFirst { it.teamId == teamId }
        if (i >= 0) {
            val row = g.rows[i]
            val rank = row.rank.toIntOrNull()?.let { AlertText.ordinal(it) } ?: AlertText.ordinal(i + 1)
            return "${g.title} · $rank"
        }
    }
    return null
}

/** One week: the label, who and where, and the result or the kickoff. */
@Composable
private fun SeasonRow(
    label: String,
    game: Game?,
    teamId: String,
    kind: SportKind,
    zone: ZoneId,
    now: Long,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().let { if (game != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = Faint,
            modifier = Modifier.width(40.dp),
        )
        if (game == null) {
            Text("BYE", style = MaterialTheme.typography.bodyLarge, color = Faint)
            return@Row
        }
        val home = game.home.teamId == teamId
        val mine = if (home) game.home else game.away
        val theirs = if (home) game.away else game.home
        val final = game.state == GameState.FINAL
        val won = final && (mine.score ?: 0) > (theirs.score ?: 0)
        val lost = final && (mine.score ?: 0) < (theirs.score ?: 0)
        if (final) {
            Text(
                when {
                    won -> "W"
                    lost -> "L"
                    else -> "T"
                },
                style = Marks.team.copy(fontSize = 22.sp, lineHeight = 24.sp),
                color = if (won) Color.White else Dim,
                modifier = Modifier.width(28.dp),
            )
        } else if (game.state == GameState.LIVE) {
            LiveDot()
            Spacer(Modifier.width(20.dp))
        } else {
            Spacer(Modifier.width(28.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                "${if (home) "vs" else "@"} ${theirs.abbrev.uppercase()}" +
                    if (game.eventTitle != null && game.eventClass != com.gios.lightsports.model.EventClass.NONE) " · ${game.eventTitle}" else "",
                style = MaterialTheme.typography.bodyLarge,
                color = if (lost) Dim else Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = when (game.state) {
                GameState.PRE -> listOfNotNull(
                    Fmt.dayTime(game.startMillis, zone),
                    game.broadcast,
                ).joinToString(" · ")
                GameState.LIVE -> listOfNotNull(
                    AlertText.periodLabel(kind, game.period).takeIf { it.isNotEmpty() },
                    game.clock,
                ).joinToString(" ").ifEmpty { game.statusDetail }
                GameState.FINAL -> game.statusDetail.takeIf { it.isNotEmpty() && !it.equals("Final", true) }
                    ?: Fmt.dayDate(game.startMillis, zone)
                GameState.OFF -> game.statusDetail.ifEmpty { "Postponed" }
            }
            Text(
                sub.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = Faint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (game.state != GameState.PRE && game.state != GameState.OFF) {
            Text(
                "${mine.score ?: "-"}–${theirs.score ?: "-"}",
                style = Marks.score.copy(fontSize = 24.sp, lineHeight = 26.sp),
                color = if (lost) Dim else Color.White,
                textAlign = TextAlign.End,
            )
        }
    }
}
