package com.gios.lightsports.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.gios.lightsports.MainActivity
import com.gios.lightsports.R
import com.gios.lightsports.data.Crests

/**
 * Posting the notifications.
 *
 * LightOS renders plain Android notifications — no custom surface, no special API —
 * so a stock NotificationCompat-style builder at importance DEFAULT shows up in the
 * shade exactly like a message from Chat does. Two channels so scores can be muted
 * without losing "your game is starting".
 */
object Notifier {

    /**
     * The one channel every game card is posted on.
     *
     * There used to be three — scores, schedule, live — and a game moved between them as it
     * went from "starts in 15 minutes" to a live card to a final. That was fine while each of
     * those was a different notification. It is not fine now that a game has exactly one card
     * from the reminder to the whistle: re-posting an id on a different channel is a cancel
     * and a fresh post, which throws away the card's place in the shade and its "already
     * alerted" state. One card, one channel, for the life of the game.
     *
     * IMPORTANCE_DEFAULT so the platform shows it on the lock screen and BrightControl's face
     * lets it past the importance gate, and silent by construction — no sound, no vibration —
     * because the buzz and the box in [ScoreAlert] own the interruption and can rate-limit it
     * across a burst. The platform heads-up is not wanted either; the app draws its own.
     */
    const val CHANNEL_GAME = "game"

    /** Retired. Deleted on upgrade so the shade's channel list matches what the app posts. */
    private val RETIRED_CHANNELS = listOf("scores", "schedule", "live")

    /**
     * The one-boolean contract with BrightControl's lock face.
     *
     * A foreground service's notification carries `FLAG_ONGOING_EVENT` and
     * `FLAG_FOREGROUND_SERVICE`, and BrightControl's lock face drops both on sight —
     * rightly, since that is what a sync, a download and a VPN look like, and a lock screen
     * full of receipts is the thing that filter exists to prevent. This card is not a
     * receipt: it *is* the score, and the lock screen is where a score is worth having.
     * This extra is the app saying so. BrightControl keeps an ongoing card that sets it and
     * exempts that one card from the flag and importance rules; every other app's permanent
     * notice is unaffected. A phone without BrightControl ignores it.
     */
    const val EXTRA_LOCK_KEEP = "com.gios.lightcontrol.extra.LOCK_KEEP"

    /**
     * The card's design, in five strings, for whoever draws the notification themselves.
     *
     * The shade gets an ordinary title and text and always will — every other launcher, and
     * every phone without BrightControl, shows those. These are the same words cut where the
     * design cuts them, so BrightControl's lock face and banner can lay a score out the way
     * the app's own box does instead of parsing a sentence back apart:
     *
     *     TD  (crest) SEA                        NE 7 · SEA 14     KIND / TEAM  ·  VALUE
     *     K. Walker 12 yd run · Myers kick good                    DETAIL
     *     Q2 3:24                                                  FOOT
     *
     * Anything that does not know the keys ignores them. See `SportsCard` in BrightControl.
     */
    const val EXTRA_KIND = "com.gios.lightcontrol.extra.SPORT_KIND"
    const val EXTRA_TEAM = "com.gios.lightcontrol.extra.SPORT_TEAM"
    const val EXTRA_VALUE = "com.gios.lightcontrol.extra.SPORT_VALUE"
    const val EXTRA_DETAIL = "com.gios.lightcontrol.extra.SPORT_DETAIL"
    const val EXTRA_FOOT = "com.gios.lightcontrol.extra.SPORT_FOOT"

    /**
     * How long an alert owns the card before the live updates take it back.
     *
     * The card is one thing saying two: what just happened, and where the game is. A
     * touchdown lands, and a second later the relay reports the kickoff that followed it —
     * without this the "TD SEA" line would be replaced by "Patriots 7 · Seahawks 21 · Q2"
     * before the phone was out of a pocket. Ninety seconds is long enough to read and short
     * enough that the card is never stale about a game in progress.
     */
    private const val ALERT_STICKY = 90_000L

