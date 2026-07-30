package com.gios.lightsports.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gios.lightsports.model.League
import com.gios.lightsports.model.StandingsGroup
import com.gios.lightsports.ui.theme.Dim
import com.gios.lightsports.ui.theme.Faint

/**
 * Standings for the leagues the user actually follows. Only those: a browse-every-
 * league mode would undo the point of a phone with no feed to scroll.
 */
@Composable
fun StandingsScreen(
    leagues: List<League>,
    groups: Map<String, List<StandingsGroup>>,
    followedTeamIds: Set<String>,
    onLeagueSelected: (League) -> Unit,
    onTeamHeld: (StandingsRow, League) -> Unit,
) {
    if (leagues.isEmpty()) {
        EmptyState("Follow a team and its league's table shows up here.")
        return
    }
    var selected by remember { mutableStateOf(leagues.first()) }
    LaunchedEffect(selected.id) { onLeagueSelected(selected) }

    Column(Modifier.fillMaxSize()) {
        LazyRow(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (league in leagues) {
                item(key = league.id) {
                    Chip(league.short, league.id == selected.id) { selected = league }
                }
            }
        }
        Rule()
        val tables = groups[selected.id]
        when {
            tables == null -> EmptyState("Loading…")
            tables.isEmpty() -> EmptyState("No table published for ${selected.short} right now.")
            else -> LazyColumn(Modifier.fillMaxSize()) {
                for (group in tables) {
                    item(key = "t-${group.title}") {
                        SectionHeader(group.title)
                        Table(group, followedTeamIds, selected.id) { onTeamHeld(it, selected) }
                        Rule()
                    }
                }
                item {
                    Text(
                        "Hold a team for its full stats",
                        style = MaterialTheme.typography.labelSmall,
                        color = Faint,
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    )
                    Spacer(Modifier.height(20.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Table(
    group: StandingsGroup,
    followedTeamIds: Set<String>,
    leagueId: String,
    onHold: (StandingsRow) -> Unit,
) {
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(scroll)) {
            Spacer(Modifier.width(26.dp))
            Text(
                "TEAM",
                style = MaterialTheme.typography.labelSmall,
                color = Faint,
                modifier = Modifier.width(120.dp),
            )
            for (header in group.headers) {
                Text(
                    header,
                    style = MaterialTheme.typography.labelSmall,
                    color = Faint,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    modifier = Modifier.width(46.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        for (row in group.rows) {
            // A followed team is inverted, which is the only highlight that reads on
            // a matte greyscale panel.
            val mine = row.teamId != null && "$leagueId:${row.teamId}" in followedTeamIds
            Row(
                Modifier.fillMaxWidth()
                    .background(if (mine) Color.White else Color.Black)
                    // Hold, not tap. The table scrolls sideways, and a row that
                    // navigated on tap would fire every time a drag was read as a click.
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { onHold(row) },
                    )
                    .horizontalScroll(scroll)
                    .padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val ink = if (mine) Color.Black else Color.White
                Text(
                    row.rank,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (mine) Color.Black else Dim,
                    modifier = Modifier.width(26.dp),
                )
                Text(
                    row.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(120.dp),
                )
                for (value in row.values) {
                    Text(
                        value,
                        style = MaterialTheme.typography.bodyMedium,
                        color = ink,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        modifier = Modifier.width(46.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}
