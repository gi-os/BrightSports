package com.gios.lightsports.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gios.lightsports.data.Leagues
import com.gios.lightsports.hw.WheelScroll
import com.gios.lightsports.model.FieldEntry
import com.gios.lightsports.model.FieldEvent
import com.gios.lightsports.model.GameState
import com.gios.lightsports.model.SportKind
import com.gios.lightsports.ui.theme.Dim
import com.gios.lightsports.ui.theme.Faint
import com.gios.lightsports.ui.theme.Marks
import com.gios.lightsports.ui.theme.Soft
import com.gios.lightsports.util.Fmt
import java.time.ZoneId

/**
 * The field in full: a golf leaderboard, or the classification of one session of a race
 * weekend.
 *
 * Both are the same screen because both are the same thing, a list of people in the order
 * they are winning. What differs is how much the provider knows about each of them. Golf
 * sends a total, the rounds and how far through today a player is. A racing session sends
 * a finishing order and nothing else at all.
 *
 * @param followed the follow keys already set, so a player who matters can be found in a
 *   field of a hundred and fifty without reading every row.
 */
@Composable
fun FieldScreen(
    event: FieldEvent,
    followed: Set<String> = emptySet(),
    onFollow: (FieldEntry) -> Unit = {},
) {
    val zone = ZoneId.systemDefault()
    val league = Leagues.byId(event.leagueId)
    val golf = league?.kind == SportKind.GOLF
    val list = rememberLazyListState()
    WheelScroll(list)

    // A weekend has five sessions and the last one to have run is the one worth opening
    // on. A tournament has one list and no picker at all.
    var session by remember(event.id) {
        mutableStateOf(
            event.sessions.indexOfLast { it.state == GameState.FINAL }
                .takeIf { it >= 0 } ?: (event.sessions.size - 1),
        )
    }
    val shown = event.sessions.getOrNull(session)?.entries?.takeIf { it.isNotEmpty() }
        ?: event.entries

    LazyColumn(Modifier.fillMaxSize(), state = list) {
        item(key = "head") {
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp)) {
                Text(
                    listOfNotNull(
                        league?.short,
                        when (event.state) {
                            GameState.FINAL -> "Final"
                            GameState.LIVE -> event.sessionLabel ?: "Live"
                            else -> Fmt.dayDate(event.startMillis, zone)
                        },
                    ).joinToString(" · ").uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (event.state == GameState.LIVE) Color.White else Dim,
                    maxLines = 1,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    event.name,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                )
                val sub = listOfNotNull(event.circuit, event.note).joinToString(" · ")
                if (sub.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        sub,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Faint,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(14.dp))
            }
            Rule()
        }

        if (event.sessions.size > 1) {
            item(key = "sessions") {
                LazyRow(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(event.sessions) { i, s ->
                        Chip(s.label.uppercase(), selected = i == session) { session = i }
                    }
                }
                Rule()
            }
        }

        if (shown.isEmpty()) {
            item(key = "empty") {
                Text(
                    when (event.state) {
                        GameState.PRE -> "The field is published when play starts."
                        else -> "No result for this one yet."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Dim,
                    modifier = Modifier.padding(16.dp),
                )
            }
        } else {
            // Golf's columns, named once at the top rather than repeated down the list.
            if (golf) {
                item(key = "cols") {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp),
                    ) {
                        Text(
                            "POS",
                            style = MaterialTheme.typography.labelSmall,
                            color = Faint,
                            modifier = Modifier.width(44.dp),
                        )
                        Text(
                            "PLAYER",
                            style = MaterialTheme.typography.labelSmall,
                            color = Faint,
                            modifier = Modifier.weight(1f),
                        )
                        Text("TOTAL", style = MaterialTheme.typography.labelSmall, color = Faint)
                    }
                }
            }
            itemsIndexed(shown) { _, entry ->
                FieldEntryRow(
                    entry = entry,
                    golf = golf,
                    followed = entry.athleteId != null &&
                        "${event.leagueId}:${entry.athleteId}" in followed,
                    onLongPress = { onFollow(entry) },
                )
                Rule()
            }
            item(key = "tail") {
                if (golf) {
                    Text(
                        "Press and hold a player to follow them.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Faint,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

/**
 * One line of the field.
 *
 * The total is what the field is being compared on, so it takes the condensed numeral
 * face the scores use everywhere else, and it goes white under par.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FieldEntryRow(
    entry: FieldEntry,
    golf: Boolean,
    followed: Boolean,
    onLongPress: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = {}, onLongClick = onLongPress)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            entry.position,
            style = MaterialTheme.typography.labelSmall,
            color = if (entry.position == "CUT") Faint else Dim,
            maxLines = 1,
            modifier = Modifier.width(44.dp),
        )
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Text(
                // A followed player is marked where they stand rather than lifted to the
                // top. A leaderboard with a name moved out of position says nothing true.
                if (followed) "★ ${entry.name}" else entry.name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (followed) Color.White else Soft,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val line = listOfNotNull(
                entry.thru,
                entry.rounds.takeIf { it.isNotEmpty() }?.joinToString(" "),
            ).joinToString(" · ")
            if (line.isNotEmpty()) {
                Text(
                    line,
                    style = MaterialTheme.typography.labelSmall,
                    color = Faint,
                    maxLines = 1,
                )
            }
        }
        if (golf && entry.total != null) {
            Text(
                entry.total,
                style = Marks.score.copy(fontSize = 24.sp, lineHeight = 26.sp),
                color = if ((entry.toPar ?: 0) < 0) Color.White else Dim,
                maxLines = 1,
            )
        }
    }
}
