package com.gios.lightsports.data

import com.gios.lightsports.model.EventClass

/**
 * Deciding whether a game is a one-off worth following on its own.
 *
 * Every rule here was derived from a full twelve-month sweep of ESPN's feeds — 8,231
 * games across seven leagues — rather than from guesses about what the fields contain.
 * Three findings shaped it:
 *
 * 1. **The round lives in a different field depending on the sport.** The US leagues put
 *    it in `notes[0].headline` ("Super Bowl LX", "Stanley Cup Final - Game 6") and leave
 *    `season.slug` as a flat `post-season`. Soccer does the opposite: MLS Cup carries
 *    `slug = mls-cup` with no note at all, which is why matching on notes alone missed
 *    both MLS Cup and the NWSL Championship.
 * 2. **`season.type` is unusable.** The MLS All-Star game reports type `13846` and a slug
 *    of `regular-season`; MLS playoff rounds each get their own five-digit type.
 * 3. **An off-roster competitor is the only signal for some events.** The MLS All-Star
 *    game and the MLB All-Star game carry no headline whatsoever — all that distinguishes
 *    them is competitors ("MLS All-Stars", "American All-Stars") that are absent from the
 *    league's own team list.
 */
object SpecialEvents {

    /** Named one-offs: all-star weekends, outdoor games, neutral-site showcases. */
    private val SHOWCASE_TITLES = listOf(
        "all-star", "all star", "pro bowl",
        "winter classic", "stadium series", "speedway classic", "little league classic",
        "empire classic", "global series", "world tour",
        "commissioner's cup championship", "nba cup championship",
        // Regular-season games played abroad, which ESPN names after the host city.
        "london game", "berlin game", "madrid game", "dublin game", "melbourne game",
        "abu dhabi game", "china game", "mexico city game", "são paulo game",
        "sao paulo game", "london games", "mexico city series",
    )

    /** The last series of a season, and nothing short of it. */
    private val CHAMPIONSHIP_TITLES = listOf(
        "super bowl", "world series", "stanley cup final",
        "nba finals", "wnba finals", "mls cup", "nwsl championship",
    )

    /**
     * Soccer's championship rounds, read off `season.slug`. Conference finals are
     * deliberately absent — `eastern-conference-playoffs---final` is a semi-final by
     * another name, and you already get it by following the team.
     */
    private val CHAMPIONSHIP_SLUGS = listOf("mls-cup", "playoffs---championship")

    /**
     * Headlines that exist for scheduling reasons rather than sporting ones. Without
     * this, "Postponed due to court condensation" and twenty MLB doubleheaders would
     * read as special events.
     */
    private val NOISE = listOf(
        "doubleheader", "makeup", "rain", "postpon", "travel issues",
        "series tied", "leads series", "win series", "advance", "group play",
        "suspended",
    )

    /**
     * @param note `notes[0].headline`, or null.
     * @param seasonSlug `season.slug`.
     * @param offRoster true when a competitor is missing from the league's team list.
     *
     * Preseason is excluded outright: an NBA side hosting Guangzhou or a WNBA side
     * playing Japan trips the off-roster rule, and five friendlies at the top of the
     * feed is not what anyone means by a special event.
     */
    fun classify(note: String?, seasonSlug: String?, offRoster: Boolean): EventClass {
        val slug = seasonSlug.orEmpty().lowercase()
        if (slug.replace("-", "") in setOf("preseason", "offseason")) return EventClass.NONE

        val title = note.orEmpty().lowercase()
        if (NOISE.any { it in title }) return EventClass.NONE

        if (CHAMPIONSHIP_TITLES.any { it in title }) return EventClass.CHAMPIONSHIP
        // A conference final is not a championship, whatever the slug suffix says.
        if ("conference" !in slug && CHAMPIONSHIP_SLUGS.any { it in slug }) {
            return EventClass.CHAMPIONSHIP
        }
        if (offRoster || SHOWCASE_TITLES.any { it in title }) return EventClass.SHOWCASE
        return EventClass.NONE
    }

    /** Follow key for a whole category, alongside the per-team keys. */
    fun key(leagueId: String, eventClass: EventClass): String? = when (eventClass) {
        EventClass.SHOWCASE -> "$leagueId:special"
        EventClass.CHAMPIONSHIP -> "$leagueId:championship"
        EventClass.NONE -> null
    }

    const val SUFFIX_SPECIAL = "special"
    const val SUFFIX_CHAMPIONSHIP = "championship"
}
