package com.gios.lightsports.data

import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField

/**
 * Timestamp parsing across three providers that each spell ISO 8601 differently.
 *
 * ESPN omits the seconds ("2026-07-29T16:10Z"), which the stock `ISO_INSTANT`
 * parser rejects on Android's older java.time; StatsAPI includes them; HockeyTech
 * sends a numeric offset instead of Z. One lenient formatter covers all three.
 */
object Iso {

    private val FORMAT: DateTimeFormatter = DateTimeFormatterBuilder()
        .appendPattern("yyyy-MM-dd'T'HH:mm")
        .optionalStart().appendLiteral(':').appendValue(ChronoField.SECOND_OF_MINUTE, 2).optionalEnd()
        .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true).optionalEnd()
        .appendOffsetId()
        .toFormatter()

    /** Epoch millis, or 0 when the provider sends something unparseable. */
    fun millis(text: String?): Long {
        if (text.isNullOrEmpty()) return 0L
        return runCatching {
            OffsetDateTime.parse(text, FORMAT).toInstant().toEpochMilli()
        }.getOrElse { 0L }
    }
}
