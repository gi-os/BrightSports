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
import com.gios.lightsports.model.GameState
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

    // ------------------------------------------------------------- scheduling

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        1,
        Intent(context, PollReceiver::class.java).setAction(ACTION_POLL),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * `setAndAllowWhileIdle` rather than `setExactAndAllowWhileIdle`: inexact is the
     * variant that needs no SCHEDULE_EXACT_ALARM permission, and a score notification
     * is already being held back on purpose.
     */
    fun armAt(context: Context, triggerAtMillis: Long) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val at = maxOf(triggerAtMillis, System.currentTimeMillis() + 30_000)
        runCatching {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent(context))
        }.onFailure { Log.w(TAG, "could not arm alarm", it) }
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
        val lines: List<String>,
    )

    // ------------------------------------------------------------------ poll

    /**
     * Blocking. Call from a background thread that holds a wakelock.
     *
     * @param armNext whether to schedule the next alarm before returning. False when the
     * ticker is driving, since it sleeps on its own clock and a second alarm firing
     * underneath it would poll everything twice.
     */
    fun poll(context: Context, armNext: Boolean = true): Outcome {
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
                lines = emptyList(),
            )
        }

        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val repo = SportsRepository(context)
        val (games, races) = repo.followedGames(now, zone)

        val store = SnapshotStore(File(context.filesDir, "snapshots.json"))
        val previous = store.load()
        val next = mutableMapOf<String, ScoreDiff.Snapshot>()
        val newEntries = mutableListOf<PendingQueue.Entry>()
        val delay = prefs.effectiveDelayMillis

        // Silenced teams stay in the feed and still get their snapshot kept up to date —
        // they simply produce no alerts. Keeping the snapshot fresh is what stops
        // un-silencing a team mid-game from firing a burst for everything it missed.
        val notifyKeys = prefs.notifyKeys

        for (game in games) {
            val league = Leagues.byId(game.leagueId) ?: continue
            val was = previous[game.id]
            // Both "already said that" markers are carried in rather than reset, so they
            // survive a poll that produces no alert at all.
            val snapshot = ScoreDiff.snapshot(
                game,
                soonSent = was?.soonSent == true,
                markedPeriod = was?.markedPeriod ?: 0,
            )
            // **Advanced rather than stored raw.** The delay debounce counts consecutive OFF polls,
            // and the poll that increments that count is by definition one that produces no alert —
            // so storing the raw snapshot here would reset the count on every quiet poll and it
            // would never reach the threshold.
            next[game.id] = ScoreDiff.advanced(was, snapshot)
            // Scheduled on the FINAL *transition*, before the silence filter: the
            // lingering card might be an earlier score alert from before the team
            // was silenced, and it should still leave the shade an hour after the
            // game ends. Cancelling a notification that never existed is a no-op.
            if (was != null && was.state != GameState.FINAL && snapshot.state == GameState.FINAL) {
                janitor.schedule(game.id, now + CLEANUP_DELAY)
            }
            if (!game.involves(notifyKeys)) continue
            val alerts = ScoreDiff.alerts(
                prev = was,
                now = snapshot,
                loudness = league.loudness,
                notifyStarts = prefs.notifyStarts,
                nowMillis = now,
                leadMillis = LEAD,
                markPeriods = league.markPeriods,
            )
            // The alert carries the snapshot to store, which is how the markers advance.
            alerts.firstOrNull()?.let { next[game.id] = it.snapshot }
            for (alert in alerts) {
                newEntries += PendingQueue.Entry(
                    // A reminder is useless late, so only score news is delayed.
                    dueAt = now + when (alert.kind) {
                        // A delay clearing isn't a score to protect from spoilers —
                        // it's the answer to "is it back on yet", which is useless late.
                        ScoreDiff.Kind.SOON, ScoreDiff.Kind.START, ScoreDiff.Kind.RESUMED -> 0L
                        else -> delay
                    },
                    gameId = game.id,
                    leagueId = game.leagueId,
                    kind = alert.kind,
                    title = AlertText.title(game, alert.kind),
                    body = AlertText.body(game, league, alert.kind, zone),
                )
            }
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
                )
            }
        }

        // Keep snapshots for games still in the window only; a scoreboard that has
        // rolled past a game shouldn't leave a snapshot behind to alert on next year.
        store.save(next)
        queue.add(newEntries)

        val (due, waiting) = queue.takeDue(now)
        for (entry in due) {
            Notifier.post(context, entry)
            // A FINAL held back by the spoiler delay can post after the hour the
            // transition started; rescheduling from the post gives the card its
            // full hour in the shade either way.
            if (entry.kind == ScoreDiff.Kind.FINAL) {
                janitor.schedule(entry.gameId, now + CLEANUP_DELAY)
            }
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
        val showScores = !prefs.delayEnabled
        return Outcome(
            active = prefs.liveUpdatesEnabled &&
                TickerPlan.shouldRun(watched, now, LEAD),
            nextWakeMillis = wake,
            tickerIntervalMillis = TickerPlan.intervalMillis(watched, now) {
                Leagues.byId(it.leagueId)?.kind
            },
            lines = watched.filter { it.state == GameState.LIVE }.map {
                TickerPlan.line(it, Leagues.byId(it.leagueId)?.kind, showScores)
            },
        )
    }

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
