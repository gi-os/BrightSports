package com.gios.lightsports

import com.gios.lightsports.data.SpecialEvents
import com.gios.lightsports.model.EventClass
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Every string in here was read off ESPN's live feeds during a twelve-month sweep, not
 * invented. The classifier is only as good as its vocabulary, so the vocabulary is the
 * thing under test.
 */
class SpecialEventsTest {

    private fun c(note: String?, slug: String? = "regular-season", offRoster: Boolean = false) =
        SpecialEvents.classify(note, slug, offRoster)

    // ------------------------------------------------------------ championships

    @Test
    fun `the deciders are championships`() {
        assertEquals(EventClass.CHAMPIONSHIP, c("Super Bowl LX", "post-season"))
        assertEquals(EventClass.CHAMPIONSHIP, c("World Series - Game 7", "post-season"))
        assertEquals(EventClass.CHAMPIONSHIP, c("Stanley Cup Final - Game 6", "post-season"))
        assertEquals(EventClass.CHAMPIONSHIP, c("NBA Finals - Game 1", "post-season"))
        assertEquals(EventClass.CHAMPIONSHIP, c("WNBA Finals - Game 4", "post-season"))
        // ESPN shouts this one in the middle of a series.
        assertEquals(EventClass.CHAMPIONSHIP, c("WNBA FINALS - Game 3", "post-season"))
    }

    @Test
    fun `soccer finals are named only in the season slug`() {
        // MLS Cup and the NWSL Championship carry no headline at all. Matching on notes
        // alone missed both of them entirely.
        assertEquals(EventClass.CHAMPIONSHIP, c(null, "mls-cup"))
        assertEquals(EventClass.CHAMPIONSHIP, c(null, "playoffs---championship"))
    }

    @Test
    fun `a cup final is a championship, its earlier rounds are not`() {
        // Leagues Cup and U.S. Open Cup rounds, as ESPN spells them.
        assertEquals(EventClass.CHAMPIONSHIP, c(null, "final"))
        assertEquals(EventClass.NONE, c(null, "league-phase"))
        assertEquals(EventClass.NONE, c(null, "round-one"))
        assertEquals(EventClass.NONE, c(null, "round-of-32"))
    }

    @Test
    fun `semifinals must not be mistaken for a final`() {
        // "semifinals" contains "final", so a substring test would promote every
        // knockout round to a championship. The match is on the slug's last segment.
        assertEquals(EventClass.NONE, c(null, "semifinals"))
        assertEquals(EventClass.NONE, c(null, "quarterfinals"))
        assertEquals(EventClass.NONE, c(null, "playoffs---semifinals"))
        assertEquals(EventClass.NONE, c(null, "playoffs---quarterfinals"))
    }

    @Test
    fun `a conference final is not a championship`() {
        assertEquals(EventClass.NONE, c(null, "eastern-conference-playoffs---final"))
        assertEquals(EventClass.NONE, c(null, "western-conference-playoffs---final"))
        assertEquals(EventClass.NONE, c("AFC Championship", "post-season"))
        assertEquals(EventClass.NONE, c("East Finals - Game 7", "post-season"))
        assertEquals(EventClass.NONE, c("West Semifinals - Game 4", "post-season"))
    }

    @Test
    fun `ordinary playoff rounds stay out`() {
        for (note in listOf(
            "NFC Wild Card Playoffs", "AFC Divisional Playoffs", "ALDS", "NLCS",
            "East 1st Round", "NBA Play-In - West - 8th Seed Game",
            "NBA Cup - Group Play", "WNBA Semifinals",
        )) {
            assertEquals("'$note' should not be special", EventClass.NONE, c(note, "post-season"))
        }
        assertEquals(EventClass.NONE, c(null, "eastern-conference-playoffs---round-one"))
    }

    // ---------------------------------------------------------------- showcases

    @Test
    fun `all-star and novelty games are showcases`() {
        assertEquals(EventClass.SHOWCASE, c("Pro Bowl Games", "post-season", offRoster = true))
        assertEquals(EventClass.SHOWCASE, c("NBA All-Star - Championship"))
        assertEquals(EventClass.SHOWCASE, c("AT&T WNBA All-Star Game"))
        assertEquals(EventClass.SHOWCASE, c("Discover NHL Winter Classic"))
        assertEquals(EventClass.SHOWCASE, c("Navy Federal Credit Union Stadium Series"))
        assertEquals(EventClass.SHOWCASE, c("MLB Speedway Classic"))
        assertEquals(EventClass.SHOWCASE, c("Little League Classic"))
        assertEquals(EventClass.SHOWCASE, c("NHL Global Series"))
        assertEquals(EventClass.SHOWCASE, c("NBA Cup Championship"))
        assertEquals(EventClass.SHOWCASE, c("WNBA Commissioner's Cup Championship"))
    }

    @Test
    fun `games abroad are showcases`() {
        for (note in listOf(
            "NFL São Paulo Game", "NFL Dublin Game", "NFL London Games", "NFL Berlin Game",
            "NFL Madrid Game", "NBA Mexico City Game 2025", "NBA London Game 2026",
            "MLB World Tour: Mexico City Series",
        )) {
            assertEquals("'$note' should be a showcase", EventClass.SHOWCASE, c(note))
        }
    }

    @Test
    fun `an off-roster competitor is a showcase even with no headline`() {
        // The MLS and MLB all-star games publish nothing but the fixture itself.
        assertEquals(EventClass.SHOWCASE, c(null, "regular-season", offRoster = true))
    }

    @Test
    fun `preseason friendlies against foreign clubs are not events`() {
        // NBA vs Guangzhou and Hapoel, WNBA vs Niger and Japan: all off-roster, all
        // preseason, and five of them at the top of the feed is not a special event.
        assertEquals(EventClass.NONE, c(null, "preseason", offRoster = true))
        assertEquals(EventClass.NONE, c("NBA Melbourne Game", "preseason", offRoster = true))
    }

    // -------------------------------------------------------------------- noise

    @Test
    fun `scheduling notes are not events`() {
        for (note in listOf(
            "Doubleheader", "Doubleheader - Game 2 - Makeup from April 29",
            "Rain - Makeup date July 22", "Makeup from Jan 26",
            "Postponed due to court condensation. Makeup date Jan 29",
            "Travel Issues - Makeup date July 20",
            "Series tied 1-1", "PHI win series 2-0", "NYC leads series 1-0",
            "Washington Spirit advance 3-1 on penalties",
        )) {
            assertEquals("'$note' should not be special", EventClass.NONE, c(note))
        }
    }

    @Test
    fun `an ordinary game is nothing at all`() {
        assertEquals(EventClass.NONE, c(null))
        assertEquals(EventClass.NONE, c(""))
    }

    // --------------------------------------------------------------------- keys

    @Test
    fun `each class has its own follow key`() {
        assertEquals("nfl:championship", SpecialEvents.key("nfl", EventClass.CHAMPIONSHIP))
        assertEquals("nfl:special", SpecialEvents.key("nfl", EventClass.SHOWCASE))
        assertEquals(null, SpecialEvents.key("nfl", EventClass.NONE))
    }
}
