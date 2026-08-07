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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import com.gios.lightsports.data.SpecialEvents
import com.gios.lightsports.hw.WheelScroll
import com.gios.lightsports.model.League
import com.gios.lightsports.model.TeamRef
import com.gios.lightsports.ui.theme.Dim
import com.gios.lightsports.ui.theme.Faint

/**
 * Pick teams. A league is chosen first, then its clubs — 22 leagues and, once college
 * football's ~140 FBS programs are in the mix, well over a thousand teams is far too
 * many for one alphabetical list.
 *
 * Which league is open lives in the caller, so the top bar can own the back button the
 * way every LightOS screen does.
 */
@Composable
fun FollowScreen(
    openLeague: League?,
    teamsByLeague: Map<String, List<TeamRef>>,
    follows: Set<String>,
    muted: Set<String>,
    onOpenLeague: (League) -> Unit,
    onToggle: (String) -> Unit,
    onToggleMute: (String) -> Unit,
) {
    // Two lists, one screen, and only ever one of them on it. Both states are hoisted
    // here rather than inside the branches so that stepping into a league and back out
    // returns to where the league list was left.
    val leagueList = rememberLazyListState()
    val teamList = rememberLazyListState()
    WheelScroll(leagueList, active = openLeague == null)
    WheelScroll(teamList, active = openLeague != null)

    if (openLeague == null) {
        LazyColumn(Modifier.fillMaxSize(), state = leagueList) {
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
                                state("${l.id}:series", follows, muted)
                            } else if (count > 0) "$count" else null,
                            onClick = {
                                // Racing has no clubs to choose between; following the
                                // series is the whole interaction.
                                if (l.isRacing) onToggle("${l.id}:series") else onOpenLeague(l)
                            },
                            onLongClick = if (l.isRacing && "${l.id}:series" in follows) {
                                { onToggleMute("${l.id}:series") }
                            } else null,
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
        if (openLeague.hasEvents) {
            EventToggles(openLeague, follows, muted, onToggle, onToggleMute)
        }
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
                    LazyColumn(Modifier.fillMaxSize(), state = teamList) {
                        for (team in filtered) {
                            item(key = team.key) {
                                val on = team.key in follows
                                val silent = team.key in muted
                                MenuRow(
                                    label = team.displayName,
                                    detail = when {
                                        on && silent -> "[ SILENT ]"
                                        on -> "[ ON ]"
                                        else -> null
                                    },
                                    sub = if (on && silent) "In the feed, no alerts" else null,
                                    dim = !on,
                                    onClick = { onToggle(team.key) },
                                    // Only a followed team can be silenced; holding an
                                    // unfollowed one would silently create a follow.
                                    onLongClick = if (on) {
                                        { onToggleMute(team.key) }
                                    } else null,
                                )
                                Rule()
                            }
                        }
                        item {
                            Text(
                                "Hold a followed team to keep it in the feed without alerts",
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
    }
}

/**
 * Follow a category rather than a club. Starring championship games gets you the Super
 * Bowl or MLS Cup whoever reaches it; starring special games gets the all-star weekend,
 * the Winter Classic and the games played abroad.
 */
@Composable
private fun EventToggles(
    league: League,
    follows: Set<String>,
    muted: Set<String>,
    onToggle: (String) -> Unit,
    onToggleMute: (String) -> Unit,
) {
    val special = "${league.id}:${SpecialEvents.SUFFIX_SPECIAL}"
    val championship = "${league.id}:${SpecialEvents.SUFFIX_CHAMPIONSHIP}"
    Column(Modifier.fillMaxWidth()) {
        SectionHeader("EVENTS")
        MenuRow(
            label = "Championship games",
            detail = state(championship, follows, muted),
            sub = league.championshipExample,
            dim = championship !in follows,
            onClick = { onToggle(championship) },
            onLongClick = if (championship in follows) {
                { onToggleMute(championship) }
            } else null,
        )
        Rule()
        MenuRow(
            label = "Special games",
            detail = state(special, follows, muted),
            sub = league.specialExample,
            dim = special !in follows,
            onClick = { onToggle(special) },
            onLongClick = if (special in follows) {
                { onToggleMute(special) }
            } else null,
        )
        Rule()
        SectionHeader("TEAMS")
    }
}

/** On, on-but-silent, or off — the same three states a team row shows. */
private fun state(key: String, follows: Set<String>, muted: Set<String>): String = when {
    key in muted && key in follows -> "[ SILENT ]"
    key in follows -> "[ ON ]"
    else -> "OFF"
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
                    val short = Leagues.byId(leagueId)?.short ?: leagueId.uppercase()
                    val label = when (teamId) {
                        "series" -> short
                        SpecialEvents.SUFFIX_CHAMPIONSHIP -> "$short FINAL"
                        SpecialEvents.SUFFIX_SPECIAL -> "$short EVENTS"
                        else -> teamsByLeague[leagueId]
                            ?.firstOrNull { it.teamId == teamId }?.abbrev ?: teamId
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
