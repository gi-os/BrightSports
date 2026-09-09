package com.gios.lightsports.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gios.lightsports.data.Leagues
import com.gios.lightsports.hw.WheelScroll
import com.gios.lightsports.model.Game
import com.gios.lightsports.model.GameState
import com.gios.lightsports.model.Play
import com.gios.lightsports.model.ScoringPlay
import com.gios.lightsports.model.Side
import com.gios.lightsports.model.SportKind
import com.gios.lightsports.notify.AlertText
import com.gios.lightsports.notify.ScoreDiff
import com.gios.lightsports.notify.TickerPlan
import com.gios.lightsports.ui.theme.Dim
import com.gios.lightsports.ui.theme.Faint
import com.gios.lightsports.ui.theme.Field
import com.gios.lightsports.ui.theme.FieldLine
import com.gios.lightsports.ui.theme.Marks
import com.gios.lightsports.ui.theme.RuleGrey
import com.gios.lightsports.ui.theme.Soft
import com.gios.lightsports.util.Fmt
import java.time.ZoneId

/**
 * One game in full. The two marks and the score up top; for a live football game the
 * ball, the field and the last plays; then the line score or the scoring summary, and
 * the handful of facts worth knowing before watching it.
 *
 * @param plays the last few plays, newest first, or empty when the provider has none.
 * @param scoring the scoring summary once loaded; [onLoadScoring] asks for it.
 */
