package com.gios.lightsports.data

import com.gios.lightsports.model.Cup
import com.gios.lightsports.model.League
import com.gios.lightsports.model.Loudness
import com.gios.lightsports.model.Provider
import com.gios.lightsports.model.SportKind

/**
 * Every league the app knows about, and where its data comes from.
 *
 * Four providers, all keyless public JSON:
 *  - ESPN's site API covers the men's and women's majors and Formula 1.
 *  - MLB's StatsAPI is the only free source for the four full-season MiLB levels;
 *    ESPN has no minor-league scoreboard at all.
 *  - The PWHL is not on ESPN either. It runs on HockeyTech/LeagueStat, whose feed
 *    key is public and baked into thepwhl.com's own front end.
 *  - The WPBL is on neither, despite ESPN carrying the broadcast. Its own stats
 *    service publishes the season as plain JSON; see [WpblParser].
 */
object Leagues {

    val MLB = League(
        id = "mlb", name = "Major League Baseball", short = "MLB",
        kind = SportKind.BASEBALL, provider = Provider.ESPN, espnPath = "baseball/mlb",
        hasEvents = true,
        championshipExample = "World Series",
        specialExample = "All-Star Game, Little League Classic",
    )
    val AAA = League(
        id = "milb-aaa", name = "Triple-A", short = "AAA",
        kind = SportKind.BASEBALL, provider = Provider.STATSAPI, statsApiSportId = 11,
    )
    val AA = League(
        id = "milb-aa", name = "Double-A", short = "AA",
        kind = SportKind.BASEBALL, provider = Provider.STATSAPI, statsApiSportId = 12,
    )
    val HIGH_A = League(
        id = "milb-a-plus", name = "High-A", short = "A+",
        kind = SportKind.BASEBALL, provider = Provider.STATSAPI, statsApiSportId = 13,
    )
    val SINGLE_A = League(
        id = "milb-a", name = "Single-A", short = "A",
        kind = SportKind.BASEBALL, provider = Provider.STATSAPI, statsApiSportId = 14,
    )
    val NFL = League(
        id = "nfl", name = "National Football League", short = "NFL",
        kind = SportKind.FOOTBALL, provider = Provider.ESPN, espnPath = "football/nfl",
        hasEvents = true,
        championshipExample = "Super Bowl",
        specialExample = "Pro Bowl, the London and Berlin games",
        markPeriods = true,
    )
    val NBA = League(
        id = "nba", name = "National Basketball Association", short = "NBA",
        kind = SportKind.BASKETBALL, provider = Provider.ESPN, espnPath = "basketball/nba",
        loudness = Loudness.PERIOD_ONLY,
        hasEvents = true,
        championshipExample = "NBA Finals",
        specialExample = "All-Star weekend, NBA Cup final, games abroad",
        markPeriods = true,
    )
    val WNBA = League(
        id = "wnba", name = "Women's National Basketball Association", short = "WNBA",
        kind = SportKind.BASKETBALL, provider = Provider.ESPN, espnPath = "basketball/wnba",
        loudness = Loudness.PERIOD_ONLY,
        hasEvents = true,
        championshipExample = "WNBA Finals",
        specialExample = "All-Star Game, Commissioner's Cup final",
        markPeriods = true,
    )
    val NHL = League(
        id = "nhl", name = "National Hockey League", short = "NHL",
        kind = SportKind.HOCKEY, provider = Provider.ESPN, espnPath = "hockey/nhl",
        hasEvents = true,
        championshipExample = "Stanley Cup Final",
        specialExample = "Winter Classic, Stadium Series, Global Series",
        markPeriods = true,
    )
    val PWHL = League(
        id = "pwhl", name = "Professional Women's Hockey League", short = "PWHL",
        kind = SportKind.HOCKEY, provider = Provider.HOCKEYTECH, hockeyTechClient = "pwhl",
        markPeriods = true,
    )
    val WPBL = League(
        id = "wpbl", name = "Women's Pro Baseball League", short = "WPBL",
        kind = SportKind.BASEBALL, provider = Provider.WPBL,
    )
    val MLS = League(
        id = "mls", name = "Major League Soccer", short = "MLS",
        kind = SportKind.SOCCER, provider = Provider.ESPN, espnPath = "soccer/usa.1",
        hasEvents = true,
        championshipExample = "MLS Cup",
        specialExample = "MLS All-Stars vs Liga MX",
        // Both reuse the MLS team ids, so a followed club is matched in them with no
        // extra configuration. Verified: NYCFC is 17606 in all three competitions.
        cups = listOf(
            Cup("soccer/concacaf.leagues.cup", "Leagues Cup"),
            Cup("soccer/usa.open", "U.S. Open Cup"),
        ),
        markPeriods = true,
    )
    val NWSL = League(
        id = "nwsl", name = "National Women's Soccer League", short = "NWSL",
        kind = SportKind.SOCCER, provider = Provider.ESPN, espnPath = "soccer/usa.nwsl",
        hasEvents = true,
        championshipExample = "NWSL Championship",
        specialExample = "no special games published yet",
        markPeriods = true,
    )
    val F1 = League(
        id = "f1", name = "Formula 1", short = "F1",
        kind = SportKind.RACING, provider = Provider.ESPN, espnPath = "racing/f1",
        loudness = Loudness.FINAL_ONLY, isRacing = true,
    )

    val all: List<League> = listOf(
        MLB, NFL, NBA, NHL, MLS, F1,
        WNBA, NWSL, PWHL, WPBL,
        AAA, AA, HIGH_A, SINGLE_A,
    )

    /** Grouping used by the follow picker, in the order it renders. */
    val sections: List<Pair<String, List<League>>> = listOf(
        "MAJOR" to listOf(MLB, NFL, NBA, NHL, MLS),
        "WOMEN'S" to listOf(WNBA, NWSL, PWHL, WPBL),
        "MINOR LEAGUE BASEBALL" to listOf(AAA, AA, HIGH_A, SINGLE_A),
        "RACING" to listOf(F1),
    )

    fun byId(id: String): League? = all.firstOrNull { it.id == id }
}
