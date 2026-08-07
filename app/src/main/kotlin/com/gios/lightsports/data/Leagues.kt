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

    // European domestic soccer. Same ESPN site API, same parser as MLS/NWSL — only the
    // path changes. `hasEvents` is left off deliberately: showcase games abroad (the
    // Community Shield, preseason friendlies) and the exact `notes`/`season.slug` shape
    // for each league's own cup finals haven't been swept the way MLS and the US majors
    // were, so nothing here classifies a game as SHOWCASE or CHAMPIONSHIP yet.
    val EPL = League(
        id = "epl", name = "English Premier League", short = "EPL",
        kind = SportKind.SOCCER, provider = Provider.ESPN, espnPath = "soccer/eng.1",
        markPeriods = true,
    )
    val LALIGA = League(
        id = "laliga", name = "Spanish LaLiga", short = "LALIGA",
        kind = SportKind.SOCCER, provider = Provider.ESPN, espnPath = "soccer/esp.1",
        markPeriods = true,
    )
    val BUNDESLIGA = League(
        id = "bundesliga", name = "German Bundesliga", short = "BUND",
        kind = SportKind.SOCCER, provider = Provider.ESPN, espnPath = "soccer/ger.1",
        markPeriods = true,
    )
    val SERIE_A = League(
        id = "seriea", name = "Italian Serie A", short = "SERIEA",
        kind = SportKind.SOCCER, provider = Provider.ESPN, espnPath = "soccer/ita.1",
        markPeriods = true,
    )
    val LIGUE_1 = League(
        id = "ligue1", name = "French Ligue 1", short = "LIGUE1",
        kind = SportKind.SOCCER, provider = Provider.ESPN, espnPath = "soccer/fra.1",
        markPeriods = true,
    )

    // Continental club competitions. Each club's country-league roster doesn't apply
    // here — the field is fifty-plus clubs from every domestic league in Europe — so
    // these carry their own team lists rather than reusing a parent league's, unlike
    // the MLS cups.
    val UCL = League(
        id = "ucl", name = "UEFA Champions League", short = "UCL",
        kind = SportKind.SOCCER, provider = Provider.ESPN, espnPath = "soccer/uefa.champions",
        markPeriods = true,
    )
    val UEL = League(
        id = "uel", name = "UEFA Europa League", short = "UEL",
        kind = SportKind.SOCCER, provider = Provider.ESPN, espnPath = "soccer/uefa.europa",
        markPeriods = true,
    )

    /**
     * FBS only. The bare `football/college-football` path spans FBS through Division
     * III — over 750 teams — so `espnGroup = "80"` narrows the scoreboard and standings
     * to the ~140 FBS programs; the team roster then has to come from the standings
     * tree rather than the `teams` endpoint, which ignores that filter entirely (see
     * [League.espnGroup]). `hasEvents` stays off: an FBS team hosting an FCS opponent
     * is a normal September Saturday here, not the exception the off-roster check
     * elsewhere assumes it is, so treating it as a signal would flag half the
     * non-conference schedule as a showcase game.
     */
    val CFB = League(
        id = "cfb", name = "NCAA Division I FBS Football", short = "CFB",
        kind = SportKind.FOOTBALL, provider = Provider.ESPN,
        espnPath = "football/college-football", espnGroup = "80",
        markPeriods = true,
    )

    val all: List<League> = listOf(
        MLB, NFL, NBA, NHL, MLS, F1,
        EPL, LALIGA, BUNDESLIGA, SERIE_A, LIGUE_1, UCL, UEL,
        CFB,
        WNBA, NWSL, PWHL, WPBL,
        AAA, AA, HIGH_A, SINGLE_A,
    )

    /** Grouping used by the follow picker, in the order it renders. */
    val sections: List<Pair<String, List<League>>> = listOf(
        "MAJOR" to listOf(MLB, NFL, NBA, NHL, MLS),
        "SOCCER" to listOf(EPL, LALIGA, BUNDESLIGA, SERIE_A, LIGUE_1, UCL, UEL),
        "COLLEGE FOOTBALL" to listOf(CFB),
        "WOMEN'S" to listOf(WNBA, NWSL, PWHL, WPBL),
        "MINOR LEAGUE BASEBALL" to listOf(AAA, AA, HIGH_A, SINGLE_A),
        "RACING" to listOf(F1),
    )

    fun byId(id: String): League? = all.firstOrNull { it.id == id }
}
