package com.gios.lightsports.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Date and time strings, short enough for a 3.9" screen. */
object Fmt {

    private val time = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private val dayTime = DateTimeFormatter.ofPattern("EEE h:mm a", Locale.US)
    private val dayDate = DateTimeFormatter.ofPattern("EEE MMM d", Locale.US)
    private val clockOnly = DateTimeFormatter.ofPattern("h:mm", Locale.US)

    fun time(millis: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(millis).atZone(zone).format(time)

    fun dayTime(millis: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(millis).atZone(zone).format(dayTime)

    fun dayDate(millis: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(millis).atZone(zone).format(dayDate)

    fun clock(millis: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(millis).atZone(zone).format(clockOnly)

    /** "in 3 days", "in 2 hr", "in 40 min" — how long until kickoff. */
    fun until(millis: Long, nowMillis: Long): String {
        val minutes = (millis - nowMillis) / 60_000
        return when {
            minutes < 1 -> "now"
            minutes < 60 -> "in $minutes min"
            minutes < 60 * 36 -> "in ${(minutes + 30) / 60} hr"
            else -> "in ${(minutes + 12 * 60) / (24 * 60)} days"
        }
    }

    /** "updated 2 min ago", for the one line that says whether to trust the screen. */
    fun ago(millis: Long, nowMillis: Long): String {
        if (millis <= 0) return ""
        val seconds = (nowMillis - millis) / 1000
        return when {
            seconds < 45 -> "just now"
            seconds < 90 -> "1 min ago"
            seconds < 3600 -> "${seconds / 60} min ago"
            seconds < 7200 -> "1 hr ago"
            else -> "${seconds / 3600} hr ago"
        }
    }
}
