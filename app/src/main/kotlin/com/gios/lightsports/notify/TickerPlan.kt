package com.gios.lightsports.notify

import com.gios.lightsports.data.Feed
import com.gios.lightsports.model.Game
import com.gios.lightsports.model.GameState
import com.gios.lightsports.model.Situation
import com.gios.lightsports.model.SportKind
import kotlin.math.abs

/**
 * How fast to poll, and when it is worth running the foreground ticker at all.
 *
 * The arithmetic lives here, with no Android imports, because it is the part that
 * decides how much of the battery a Saturday costs and it should be provable without a
 * phone in the room. [LiveTicker] does the process work; this decides what it does.
 *
 * The cadence is tiered rather than flat. A blowout in the third quarter does not need
 * the same attention as a one-run ninth, and the poll is the expensive thing: each one
 * is a cold radio, two or three JSON fetches and a wakelock.
 */
object TickerPlan {

    /** Close and late. The end of a tight game is the only time seconds matter. */
    const val FAST_INTERVAL = 30_000L

    /** Anything else that is actually in progress. */
    const val LIVE_INTERVAL = 60_000L

    /** Nothing has started yet — enough to catch the first pitch, no more. */
    const val WARMUP_INTERVAL = 5L * 60_000

    /**
     * How often the game screen re-fetches while it is open on a live game.
     *
     * Faster than the ticker because the cost model is different: the screen is on, the
     * radio is already awake, and the person is looking at the number. Fifteen seconds is
     * about the gap between a pitch and the next one landing in the feed.
     */
    const val SCREEN_INTERVAL = 15_000L

    /** How long the screen waits before checking again whether a game has gone live. */
    const val SCREEN_IDLE_INTERVAL = 60_000L

    /**
     * Whether an open game screen should be re-fetching. Live, or close enough to the
     * start that the flip to live is what the person is waiting on.
     */
    fun screenShouldPoll(game: Game, nowMillis: Long, leadMillis: Long): Boolean =
        game.state == GameState.LIVE ||
            (game.state == GameState.PRE && game.startMillis in nowMillis..(nowMillis + leadMillis))

    /**
     * The ticker gives up after this long and hands back to the alarm chain.
     *
     * A provider that leaves a game stuck in LIVE is not hypothetical — ESPN did exactly
     * that to two 2026 Grands Prix for a whole season (see the README). Without a cap,
     * one bad record would hold a wakelock and a radio open until the phone died.
     */
    const val MAX_RUNTIME = 6L * 60 * 60 * 1000

    /**
     * Whether the ticker should be up at all, given what the user is being alerted to.
     *
     * The same predicate the alarm chain uses to decide it is in a busy stretch, so the
     * two can never disagree about whether something is happening — one saying yes and
     * the other no is a service that starts and stops itself every minute.
     */
    fun shouldRun(games: List<Game>, nowMillis: Long, leadMillis: Long): Boolean =
        Feed.hasActiveWindow(games, nowMillis, leadMillis)

    fun expired(startedAtMillis: Long, nowMillis: Long): Boolean =
        startedAtMillis > 0L && nowMillis - startedAtMillis >= MAX_RUNTIME

    /**
     * How long to wait before the next poll.
     *
     * Live games win over pending ones, and the fastest live game sets the pace for all
     * of them — one poll fetches every followed league anyway, so a second game costs
     * nothing extra once the radio is up.
     */
    fun intervalMillis(games: List<Game>, nowMillis: Long, kindOf: (Game) -> SportKind?): Long {
        val live = games.filter { it.state == GameState.LIVE }
        if (live.isNotEmpty()) {
            return if (live.any { isCrunch(it, kindOf(it)) }) FAST_INTERVAL else LIVE_INTERVAL
        }
        // Nothing live yet. Sleep most of the way to the next start, then watch for the
        // flip: a scheduled time is a plan, not a promise, and providers announce the
        // first pitch late as often as early.
        val until = games
            .filter { it.state == GameState.PRE && it.startMillis > nowMillis }
            .minOfOrNull { it.startMillis - nowMillis }
            ?: return LIVE_INTERVAL
        return until.coerceIn(LIVE_INTERVAL, WARMUP_INTERVAL)
    }

    /**
     * Whether this game is at the point where a minute is too long to wait.
     *
     * Two conditions, both required: late enough that the result is in reach, and close
     * enough that it is still in doubt. Past regulation the margin stops mattering —
     * extra innings and overtime are decided by one play whatever the score was.
     */
    fun isCrunch(game: Game, kind: SportKind?): Boolean {
        if (game.state != GameState.LIVE) return false
        if (kind == null || kind == SportKind.RACING) return false
        val regulation = regulationPeriods(kind)
        if (regulation == 0 || game.period < regulation) return false
        if (game.period > regulation) return true
        val home = game.home.score ?: return true
        val away = game.away.score ?: return true
        return abs(home - away) <= closeMargin(kind)
    }

