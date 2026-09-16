package com.gios.lightsports

import com.gios.lightsports.data.Feed
import com.gios.lightsports.data.Iso
import com.gios.lightsports.model.Game
import com.gios.lightsports.model.GameState
import com.gios.lightsports.model.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class FeedTest {

    private val newYork = ZoneId.of("America/New_York")

    /** 2026-07-29 15:00 UTC — 11am in New York. */
    private val now = Iso.millis("2026-07-29T15:00:00Z")

    private fun game(
        id: String,
        state: GameState,
        startIso: String,
    ) = Game(
        id = id,
        leagueId = "mlb",
        state = state,
        startMillis = Iso.millis(startIso),
        statusDetail = "",
        home = Side("1", "Home", "Home", "HOM", 0),
        away = Side("2", "Away", "Away", "AWY", 0),
    )

    private fun buckets(games: List<Game>) =
        Feed.build(games, emptyList(), now, newYork).map { it.bucket }

    @Test
    fun `a page is one day, and live games ride with today`() {
        // now is 11am on 29 July in New York.
        val today = game("today", GameState.PRE, "2026-07-29T23:05:00Z")
        val live = game("live", GameState.LIVE, "2026-07-29T13:00:00Z")
        val tomorrow = game("tomorrow", GameState.PRE, "2026-07-30T23:05:00Z")
        val yesterday = game("yesterday", GameState.FINAL, "2026-07-28T23:05:00Z")
        val all = listOf(today, live, tomorrow, yesterday)
        val julyTwentyNine = LocalDate.of(2026, 7, 29).toEpochDay()

        fun idsOn(day: Long) = Feed.build(all, emptyList(), now, newYork, onlyDay = day)
            .flatMap { it.items }
            .map { (it as Feed.Item.GameItem).game.id }

        assertEquals(listOf("live", "today"), idsOn(julyTwentyNine))
        assertEquals(listOf("tomorrow"), idsOn(julyTwentyNine + 1))
        assertEquals(listOf("yesterday"), idsOn(julyTwentyNine - 1))
        // A day with nothing on it is an empty page, not a fallback to the whole window.
        assertEquals(emptyList<String>(), idsOn(julyTwentyNine + 5))
    }

    @Test
    fun `a page is named for its day`() {
        val today = LocalDate.of(2026, 7, 29)
        assertEquals("TODAY", Feed.dayTitle(today, now, newYork))
        assertEquals("YESTERDAY", Feed.dayTitle(today.minusDays(1), now, newYork))
        assertEquals("TOMORROW", Feed.dayTitle(today.plusDays(1), now, newYork))
        // Past that the weekday alone would name two days inside the chevrons' reach.
        assertEquals("SAT AUG 8", Feed.dayTitle(today.plusDays(10), now, newYork))
        assertEquals("SAT AUG 8", Feed.dayLine(today.plusDays(10)))
        // The line always names the day, even where the title has a word for it.
        assertEquals("WED JUL 29", Feed.dayLine(today))
    }

    @Test
    fun `live games lead regardless of when they started`() {
        val sections = Feed.build(
            listOf(
                game("a", GameState.PRE, "2026-07-29T23:05:00Z"),
                game("b", GameState.LIVE, "2026-07-29T13:00:00Z"),
            ),
            emptyList(), now, newYork,
        )
        assertEquals(Feed.Bucket.LIVE, sections.first().bucket)
        assertEquals(listOf(Feed.Bucket.LIVE, Feed.Bucket.TODAY), sections.map { it.bucket })
    }

    @Test
    fun `a late west coast game belongs to today in the users own zone`() {
        // 2026-07-30 02:10 UTC is still 10:10pm on the 29th in New York. Bucketing on
        // the UTC date would file every night game under tomorrow.
        assertEquals(
            listOf(Feed.Bucket.TODAY),
            buckets(listOf(game("a", GameState.PRE, "2026-07-30T02:10:00Z"))),
        )
    }

    @Test
    fun `tomorrow, upcoming and recent all separate`() {
        assertEquals(
            listOf(Feed.Bucket.TODAY, Feed.Bucket.TOMORROW, Feed.Bucket.UPCOMING, Feed.Bucket.RECENT),
            buckets(
                listOf(
                    game("a", GameState.PRE, "2026-07-29T23:05:00Z"),
                    game("b", GameState.PRE, "2026-07-30T23:05:00Z"),
                    game("c", GameState.PRE, "2026-08-02T17:05:00Z"),
                    game("d", GameState.FINAL, "2026-07-28T23:05:00Z"),
                ),
            ),
        )
    }

    @Test
    fun `games far outside the window are dropped`() {
        assertTrue(
            buckets(
                listOf(
                    game("a", GameState.FINAL, "2026-06-01T23:05:00Z"),
                    game("b", GameState.PRE, "2026-09-01T23:05:00Z"),
                ),
            ).isEmpty(),
        )
    }

    @Test
    fun `recent runs newest first`() {
        val sections = Feed.build(
            listOf(
                game("older", GameState.FINAL, "2026-07-27T23:05:00Z"),
                game("newer", GameState.FINAL, "2026-07-28T23:05:00Z"),
            ),
            emptyList(), now, newYork,
        )
        // One section per day now, newest day first, newest game first inside a day.
        assertTrue(sections.all { it.bucket == Feed.Bucket.RECENT })
        val ids = sections.flatMap { it.items }.map { (it as Feed.Item.GameItem).game.id }
        assertEquals(listOf("newer", "older"), ids)
    }

    @Test
    fun `a game with no usable date is skipped rather than crashing the feed`() {
        assertTrue(buckets(listOf(game("a", GameState.PRE, "not a date"))).isEmpty())
    }

    @Test
    fun `a result four days old still shows, because it is still fetched`() {
        // The repository asks for four days back. If the feed only kept three, that
        // fourth day was downloaded and thrown away — which reads as a missing team.
        assertEquals(
            listOf(Feed.Bucket.RECENT),
            buckets(listOf(game("a", GameState.FINAL, "2026-07-25T23:30:00Z"))),
        )
    }

    // ------------------------------------------------------------ poll timing

    @Test
    fun `a live game means the short polling interval`() {
        val wake = Feed.nextWakeMillis(
            games = listOf(game("a", GameState.LIVE, "2026-07-29T13:00:00Z")),
            nowMillis = now,
            liveIntervalMillis = 120_000,
            leadMillis = 900_000,
            idleIntervalMillis = 3_600_000,
        )
        assertEquals(now + 120_000, wake)
    }

    @Test
    fun `with nothing live the next wake lands just before the next start`() {
        val start = Iso.millis("2026-07-29T19:00:00Z")
        val wake = Feed.nextWakeMillis(
            games = listOf(game("a", GameState.PRE, "2026-07-29T19:00:00Z")),
            nowMillis = now,
            liveIntervalMillis = 120_000,
            leadMillis = 900_000,
            idleIntervalMillis = 24 * 3_600_000,
        )
        assertEquals(start - 900_000, wake)
    }

    @Test
    fun `an empty schedule still wakes up eventually`() {
        val wake = Feed.nextWakeMillis(
            games = emptyList(),
            nowMillis = now,
            liveIntervalMillis = 120_000,
            leadMillis = 900_000,
            idleIntervalMillis = 3_600_000,
        )
        assertEquals(now + 3_600_000, wake)
    }

    @Test
    fun `the active window covers games about to start`() {
        assertTrue(
            Feed.hasActiveWindow(
                listOf(game("a", GameState.PRE, "2026-07-29T15:10:00Z")),
                now,
                leadMillis = 900_000,
            ),
        )
        assertTrue(
            !Feed.hasActiveWindow(
                listOf(game("a", GameState.PRE, "2026-07-29T23:10:00Z")),
                now,
                leadMillis = 900_000,
            ),
        )
    }

    // ----------------------------------------------------------------- dates

    @Test
    fun `all three providers date formats parse`() {
        // ESPN omits seconds, StatsAPI includes them, HockeyTech sends an offset.
        assertEquals(Iso.millis("2026-07-29T16:10:00Z"), Iso.millis("2026-07-29T16:10Z"))
        assertEquals(
            Iso.millis("2026-05-08T23:00:00Z"),
            Iso.millis("2026-05-08T19:00:00-04:00"),
        )
        assertEquals(0L, Iso.millis("garbage"))
        assertEquals(0L, Iso.millis(null))
    }

    @Test
    fun `an unparseable date sorts nowhere rather than to 1970`() {
        assertNull(
            Feed.build(
                listOf(game("a", GameState.PRE, "")),
                emptyList(), now, newYork,
            ).firstOrNull(),
        )
    }
}

