package com.gios.lightsports.notify

import com.gios.lightsports.data.Feed
import com.gios.lightsports.model.Game
import com.gios.lightsports.model.GameState
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
    }

    /**
     * One line of the ongoing card.
     *
     * @param showScores false while the spoiler delay is on. The whole point of that
     * setting is that the phone must not get ahead of the stream, and a card sitting in
     * the shade with the current score would walk straight through it. The matchup and
     * the period are not a result, so they stay either way.
     */
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
