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
    ): Notification {
        ensureChannels(context)
        val tap = PendingIntent.getActivity(
            context,
            TICKER_REQUEST,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(context, CHANNEL_LIVE)
            .setSmallIcon(R.drawable.ic_stat_score)
            .setContentTitle(lines.firstOrNull() ?: title)
            .setContentIntent(tap)
            .setOngoing(true)
            // No timestamp: a card that has been up for two hours saying "2:04 PM" reads
            // as a stale notification rather than a running one.
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
        // The first line is the headline; the rest only exist on a doubleheader evening.
        if (lines.size > 1) {
            builder.setContentText(lines.drop(1).joinToString(" · "))
            builder.setStyle(Notification.BigTextStyle().bigText(lines.joinToString("\n")))
        }
        return builder.build()
    }

    /** Redraw the ticker card in place. Silent by channel, so it never re-alerts. */
    fun updateTicker(context: Context, id: Int, lines: List<String>) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.notify(id, tickerNotification(context, "Live", lines))
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
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setShowWhen(true)
            .setOnlyAlertOnce(false)
            .build()

        // One notification id per game, so a second score replaces the first rather
        // than stacking six cards for one baseball game.
        manager.notify(entry.gameId.hashCode(), notification)

        // The notification is the record; the box is the alert. Raised after, so a
        // failure to draw it still leaves the score in the shade.
        ScoreAlert.show(context, entry)
    }

    private const val TICKER_REQUEST = 0x5D07
}
