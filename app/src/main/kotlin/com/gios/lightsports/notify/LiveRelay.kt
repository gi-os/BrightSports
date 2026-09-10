package com.gios.lightsports.notify

import android.content.Context
import android.util.Log
import com.gios.lightsports.data.Prefs
import com.gios.lightsports.data.RelaySnapshot
import com.gios.lightsports.model.Game
import com.gios.lightsports.model.GameState
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.TimeUnit

/**
 * The push side of live scores: one websocket to the BasilNet relay's ntfy, subscribed to
 * `bs-<eventId>` for every followed game that is on or about to be.
 *
 * Process-wide and owned by nobody in particular. The ticker calls [sync] after each poll
 * and the screen calls it after each refresh; whichever is alive keeps the socket up, and
 * the socket lives exactly as long as there is a game worth listening to. A message
 * lands on the phone's copy of the game ([RelaySnapshot.apply]), goes through
 * [ScoreWatcher.pushUpdate] for alerts — the same rules as a poll — and out to whatever
 * screen is listening.
 *
 * What it does not do: fetch. It never learns about a game the poll has not already
 * fetched, so a dead relay degrades to the poll and nothing else.
 */
object LiveRelay {

    private const val TAG = "LiveRelay"

    /** Poll cadence for the ticker while the socket is up: a safety net, not the source. */
    const val SAFETY_INTERVAL = 5L * 60_000

    /** How long an open game screen waits between fetches while the socket is up. */
    const val SCREEN_INTERVAL = 60_000L

    @Volatile var connected: Boolean = false; private set
    @Volatile var lastMessageAt: Long = 0L; private set
    @Volatile var lastHeartbeatAt: Long = 0L; private set
    @Volatile private var lastId: String? = null

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            // A read timeout would kill an idle-but-healthy stream; ntfy sends a keepalive
            // every 45 s and OkHttp pings every 30, so silence is caught by both.
            .readTimeout(0, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
    }

    private val lock = Any()
    private var socket: WebSocket? = null
    private var topics: Set<String> = emptySet()
    private var appContext: Context? = null
    private var backoffMs = 2_000L
    private var reconnectThread: Thread? = null

    /** The phone's latest copy of each game the socket covers, by id. */
    private val games = mutableMapOf<String, Game>()
    private val listeners = CopyOnWriteArraySet<(Game) -> Unit>()

    fun addListener(l: (Game) -> Unit) { listeners += l }
    fun removeListener(l: (Game) -> Unit) { listeners -= l }

    /** The games the socket currently covers, newest state first. */
    fun current(): List<Game> = synchronized(lock) { games.values.toList() }

    /**
     * Tell the relay which games matter now. Live games and games starting inside
     * [ScoreWatcher.LEAD] get a topic; everything else is dropped. An unchanged topic set
     * keeps the socket; a changed one reconnects; an empty one closes it.
     */
    fun sync(context: Context, candidates: List<Game>, nowMillis: Long = System.currentTimeMillis()) {
        val app = context.applicationContext
        val prefs = Prefs(app)
        if (!prefs.relayEnabled || !prefs.liveUpdatesEnabled || !prefs.notificationsEnabled) {
            stop()
            return
        }
        val wanted = candidates.filter {
            it.state == GameState.LIVE ||
                (it.state == GameState.PRE && it.startMillis in nowMillis..(nowMillis + ScoreWatcher.LEAD))
        }
        val next = wanted.map { RelaySnapshot.topicFor(it.id) }.toSet() +
            (if (wanted.isEmpty()) emptySet() else setOf(RelaySnapshot.HEARTBEAT_TOPIC))
        synchronized(lock) {
            appContext = app
            // Fresh copies from the poll win over what the socket last said: the poll is the
            // whole truth and the socket is a stream of corrections to it.
            for (g in wanted) games[g.id] = g
            games.keys.retainAll(wanted.map { it.id }.toSet())
            if (next.isEmpty()) {
                closeLocked()
                return
            }
            if (next == topics && socket != null) return
            topics = next
            closeLocked()
            openLocked(prefs.relayUrl)
        }
    }

    fun stop() {
        synchronized(lock) {
            topics = emptySet()
            games.clear()
            closeLocked()
        }
    }

    private fun closeLocked() {
        socket?.let { runCatching { it.close(1000, "bye") } }
        socket = null
        connected = false
    }

    private fun openLocked(baseUrl: String) {
        val base = baseUrl.trimEnd('/').replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
        // A reconnect asks for what it missed; a fresh subscription asks for the last few
        // minutes so a phone that comes up mid-drive is told where the ball is.
        val since = lastId ?: "10m"
        val url = "$base/${topics.sorted().joinToString(",")}/ws?since=$since"
        Log.d(TAG, "open ${topics.size} topics")
        val request = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Android) BrightSports")
            .build()
        socket = client.newWebSocket(request, Listener(url))
    }

    private class Listener(private val url: String) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(lock) { if (socket !== webSocket) return }
            connected = true
            backoffMs = 2_000L
            Health.recordRelay(appContext, connected = true)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            synchronized(lock) { if (socket !== webSocket) return }
            lastMessageAt = System.currentTimeMillis()
            val (topic, body) = RelaySnapshot.unwrap(text) ?: return
            runCatching { org.json.JSONObject(text).optString("id") }.getOrNull()
                ?.takeIf { it.isNotEmpty() }?.let { lastId = it }
            if (topic == RelaySnapshot.HEARTBEAT_TOPIC) {
                lastHeartbeatAt = lastMessageAt
                return
            }
            val id = RelaySnapshot.gameId(body) ?: return
            val updated = synchronized(lock) {
                val current = games[id] ?: return
                val next = RelaySnapshot.apply(current, body)
                if (next === current) return
                games[id] = next
                next
            }
            val ctx = appContext
            if (ctx != null) {
                runCatching { ScoreWatcher.pushUpdate(ctx, updated) }
                    .onFailure { Log.w(TAG, "push failed", it) }
            }
            for (l in listeners) runCatching { l(updated) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "socket failed: ${t.message}")
            dropped(webSocket)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            dropped(webSocket)
        }

        private fun dropped(webSocket: WebSocket) {
            synchronized(lock) {
                if (socket !== webSocket) return
                socket = null
                connected = false
                Health.recordRelay(appContext, connected = false)
                if (topics.isEmpty()) return
                // Reconnect with a doubling wait, capped at a minute. The ticker's own
                // safety poll runs underneath, so a relay that stays down costs latency
                // and nothing else.
                val wait = backoffMs
                backoffMs = minOf(backoffMs * 2, 60_000L)
                val t = Thread {
                    try { Thread.sleep(wait) } catch (_: InterruptedException) { return@Thread }
                    synchronized(lock) {
                        if (socket == null && topics.isNotEmpty()) {
                            appContext?.let { openLocked(Prefs(it).relayUrl) }
                        }
                    }
                }.apply { isDaemon = true }
                reconnectThread = t
                t.start()
            }
        }
    }
}
