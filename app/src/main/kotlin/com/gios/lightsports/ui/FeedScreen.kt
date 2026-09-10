package com.gios.lightsports.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import com.gios.lightsports.data.Feed
import com.gios.lightsports.data.Leagues
import com.gios.lightsports.hw.WheelScroll
import com.gios.lightsports.model.Game
import com.gios.lightsports.model.GameState
import com.gios.lightsports.model.RaceEvent
import com.gios.lightsports.model.Side
import com.gios.lightsports.model.SportKind
import com.gios.lightsports.notify.AlertText
import com.gios.lightsports.notify.ScoreDiff
import com.gios.lightsports.notify.TickerPlan
import com.gios.lightsports.ui.theme.Marks
import com.gios.lightsports.ui.theme.Soft
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
    onTeam: (String) -> Unit = {},
    onRefresh: () -> Unit = {},
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
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onRefresh)
                    .padding(start = 16.dp, end = 16.dp, top = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    if (state.loading) "REFRESHING…"
                    else "UPDATED ${Fmt.ago(state.updatedAt, System.currentTimeMillis()).uppercase()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Faint,
                    maxLines = 1,
                )
                if (state.subtitle != null) {
                    Text(
                        state.subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = Faint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End,
                        modifier = Modifier.padding(start = 12.dp).weight(1f, fill = false),
                    )
                }
            }
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
            item(key = "idle-h") { SectionHeader("NO GAME THIS WEEK") }
            for (team in state.idle) {
                item(key = "idle-${team.key}") {
                    // "Kansas City Chiefs" / "BYE · next vs BAL · Sun Sep 20 4:25 PM". Tap
                    // for the team's season.
                    Row(
                        Modifier.fillMaxWidth().clickable { onTeam(team.key) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TeamLogo(logos[team.key], size = 24.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                team.label,
                                style = MaterialTheme.typography.bodyLarge,
                                color = Dim,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (team.note != null) {
                                Text(
                                    team.note.uppercase(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Faint,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    Rule()
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
    val kind = league?.kind
    val live = game.state == GameState.LIVE
    val final = game.state == GameState.FINAL

    // In a finished game the winner stays white and the loser drops to grey. It is
    // the only way to show a result at a glance without colour.
    val homeWon = final && (game.home.score ?: 0) > (game.away.score ?: 0)
    val awayWon = final && (game.away.score ?: 0) > (game.home.score ?: 0)
    val situation = game.situation
    val football = kind == SportKind.FOOTBALL
    val showTimeouts = live && football && situation?.homeTimeouts != null

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
                        GameState.PRE -> Fmt.dayTime(game.startMillis, zone).uppercase()
                        GameState.LIVE -> liveLabel(game, kind)
                        GameState.FINAL -> game.statusDetail.ifEmpty { "Final" }.uppercase()
                        GameState.OFF -> game.statusDetail.ifEmpty { "Postponed" }.uppercase()
                    },
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = if (live) Color.White else Dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (game.state != GameState.FINAL && game.broadcast != null) {
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
            kind = kind,
            logoUrl = logos["${game.leagueId}:${game.away.teamId}"],
            dimmed = final && !awayWon,
            showScore = game.state != GameState.PRE,
            hasBall = live && situation?.possession == game.away.teamId,
            timeouts = if (showTimeouts) situation?.awayTimeouts else null,
        )
        Spacer(Modifier.height(4.dp))
        TeamLine(
            side = game.home,
            kind = kind,
            logoUrl = logos["${game.leagueId}:${game.home.teamId}"],
            dimmed = final && !homeWon,
            showScore = game.state != GameState.PRE,
            hasBall = live && situation?.possession == game.home.teamId,
            timeouts = if (showTimeouts) situation?.homeTimeouts else null,
        )
        // The third line: what is happening (live), what to expect (pre-game), or what
        // happened (final). One line each, none of them when there is nothing to say.
        val third = when (game.state) {
            // A tennis score is the sets written out: "6-3 1-6 1-0". The column at the
            // right is sets won, which alone says nothing about how the match went.
            GameState.LIVE, GameState.FINAL -> if (kind == SportKind.TENNIS) {
                AlertText.setLine(game).takeIf { it.isNotEmpty() }
            } else if (game.state == GameState.LIVE) situationLine(game, kind) else game.headline
            GameState.PRE -> listOfNotNull(game.odds, game.overUnder?.let { "O/U $it" }, game.weather)
                .joinToString(" · ").takeIf { it.isNotEmpty() }
            GameState.OFF -> null
        }
        if (third != null || (live && situation?.isRedZone == true)) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (third != null) {
                    Text(
                        third,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (live) Soft else Dim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                if (live && situation?.isRedZone == true) {
                    Spacer(Modifier.width(10.dp))
                    Tag("RED ZONE")
                }
            }
        }
    }
}

/** "Q2 3:24" for the sports with a clock; the provider's words for the rest. */
private fun liveLabel(game: Game, kind: SportKind?): String {
    if (kind == SportKind.FOOTBALL || kind == SportKind.BASKETBALL || kind == SportKind.HOCKEY) {
        val period = AlertText.periodLabel(kind, game.period)
        if (period.isNotEmpty() && game.clock != null &&
            !ScoreDiff.explicitBoundary(game.statusName, game.statusDetail)
        ) return "$period ${game.clock}"
    }
    return game.statusDetail.ifEmpty { "Live" }.uppercase()
}

/**
 * The live line under the two teams. Football: down and distance. Baseball: the count
 * and the outs. Everything else: nothing, the status line already said what matters.
 */
private fun situationLine(game: Game, kind: SportKind?): String? {
    val s = game.situation ?: return null
    return when (kind) {
        SportKind.FOOTBALL -> s.downDistance
        SportKind.BASEBALL -> listOfNotNull(
            if (s.balls != null && s.strikes != null) "${s.balls}-${s.strikes}" else null,
            s.outs?.let { if (it == 1) "1 out" else "$it outs" },
            runners(s),
        ).joinToString(" · ").takeIf { it.isNotEmpty() }
        else -> null
    }
}

/**
 * "Runners on 1st and 2nd". One implementation, in [TickerPlan], because the same sentence
 * goes on the shade card and a second copy here would drift from it.
 */
fun runners(s: com.gios.lightsports.model.Situation): String? = TickerPlan.runners(s)

/** Inverted label: white block, black caps. The one emphasis a greyscale panel has. */
@Composable
fun Tag(text: String) {
    Box(Modifier.background(Color.White).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = Color.Black,
            maxLines = 1,
        )
    }
}

/**
 * The team mark: a crest and the abbreviation in the condensed face. Tennis has no
 * three-letter code worth reading, so a player keeps their short name in the text face.
 */
@Composable
fun TeamMark(
    side: Side,
    kind: SportKind?,
    logoUrl: String?,
    dimmed: Boolean,
    style: androidx.compose.ui.text.TextStyle = Marks.team,
    logoSize: androidx.compose.ui.unit.Dp = 24.dp,
    modifier: Modifier = Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        TeamLogo(logoUrl, size = logoSize, alpha = if (dimmed) 0.45f else 1f)
        Spacer(Modifier.width(10.dp))
        if (kind == SportKind.TENNIS) {
            Text(
                side.short,
                style = MaterialTheme.typography.titleMedium,
                color = if (dimmed) Dim else Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        } else {
            Text(
                side.abbrev.uppercase(),
                style = style,
                color = if (dimmed) Dim else Color.White,
                maxLines = 1,
            )
        }
    }
}

/** Three squares, filled for the timeouts a side still has. */
@Composable
fun Timeouts(left: Int, dimmed: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (i in 0 until 3) {
            val filled = i < left
            Box(
                Modifier.size(6.dp).let {
                    if (filled) it.background(if (dimmed) Dim else Color.White)
                    else it.border(1.dp, Faint)
                },
            )
        }
    }
}

@Composable
private fun TeamLine(
    side: Side,
    kind: SportKind?,
    logoUrl: String?,
    dimmed: Boolean,
    showScore: Boolean,
    hasBall: Boolean = false,
    timeouts: Int? = null,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // The mark and its facts share one weighted slot so the score is pinned to the
        // right edge whatever the name's width. Two weighted siblings did not do that:
        // a mark that does not fill its share leaves the gap where it stood, and the
        // score column wandered by team.
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            TeamMark(side, kind, logoUrl, dimmed, modifier = Modifier.weight(1f, fill = false))
            // "#7 · 2-0 · BALL": the poll rank, the record, and who has it.
            val facts = listOfNotNull(
                side.rank?.let { "#$it" },
                side.record,
                "BALL".takeIf { hasBall },
            ).joinToString(" · ")
            if (facts.isNotEmpty()) {
                Spacer(Modifier.width(12.dp))
                Text(
                    facts,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (hasBall) Dim else Faint,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        if (timeouts != null) {
            Timeouts(timeouts, dimmed)
            Spacer(Modifier.width(14.dp))
        }
        if (showScore) {
            Text(
                side.score?.toString() ?: "-",
                style = Marks.score,
                color = if (dimmed) Dim else Color.White,
                textAlign = TextAlign.End,
                modifier = Modifier.widthIn(min = 36.dp),
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
