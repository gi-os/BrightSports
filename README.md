# LightSports

A scores app for the Light Phone III. Follow your teams, see one column of scores,
get notified when something happens.

Launcher label: **Sports** · package `com.gios.lightsports`

## Leagues

| | |
|---|---|
| Majors | MLB, NFL, NBA, NHL, MLS (+ Leagues Cup, U.S. Open Cup) |
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

MLS clubs also play knockout competitions ESPN serves as separate leagues — the Leagues
Cup and the U.S. Open Cup. Those reuse the parent league's team ids (NYCFC is `17606` in
all three), so a followed club is matched in them with no extra configuration; the games
are filed under MLS and the row names the competition instead of the league. The roster
check is deliberately skipped for cups: the field is full of Liga MX and USL clubs, and
applying it would make every tie look like an all-star fixture. Both cups are fetched on
every poll — measured live, they add ~260ms to a ~430ms total, and the out-of-season one
answers in under a kilobyte.

Classifying those events took a twelve-month sweep of all seven ESPN leagues — 8,231
games — because the round is stored in a different field depending on the sport. The US
leagues put it in `notes[0].headline` ("Super Bowl LX", "Stanley Cup Final - Game 6") and
leave `season.slug` as a flat `post-season`; soccer does the reverse, so MLS Cup arrives
as `slug = mls-cup` with no note at all. `season.type` is useless for this — the MLS
All-Star game reports `13846`. And some fixtures carry no title in either field: all that
marks the MLS and MLB all-star games is a competitor absent from the league's own team
list. See `data/SpecialEvents.kt`; the vocabulary is what `SpecialEventsTest` pins down.

A third quirk bites in F1: ESPN leaves `completed:false` on sessions of weekends that
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
  Upcoming / Recent, with each club's crest on its line. Finished games dim the loser,
  since colour is not available. Followed teams with nothing in the window are listed
  under "no game scheduled" rather than left out, so a team between fixtures can't be
  mistaken for a team that failed to load.

  Crests are loaded by `ui/Logos.kt` — about seventy lines instead of an image-loading
  library, because the job is one small PNG per club cached forever. They are downsampled
  on decode: ESPN serves 500px crests, which is a megabyte of ARGB_8888 for a 24dp view.
- **My teams** — league, then club. Search within a league. F1 is followed as a series.
  Each ESPN league also has two category stars above its team list:
  **Championship games** (Super Bowl, World Series, Stanley Cup Final, NBA/WNBA Finals,
  MLS Cup, NWSL Championship) and **Special games** (all-star weekends, Winter Classic,
  Stadium Series, NBA Cup final, and the games played abroad). Star a category and you
  get those fixtures whoever is playing in them.
- **Standings** — followed leagues only. Your team's row inverts. **Hold a row** for
  every stat the provider sent for that team, which is three or four times what fits in
  the table: run differential, streaks, home and away splits, a driver's points at every
  round of the season.
- **Settings** — my teams, notifications, and the spoiler delay.

## The wheel

Turning the brightness wheel scrolls whatever list is up: the feed, a game, the table, the
team picker, settings. That needs nothing but LightSports installed — no service, no
permission, no root — because the app reads the keys itself.

It works because the wheel arrives as an ordinary key event. Light patched
`/system/usr/keylayout/Generic.kl` to label scancodes 19 and 20 `WHEEL_CCW`/`WHEEL_CW`,
and nothing in `PhoneWindowManager` intercepts them, so they reach the focused window like
any other key — which is also why an app that ignores the keycode appears to have a dead
wheel. `hw/LightKeys.kt` resolves the labels at runtime and falls back to the raw
scancode, gated on the sensor's device name so a paired keyboard's `r` can't scroll the
standings.

The handling lives in `dispatchKeyEvent`, above the view hierarchy, so a notch beats the
team-search field when it has focus. Notches are frame-timed rather than applied on
arrival: the sensor fires every ~35 ms, faster than a frame, and acting on each one gives
a stack of jumps with nothing for the eye to follow. And the first notch after a pause is
held until a second confirms it, because the wheel sits under a thumb and a stray brush
should not move the score you were reading. `hw/Wheel.kt` has the numbers; LightNews has
the long version.

