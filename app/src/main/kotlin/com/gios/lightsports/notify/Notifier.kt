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

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        // Importance DEFAULT, not HIGH: a score is worth the shade and a buzz, not a
        // box thrown over whatever is on screen.
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SCORES, "Scores", NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "Score changes and final results" },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SCHEDULE, "Game starts", NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "Reminders when a followed team is about to play" },
        )
    }

    fun post(context: Context, entry: PendingQueue.Entry) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannels(context)

        val channel = when (entry.kind) {
            ScoreDiff.Kind.START, ScoreDiff.Kind.OFF -> CHANNEL_SCHEDULE
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
    }
}