class FeedDayTitlesTest {
    private val newYork = java.time.ZoneId.of("America/New_York")
    // Wednesday 2026-09-09, 3pm New York.
    private val now = java.time.Instant.parse("2026-09-09T19:00:00Z").toEpochMilli()

    private fun game(id: String, state: com.gios.lightsports.model.GameState, iso: String) = com.gios.lightsports.model.Game(
        id = id, leagueId = "nfl", state = state,
        startMillis = com.gios.lightsports.data.Iso.millis(iso), statusDetail = "",
        home = com.gios.lightsports.model.Side("1", "Home", "Home", "HOM", 0),
        away = com.gios.lightsports.model.Side("2", "Away", "Away", "AWY", 0),
    )

    @Test
    fun `a football week is split into its days`() {
        val sections = Feed.build(
            listOf(
                game("thu", com.gios.lightsports.model.GameState.PRE, "2026-09-11T00:20:00Z"), // Thu 8:20pm ET = tomorrow
                game("sat", com.gios.lightsports.model.GameState.PRE, "2026-09-12T19:30:00Z"),
                game("sun1", com.gios.lightsports.model.GameState.PRE, "2026-09-13T17:00:00Z"),
                game("sun2", com.gios.lightsports.model.GameState.PRE, "2026-09-13T20:25:00Z"),
                game("last", com.gios.lightsports.model.GameState.FINAL, "2026-09-06T17:00:00Z"),
                game("yday", com.gios.lightsports.model.GameState.FINAL, "2026-09-08T23:00:00Z"),
            ),
            emptyList(), now, newYork,
        )
        assertEquals(
            listOf("TOMORROW", "SATURDAY", "SUNDAY", "YESTERDAY", "LAST SUNDAY"),
            sections.map { it.title },
        )
        assertEquals(2, sections.first { it.title == "SUNDAY" }.items.size)
    }
}
