package com.gios.lightsports.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.gios.lightsports.MainActivity
import com.gios.lightsports.R

/**
 * Posting the notifications.
 *
 * LightOS renders plain Android notifications — no custom surface, no special API —
 * so a stock NotificationCompat-style builder at importance DEFAULT shows up in the
 * shade exactly like a message from Chat does. Two channels so scores can be muted
 * without losing "your game is starting".
 */
object Notifier {

    const val CHANNEL_SCORES = "scores"
    const val CHANNEL_SCHEDULE = "schedule"
    const val CHANNEL_LIVE = "live"

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

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        // Vibration off on both channels: the box in ScoreAlert owns the buzz, so it can
        // be rate-limited across a burst and still fire when the box can't be shown.
        // Importance DEFAULT, not HIGH — the platform heads-up is not wanted here, the
        // app draws its own.
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SCORES, "Scores", NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Score changes and final results"
                enableVibration(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SCHEDULE, "Game starts", NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Reminders when a followed team is about to play"
                enableVibration(false)
            },
        )
        // IMPORTANCE_LOW: this card is the receipt for a foreground service, not news.
        // It must be visible — that is the deal a foreground service makes — but it
        // should never be the reason the phone lights up. The alerts do that.
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_LIVE, "Live updates", NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shown while a followed game is in progress"
                enableVibration(false)
                setShowBadge(false)
            },
        )
    }

    /**
     * The ongoing card the live ticker runs under.
     *
     * @param lines one per live game, already written by [TickerPlan.line] — which is
     * where the decision about whether they carry a score lives.
     */
    fun tickerNotification(
        context: Context,
        title: String,
        lines: List<String>,
        detail: String? = null,
        gameId: String? = null,
        leagueId: String? = null,
    ): Notification {
        ensureChannels(context)
        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        // One game live: the card opens that game. Several: the feed, which is where you
        // would have to choose anyway.
        if (gameId != null) {
            open.putExtra(MainActivity.EXTRA_GAME_ID, gameId)
            if (leagueId != null) open.putExtra(MainActivity.EXTRA_LEAGUE_ID, leagueId)
        }
        val tap = PendingIntent.getActivity(
            context,
            TICKER_REQUEST,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // The rest of the games on a doubleheader evening; otherwise the situation line.
        val second = if (lines.size > 1) lines.drop(1).joinToString(" · ") else detail.orEmpty()
        val expanded = (lines + listOfNotNull(detail.takeIf { lines.size <= 1 })).joinToString("\n")
        val builder = Notification.Builder(context, CHANNEL_LIVE)
            .setSmallIcon(R.drawable.ic_stat_score)
            .setContentTitle(lines.firstOrNull() ?: title)
            .setContentIntent(tap)
            .setOngoing(true)
            // No timestamp: a card that has been up for two hours saying "2:04 PM" reads
            // as a stale notification rather than a running one.
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            // A score is not private. Without this the platform may redact the card on a
            // secured lock screen, which is the one place it is most worth reading.
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            // Running information, not a service the user should have to think about.
            // Deliberately not CATEGORY_SERVICE: BrightControl's lock face reads that as a
            // permanent notice and drops it.
            .setCategory(Notification.CATEGORY_STATUS)
        if (second.isNotEmpty()) {
            builder.setContentText(second)
            builder.setStyle(Notification.BigTextStyle().bigText(expanded))
        }
        // The lock-face contract. Set last, because Builder.build() copies the extras it
        // owns over this bundle and a value written after the build is never seen.
        builder.extras.putBoolean(EXTRA_LOCK_KEEP, true)
        return builder.build()
    }

    /**
     * Redraw the ticker card in place. Silent by channel, so it never re-alerts.
     *
     * @param detail the situation line, drawn under the score when one game is live.
     * @param gameId the game the card opens, when exactly one game is live.
     */
    fun updateTicker(
        context: Context,
        id: Int,
        lines: List<String>,
        detail: String? = null,
        gameId: String? = null,
        leagueId: String? = null,
    ) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.notify(id, tickerNotification(context, "Live", lines, detail, gameId, leagueId))
    }

    fun post(context: Context, entry: PendingQueue.Entry) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannels(context)

        val channel = when (entry.kind) {
            ScoreDiff.Kind.SOON, ScoreDiff.Kind.START, ScoreDiff.Kind.OFF,
            ScoreDiff.Kind.RESUMED,
            -> CHANNEL_SCHEDULE
            else -> CHANNEL_SCORES
        }
        val tap = PendingIntent.getActivity(
            context,
            entry.gameId.hashCode(),
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_GAME_ID, entry.gameId)
                .putExtra(MainActivity.EXTRA_LEAGUE_ID, entry.leagueId)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_score)
            .setContentTitle(entry.title)
            .setContentText(entry.body)
            // The same words again, expanded, so a play description is not cut at one
            // line in the shade. Title and text stay as plain extras on purpose:
            // BrightControl's banner and lock face read EXTRA_TITLE / EXTRA_TEXT, and a
            // style that carries the words elsewhere would draw a blank box there.
            .setStyle(Notification.BigTextStyle().bigText(entry.body))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setShowWhen(true)
            .setOnlyAlertOnce(false)
            .build()

        // One notification id per game, so a second score replaces the first rather
        // than stacking six cards for one baseball game.
        manager.notify(cardId(entry.gameId), notification)

        // The notification is the record; the box is the alert. Raised after, so a
        // failure to draw it still leaves the score in the shade.
        ScoreAlert.show(context, entry)
    }

    /** The id every alert for one game shares, so a newer card replaces the older one. */
    fun cardId(gameId: String): Int = gameId.hashCode()

    private const val TICKER_REQUEST = 0x5D07
}