@Composable
fun GameScreen(
    game: Game,
    tracking: Boolean = false,
    logos: Map<String, String> = emptyMap(),
    plays: List<Play> = emptyList(),
    scoring: List<ScoringPlay>? = null,
    onLoadScoring: () -> Unit = {},
    onLoadPlays: () -> Unit = {},
    onTeam: (Side) -> Unit = {},
) {
    val zone = ZoneId.systemDefault()
    val league = Leagues.byId(game.leagueId)
    val kind = league?.kind ?: SportKind.BASEBALL
    val football = kind == SportKind.FOOTBALL
    val live = game.state == GameState.LIVE
    val final = game.state == GameState.FINAL
    val scroll = rememberScrollState()
    WheelScroll(scroll)

    // A screen that is re-fetching every fifteen seconds is only useful if it hasn't
    // gone to sleep between refreshes. A settled game you are just reading is not
    // tracking, so the panel is free to sleep on that one.
    KeepAwake(tracking)

    // The plays are fetched with every live refresh; a game opened mid-way gets its
    // first batch here rather than fifteen seconds later.
    LaunchedEffect(game.id, live) { if (live) onLoadPlays() }

    // A finished game opens on the scoring summary, a live one on the line score.
    var showScoring by remember(game.id) { mutableStateOf(final && (football || kind == SportKind.SOCCER)) }
    LaunchedEffect(showScoring, game.home.score, game.away.score, game.state) {
        if (showScoring && kind != SportKind.SOCCER && kind != SportKind.BASKETBALL) onLoadScoring()
    }

    val homeWon = final && (game.home.score ?: 0) > (game.away.score ?: 0)
    val awayWon = final && (game.away.score ?: 0) > (game.home.score ?: 0)

    Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
        // ---- the status line: where the game is, and how fresh this screen is
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (live) {
                    LiveDot()
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    statusLabel(game, league?.short, kind, zone),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (live) Color.White else Dim,
                    maxLines = 1,
                )
            }
            Text(
                when {
                    // Says so once rather than counting down: a ticking "12s ago" on a
                    // matte panel is a distraction from the score it sits next to.
                    tracking -> "UPDATING EVERY ${TickerPlan.SCREEN_INTERVAL / 1000} S"
                    game.state == GameState.PRE ->
                        Fmt.until(game.startMillis, System.currentTimeMillis()).uppercase()
                            .let { if (it == "NOW") "STARTING" else (if (football) "KICKOFF " else "STARTS ") + it }
                    else -> listOfNotNull(
                        Fmt.dayDate(game.startMillis, zone).uppercase(),
                        game.venue?.uppercase(),
                    ).joinToString(" · ")
                },
                style = MaterialTheme.typography.labelSmall,
                color = Faint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp).weight(1f, fill = false),
                textAlign = TextAlign.End,
            )
        }

        // ---- the two marks and the score
        Spacer(Modifier.height(14.dp))
        ScoreHeader(
            game = game,
            kind = kind,
            logos = logos,
            dimAway = final && !awayWon,
            dimHome = final && !homeWon,
            onTeam = onTeam,
        )

        // ---- live football: the ball, the field, the drive
        val situation = game.situation
        if (live && football && situation != null) {
            val offense = game.offense
            Spacer(Modifier.height(18.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    listOfNotNull(offense?.let { "${it.abbrev} ball" }, situation.downDistance)
                        .joinToString(" · ").ifEmpty { game.statusDetail },
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (situation.isRedZone) {
                    Spacer(Modifier.width(10.dp))
                    Tag("RED ZONE")
                }
            }
            if (offense != null) {
                Spacer(Modifier.height(8.dp))
                FootballField(
                    offense = offense,
                    defense = game.defense ?: offense,
                    yardsToGoal = situation.yardsToGoal(offense.abbrev),
                    distance = situation.distance,
                )
            }
            val drive = situation.drive?.let { "DRIVE · ${it.uppercase()}" }
            val lastType = plays.firstOrNull()?.type?.uppercase()
            if (drive != null || lastType != null) {
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        drive ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        color = Faint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (lastType != null) {
                        Text(
                            lastType,
                            style = MaterialTheme.typography.labelSmall,
                            color = Faint,
                            maxLines = 1,
                        )
                    }
                }
            }
        } else if (!football && game.state != GameState.PRE) {
            // The other sports' strips: the diamond and the count, shots and possession,
            // leaders and shooting, shots on goal. Each draws only what the feed sent.
            Spacer(Modifier.height(16.dp))
            SportStrip(game, kind)
        }
        Spacer(Modifier.height(18.dp))
        Rule()

        // ---- the last plays, live only
        if (live && plays.isNotEmpty()) {
            SectionHeader("LAST PLAYS")
            for ((i, play) in plays.take(6).withIndex()) {
                PlayRow(play, kind, first = i == 0)
            }
            Spacer(Modifier.height(10.dp))
            Rule()
        }

        // ---- line score / scoring summary
        val hasLine = game.away.lineScore.isNotEmpty() || game.home.lineScore.isNotEmpty()
        val soccer = kind == SportKind.SOCCER
        // Soccer's story is on the scoreboard itself (goals and cards under `details`);
        // basketball's summary has no scoring list worth the half-megabyte. The rest ask
        // the game summary for their scoring plays.
        val canScore = league?.provider == com.gios.lightsports.model.Provider.ESPN &&
            kind != SportKind.TENNIS && kind != SportKind.RACING && kind != SportKind.BASKETBALL &&
            game.state != GameState.PRE
        if (hasLine || canScore) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (hasLine) {
                    Chip(lineScoreTitle(kind), selected = !showScoring) { showScoring = false }
                }
                if (canScore) {
                    Chip(if (soccer) "TIMELINE" else "SCORING", selected = showScoring || !hasLine) {
                        showScoring = true
                    }
                }
            }
            when {
                (showScoring || !hasLine) && soccer -> TimelineList(game, game.timeline)
                showScoring || !hasLine -> ScoringList(game, scoring)
                else -> LineScoreTable(game, kind)
            }
            Rule()
        }

        // ---- details
        SectionHeader("DETAILS")
        game.note?.let {
            MenuRow(if (kind == SportKind.TENNIS) "Round" else "Series", detail = null, sub = it)
        }
        MenuRow(
            when (kind) {
                SportKind.BASEBALL -> "First pitch"
                SportKind.FOOTBALL -> "Kickoff"
                SportKind.BASKETBALL -> "Tip-off"
                else -> "Start"
            },
            detail = Fmt.time(game.startMillis, zone),
            sub = Fmt.dayDate(game.startMillis, zone),
        )
        game.venue?.let { MenuRow(if (kind == SportKind.TENNIS) "Court" else "Venue", sub = it) }
        game.broadcast?.let { MenuRow("TV", detail = it) }
        if (game.state == GameState.PRE) {
            game.odds?.let { MenuRow("Line", detail = it) }
            game.overUnder?.let { MenuRow("Over / under", detail = it) }
        }
        game.weather?.let { MenuRow("Weather", detail = it) }
        if (game.state == GameState.PRE) {
            game.away.record?.let { MenuRow(game.away.short, detail = it) }
            game.home.record?.let { MenuRow(game.home.short, detail = it) }
        }
        Spacer(Modifier.height(32.dp))
    }
}