    /** When each game's card last carried an alert, so a live update does not step on it. */
    private val alertAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_GAME, "Games", NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "One card per game: the score while it is on, and what just happened"
                enableVibration(false)
                setSound(null, null)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
        // Deleting a channel cancels whatever it still holds, which on an upgrade is the old
        // ticker card -- and the next poll posts the new one a moment later.
        for (old in RETIRED_CHANNELS) runCatching { manager.deleteNotificationChannel(old) }
    }

    /**
     * One game's card: the same notification whether it is drawing the live score or the
     * touchdown that just landed.
     *
     * @param ongoing true while the game is on. An ongoing card cannot be swiped away, which
     *   is right for a game in progress and wrong the moment it ends — see [settle].
     * @param lockKeep whether to ask BrightControl's lock face to keep it. Only the game the
     *   ticker is running in front of asks: four ongoing rows would be the whole face.
     */
    fun gameCard(
        context: Context,
        gameId: String,
        leagueId: String?,
        text: GameCardText,
        ongoing: Boolean,
        lockKeep: Boolean = ongoing,
    ): Notification {
        ensureChannels(context)
        val open = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_GAME_ID, gameId)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (leagueId != null) open.putExtra(MainActivity.EXTRA_LEAGUE_ID, leagueId)
        val tap = PendingIntent.getActivity(
            context,
            cardId(gameId),
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(context, CHANNEL_GAME)
            .setSmallIcon(R.drawable.ic_stat_score)
            .setContentTitle(text.title)
            .setContentIntent(tap)
            .setOngoing(ongoing)
            // A finished game is worth a timestamp; a card that has been up for two hours
            // saying "2:04 PM" over a live score reads as a stale notification.
            .setShowWhen(!ongoing)
            .setAutoCancel(!ongoing)
            // The card is redrawn every time the ball moves. The alert is the app's own
            // buzz and box, so the platform must never treat a redraw as news.
            .setOnlyAlertOnce(true)
            // A score is not private. Without this the platform may redact the card on a
            // secured lock screen, which is the one place it is most worth reading.
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            // Running information, not a service the user should have to think about.
            // Deliberately not CATEGORY_SERVICE: BrightControl's lock face reads that as a
            // permanent notice and drops it.
            .setCategory(Notification.CATEGORY_STATUS)
        if (!text.body.isNullOrBlank()) {
            builder.setContentText(text.body)
            // The same words again, expanded, so a play description is not cut at one line
            // in the shade. Title and text stay as plain extras on purpose: BrightControl's
            // banner and lock face read EXTRA_TITLE / EXTRA_TEXT, and a style that carries
            // the words elsewhere would draw a blank box there.
            builder.setStyle(Notification.BigTextStyle().bigText(text.title + "\n" + text.body))
        }
        // The crest, when one has already been fetched for the feed. Best effort and never a
        // download: this runs on whatever thread a score arrived on.
        val crest = text.crestTeamId?.let { Crests.bitmap(context, leagueId, it) }
        if (crest != null) builder.setLargeIcon(crest)
        // Set last: Builder.build() copies the extras it owns over this bundle, so a value
        // written afterwards is never seen.
        if (lockKeep) builder.extras.putBoolean(EXTRA_LOCK_KEEP, true)
        text.kind?.let { builder.extras.putString(EXTRA_KIND, it) }
        text.team?.let { builder.extras.putString(EXTRA_TEAM, it) }
        text.value?.let { builder.extras.putString(EXTRA_VALUE, it) }
        text.detail?.let { builder.extras.putString(EXTRA_DETAIL, it) }
        text.foot?.let { builder.extras.putString(EXTRA_FOOT, it) }
        return builder.build()
    }

    /**
     * Draw a game's live state, unless an alert is still on the card.
     *
     * Called on every poll and on every relay message, which is several times a minute — so
     * it is silent, it never re-alerts, and it stands aside for [ALERT_STICKY] after
     * something worth a buzz happened.
     */
    fun updateGameCard(
        context: Context,
        gameId: String,
        leagueId: String?,
        text: GameCardText,
        ongoing: Boolean,
        lockKeep: Boolean = ongoing,
    ) {
        val recent = alertAt[gameId] ?: 0L
        if (System.currentTimeMillis() - recent < ALERT_STICKY) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            manager.notify(cardId(gameId), gameCard(context, gameId, leagueId, text, ongoing, lockKeep))
        }
    }

    /**
     * Take the ongoing flag off the cards a stopped ticker leaves behind.
     *
     * An ongoing card cannot be swiped away and the platform refuses to cancel it, so a game
     * that ends while the phone is asleep would otherwise leave a card nothing could clear —
     * not the user, not [Janitor]. Re-posted with the same words and no flag.
     */
    fun settleGameCards(context: Context, gameIds: Collection<String>) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val live = runCatching { manager.activeNotifications }.getOrNull() ?: return
        for (id in gameIds) {
            val card = live.firstOrNull { it.id == cardId(id) } ?: continue
            if (card.notification.flags and Notification.FLAG_ONGOING_EVENT == 0) continue
            val extras = card.notification.extras
            // Re-posted with what it already said, design and all: this is the same card
            // losing a flag, not a new one.
            runCatching {
                manager.notify(
                    cardId(id),
                    gameCard(
                        context,
                        gameId = id,
                        leagueId = null,
                        text = GameCardText(
                            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
                            body = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
                            kind = extras.getString(EXTRA_KIND),
                            team = extras.getString(EXTRA_TEAM),
                            value = extras.getString(EXTRA_VALUE),
                            detail = extras.getString(EXTRA_DETAIL),
                            foot = extras.getString(EXTRA_FOOT),
                        ),
                        ongoing = false,
                    ),
                )
            }
        }
    }

    fun post(context: Context, entry: PendingQueue.Entry) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannels(context)
        // One card per game, from the fifteen-minute warning to the final whistle. An alert
        // is not a second notification about a game already in the shade; it is that card,
        // saying what just happened.
        alertAt[entry.gameId] = System.currentTimeMillis()
        runCatching {
            manager.notify(
                cardId(entry.gameId),
                gameCard(
                    context,
                    gameId = entry.gameId,
                    leagueId = entry.leagueId,
                    text = entry.card(),
                    // A final, a postponement and a kickoff reminder are all news about a game
                    // that is not running: those cards must be clearable.
                    ongoing = entry.live,
                ),
            )
        }

        // The notification is the record; the box is the alert. Raised after, so a
        // failure to draw it still leaves the score in the shade.
        ScoreAlert.show(context, entry)
    }

    /** The id every alert for one game shares, so a newer card replaces the older one. */
    fun cardId(gameId: String): Int = gameId.hashCode()

    private const val TICKER_REQUEST = 0x5D07
}
