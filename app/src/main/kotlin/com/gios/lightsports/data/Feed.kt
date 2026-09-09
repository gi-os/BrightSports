package com.gios.lightsports.data

import com.gios.lightsports.model.Game
import com.gios.lightsports.model.GameState
import com.gios.lightsports.model.RaceEvent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Turning a pile of games from nine leagues into the one scrollable column the app
 * shows. Pure functions on purpose — this is the logic most likely to be wrong at a
 * date boundary, and it is covered by unit tests.
 */
object Feed {

    enum class Bucket { LIVE, TODAY, TOMORROW, UPCOMING, RECENT }

    sealed interface Item {
        val sortMillis: Long

        data class GameItem(val game: Game) : Item {
            override val sortMillis: Long get() = game.startMillis
        }

        data class RaceItem(val race: RaceEvent) : Item {
            override val sortMillis: Long get() = race.sessionMillis ?: race.startMillis
        }
    }

    /**
     * @param dayEpoch the calendar day this section covers (epoch day in the user's zone),
     * or 0 for LIVE. One section per day rather than one per bucket: a football week is
     * Thursday, Saturday, Sunday and Monday, and "UPCOMING" over all four says nothing.
     */
    data class Section(
        val bucket: Bucket,
        val title: String,
        val items: List<Item>,
        val dayEpoch: Long = 0L,
    )

    /**
     * How far back a finished game stays in the feed. Must not be shorter than the
     * repository's fetch window, or a result gets downloaded and then silently dropped —
     * which looks exactly like a team going missing from the feed.
     */
    private const val RECENT_DAYS = 4L

    /** How far ahead the schedule runs before it stops being "upcoming". */
    private const val UPCOMING_DAYS = 10L

    fun build(
        games: List<Game>,
        races: List<RaceEvent>,
        nowMillis: Long,
        zone: ZoneId,
        /** How far back and ahead of today a game is kept. Widened when the feed is paged to another week. */
        backDays: Long = RECENT_DAYS,
        aheadDays: Long = UPCOMING_DAYS,
    ): List<Section> {
        val today = localDate(nowMillis, zone)
        // Keyed by bucket then day, so LIVE is one section and every other bucket is one
        // per calendar day.
        val groups = linkedMapOf<Pair<Bucket, Long>, MutableList<Item>>()

        fun add(bucket: Bucket, atMillis: Long, item: Item) {
            val day = if (bucket == Bucket.LIVE) 0L else localDate(atMillis, zone).toEpochDay()
            groups.getOrPut(bucket to day) { mutableListOf() } += item
        }

        for (game in games) {
            val bucket = bucketFor(game.state, game.startMillis, today, zone, backDays, aheadDays) ?: continue
            add(bucket, game.startMillis, Item.GameItem(game))
        }
        for (race in races) {
            val at = race.sessionMillis ?: race.startMillis
            val bucket = bucketFor(race.state, at, today, zone, backDays, aheadDays) ?: continue
            add(bucket, at, Item.RaceItem(race))
        }

        val out = mutableListOf<Section>()
        for (bucket in Bucket.entries) {
            val days = groups.keys.filter { it.first == bucket }.map { it.second }
            // Upcoming days run forward; results run backward, newest day first.
            val ordered = if (bucket == Bucket.RECENT) days.sortedDescending() else days.sorted()
            for (day in ordered) {
                val items = groups[bucket to day] ?: continue
                val sorted = if (bucket == Bucket.RECENT) {
                    items.sortedByDescending { it.sortMillis }
                } else {
                    items.sortedBy { it.sortMillis }
                }
                out += Section(bucket, title(bucket, day, today), sorted, day)
            }
        }
        return out
    }

    private val weekday = DateTimeFormatter.ofPattern("EEEE", Locale.US)
    private val dayDate = DateTimeFormatter.ofPattern("EEE MMM d", Locale.US)

    /**
     * "LIVE", "TODAY", "TOMORROW", then the weekday for the rest of the week and a date
     * past that. Results say "YESTERDAY" and "LAST SUNDAY" so a Saturday four days back
     * and a Saturday three days ahead never share a header.
     */
    private fun title(bucket: Bucket, dayEpoch: Long, today: LocalDate): String {
        val day = LocalDate.ofEpochDay(dayEpoch)
        val diff = dayEpoch - today.toEpochDay()
        return when (bucket) {
            Bucket.LIVE -> "LIVE"
            Bucket.TODAY -> "TODAY"
            Bucket.TOMORROW -> "TOMORROW"
            Bucket.UPCOMING -> if (diff <= 6) day.format(weekday).uppercase()
            else day.format(dayDate).uppercase()
            Bucket.RECENT -> when {
                diff == -1L -> "YESTERDAY"
                diff >= -6 -> "LAST " + day.format(weekday).uppercase()
                else -> day.format(dayDate).uppercase()
            }
        }
    }