/** "Q2 · 3:24" live, "NFL · FINAL" after, "WEEK 2 · SUN 1:00 PM" before. */
private fun statusLabel(game: Game, leagueShort: String?, kind: SportKind, zone: ZoneId): String {
    val week = game.week?.let { "WEEK $it" }
    return when (game.state) {
        GameState.PRE -> listOfNotNull(week ?: leagueShort, Fmt.dayTime(game.startMillis, zone).uppercase())
            .joinToString(" · ")
        GameState.LIVE -> {
            val period = AlertText.periodLabel(kind, game.period)
            if (period.isNotEmpty() && game.clock != null &&
                !ScoreDiff.explicitBoundary(game.statusName, game.statusDetail) &&
                kind != SportKind.BASEBALL
            ) "$period · ${game.clock}" else game.statusDetail.ifEmpty { "LIVE" }.uppercase()
        }
        GameState.FINAL -> listOfNotNull(week ?: leagueShort, game.statusDetail.ifEmpty { "Final" }.uppercase())
            .joinToString(" · ")
        GameState.OFF -> listOfNotNull(leagueShort, game.statusDetail.ifEmpty { "Postponed" }.uppercase())
            .joinToString(" · ")
    }
}

/** The blinking square beside a live status. Blinks by the poll, not a timer: it is redrawn as the data lands. */
@Composable
fun LiveDot() {
    Box(Modifier.width(8.dp).height(8.dp).background(Color.White))
}

/**
 * Away on the left, home on the right, the score between. A pre-game shows "AT" (or
 * "VS" on a neutral field) where the score will be.
 */
@Composable
private fun ScoreHeader(
    game: Game,
    kind: SportKind,
    logos: Map<String, String>,
    dimAway: Boolean,
    dimHome: Boolean,
    onTeam: (Side) -> Unit,
) {
    val live = game.state == GameState.LIVE
    val showTimeouts = live && kind == SportKind.FOOTBALL && game.situation?.homeTimeouts != null
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        SideColumn(
            side = game.away, kind = kind, logoUrl = logos["${game.leagueId}:${game.away.teamId}"],
            dimmed = dimAway, alignEnd = false,
            timeouts = if (showTimeouts) game.situation?.awayTimeouts else null,
            modifier = Modifier.weight(1f).clickable { onTeam(game.away) },
        )
        if (game.state == GameState.PRE) {
            Text(
                // A match has no host; a neutral-site game has one in name only.
                if (game.neutralSite || kind == SportKind.TENNIS) "VS" else "AT",
                style = Marks.bigDash,
                color = Faint,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        } else {
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text(
                    game.away.score?.toString() ?: "-",
                    style = Marks.big,
                    color = if (dimAway) Dim else Color.White,
                )
                Text(
                    "–",
                    style = Marks.bigDash,
                    color = Faint,
                    modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 6.dp),
                )
                Text(
                    game.home.score?.toString() ?: "-",
                    style = Marks.big,
                    color = if (dimHome) Dim else Color.White,
                )
            }
        }
        SideColumn(
            side = game.home, kind = kind, logoUrl = logos["${game.leagueId}:${game.home.teamId}"],
            dimmed = dimHome, alignEnd = true,
            timeouts = if (showTimeouts) game.situation?.homeTimeouts else null,
            modifier = Modifier.weight(1f).clickable { onTeam(game.home) },
        )
    }
}