Only the turns are handled here; the wheel click and the camera button do nothing in this
app. If you want those, [LightControl](https://github.com/gi-os/LightControl) is a separate
and optional install that gives them to the whole phone — hold the wheel in and turn for
brightness, tap it for the flashlight, the camera button for the camera, and each of them
rebindable, tap and hold separately, to any app you have. It also hands brightness or a
synthetic-swipe scroll to apps that carry no wheel code at all.

It doesn't cost you the scrolling above. LightControl is a phone-wide key filter, and it
deliberately passes bare turns through to `com.gios.*` (and to LightFastread, LightRSS and
LightPhono), because scrolling a notch at a time from inside the app beats anything reachable
from outside it.

```bash
# Optional: LightControl, for brightness, the flashlight and the camera button
adb install -r LightControl-v1.0.x.apk

# The key service. NOTE: this setting is a list, and this command REPLACES it —
# if you also run LightVoice's push-to-talk, colon-join both components instead.
adb shell settings put secure enabled_accessibility_services \
  com.gios.lightcontrol/com.gios.lightcontrol.keys.ControlService
adb shell settings put secure accessibility_enabled 1

# Brightness, and the level readout + opening apps from the service
adb shell appops set com.gios.lightcontrol WRITE_SETTINGS allow
adb shell appops set com.gios.lightcontrol SYSTEM_ALERT_WINDOW allow
```

Latest APK: https://github.com/gi-os/LightControl/releases/latest

## Notifications

Alerts use LightChat's notifier design, carried over wholesale. The shade notification is
the *record* — it stays in LightOS's list and drives LightGlance's dot — and a box over
whatever the phone is showing is the *alert*, chosen two ways:

- **Awake and unlocked → an overlay window** (`ScoreAlertOverlay`). Nothing else is
  interrupted: the app underneath keeps running and every touch outside the box reaches it.
  An activity can't do that, floating or not — anything on top pauses what's below.
- **Screen off or locked → an activity** (`ScoreAlertActivity`). An overlay window sits
  below the keyguard and can't wake the panel, so for a walk-off home run while the phone
  is face-down on a desk, only `showWhenLocked` + `turnScreenOn` will do.

Both need the `SYSTEM_ALERT_WINDOW` appop — for the overlay obviously, and for the activity
because on Android 14 that appop is what exempts an app from background-activity-start
restrictions. One-time, adb only, since LightOS has no Settings screen for it:

```
adb shell appops set com.gios.lightsports SYSTEM_ALERT_WINDOW allow
```

Without it the buzz still fires and the notification is still posted; only the box is
missing. Vibration is disabled on both channels so the box owns the buzz — one place to
tune, rate-limited to one per 1.5s so a score and the final whistle together feel like one
event.


Score alerts run on `AlarmManager.setAndAllowWhileIdle`, the only alarm that fires
during Doze — and its firing is what grants the short network window the poll needs. It
has no repeating form, so each run arms the next, and both boot and app launch re-arm
the chain (a force-stop cancels every alarm an app owns).

- A nudge 15 minutes before a followed team kicks off, once per game — the lead window
  spans seven or eight polls, so it's guarded by a flag on the stored snapshot rather than
  by a state change. Nothing is announced early if the start time has already passed.
- Every score in baseball, hockey, soccer and football.
- **Basketball reports at the end of each quarter only.** Forty buckets a night is a
  pager, not a notification.
- **The end of each period is marked** — halftime, the end of a quarter, an intermission —
  in every sport except baseball, where nine innings and eighteen half-innings are nobody's
  idea of an event. A 0-0 halftime still counts: the mark doesn't wait for a score.

  Which period just ended is read from three signals, because no one of them is everywhere.
  ESPN names the phase for soccer (`STATUS_HALFTIME`, `STATUS_SECOND_HALF`) and falls back
  to a flat `STATUS_IN_PROGRESS` for the US leagues, which say it in the human text instead
  ("End of 1st Quarter"); failing both, the period number going up means the previous one
  ended. Deduplicated against the last period marked, since halftime lasts fifteen minutes
  and the poll runs every two.
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