    /**
     * A game belongs to the day it is played in the user's own time zone, not UTC —
     * otherwise every West Coast night game lands on tomorrow.
     */
    private fun bucketFor(
        state: GameState,
        atMillis: Long,
        today: LocalDate,
        zone: ZoneId,
        backDays: Long = RECENT_DAYS,
        aheadDays: Long = UPCOMING_DAYS,
    ): Bucket? {
        if (atMillis <= 0L) return null
        if (state == GameState.LIVE) return Bucket.LIVE
        val date = localDate(atMillis, zone)
        val days = date.toEpochDay() - today.toEpochDay()
        return when {
            days == 0L -> Bucket.TODAY
            days == 1L -> Bucket.TOMORROW
            days in 2..aheadDays -> Bucket.UPCOMING
            days < 0L && -days <= backDays -> Bucket.RECENT
            else -> null
        }
    }

    /**
     * The header for a paged feed: the football week when the games in view carry one,
     * else the date range. "WEEK 2" over a week of NFL; "SEP 10 – 15" over a week of
     * baseball. The NFL's week wins when college football, a week ahead in its own count,
     * is in the same view.
     */
    fun weekTitle(games: List<Game>, fromMillis: Long, toMillis: Long, zone: ZoneId): String {
        val weeks = games.filter { it.week != null }
        if (weeks.isNotEmpty() && weeks.size * 2 >= games.size) {
            val nfl = weeks.filter { it.leagueId == "nfl" }
            val pick = (nfl.ifEmpty { weeks }).groupingBy { it.week!! }.eachCount()
                .maxByOrNull { it.value }?.key
            if (pick != null) return "WEEK $pick"
        }
        val from = localDate(fromMillis, zone)
        val to = localDate(toMillis, zone)
        val month = DateTimeFormatter.ofPattern("MMM d", Locale.US)
        return if (from.month == to.month) {
            "${from.format(month)} – ${to.dayOfMonth}".uppercase()
        } else {
            "${from.format(month)} – ${to.format(month)}".uppercase()
        }
    }

    /** "3–1 FOR YOUR TEAMS": followed teams' results in a set of finals, or null when none are final. */
    fun recordLine(games: List<Game>, follows: Set<String>): String? {
        var w = 0; var l = 0
        for (g in games) {
            if (g.state != GameState.FINAL) continue
            val h = g.home.score ?: continue
            val a = g.away.score ?: continue
            if (h == a) continue
            val homeMine = "${g.leagueId}:${g.home.teamId}" in follows
            val awayMine = "${g.leagueId}:${g.away.teamId}" in follows
            if (homeMine && !awayMine) { if (h > a) w++ else l++ }
            else if (awayMine && !homeMine) { if (a > h) w++ else l++ }
        }
        if (w + l == 0) return null
        return "$w–$l FOR YOUR TEAMS"
    }

    /**
     * Followed teams with nothing in the window, so the feed can say so rather than
     * leaving them out. A team between fixtures and a team that failed to load look
     * identical otherwise, and the app gives no way to tell them apart.
     *
     * @param follows keys as stored, `leagueId:teamId`.
     * @param label resolves a key to something worth printing, or null to skip it.
     */
    fun idleFollows(
        follows: Set<String>,
        games: List<Game>,
        races: List<RaceEvent>,
        label: (String) -> String?,
    ): List<String> {
        if (follows.isEmpty()) return emptyList()
        val busy = mutableSetOf<String>()
        for (game in games) {
            busy += "${game.leagueId}:${game.home.teamId}"
            busy += "${game.leagueId}:${game.away.teamId}"
            // A doubles pair counts for both its players.
            for (id in game.home.memberIds + game.away.memberIds) busy += "${game.leagueId}:$id"
        }
        // Racing is followed as a series, so any race at all counts as the series being
        // accounted for.
        for (race in races) busy += "${race.leagueId}:series"
        return follows.filter { it !in busy }.mapNotNull(label).sorted()
    }

    /**
     * `LocalDate.ofInstant` is a Java 9 addition and is missing from the java.time
     * subset on older Android releases, so the date is derived the Java 8 way.
     */
    private fun localDate(millis: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    /**
     * Games worth polling right now: anything live, plus anything starting within the
     * window. Everything else can wait for the next scheduled wake-up.
     */
    fun hasActiveWindow(games: List<Game>, nowMillis: Long, leadMillis: Long): Boolean =
        games.any {
            it.state == GameState.LIVE ||
                (it.state == GameState.PRE && it.startMillis in nowMillis..(nowMillis + leadMillis))
        }

    /**
     * When to wake up next. While something is live, poll on the short interval;
     * otherwise sleep until just before the next scheduled start.
     */
    fun nextWakeMillis(
        games: List<Game>,
        nowMillis: Long,
        liveIntervalMillis: Long,
        leadMillis: Long,
        idleIntervalMillis: Long,
    ): Long {
        if (games.any { it.state == GameState.LIVE }) return nowMillis + liveIntervalMillis
        val nextStart = games
            .filter { it.state == GameState.PRE && it.startMillis > nowMillis }
            .minOfOrNull { it.startMillis }
        val target = nextStart?.minus(leadMillis)
        return when {
            target == null -> nowMillis + idleIntervalMillis
            target <= nowMillis + liveIntervalMillis -> nowMillis + liveIntervalMillis
            else -> minOf(target, nowMillis + idleIntervalMillis)
        }
    }
}