@Composable
private fun SideColumn(
    side: Side,
    kind: SportKind,
    logoUrl: String?,
    dimmed: Boolean,
    alignEnd: Boolean,
    timeouts: Int?,
    modifier: Modifier = Modifier,
) {
    Column(modifier, horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start) {
        TeamMark(side, kind, logoUrl, dimmed, style = Marks.teamLarge, logoSize = 30.dp)
        Spacer(Modifier.height(2.dp))
        val facts = listOfNotNull(
            side.short.uppercase().takeIf { kind != SportKind.TENNIS },
            side.rank?.let { "#$it" },
            side.record,
            side.hits?.let { "$it H" },
            side.errors?.let { "$it E" },
        ).joinToString(" · ")
        if (facts.isNotEmpty()) {
            Text(
                facts,
                style = MaterialTheme.typography.labelSmall,
                color = Faint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = if (alignEnd) TextAlign.End else TextAlign.Start,
            )
        }
        if (timeouts != null) {
            Spacer(Modifier.height(6.dp))
            Timeouts(timeouts, dimmed)
        }
    }
}

/**
 * The field: the offense's end on the left, the defense's on the right, filled to the
 * ball, a dashed line at the first-down marker, the fifty at the middle. Drawn from
 * "yards to goal" so it is the same picture whichever team has the ball.
 */
@Composable
fun FootballField(offense: Side, defense: Side, yardsToGoal: Int?, distance: Int?) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(44.dp)) {
        EndZone(offense.abbrev, filled = true)
        Canvas(Modifier.weight(1f).fillMaxHeight()) {
            val w = size.width
            val h = size.height
            // Edge lines.
            drawLine(FieldLine, Offset(0f, 0.5f), Offset(w, 0.5f), 1f)
            drawLine(FieldLine, Offset(0f, h - 0.5f), Offset(w, h - 0.5f), 1f)
            val ballX = yardsToGoal?.let { (100 - it).coerceIn(0, 100) / 100f * w }
            // Ground gained so far, from the offense's own goal line to the ball.
            if (ballX != null) drawRect(Field, Offset.Zero, Size(ballX, h))
            // A line every ten yards, the fifty a shade brighter.
            for (i in 1..9) {
                val x = w * i / 10f
                drawLine(if (i == 5) Dim else FieldLine, Offset(x, 0f), Offset(x, h), 1f)
            }
            if (ballX != null) {
                drawLine(Color.White, Offset(ballX, 0f), Offset(ballX, h), 2.dp.toPx())
                // The marker above the ball.
                val tri = Path().apply {
                    moveTo(ballX - 5.dp.toPx(), 0f)
                    lineTo(ballX + 5.dp.toPx(), 0f)
                    lineTo(ballX, 6.dp.toPx())
                    close()
                }
                drawPath(tri, Color.White)
                // First down. Past the goal line there is no marker, only the end zone.
                if (distance != null && distance > 0 && yardsToGoal != null && distance < yardsToGoal) {
                    val fdX = ballX + distance / 100f * w
                    drawLine(
                        Dim, Offset(fdX, 0f), Offset(fdX, h), 2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
                    )
                }
            }
        }
        EndZone(defense.abbrev, filled = false)
    }
}

/** The end-zone block with the team's mark written up its side. */
@Composable
private fun EndZone(abbrev: String, filled: Boolean) {
    Box(
        Modifier.width(24.dp).fillMaxHeight()
            .background(if (filled) Color.White else RuleGrey),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            abbrev.uppercase(),
            style = Marks.tiny,
            color = if (filled) Color.Black else Dim,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.rotate(if (filled) -90f else 90f).widthIn(min = 40.dp),
            textAlign = TextAlign.Center,
        )
    }
}

