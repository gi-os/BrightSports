package com.gios.lightsports.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gios.lightsports.data.Leagues
import com.gios.lightsports.model.League
import com.gios.lightsports.model.TeamRef
import com.gios.lightsports.ui.theme.Dim
import com.gios.lightsports.ui.theme.Faint

/**
 * Pick teams. A league is chosen first, then its clubs — 13 leagues and roughly 500
 * teams is far too many for one alphabetical list.
 *
 * Which league is open lives in the caller, so the top bar can own the back button the
 * way every LightOS screen does.
 */
@Composable
fun FollowScreen(
    openLeague: League?,
    teamsByLeague: Map<String, List<TeamRef>>,
    follows: Set<String>,
    onOpenLeague: (League) -> Unit,
    onToggle: (String) -> Unit,
) {
    if (openLeague == null) {
        LazyColumn(Modifier.fillMaxSize()) {
            item { FollowSummary(follows, teamsByLeague, onToggle) }
            for ((sectionTitle, leagues) in Leagues.sections) {
                item(key = "s-$sectionTitle") { SectionHeader(sectionTitle) }
                for (l in leagues) {
                    item(key = "l-${l.id}") {
                        val count = follows.count { it.startsWith("${l.id}:") }
                        MenuRow(
                            label = l.short,
                            sub = l.name,
                            detail = if (l.isRacing) {
                                if ("${l.id}:series" in follows) "[ ON ]" else "OFF"
                            } else if (count > 0) "$count" else null,
                            onClick = {
                                // Racing has no clubs to choose between; following the
                                // series is the whole interaction.
                                if (l.isRacing) onToggle("${l.id}:series") else onOpenLeague(l)
                            },
                        )
                        Rule()
                    }
                }
            }
            item { Spacer(Modifier.height(28.dp)) }
        }
        return
    }

    var query by remember(openLeague.id) { mutableStateOf("") }
    val teams = teamsByLeague[openLeague.id]

    Column(Modifier.fillMaxSize()) {
        SearchField(query) { query = it }
        Rule()
        when {
            teams == null -> EmptyState("Loading teams…")
            teams.isEmpty() -> EmptyState("Couldn't load the team list.\nCheck your connection.")
            else -> {
                val filtered = if (query.isBlank()) teams else teams.filter {
                    it.displayName.contains(query, true) || it.abbrev.contains(query, true)
                }
                if (filtered.isEmpty()) {
                    EmptyState("No team matches “$query”.")
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        for (team in filtered) {
                            item(key = team.key) {
                                val on = team.key in follows
                                MenuRow(
                                    label = team.displayName,
                                    detail = if (on) "[ ON ]" else null,
                                    dim = !on,
                                    onClick = { onToggle(team.key) },
                                )
                                Rule()
                            }
                        }
                        item { Spacer(Modifier.height(28.dp)) }
                    }
                }
            }
        }
    }
}

/** The teams already followed, as removable chips, so unfollowing takes one tap. */
@Composable
private fun FollowSummary(
    follows: Set<String>,
    teamsByLeague: Map<String, List<TeamRef>>,
    onToggle: (String) -> Unit,
) {
    if (follows.isEmpty()) return
    Column(Modifier.fillMaxWidth()) {
        SectionHeader("FOLLOWING · ${follows.size}")
        LazyRow(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (key in follows.sorted()) {
                item(key = key) {
                    val leagueId = key.substringBefore(':')
                    val teamId = key.substringAfter(':')
                    val label = if (teamId == "series") {
                        Leagues.byId(leagueId)?.short ?: leagueId.uppercase()
                    } else {
                        teamsByLeague[leagueId]?.firstOrNull { it.teamId == teamId }?.abbrev
                            ?: teamId
                    }
                    Chip(label = "$label ×", selected = true) { onToggle(key) }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Rule()
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "SEARCH",
            style = MaterialTheme.typography.labelSmall,
            color = Faint,
            modifier = Modifier.padding(end = 12.dp),
        )
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
            cursorBrush = SolidColor(Color.White),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text(
                        "team name",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Dim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                inner()
            },
        )
    }
}
