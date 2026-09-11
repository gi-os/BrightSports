package com.gios.lightsports.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.gios.lightsports.data.Feed
import com.gios.lightsports.data.Leagues
import com.gios.lightsports.data.Prefs
import com.gios.lightsports.data.SportsRepository
import com.gios.lightsports.model.Game
import com.gios.lightsports.model.GameState
import com.gios.lightsports.model.SportKind
import org.json.JSONObject
import java.io.File
import java.time.ZoneId

/**
 * The background half of the app.
 *
 * Nothing here uses WorkManager or a coroutine timer, because on the Light Phone III
 * neither runs while the screen is off: Doze suspends the CPU and cuts the app's
 * network. The one thing that still fires is
 * `AlarmManager.setAndAllowWhileIdle`, and each firing grants a short
 * temporary-allowlist window with network access — which is the only reason a REST
 * poll is possible at all. It has no repeating form, so every run arms the next one.
 */
object ScoreWatcher {

    private const val TAG = "ScoreWatcher"
    const val ACTION_POLL = "com.gios.lightsports.POLL"

    /**
     * While a followed game is live — and, in practice, a floor rather than an interval:
     * Doze throttles allow-while-idle alarms to roughly one firing every nine minutes.
     * That is what [LiveTicker] exists to get out from under; this is what the app falls
     * back to when the service cannot run.
     */
    private const val LIVE_INTERVAL = 2L * 60 * 1000

    /** Nothing live: the longest the app will go without checking the schedule. */
    private const val IDLE_INTERVAL = 3L * 60 * 60 * 1000

    /**
     * Wake up this far before a scheduled start so the tip-off alert is on time, and the
     * width of the window the live ticker treats as "something is about to happen".
     */
    const val LEAD = 15L * 60 * 1000

    /** How long a game's notification stays in the shade after the game ends. */
    private const val CLEANUP_DELAY = 60L * 60 * 1000

    /**
     * How long "they have started" or "it is back on" stays true.
     *
     * Ten minutes. Long enough to survive a poll that ran late, short enough that it can
     * never be the thing announcing a game you are already watching.
     */
    private const val FRESH_WINDOW = 10L * 60 * 1000

    /** How long "they're in the red zone" is worth saying. */
    private const val REDZONE_WINDOW = 4L * 60 * 1000

    /**
     * How long a six-point touchdown waits for its kick before posting anyway. The kick is
     * usually inside a minute; seventy-five seconds covers a review without holding a
     * blocked kick's touchdown for the rest of the quarter.
     */
    const val PAT_HOLD = 75L * 1000

