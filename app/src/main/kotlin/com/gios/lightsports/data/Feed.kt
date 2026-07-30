package com.gios.lightsports.data

import com.gios.lightsports.model.Game
import com.gios.lightsports.model.GameState
import com.gios.lightsports.model.RaceEvent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

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

    data class Section(val bucket: Bucket, val title: String, val items: List<Item>)

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
    ): List<Section> {
        val today = localDate(nowMillis, zone)
        val buckets = linkedMapOf<Bucket, MutableList<Item>>()

        fun add(bucket: Bucket, item: Item) {
            buckets.getOrPut(bucket) { mutableListOf() } += item
        }

        for (game in games) {
            val bucket = bucketFor(game.state, game.startMillis, today, zone) ?: continue
            add(bucket, Item.GameItem(game))
        }
        for (race in races) {
            val at = race.sessionMillis ?: race.startMillis
            val bucket = bucketFor(race.state, at, today, zone) ?: continue
            add(bucket, Item.RaceItem(race))
        }

        return Bucket.entries.mapNotNull { bucket ->
            val items = buckets[bucket]?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val sorted = if (bucket == Bucket.RECENT) {
                items.sortedByDescending { it.sortMillis }
            } else {
                items.sortedBy { it.sortMillis }
            }
            Section(bucket, title(bucket), sorted)
        }
    }

    private fun title(bucket: Bucket) = when (bucket) {
        Bucket.LIVE -> "LIVE"
        Bucket.TODAY -> "TODAY"
        Bucket.TOMORROW -> "TOMORROW"
        Bucket.UPCOMING -> "UPCOMING"
        Bucket.RECENT -> "RECENT"
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
    ): Bucket? {
        if (atMillis <= 0L) return null
        if (state == GameState.LIVE) return Bucket.LIVE
        val date = localDate(atMillis, zone)
        val days = date.toEpochDay() - today.toEpochDay()
        return when {
            days == 0L -> Bucket.TODAY
            days == 1L -> Bucket.TOMORROW
            days in 2..UPCOMING_DAYS -> Bucket.UPCOMING
            days < 0L && -days <= RECENT_DAYS -> Bucket.RECENT
            else -> null
        }
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