/** One line of the play-by-play: the clock, the play, the down it started on. */
@Composable
private fun PlayRow(play: Play, kind: SportKind, first: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            play.clock ?: AlertText.periodLabel(kind, play.period),
            style = MaterialTheme.typography.labelSmall,
            color = Faint,
            maxLines = 1,
            modifier = Modifier.width(44.dp).padding(top = 2.dp),
        )
        Text(
            AlertText.cleanPlay(play.text) ?: play.text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (first) Color.White else Dim,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(end = 10.dp),
        )
        val down = play.downDistance?.substringBefore(' ')?.takeIf { it.isNotEmpty() }
        if (down != null && kind == SportKind.FOOTBALL) {
            Text(
                down.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = Faint,
                maxLines = 1,
                modifier = Modifier.padding(top = 2.dp),
            )
        } else if (play.scoring) {
            Tag(if (play.scoreValue > 0) "+${play.scoreValue}" else "SCORE")
        }
    }
}

/**
 * The scoring summary, newest first: what kind of score, who, when, and the score it
 * made. Null while loading; empty when the provider has none.
 */
@Composable
private fun ScoringList(game: Game, scoring: List<ScoringPlay>?) {
    when {
        scoring == null -> Text(
            "Loading…",
            style = MaterialTheme.typography.bodyMedium,
            color = Dim,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        scoring.isEmpty() -> Text(
            if (game.state == GameState.LIVE) "Nobody has scored yet." else "No scoring summary for this game.",
            style = MaterialTheme.typography.bodyMedium,
            color = Dim,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        else -> Column(Modifier.padding(bottom = 8.dp)) {
            val kind = Leagues.byId(game.leagueId)?.kind ?: SportKind.FOOTBALL
            for (play in scoring.asReversed()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        play.kind.uppercase().ifEmpty { "•" },
                        style = Marks.team.copy(fontSize = 20.sp, lineHeight = 22.sp),
                        color = Color.White,
                        maxLines = 1,
                        modifier = Modifier.width(44.dp),
                    )
                    Column(Modifier.weight(1f).padding(end = 10.dp)) {
                        Text(
                            play.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Soft,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            listOfNotNull(
                                AlertText.periodLabel(kind, play.period).takeIf { it.isNotEmpty() },
                                play.clock,
                                play.teamAbbrev,
                            ).let { parts ->
                                // "Q4 8:12 · SEA": period and clock together, then the team.
                                val moment = parts.take(if (play.clock != null) 2 else 1).joinToString(" ")
                                listOfNotNull(moment.takeIf { it.isNotEmpty() }, play.teamAbbrev).joinToString(" · ")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = Faint,
                            maxLines = 1,
                        )
                    }
                    Text(
                        "${play.awayScore}–${play.homeScore}",
                        style = Marks.score.copy(fontSize = 22.sp, lineHeight = 24.sp),
                        color = Dim,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private fun lineScoreTitle(kind: SportKind) = when (kind) {
    SportKind.BASEBALL -> "BY INNING"
    SportKind.HOCKEY -> "BY PERIOD"
    SportKind.SOCCER -> "BY HALF"
    SportKind.TENNIS -> "BY SET"
    else -> "BY QUARTER"
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
        if (kind == SportKind.BASEBALL && (game.away.hits != null || game.home.hits != null)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CellText("H", header = true)
                CellText(game.away.hits?.toString() ?: "")
                CellText(game.home.hits?.toString() ?: "")
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CellText("E", header = true)
                CellText(game.away.errors?.toString() ?: "")
                CellText(game.home.errors?.toString() ?: "")
            }
        }
    }
}

private fun totalLabel(kind: SportKind) = when (kind) {
    SportKind.BASEBALL -> "R"
    // Sets, not points: the total column is the match score.
    SportKind.TENNIS -> "S"
    else -> "T"
}

private fun periodHeader(kind: SportKind, period: Int): String = when (kind) {
    SportKind.BASEBALL -> period.toString()
    // "Set 3" is the alert wording; the 28dp cell takes two characters.
    SportKind.TENNIS -> "S$period"
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