    /** The last period of regulation: the 9th inning, the 4th quarter, the 3rd period. */
    fun regulationPeriods(kind: SportKind): Int = when (kind) {
        SportKind.BASEBALL -> 9
        SportKind.FOOTBALL -> 4
        SportKind.BASKETBALL -> 4
        SportKind.HOCKEY -> 3
        SportKind.SOCCER -> 2
        SportKind.RACING -> 0
        // The third set is the earliest a match can end in either format, and past it
        // every set is a deciding one for somebody.
        SportKind.TENNIS -> 3
    }

    /**
     * How many points still counts as anybody's game, per sport. A six-point NBA lead
     * with a quarter left is two possessions; a six-run lead in the ninth is over.
     */
    fun closeMargin(kind: SportKind): Int = when (kind) {
        SportKind.BASEBALL -> 2
        SportKind.FOOTBALL -> 8
        SportKind.BASKETBALL -> 6
        SportKind.HOCKEY -> 1
        SportKind.SOCCER -> 1
        SportKind.RACING -> 0
        // Sets. A set apart is one set from over either way.
        SportKind.TENNIS -> 1
    }

    /**
     * One line of the ongoing card.
     *
     * @param showScores false while the spoiler delay is on. The whole point of that
     * setting is that the phone must not get ahead of the stream, and a card sitting in
     * the shade with the current score would walk straight through it. The matchup and
     * the period are not a result, so they stay either way.
     */
    /**
     * Everything the ongoing card needs: a line per live game, the situation under it when
     * exactly one is on, and which game it opens.
     *
     * Built in one place because the card is drawn from two — the poll and the relay
     * listener — and a second copy of these rules would drift from the first.
     */
    data class Card(
        val lines: List<String>,
        val detail: String? = null,
        val gameId: String? = null,
        val leagueId: String? = null,
    )

    fun card(live: List<Game>, showScores: Boolean, kindOf: (Game) -> SportKind?): Card {
        val lines = live.map { line(it, kindOf(it), showScores) }
        val only = live.singleOrNull()
        return Card(
            lines = lines,
            // Held back with the score. The spoiler delay exists to keep the phone behind
            // the broadcast, and a drive that has reached the ten is the kind of thing that
            // gets there first.
            detail = only?.takeIf { showScores }?.let { detail(it, kindOf(it)) },
            gameId = only?.id,
            leagueId = only?.leagueId,
        )
    }

    /**
     * The second line of the live card: what is happening right now, in the provider's own
     * terms. Football gives the ball and the down, baseball the count and the outs,
     * everything else the period and the clock.
     *
     * Null when there is nothing to add — the first line already carries the score and the
     * period, and a card whose second line repeats the first reads as a rendering fault.
     */
    fun detail(game: Game, kind: SportKind?): String? {
        if (game.state != GameState.LIVE) return null
        val s = game.situation
        val clock = listOfNotNull(
            kind?.let { periodLabel(it, game.period) }?.takeIf { it.isNotEmpty() },
            game.clock,
        ).joinToString(" ").takeIf { it.isNotEmpty() }
        val situation = when (kind) {
            SportKind.FOOTBALL -> listOfNotNull(
                game.offense?.let { "${it.abbrev} ball" },
                s?.downDistance ?: s?.shortDownDistance,
                "RED ZONE".takeIf { s?.isRedZone == true },
            ).joinToString(" · ").takeIf { it.isNotEmpty() }
            SportKind.BASEBALL -> listOfNotNull(
                if (s?.balls != null && s.strikes != null) "${s.balls}-${s.strikes}" else null,
                s?.outs?.let { if (it == 1) "1 out" else "$it out" },
                s?.let { runners(it) },
            ).joinToString(" · ").takeIf { it.isNotEmpty() }
            else -> null
        }
        // The clock is already on the first line for the sports that have one, so it is only
        // repeated here when nothing better exists to say.
        return situation ?: clock?.takeIf { it != periodLabel(kind ?: return null, game.period) }
    }

    /** "Runners on 1st and 2nd", "Bases loaded", null for nobody on. */
    fun runners(s: Situation): String? {
        val on = listOfNotNull(
            "1st".takeIf { s.onFirst }, "2nd".takeIf { s.onSecond }, "3rd".takeIf { s.onThird },
        )
        return when (on.size) {
            0 -> null
            3 -> "Bases loaded"
            1 -> "Runner on ${on[0]}"
            else -> "Runners on ${on[0]} and ${on[1]}"
        }
    }

    private fun periodLabel(kind: SportKind, period: Int) = AlertText.periodLabel(kind, period)

    fun line(game: Game, kind: SportKind?, showScores: Boolean): String {
        val where = kind?.let { AlertText.periodLabel(it, game.period) }.orEmpty()
            .ifEmpty { game.statusDetail }
        val head = if (showScores) {
            "${game.away.short} ${game.away.score ?: 0} · ${game.home.short} ${game.home.score ?: 0}"
        } else {
            "${game.away.short} at ${game.home.short}"
        }
        return if (where.isEmpty()) head else "$head · $where"
    }
}
