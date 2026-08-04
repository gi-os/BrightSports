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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gios.lightsports.data.Feed
import com.gios.lightsports.data.Leagues
import com.gios.light.common.hw.WheelScroll
import com.gios.lightsports.model.Game
import com.gios.lightsports.model.GameState
import com.gios.lightsports.model.RaceEvent
import com.gios.lightsports.model.Side
import com.gios.lightsports.ui.theme.Dim
import com.gios.lightsports.ui.theme.Faint
import com.gios.lightsports.util.Fmt
import java.time.ZoneId

/**
 * The whole point of the app: one column, followed teams only, newest thing at the
 * top. No league tabs, no browse mode — if it isn't a team you follow it isn't here.
 */
@Composable
fun FeedScreen(
    state: SportsViewModel.FeedState,
    hasFollows: Boolean,
    logos: Map<String, String>,
    onGame: (Game) -> Unit,
    onEditTeams: () -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val listState = rememberLazyListState()
    WheelScroll(listState)

    if (!hasFollows) {
        Column(Modifier.fillMaxSize()) {
            EmptyState(
                "No teams yet.\n\nPick your teams and their games show up here.",
                Modifier.weight(1f),
            )
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onEditTeams)
                    .padding(horizontal = 16.dp, vertical = 18.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    "[ CHOOSE TEAMS ]",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                )
            }
        }
        return
    }

    if (state.sections.isEmpty() && state.idle.isEmpty()) {
        EmptyState(
            if (state.loading) "Loading…"
            else if (state.offline) "Couldn't reach the scores.\nPull down to try again."
            else "Nothing scheduled.\n\nYour teams are between games.",
        )
        return
    }

    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        // The refresh control is an icon in the top bar now, so this line is the only
        // thing saying whether the screen can be trusted.
        item(key = "stamp") {
            Text(
                if (state.loading) "REFRESHING…"
                else "UPDATED ${Fmt.ago(state.updatedAt, System.currentTimeMillis()).uppercase()}",
                style = MaterialTheme.typography.labelSmall,
                color = Faint,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 10.dp),
            )
        }
        for (section in state.sections) {
            item(key = "h-${section.title}") { SectionHeader(section.title) }
            for (item in section.items) {
                when (item) {
                    is Feed.Item.GameItem -> item(key = "g-${item.game.leagueId}-${item.game.id}") {
                        GameRow(item.game, zone, logos) { onGame(item.game) }
                        Rule()
                    }
                    is Feed.Item.RaceItem -> item(key = "r-${item.race.id}") {
                        RaceRow(item.race, zone)
                        Rule()
                    }
                }
            }
        }
        if (state.idle.isNotEmpty()) {
            item(key = "idle") {
                SectionHeader("NO GAME SCHEDULED")
                for (team in state.idle) {
                    Text(
                        team,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Dim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
        }
        item { Spacer(Modifier.height(28.dp)) }
    }
}

@Composable
fun GameRow(
    game: Game,
    zone: ZoneId,
    logos: Map<String, String> = emptyMap(),
    onClick: () -> Unit,
) {
    val league = Leagues.byId(game.leagueId)
    val live = game.state == GameState.LIVE
    val final = game.state == GameState.FINAL

    // In a finished game the winner stays white and the loser drops to grey. It is
    // the only way to show a result at a glance without colour.
    val homeWon = final && (game.home.score ?: 0) > (game.away.score ?: 0)
    val awayWon = final && (game.away.score ?: 0) > (game.home.score ?: 0)

    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                listOfNotNull(
                    // "SUPER BOWL LX" earns the league's slot on the line; nobody needs
                    // telling which league the Super Bowl belongs to. A cup game names
                    // its competition, since "MLS" would be actively wrong for a
                    // Leagues Cup tie against Toluca.
                    game.eventTitle?.uppercase() ?: game.competition?.uppercase()
                        ?: league?.short,
                    when (game.state) {
                        GameState.PRE -> Fmt.time(game.startMillis, zone)
                        GameState.LIVE -> game.statusDetail.ifEmpty { "Live" }
                        GameState.FINAL -> game.statusDetail.ifEmpty { "Final" }
                        GameState.OFF -> game.statusDetail.ifEmpty { "Postponed" }
                    },
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = if (live) Color.White else Dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (game.state == GameState.PRE && game.broadcast != null) {
                Text(
                    game.broadcast,
                    style = MaterialTheme.typography.labelSmall,
                    color = Faint,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        TeamLine(
            side = game.away,
            logoUrl = logos["${game.leagueId}:${game.away.teamId}"],
            dimmed = final && !awayWon,
            showScore = game.state != GameState.PRE,
        )
        Spacer(Modifier.height(4.dp))
        TeamLine(
            side = game.home,
            logoUrl = logos["${game.leagueId}:${game.home.teamId}"],
            dimmed = final && !homeWon,
            showScore = game.state != GameState.PRE,
        )
    }
}

@Composable
private fun TeamLine(side: Side, logoUrl: String?, dimmed: Boolean, showScore: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TeamLogo(logoUrl, size = 24.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            side.short,
            style = MaterialTheme.typography.titleMedium,
            color = if (dimmed) Dim else Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (side.record != null) {
            Text(
                side.record,
                style = MaterialTheme.typography.labelSmall,
                color = Faint,
                maxLines = 1,
            )
            Spacer(Modifier.width(12.dp))
        }
        if (showScore) {
            Text(
                side.score?.toString() ?: "-",
                style = MaterialTheme.typography.titleLarge,
                color = if (dimmed) Dim else Color.White,
                fontWeight = if (dimmed) FontWeight.Light else FontWeight.Normal,
            )
        }
    }
}

@Composable
fun RaceRow(race: RaceEvent, zone: ZoneId) {
    val league = Leagues.byId(race.leagueId)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            listOfNotNull(
                league?.short,
                when (race.state) {
                    GameState.FINAL -> "Final"
                    GameState.LIVE -> race.sessionLabel ?: "Live"
                    else -> race.sessionMillis?.let { Fmt.dayTime(it, zone) }
                },
                race.sessionLabel?.takeIf { race.state == GameState.PRE },
            ).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = if (race.state == GameState.LIVE) Color.White else Dim,
            maxLines = 1,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            race.shortName,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (race.podium.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            race.podium.forEachIndexed { i, name ->
                Text(
                    "${i + 1}  $name",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (i == 0) Color.White else Dim,
                    maxLines = 1,
                )
            }
        }
    }
}