    // ------------------------------------------------------------- scheduling

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        1,
        Intent(context, PollReceiver::class.java).setAction(ACTION_POLL),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * `setExactAndAllowWhileIdle`, and not for the exactness.
     *
     * Both variants are throttled to roughly one firing every nine minutes in Doze, so
     * on the clock they are the same alarm. The difference is the other rule: a
     * broadcast delivered by an *exact* alarm is exempt from the Android 12 restriction
     * on starting a foreground service from the background. The inexact one is not.
     *
     * That is the whole reason scores were still landing ten minutes late with the
     * ticker shipped and switched on. [PollReceiver] would find a live game, call
     * [LiveTicker.start], and be refused -- silently, on a sleeping phone, which is the
     * only phone the ticker was ever written for. The 30-60 second poll worked whenever
     * the app was open and never once when it was in a pocket.
     *
     * Falls back to the inexact form if exact alarms are refused, because a slow chain
     * beats a broken one.
     */
    fun armAt(context: Context, triggerAtMillis: Long) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val at = maxOf(triggerAtMillis, System.currentTimeMillis() + 30_000)
        val intent = pendingIntent(context)
        val exact = Health.exactAlarms(context)
        val armed = runCatching {
            if (exact) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
            }
        }.isSuccess
        // A SecurityException here means the exact-alarm appop was revoked between the
        // check and the call. One retry on the variant that can never be revoked.
        if (!armed) {
            runCatching {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
            }.onFailure { Log.w(TAG, "could not arm alarm", it) }
        }
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(context))
    }

    /**
     * Called on app launch as well as after every poll. A force-stop cancels every
     * alarm the app owns, and alarms don't survive a reboot, so the two entry points
     * that always happen have to re-arm.
     */
    fun ensureArmed(context: Context) {
        val prefs = Prefs(context)
        if (!prefs.notificationsEnabled || prefs.follows.isEmpty()) {
            cancel(context)
            // Nothing to watch: the ticker has no business being up, and it holds a
            // wakelock and a card in the shade until somebody says so.
            LiveTicker.stop(context)
            return
        }
        armAt(context, System.currentTimeMillis() + LIVE_INTERVAL)
    }

    /**
     * What one poll found, for whoever asked for it.
     *
     * The alarm chain uses [nextWakeMillis] and ignores the rest; the ticker uses
     * [active] to decide whether to keep going, [tickerIntervalMillis] for how long to
     * sleep, and [lines] for the card.
     */
    data class Outcome(
        /** A followed, unsilenced game is live or about to be. */
        val active: Boolean,
        val nextWakeMillis: Long,
        val tickerIntervalMillis: Long,
        /** One card per live game — what each says, and which game each opens. */
        val cards: List<TickerPlan.Card>,
    )

    // ------------------------------------------------------------------ poll

    /**
     * Blocking. Call from a background thread that holds a wakelock.
     *
     * @param armNext whether to schedule the next alarm before returning. False when the
     * ticker is driving, since it sleeps on its own clock and a second alarm firing
     * underneath it would poll everything twice.
     */
    /**
     * Clear anything whose hour is up, without polling anything.
     *
     * Called when the app opens. A poll is what normally sweeps, and a poll needs an alarm
     * that fired -- which is exactly what has not been happening on a phone showing a card
     * from yesterday. Opening the app is the one moment the user has told us they are
     * looking at it, and a sweep costs one file read.
     */
    fun sweepStale(context: Context) {
        runCatching {
            Janitor(File(context.filesDir, "cleanup.json"))
                .sweep(context, System.currentTimeMillis())
        }
    }

    fun poll(context: Context, armNext: Boolean = true): Outcome {
        // armNext is false only when the ticker is driving, which makes it the one
        // reliable signal of which half of the app is doing the work. Recorded rather
        // than logged: a phone in a pocket has no logcat attached, and "which path am I
        // on" was unanswerable for three releases.
        Health.recordPoll(context, if (armNext) Health.SOURCE_ALARM else Health.SOURCE_TICKER)
        val prefs = Prefs(context)
        val queue = PendingQueue(File(context.filesDir, "pending.json"))
        val janitor = Janitor(File(context.filesDir, "cleanup.json"))

        if (!prefs.notificationsEnabled || prefs.follows.isEmpty()) {
            queue.clear()
            cancel(context)
            return Outcome(
                active = false,
                nextWakeMillis = 0L,
                tickerIntervalMillis = 0L,
                cards = emptyList(),
            )
        }

        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val repo = SportsRepository(context)
        val (games, races) = repo.followedGames(now, zone)

        val store = SnapshotStore(File(context.filesDir, "snapshots.json"))
        val next = mutableMapOf<String, ScoreDiff.Snapshot>()
        val newEntries = mutableListOf<PendingQueue.Entry>()
        val delay = prefs.effectiveDelayMillis
        // Silenced teams stay in the feed and still get their snapshot kept up to date —
        // they simply produce no alerts. Keeping the snapshot fresh is what stops
        // un-silencing a team mid-game from firing a burst for everything it missed.
        val notifyKeys = prefs.notifyKeys
        synchronized(storeLock) {
        val previous = store.load()

        for (game in games) {
            val was = previous[game.id]
            val (stored, entries) = evaluateGame(context, game, was, prefs, now, zone, janitor, notifyKeys)
                ?: continue
            next[game.id] = stored
            newEntries += entries
        }

        // A race weekend is only ever worth one notification: the result.
        for (race in races) {
            val league = Leagues.byId(race.leagueId) ?: continue
            val key = "race:${race.id}"
            val snapshot = ScoreDiff.Snapshot(key, race.leagueId, race.state, null, null, 0)
            next[key] = snapshot
            val prev = previous[key]
            if (prev != null && prev.state != GameState.FINAL && race.state == GameState.FINAL) {
                janitor.schedule(key, now + CLEANUP_DELAY)
            }
            if ("${race.leagueId}:series" !in notifyKeys) continue
            if (prev != null && prev.state != GameState.FINAL && race.state == GameState.FINAL) {
                newEntries += PendingQueue.Entry(
                    dueAt = now + delay,
                    gameId = key,
                    leagueId = race.leagueId,
                    kind = ScoreDiff.Kind.FINAL,
                    title = AlertText.raceTitle(race),
                    body = AlertText.raceBody(race, league),
                    // A race has no score to put on the right; the podium is the detail.
                    label = "FINAL",
                    detail = AlertText.raceBody(race, league),
                    foot = league.short,
                )
            }
        }

        // Keep snapshots for games still in the window only; a scoreboard that has
        // rolled past a game shouldn't leave a snapshot behind to alert on next year.
        store.save(next)
        queue.add(newEntries)
        }

        val (due, waiting) = queue.takeDue(now)
        for (entry in due) {
            Notifier.post(context, entry)
            // The clock starts at the post, for every kind. Tracking only FINALs meant a
            // card whose full time was never observed -- a phone asleep, in Doze, or out of
            // signal at the whistle -- was invisible to the janitor forever, and once the
            // game left the scoreboard window its snapshot went too, so the transition
            // could never be seen again. Yesterday's score stayed on the lock screen.
            //
            // Every later post for the same game moves this out, and so does the game still
            // being live, so the rule is simply: an hour after the last thing worth saying.
            janitor.schedule(entry.gameId, now + CLEANUP_DELAY)
        }
        janitor.sweep(context, now)

        val scheduleWake = Feed.nextWakeMillis(
            games = games,
            nowMillis = now,
            liveIntervalMillis = LIVE_INTERVAL,
            leadMillis = LEAD,
            idleIntervalMillis = IDLE_INTERVAL,
        )
        val queueWake = waiting.minOfOrNull { it.dueAt }
        val cleanupWake = janitor.nextDue()
        val wake = minOf(scheduleWake, queueWake ?: Long.MAX_VALUE, cleanupWake ?: Long.MAX_VALUE)
        if (armNext) armAt(context, wake)

        // Only games that are allowed to interrupt count towards running the ticker. A
        // silenced team stays in the feed and gets its snapshot kept up to date above,
        // but it will not put a service and a card up for a game it is never going to
        // say anything about.
        val watched = games.filter { it.involves(notifyKeys) }
        // The relay socket follows the poll's view of what is on: it opens for the games
        // worth hearing about and closes when none is left. Silenced teams stay out, as
        // they do for the ticker.
        runCatching { LiveRelay.sync(context, watched, now) }
            .onFailure { Log.w(TAG, "relay sync failed", it) }
        return Outcome(
            active = prefs.liveUpdatesEnabled &&
                TickerPlan.shouldRun(watched, now, LEAD),
            nextWakeMillis = wake,
            tickerIntervalMillis = TickerPlan.intervalMillis(watched, now) {
                Leagues.byId(it.leagueId)?.kind
            },
            cards = liveCards(context, watched.filter { it.state == GameState.LIVE }, now),
        )
    }

    /**
     * The live cards, with the spoiler delay applied to the score rather than to its existence.
     *
     * Shared by the poll and by the relay's redraw so both draw the same figure: the store is
     * rolled forward here, once, and a card is never built from a score the other path has not
     * seen. See [ScoreHold].
     */
    fun liveCards(context: Context, live: List<Game>, now: Long): List<TickerPlan.Card> {
        val delay = Prefs(context).effectiveDelayMillis
        val shown = synchronized(holdLock) {
            runCatching {
                ScoreHold.release(File(context.filesDir, "score-hold.json"), live, delay, now)
            }.getOrDefault(emptyMap())
        }
        return TickerPlan.cards(live, { Leagues.byId(it.leagueId)?.kind }) { shown[it.id] }
    }

    /** The hold's file, like the snapshot store, is written from the poll and the socket both. */
    private val holdLock = Any()

    /**
     * One game through the diff: the snapshot to store and the alerts it produced.
     *
     * Shared by the poll (every game in the feed) and the relay (one game, the moment it
     * changes), so a score arriving over the socket is judged by exactly the rules a
     * polled one is — same loudness, same red-zone gate, same spoiler hold. Null when
     * the league is unknown.
     */
    private fun evaluateGame(
        context: Context,
        game: Game,
        was: ScoreDiff.Snapshot?,
        prefs: Prefs,
        now: Long,
        zone: ZoneId,
        janitor: Janitor,
        notifyKeys: Set<String>,
    ): Pair<ScoreDiff.Snapshot, List<PendingQueue.Entry>>? {
        val league = Leagues.byId(game.leagueId) ?: return null
        val delay = prefs.effectiveDelayMillis
        // Both "already said that" markers are carried in rather than reset, so they
        // survive a poll that produces no alert at all.
        val snapshot = ScoreDiff.snapshot(
            game,
            soonSent = was?.soonSent == true,
            markedPeriod = was?.markedPeriod ?: 0,
        ).copy(
            // The football markers too: a touchdown waiting for its kick, and whether
            // the late-and-close nudge has gone. Time-gated where it matters, so a
            // stale tdAt from a missed kick is inert rather than wrong.
            tdAt = was?.tdAt ?: 0L,
            tdText = was?.tdText,
            closeSaid = was?.closeSaid == true,
        )
        // **Advanced rather than stored raw.** The delay debounce counts consecutive OFF polls,
        // and the poll that increments that count is by definition one that produces no alert —
        // so storing the raw snapshot here would reset the count on every quiet poll and it
        // would never reach the threshold.
        var stored = ScoreDiff.advanced(was, snapshot)
        // A card for a game that is still going keeps its hour topped up, so a quiet
        // second half never loses the score. Everything else -- a game that has
        // finished, or one that has dropped out of the feed entirely -- lets the clock
        // run down and goes an hour after the last thing worth saying. Before the
        // silence filter, because a lingering card might be from before the team was
        // silenced and should still leave the shade.
        if (snapshot.state != GameState.FINAL && janitor.has(game.id)) {
            janitor.schedule(game.id, now + CLEANUP_DELAY)
        }
        // Kickoff. "Starting soon" has stopped being true, and with a league set to
        // final-only there is no later alert to replace the card -- so it sat in the
        // shade for the whole game, next to the score, reading as two notifications
        // about one match. Dropped here rather than left to expire, because it is not
        // stale, it is wrong.
        if (was?.state == GameState.PRE && snapshot.state == GameState.LIVE) {
            janitor.drop(context, game.id)
        }
        if (!game.involves(notifyKeys)) return stored to emptyList()
        // The red-zone nudge is for *your* team driving, not the other one's.
        val offenseKey = game.offense?.let { "${game.leagueId}:${it.teamId}" }
        val alerts = ScoreDiff.alerts(
            prev = was,
            now = snapshot,
            loudness = prefs.loudnessFor(league),
            notifyStarts = prefs.notifyStarts,
            nowMillis = now,
            leadMillis = LEAD,
            markPeriods = league.markPeriods && prefs.alertBreaks,
            redZoneWanted = league.kind == SportKind.FOOTBALL && prefs.alertRedZone &&
                offenseKey != null && offenseKey in notifyKeys,
            closeWanted = league.kind == SportKind.FOOTBALL && prefs.alertClose,
            regulationPeriods = TickerPlan.regulationPeriods(league.kind),
            closeMargin = TickerPlan.closeMargin(league.kind),
            notifyFinal = prefs.alertBreaks,
            sport = league.kind,
        )
        // The alert carries the snapshot to store, which is how the markers advance.
        alerts.firstOrNull()?.let { stored = it.snapshot }
        val entries = alerts.map { alert ->
            val card = AlertText.cardText(game, league, alert.kind, zone, alert.prev)
            // A touchdown seen at six points is waiting for its kick. Held a little
            // beyond the spoiler delay so the seven-point version replaces it rather
            // than following it -- see PendingQueue.Entry.createdAt.
            val patHold = alert.kind == ScoreDiff.Kind.SCORE &&
                alert.snapshot.tdAt > 0L && alert.snapshot.tdAt >= now
            PendingQueue.Entry(
                // A reminder is useless late, so only score news is delayed.
                dueAt = now + when (alert.kind) {
                    // A delay clearing isn't a score to protect from spoilers —
                    // it's the answer to "is it back on yet", which is useless late.
                    ScoreDiff.Kind.SOON, ScoreDiff.Kind.START, ScoreDiff.Kind.RESUMED -> 0L
                    ScoreDiff.Kind.SCORE -> if (patHold) maxOf(delay, PAT_HOLD) else delay
                    else -> delay
                },
                createdAt = now,
                // Being due is not the same as being still true, and a phone that was
                // asleep is the gap between them. See [PendingQueue.Entry.expiresAt].
                expiresAt = when (alert.kind) {
                    // At kickoff, exactly. After that it is not a reminder, it is a
                    // wrong statement about a game already under way.
                    ScoreDiff.Kind.SOON -> game.startMillis
                    // "They're under way" is worth knowing for a few minutes and not
                    // for an afternoon.
                    ScoreDiff.Kind.START, ScoreDiff.Kind.RESUMED -> now + FRESH_WINDOW
                    // A drive lasts minutes. "They're in the red zone" after the
                    // touchdown alert has landed is noise about the past.
                    ScoreDiff.Kind.REDZONE -> now + REDZONE_WINDOW
                    ScoreDiff.Kind.CLOSE -> now + FRESH_WINDOW
                    // A score is still a score whenever you read it.
                    else -> 0L
                },
                gameId = game.id,
                leagueId = game.leagueId,
                kind = alert.kind,
                // A card for a game in progress is ongoing; the whistle, a postponement and
                // the fifteen-minute warning all leave one that can be swiped away.
                live = game.state == GameState.LIVE && alert.kind != ScoreDiff.Kind.FINAL,
                title = card.title,
                body = card.body.orEmpty(),
                // The same words, cut where the card's design cuts them. See [GameCardText].
                label = card.kind,
                team = card.team,
                value = card.value,
                detail = card.detail,
                foot = card.foot,
                crestTeamId = card.crestTeamId,
                // The numbers behind the words, so the card's hold can tell a redraw that
                // agrees with this alert from one that has moved past it.
                away = game.away.score,
                home = game.home.score,
            )
        }
        return stored to entries
    }

    /**
     * One game, fresh off the relay socket. Runs the same diff as a poll and posts what is
     * due, without fetching anything: the socket already delivered the state. The snapshot
     * file is read and written under the same lock the poll uses, so a poll and a push
     * landing together cannot lose each other's markers.
     */
    fun pushUpdate(context: Context, game: Game) {
        val prefs = Prefs(context)
        if (!prefs.notificationsEnabled || prefs.follows.isEmpty()) return
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val queue = PendingQueue(File(context.filesDir, "pending.json"))
        val janitor = Janitor(File(context.filesDir, "cleanup.json"))
        val store = SnapshotStore(File(context.filesDir, "snapshots.json"))
        synchronized(storeLock) {
            val all = store.load().toMutableMap()
            val (stored, entries) = evaluateGame(
                context, game, all[game.id], prefs, now, zone, janitor, prefs.notifyKeys,
            ) ?: return
            all[game.id] = stored
            store.save(all)
            queue.add(entries)
        }
        Health.recordPoll(context, Health.SOURCE_RELAY)
        val (due, _) = queue.takeDue(now)
        for (entry in due) {
            Notifier.post(context, entry)
            janitor.schedule(entry.gameId, now + CLEANUP_DELAY)
        }
        janitor.sweep(context, now)
    }

    /** Guards the snapshot file between the poll and a relay push. */
    private val storeLock = Any()

    // ----------------------------------------------------------------- store

    /** Last seen state per game, as a flat JSON object keyed by game id. */
    class SnapshotStore(private val file: File) {

        fun load(): Map<String, ScoreDiff.Snapshot> {
            val text = runCatching { file.readText() }.getOrNull() ?: return emptyMap()
            val root = runCatching { JSONObject(text) }.getOrNull() ?: return emptyMap()
            val out = mutableMapOf<String, ScoreDiff.Snapshot>()
            for (key in root.keys()) {
                val o = root.optJSONObject(key) ?: continue
                val state = runCatching { GameState.valueOf(o.optString("state")) }.getOrNull()
                    ?: continue
                out[key] = ScoreDiff.Snapshot(
                    gameId = key,
                    leagueId = o.optString("league"),
                    state = state,
                    home = if (o.has("home")) o.optInt("home") else null,
                    away = if (o.has("away")) o.optInt("away") else null,
                    period = o.optInt("period"),
                    startMillis = o.optLong("start"),
                    soonSent = o.optBoolean("soon"),
                    markedPeriod = o.optInt("marked"),
                    // Persisted, and it has to be: the alarm path detects a delay in a broadcast
                    // receiver that dies seconds later, so a counter held only in memory would
                    // start again from zero at every poll and never confirm anything.
                    offPolls = o.optInt("offPolls"),
                    offAnnounced = o.optBoolean("offSaid"),
                    clock = o.optString("clock").takeIf { it.isNotEmpty() },
                    possession = o.optString("poss").takeIf { it.isNotEmpty() },
                    redZone = o.optBoolean("rz"),
                    closeSaid = o.optBoolean("closeSaid"),
                    tdAt = o.optLong("tdAt"),
                    tdText = o.optString("tdText").takeIf { it.isNotEmpty() },
                    lastPlay = o.optString("lastPlay").takeIf { it.isNotEmpty() },
                )
            }
            return out
        }

        fun save(map: Map<String, ScoreDiff.Snapshot>) {
            val root = JSONObject()
            for ((key, s) in map) {
                val o = JSONObject()
                    .put("league", s.leagueId)
                    .put("state", s.state.name)
                    .put("period", s.period)
                    .put("start", s.startMillis)
                    .put("soon", s.soonSent)
                    .put("marked", s.markedPeriod)
                    .put("offPolls", s.offPolls)
                    .put("offSaid", s.offAnnounced)
                    .put("rz", s.redZone)
                    .put("closeSaid", s.closeSaid)
                    .put("tdAt", s.tdAt)
                if (s.clock != null) o.put("clock", s.clock)
                if (s.possession != null) o.put("poss", s.possession)
                if (s.tdText != null) o.put("tdText", s.tdText)
                if (s.lastPlay != null) o.put("lastPlay", s.lastPlay)
                if (s.home != null) o.put("home", s.home)
                if (s.away != null) o.put("away", s.away)
                root.put(key, o)
            }
            runCatching { file.writeText(root.toString()) }
        }
    }
}

