package com.gios.lightsports.notify

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat

/**
 * The foreground half of the polling story: the thing that runs while a game is on.
 *
 * The alarm chain in [ScoreWatcher] is the only mechanism that survives Doze, and it
 * pays for that with latency. `setAndAllowWhileIdle` is throttled to roughly one firing
 * every nine minutes once the screen has been off a while, so a two-minute interval was
 * two minutes on paper and nine in the pocket — a run scored in the first at bat of an
 * inning could land after the inning was over, and the spoiler delay stacked on top of
 * that.
 *
 * A foreground service is not throttled. So: alarms keep the schedule, and the moment
 * something a followed team is actually in kicks off, this takes over and polls on
 * [TickerPlan]'s cadence — a minute normally, thirty seconds in a close finish. It stops
 * itself at the final whistle and hands the chain back. Nothing changes anywhere else:
 * the idle interval is still three hours, and an evening with no games costs exactly
 * what it did before.
 *
 * The card in the shade is the price of admission — a foreground service must be
 * visible. It is at least useful: it carries the live score, unless the spoiler delay is
 * on, in which case it carries the matchup and nothing that would give the game away.
 */
class LiveTicker : Service() {

    // java.lang.Object explicitly: the sleep between polls has to be interruptible, and
    // wait/notify is the only monitor Kotlin exposes without pulling in coroutines for a
    // loop that is already on its own thread.
    private val gate = java.lang.Object()

    @Volatile private var stopping = false
    private var worker: Thread? = null
    private var startedAt = 0L

    /** Where the alarm chain should resume when this stops. */
    @Volatile private var handBackAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Already ticking. A second start is an ordinary event — the alarm backstop, the
        // app being opened, a poll that found a second game — and means nothing new.
        if (worker != null) return START_STICKY

        val promoted = runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                Notifier.tickerNotification(this, "Checking scores", emptyList()),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    0
                },
            )
        }.isSuccess

        // Android 12 onwards refuses a foreground service started from the background
        // outside a short list of exemptions. Nothing here is worth crashing over: the
        // alarm chain is still armed and still works, it is just slower, which is
        // precisely the state this app shipped in until now.
        if (!promoted) {
            Log.w(TAG, "not allowed to go foreground; leaving it to the alarm chain")
            stopSelf()
            return START_NOT_STICKY
        }

        running = true
        startedAt = System.currentTimeMillis()
        val app = applicationContext
        worker = Thread { loop(app) }.apply { isDaemon = true; start() }
        return START_STICKY
    }

    /**
     * Poll, show, sleep, repeat — on its own thread, because every iteration blocks on
     * the network and a service has no thread of its own.
     */
    private fun loop(context: Context) {
        val power = context.getSystemService(PowerManager::class.java)
        // The service keeps the process alive; it does not keep the CPU awake between
        // ticks. Held across the fetch only, and time-limited so a wedged socket cannot
        // leak it.
        val wake = power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lightsports:ticker")
            ?.apply { setReferenceCounted(false) }
        var failures = 0

        while (!stopping) {
            val outcome = runCatching {
                runCatching { wake?.acquire(WAKELOCK_TIMEOUT) }
                try {
                    ScoreWatcher.poll(context, armNext = false)
                } finally {
                    runCatching { if (wake?.isHeld == true) wake.release() }
                }
            }.getOrElse {
                Log.w(TAG, "poll failed", it)
                null
            }

            if (outcome == null) {
                // A single failure is a subway tunnel. Several in a row is something this
                // loop cannot fix by trying harder, and the alarm chain retries cheaper.
                if (++failures >= MAX_FAILURES) {
                    handBackAt = System.currentTimeMillis() + TickerPlan.WARMUP_INTERVAL
                    break
                }
                sleep(TickerPlan.LIVE_INTERVAL)
                continue
            }
            failures = 0
            handBackAt = outcome.nextWakeMillis

            // Nothing left in progress, or this has been up long enough to be suspicious.
            if (!outcome.active || TickerPlan.expired(startedAt, System.currentTimeMillis())) break

            runCatching {
                Notifier.updateTicker(context, NOTIFICATION_ID, outcome.lines)
            }
            sleep(outcome.tickerIntervalMillis)
        }
        stopSelf()
    }

    /** Interruptible: stopping the service should not wait out a sleep. */
    private fun sleep(millis: Long) {
        synchronized(gate) {
            if (stopping) return
            runCatching { gate.wait(millis.coerceAtLeast(1_000L)) }
        }
    }

    override fun onDestroy() {
        stopping = true
        synchronized(gate) { gate.notifyAll() }
        running = false
        worker = null
        // Whatever happens, the chain goes back on. This is the one line that decides
        // whether a killed service is a slower app or a silent one.
        val app = applicationContext
        val at = handBackAt.takeIf { it > 0L } ?: (System.currentTimeMillis() + BACKSTOP)
        ScoreWatcher.armAt(app, at)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "LiveTicker"
        private const val NOTIFICATION_ID = 0x5D07
        private const val WAKELOCK_TIMEOUT = 45_000L
        private const val MAX_FAILURES = 5

        /**
         * How far out the alarm is pushed while the ticker has the wheel. Short enough
         * that a service killed for memory is noticed within a quarter of an hour, long
         * enough that the two are not polling on top of each other.
         */
        const val BACKSTOP = 15L * 60_000

        /**
         * Read from the alarm receiver to decide whether to poll at all. A stale `true`
         * costs one skipped alarm poll; the backstop re-arms either way.
         */
        @Volatile
        var running: Boolean = false
            private set

        /** Never throws: this is an optimisation, and the alarm chain is the floor. */
        fun start(context: Context) {
            if (running) return
            runCatching {
                context.startForegroundService(Intent(context, LiveTicker::class.java))
            }.onFailure { Log.w(TAG, "could not start", it) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, LiveTicker::class.java)) }
        }
    }
}
