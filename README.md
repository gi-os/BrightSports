# LightSports

A scores app for the Light Phone III. Follow your teams, see one column of scores,
get notified when something happens.

Launcher label: **Sports** · package `com.gios.lightsports`

## Leagues

| | |
|---|---|
| Majors | MLB, NFL, NBA, NHL, MLS |
| Women's | WNBA, NWSL, PWHL |
| Minor league baseball | Triple-A, Double-A, High-A, Single-A |
| Racing | Formula 1 |

## Data sources

All keyless public JSON. No account, no API key to paste in.

- **ESPN site API** — the majors, the women's leagues, F1.
  `site.api.espn.com/apis/site/v2/sports/<sport>/<league>/scoreboard`
- **MLB StatsAPI** — the four MiLB levels by `sportId` (11/12/13/14). ESPN publishes no
  minor-league scoreboard, and this is the feed MiLB.com itself runs on.
- **HockeyTech / LeagueStat** — the PWHL, which is on no mainstream scores API. The feed
  key in `HockeyTechParser` is the public one thepwhl.com ships in its own front end.

Two provider quirks are worth knowing before touching the parsers: MLB's standings
endpoint silently ignores `sportId` and has to be asked by `leagueId`, and HockeyTech
returns its standings wrapped in a bare pair of parentheses left over from JSONP.

A third one bites in F1: ESPN leaves `completed:false` on sessions of weekends that
finished months ago (Bahrain and Saudi Arabia 2026 both do), so race state is read from
each session's `state` string and the event `endDate`, never from that flag.

## Screens

Navigation follows the LightOS bar idiom: a top bar carrying the title and a back
chevron, and a four-icon action bar along the bottom. `ui/LightBars.kt` rebuilds
`LightTopBar` and `LightBottomBar` from Light's own `sdk/ui` library — same 27-column
grid, same 3- and 4-unit bar heights, same slot rules, same LightOS icon drawables. They
are reimplemented rather than imported because the SDK artifacts sit on GitHub Packages
behind a token and this ships as a plain APK; if that ever opens up, delete that file.

- **Scores** — one feed, followed teams only, grouped Live / Today / Tomorrow /
  Upcoming / Recent. Finished games dim the loser, since colour is not available.
- **My teams** — league, then club. Search within a league. F1 is followed as a series.
- **Standings** — followed leagues only. Your team's row inverts.
- **Settings** — notifications and the spoiler delay.

## Notifications

Score alerts run on `AlarmManager.setAndAllowWhileIdle`, the only alarm that fires
during Doze — and its firing is what grants the short network window the poll needs. It
has no repeating form, so each run arms the next, and both boot and app launch re-arm
the chain (a force-stop cancels every alarm an app owns).

- Every score in baseball, hockey, soccer and football.
- **Basketball reports at the end of each quarter only.** Forty buckets a night is a
  pager, not a notification.
- F1 posts the podium once, when the weekend goes final.
- All score alerts are held **5 minutes** by default so the phone doesn't beat the
  stream. Adjustable or off in settings. Several scores inside one delay window collapse
  into a single notification carrying the current score.
- Expect roughly a nine minute floor between checks once the screen has been off a
  while; Doze throttles idle alarms. `adb shell dumpsys deviceidle whitelist
  +com.gios.lightsports` removes the throttle if you want it tighter.

## Build

```
./gradlew :app:testDebugUnitTest      # parsers, feed bucketing, notification gating
./gradlew :app:assembleRelease
python3 scripts/generate_icon.py      # only if the launcher mark changes
```

Every push to `main` cuts a GitHub Release. The APK is signed with the committed
keystore and CI fails if the certificate drifts from `signing-fingerprint.txt` — an
Obtainium update dies with a bare "Failure: Invalid" otherwise.

Bump `versionName` in `app/build.gradle.kts` when you want Obtainium to see a new
release; the tag is derived from it.