/**
 * The alarm lands here. `goAsync` plus a thread because the broadcast's own wakelock
 * is released the moment `onReceive` returns, which is well short of a network round
 * trip over a cold radio.
 */
class PollReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext

        // The ticker is already polling faster than this alarm ever could. Don't fetch
        // everything a second time — just keep an alarm in the diary, so a service the
        // system kills for memory is picked up within the quarter hour rather than never.
        if (LiveTicker.running) {
            ScoreWatcher.armAt(app, System.currentTimeMillis() + LiveTicker.BACKSTOP)
            return
        }

        val pending = goAsync()
        Thread {
            try {
                val outcome = ScoreWatcher.poll(app)
                // Something is on. Hand over to the foreground service, which is not
                // subject to the nine-minute Doze floor this alarm is.
                if (outcome.active) LiveTicker.start(app)
            } catch (t: Throwable) {
                Log.w("PollReceiver", "poll failed", t)
                // Never leave the chain broken: a failed poll still arms the next one.
                ScoreWatcher.armAt(app, System.currentTimeMillis() + 10 * 60 * 1000)
            } finally {
                pending.finish()
            }
        }.start()
    }
}

/** Alarms do not survive a reboot, so the chain is restarted here. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON"
        ) return
        ScoreWatcher.ensureArmed(context.applicationContext)
    }
}
