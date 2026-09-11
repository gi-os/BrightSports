## BrightSports v2.7 — you can see it working

**The update line flashes white each time an update lands.** A live game screen says it refreshes every fifteen seconds and then gives no sign of having done it, because the score is usually the same score. The line now goes white for a moment whenever data arrives, whether or not anything changed. A fetch that changed nothing still happened, and that is the case worth showing.

**And the line says the real number.** With the relay socket up the screen checks once a minute instead of every fifteen seconds, since the scores arrive on their own. It used to claim fifteen either way.

**The refresh button shows its work.** A white band runs along the rule under the top bar while a refresh is in flight, and the `UPDATED` line turns white and reads `REFRESHING…`. A tap over a slow radio used to change nothing on screen for a second or two, which reads as a tap that missed.

**A score reaches the lock screen as soon as it happens, with the hold off.** An alert owns its card for ninety seconds so that `TD SEA` is not wiped by the kickoff a second later. That hold was also swallowing the next *score*: a two-point conversion, or a second touchdown inside the minute and a half, sat unlisted while the card showed the old number. The hold now protects the wording and steps aside for a score it has not seen.

## BrightSports v2.6 — the spoiler hold holds the score, it does not delete it

**The live card shows the score again.** The setting said hold score alerts, and that is what it did to a buzz. An alert waits its five minutes, then goes off carrying the score. The ongoing card read the same setting and did something else. It drew no score at all, all game, with the hold on by default. That is why the lock screen said `Patriots at Seahawks · Q2` and stopped there.

**Now the card runs five minutes behind.** A touchdown reaches the lock screen at the moment the alert for it fires. A game nobody has scored in reads as live. Set the hold to none and the card is live to the second.

**A game the phone has only picked up shows its score right away.** Nothing about it is news yet. The alerts have always worked that way, and holding the first score back would leave a blank card for five minutes at the start of every game.

**Several scores inside one window arrive in order.** Two touchdowns ninety seconds apart reach the card ninety seconds apart, five minutes later. The card never skips ahead to the newest.

**The situation line waits for the score it belongs to.** A down and distance, a count, a red zone: each describes this second. The card carries them once its score has caught up.

**The setting now reads "Hold scores"**, since it holds both.

## BrightSports v2.5 — the card says where the design cuts

**A score on the lock face is now the card you drew.** BrightSports used to write the whole alert into the title. `TD SEA · Patriots 7 · Seahawks 21`. BrightControl took that apart again with a regular expression, which holds right up to the day the wording changes.

**The card now ships as five strings beside the title.** The kind (`TD`, `RED ZONE`, `ONE-SCORE GAME`, or a live game's matchup), the team, the figure, the play, and the clock. They are `SPORT_KIND`, `SPORT_TEAM`, `SPORT_VALUE`, `SPORT_DETAIL` and `SPORT_FOOT`. BrightControl v4.31 reads them and draws the box. Nothing infers anything.

**The team's crest rides along** as the card's large icon, off the file the feed already downloaded. There is no network call. A card with no crest reads perfectly well.

**The title and the body do not change.** The shade, LightGlance and any other phone show what they showed before.

**The figure is a scoreline now.** `NE 7 · SEA 14`, not the full club names, so the whole card fits one line of a 3.9" panel. A red-zone card shows the team in the zone. A one-score card shows the margin.

## BrightSports v2.4 — one card per game, and it is the alert

**A game is one notification now, start to finish.** The fifteen-minute warning, the live score, every touchdown, and the final all land on the same card. Until now a live game could hold two: the ticker's ongoing card, and whatever the last alert posted. On a Sunday afternoon that was two rows a game.

**The card carries the alert design.** It reads the way the on-screen box does: `TD SEA · Patriots 7 · Seahawks 21` on the first line, the play and the clock under it. BrightControl v4.30 draws that on the lock face, with the kind large. It already drew it that way on a banner. Between events the card shows the score and the situation.

**An alert owns the card for ninety seconds.** The relay reports the kickoff a second after the touchdown. Without the hold, "TD SEA" would be gone before the phone was out of a pocket.

**One channel.** A game used to move between three, from a reminder to a live card to a final. A card cannot change channel without being thrown away and re-posted. The new one is silent by construction: no sound, no vibration. The buzz and the box own the interruption, as they always did.

**Every card clears.** A finished game's card is swipeable, and the ticker takes the ongoing flag off anything it leaves behind. A game that ended while the phone was asleep used to leave a row nothing could clear.

**Only the first live game asks for the lock face.** Four ongoing rows on a Sunday would be the whole screen. The rest are in the shade.

## BrightSports v2.3 — the live card reaches the lock screen

**The score card now asks to stay.** While a followed game is on, BrightSports keeps an ongoing card in the shade. BrightControl's lock face dropped it, and it was right to. A foreground service's notification carries the same two flags whether it is a download, a VPN, or a score. A lock screen full of receipts is what that filter is for. The card now sets one extra, `com.gios.lightcontrol.extra.LOCK_KEEP`, to say it is content rather than a receipt. BrightControl v4.29 keeps a card that sets it. A phone without BrightControl ignores the extra.

**The card says more.** One live game shows the score on the first line and the situation on the second. Football: "SEA ball · 2nd & 7 at NE 16 · RED ZONE". Baseball: "2-1 · 1 out · Runners on 1st and 2nd". Tapping it opens that game. Two or more games list them all, and the card opens the feed.

**The spoiler hold covers the second line too.** With the hold on, the card reads "Patriots at Seahawks · Q2" and nothing else. A drive that has reached the ten gets ahead of a stream as surely as a score does.

**Also:** the card is public, so a secured lock screen shows it instead of redacting it. Its category is status, not service.

## BrightSports v2.2 — live scores over a relay, not a poll

**One socket instead of a poll.** A relay on BasilNet (`relay/` in this repo) sits on ESPN's own live feed, the FastCast websocket behind espn.com's scoreboard. It pushes each change to `sports.gzl.dev` the moment it lands. The phone opens one websocket to that host and subscribes to the games in its own feed. A score arrives in one to two seconds. The poll took fifteen seconds with the screen on and thirty to sixty in a pocket.

**Nothing changes in what you hear.** A relay message lands on the game the phone already has. It goes through the same diff as a polled one. Same loudness, same red zone and one-score rules, same spoiler hold. The relay never decides what is worth a buzz.

**The poll stays underneath.** While the socket is up, the live ticker polls every five minutes as a check. The open game screen fetches once a minute. If the socket drops, the next tick goes back to the old pace. If BasilNet is down, the app is the v2.1 app.

**Privacy.** The relay does not know who follows what. It publishes every live game to its own topic and each phone subscribes to its own. Phones read only. The relay alone can publish.

**Settings → Delivery → Live relay** shows whether the socket is up and when the relay last sent a heartbeat. Off returns to polling.

**Coverage.** NFL, college football, MLB, NBA, WNBA, NHL and every soccer league the app carries. Racing, tennis, the minor leagues and the PWHL stay on the poll. ESPN's live feed has no channel for them.

## BrightSports v2.1 — find any game, and a refresh button

**Find a game.** A search tab in the action bar. Type a team, a player or a league. The app lists its games in the feed's window: live first, then by day. Followed or not. Type "NFL" for the whole slate, "Chiefs" for one club. The team lists are on disk from the picker, so the app fetches only the leagues with a match.

**Refresh.** The circular arrow is back in the action bar, between Find and Standings. It re-fetches whatever tab is up: the feed, the standings, or the last search. The week chevrons in the top bar stay.

**Tap a team, get the game.** Tap a team's mark on a game screen while that team is playing. Or tap the team in NO GAME THIS WEEK. The app opens the live game instead of the season page. The season page is one back-press away. It shows a NOW row above the season while a game is on.

## BrightSports v2.0 patch — the score column, and tennis

**Scores line up again.** On the feed, each row's score sat a different distance from the right edge. The distance depended on the team name. The mark and the record now share one slot. The score sits at the edge, as in the mock.

**Tennis reads like tennis.** A live or finished match shows the set line under the two names ("6-3 1-6 1-0"). The column at the right stays sets won. Long names shorten with an ellipsis instead of pushing the score off the row. A match that has not started shows VS between the players, not AT.

## BrightSports v2.0 — every sport gets its strip

Part four closes the football work and carries the same game screen to the other sports. The strip under the score is the one part that changes per sport. The header, the last plays and the line score stay the same.

**Baseball.** The diamond with the runners on it, the count in the scoreboard face, the outs as three squares, and who is up with their day so far ("B. Callahan batting · 1-2, 2B, RBI"). Under it, the pitcher and his line ("T. Adams pitching · 2.1 IP, 0 ER, H, 2 K, 2 BB"). The line score adds H and E columns. ESPN only. The minor leagues come from MLB StatsAPI, which has no situation block in the schedule the app polls.

**Soccer.** Shots, shots on target and possession for each side. A TIMELINE view in place of SCORING: goals with the running score, penalties, own goals, yellow and red cards, newest first. It comes from the scoreboard's own `details` list, so it costs no extra request. A finished match opens on the timeline.

**Basketball.** Each side's scoring leader ("BRUNSON 34 PTS") and shooting line (FG %, 3PT %, rebounds). The last plays come from the play-by-play. There is no scoring list. A basketball game has two hundred baskets and the summary is half a megabyte.

**Hockey.** Shots on goal for each side and the save percentage. ESPN does not send shots as a team stat. The app derives them from the other side's saves plus this side's goals, which is the same number. Power-play state is not in the feed. The strip leaves it out rather than guess.

**Everything else from v1.27 through v1.29** is in this build: the football feed rows, the TD/FG alerts, the touchdowns loudness, the red zone and one-score moments, the field, the scoring summary, week paging, the team page and the bye row.

## BrightSports v1.29 — the week, the team, and the bye

Part three. The feed pages by week, every team has a season page, and a team on its bye says so.

**Week paging.** The two chevrons in the top bar move the feed a week at a time, four weeks either way. The title reads the games in view. WEEK 2 over a week of NFL. SEP 10 – 15 over a week of baseball. The line under it shows the date range and ALL FINAL once the week ends. It also shows your teams' record for that week, as 3–1 FOR YOUR TEAMS. Refresh moved: tap the UPDATED line.

**The team page.** Tap a team's mark on the game screen, or a team in NO GAME THIS WEEK, to open its season. The nickname in the scoreboard face, the record, and the division and place from the standings. Then a row per week. W or L with the score. A live score with the clock. The next kickoff with its network. Or BYE. Tap a row to open that game, played or not. Every ESPN league gets the page. Football lists all eighteen weeks. The others list their games.

**The bye row.** A followed football team with no game in the window sat under NO GAME SCHEDULED with nothing else to say. It now reads BYE · NEXT VS BAL · SUN SEP 20 4:25 PM, from the team schedule.

**What it costs.** The schedule for one NFL team is about 240 KB, because every event carries both clubs' records and logos. The app stores it for six hours. A game that finishes is already in the feed, so the schedule can lag without anyone noticing.

## BrightSports v1.28 — the game screen shows the field

Part two of the football work. The game screen is new from the top down. The feed and the alerts stay as they were in v1.27.

**The header.** The away mark on the left, the home mark on the right, the score between them in the scoreboard face. Under each mark: the nickname, the poll rank, the record, and the timeouts left as three squares. A pre-game shows AT between the marks, or VS on a neutral field. The line above gives the quarter and the clock. The line to the right says how fresh the screen is.

**The field.** A live football game draws the field under the score. The offense's end zone is on the left and carries its mark. The app fills the ground gained so far. A white line and a marker show the ball. A dashed line shows the first-down spot. The fifty is the brighter line in the middle. Above the field: who has the ball and the down and distance, with a RED ZONE tag inside the 20. Below it: the drive so far (7 plays, 58 yards, 3:41) and the type of the last play.

**Last plays.** The six most recent plays, newest first, with the clock and the down each one started on. The app fetches these with each 15-second refresh while a live game is open. It never fetches them in the background. The request is ~36 KB. The source is ESPN's play-by-play with `sort=desc`, which puts the newest plays on the first page.

**Scoring summary.** A switch under the field toggles between the line score (BY QUARTER) and SCORING. The scoring view lists each score, newest first. Each row has the kind (TD or FG), the play as the box score writes it, the quarter and clock, the team, and the running score. A finished football game opens on SCORING. The app fetches the summary once per score. It stores a finished game's summary on the phone and never fetches it twice.

**Other sports.** The header and the line score apply to every sport. A live baseball game shows the batter, the count, the outs and the runners under the score. The strips for baseball, soccer, basketball and hockey come in v2.0.

## BrightSports v1.27 — football alerts that say what happened

The first of four football releases for the 2026 season. This one changes the feed rows and the alerts. The game screen, the team page and the week view come next.

**The feed row reads the game.** Each team is a crest and a three-letter mark in a condensed face. A live football row shows who has the ball, the down and distance, and the timeouts left. Inside the 20 it shows a RED ZONE tag. A pre-game row shows the line, the total and the forecast. A finished row shows the ESPN headline. College teams show their poll rank.

**Days, not buckets.** Upcoming games sit under their own day: THURSDAY, SATURDAY, SUNDAY. Results sit under YESTERDAY and LAST SUNDAY. A football week is four days of games. One header for all four said nothing.

**Alerts name the play.** The title of a football score alert is TD, FG, SAFETY or TD +2, then the team that scored and the score. The body is the play in the provider's words and the game clock. Example: "S.Darnold pass deep right to J.Smith-Njigba for 31 yards, TOUCHDOWN · Q2 3:24". A touchdown seen at six points waits 75 seconds for its kick. It posts once, as seven. The on-screen box draws the label large.

**Football has its own loudness.** Settings gains a FOOTBALL ALERTS section with four levels: every score, touchdowns, quarters, final. The default is touchdowns. At that level the app does not announce a field goal on its own. The quarter mark and the final carry the score.

**Two new moments.** Red zone: one alert when a team you follow crosses the 20, once per trip. One-score game: one alert when the fourth quarter reaches 5:00 with the margin inside eight. Both are on by default. Both wait out the spoiler setting. A third switch, "Halftime and final", turns the period marks and the final off for every sport.

**BrightControl reads the same words.** The title and text stay as plain notification extras. The banner and the lock face in BrightControl show the new alerts with no change on their side.

## BrightSports v1.26 — the score screen stays awake while it's updating

**A live game no longer lets the panel sleep out from under itself.**

Open a live game — or one about to start — and the screen re-fetches every fifteen seconds.
Until now that only worked as long as the phone's own screen timeout hadn't fired, so a game
you were watching tick along would dim and lock between refreshes, and the fifteen seconds you
were promised became "whenever you next wake it up." While a game is open and tracking, the
screen now stays on; leave the game, or open a final that's settled and no longer updating, and
the panel sleeps normally again. Same keep-awake as the rest of the fleet — it rides the screen
flag, not a wakelock, so there's no battery cost beyond the screen being on, which is the thing
you asked for.

## BrightSports v1.25 — FCS, the Grand Slams, and a score screen that keeps up

Three additions, none of them a fix.

**FCS college football.** UC Davis, Montana, the Ivies, the SWAC — the 128 programs a division
below the FBS list the app already had. Same ESPN feed, filtered with `groups=81` the way FBS is
with `80`. When an FCS side plays up in September (Hampton at Maryland) the game is in both feeds,
and it shows once per team you follow, not twice. It sits next to FBS under College Football.

**Tennis: the four Grand Slams, singles and doubles.** A new Tennis section in My Teams. Follow
players — the roster is the ATP and WTA top 150 plus everyone in the current draws, so a
qualifier on a run is in the picker the week it matters — and a doubles pair counts for either
name. Two category toggles stand in for the usual championship and special stars: **Finals**
(every draw's final) and **Quarterfinals onward**. The score is sets, with games per set as the
line score and tiebreaks printed the way a scoreline prints them, 7(7)-6(3). The feed row reads
`US OPEN · WS · QF · 3rd`; the detail screen names the court and the round. Alerts fire on each
set won and carry the full scoreline. Standings shows the two rankings. Tour stops between the
slams are not carried — forty tournaments a year was more feed than anyone asked for.

**The open game updates itself.** Open a live game and it re-fetches every fifteen seconds for as
long as it is in progress, and says so under the header. Only that game's league is fetched, and it
stops the moment you leave the screen. A match about to start polls too, so the flip to live
happens in front of you. The background ticker is unchanged: this is for when the screen is on and
the phone is in your hand.

## BrightSports v1.24 — the live check was being refused, and nothing said so

Scores were still landing ten minutes late with the live ticker shipped, switched on, and working
perfectly whenever anyone looked at it. That last part is the whole bug.

**An inexact alarm cannot start a foreground service.** There are two ways this app checks a score:
the alarm chain, which survives Doze and is throttled to roughly one firing every nine minutes, and
the live ticker, which is not throttled and checks every thirty to sixty seconds. The alarm chain is
what hands over to the ticker when a followed game starts. Android 12 onwards refuses a foreground
service started from the background — unless the broadcast that started it came from an **exact**
alarm. This app used the inexact variant, deliberately, because it needs no permission.

So the handover was refused every time the phone was asleep. Which is the only phone the ticker was
ever written for. Open the app during a game and the ticker came straight up, because a visible
activity is allowed to start one; put the phone in a pocket and it never ran at all. The app had two
speeds and reached the fast one exactly when you were already looking at the score.

**Alarms are exact now.** Not for the precision — both variants sit under the same nine-minute Doze
floor and nothing about the schedule changes. It is for the exemption that comes with them. The
permission is granted at install and asks the user nothing.

**And a refusal was making things slower, not merely not faster.** When the service could not go
foreground it stopped itself, and stopping is what arms the next alarm — from a field that was still
zero, which means the fifteen-minute backstop. So every poll that found a live game pushed the next
one from two minutes out to fifteen, over and over, for the length of the game. A refused fast path
was actively degrading the slow one. It hands back at two minutes now.

**Settings can finally answer "why was that late".** A new Delivery section says which of the two
paths ran last and how long ago — *Live — every 30–60s* or *Alarms — up to 9 min* — and, underneath,
what is standing in the way if anything is. None of this was visible before: a refusal was one line
in a logcat nobody has attached to a phone in their pocket.

**Battery optimisation is one tap.** An app the phone has stopped putting to sleep is not in Doze at
all, so the nine-minute floor stops existing and the alarm chain runs at its stated two minutes even
if the ticker never comes up. Optional, asked for from Settings and never at launch. If LightOS
opens nothing, the row gives you the adb line.

Worth checking while you are in there: **the spoiler delay is on by default at five minutes**, and it
is added to everything above.

## BrightSports v1.23 — one card per game, and it leaves after an hour

Two reports, one cause: a score from yesterday still on the lock screen, and two cards for the
same match — the score, and a "starting soon" sitting next to it.

**The removal was scheduled on the full-time whistle, and only if the phone saw it.** A card got a
removal time on the one poll where the game went from live to final. Miss that poll — asleep, in
Doze, out of signal — and nothing ever wrote one: once the game rolled out of the scoreboard
window its snapshot went too, so the transition could never be observed again, and the card sat
there indefinitely. A Phillies game from the day before is exactly that.

**The clock now starts when a card is posted**, which needs no transition to be caught. Every later
card for the same game pushes it out, and so does the game still being in progress — so a quiet
second half never loses the score, and the rule ends up being the simple one: **an hour after the
last thing worth saying.**

**And the shade is now a second source of truth.** Anything in it that nothing is tracking and that
is over an hour old gets cleared. That is what removes the cards already stuck on your phone, which
no amount of new tracking would have reached. The live ticker's own card is excluded — it is the
receipt for a running service, not news.

**"Starting soon" goes at kickoff.** It had no way to leave: with a league set to final-only there
is no later card to replace it, so it stayed in the shade beside the score for the whole game and
read as two notifications about one match. It is dropped the moment the game goes live.

**And it is never posted late.** Being *due* and being *still true* are different questions, and a
phone that was asleep is the gap between them — a "starts in 15 minutes" held in the queue used to
post on the next wake-up, announcing a game already in the second inning. Schedule alerts now carry
an expiry: "starting soon" dies at kickoff, "they're under way" after ten minutes. A score has no
expiry, because a score is still a score whenever you read it.

**Opening the app sweeps.** A poll is what normally clears things, and a poll needs an alarm that
fired — which is precisely what has not been happening on a phone showing yesterday's game.

## BrightSports v1.22 — one box for a goal, not two

BrightControl v3.65 grew a heads-up box of its own. It reads the shade and puts the same box over
the screen for whatever posted — including the notification this app raises a moment before it
draws its own. So with both switched on, a goal was one buzz and **two boxes**, one landing on top
of the other.

**This one now stands aside.** BrightControl says who is drawing the box; when it is, the box here
is skipped. The setting is untouched — it still reads as yours, and the row shows CONTROL rather
than pretending to be on while nothing appears. Turn banners off over there and this app's box
comes straight back, with nothing to set here.

**The buzz and the notification never change.** Both happen before the gate, and both have to: the
notification is the record BrightControl reads and the janitor clears an hour after full time. If
BrightControl's listener grant ever lapses, this app has still buzzed and still filled the shade.

**And it checks BrightControl is really there.** A remembered claim from an app that has since been
uninstalled would have silenced this box for good, with nothing on the phone to explain why.

## BrightSports v1.21 — the new key is withdrawn; this installs over what you have

**No uninstall. This is an ordinary update.** v1.20 was signed with a brand-new certificate, which
meant it could only be installed by removing the app first and losing followed teams, alert settings and the score snapshot.
That cost was not worth what it bought, so it has been withdrawn. v1.21 is signed with the same
certificate every release before v1.20 used, and it installs straight over the copy on your phone.

If you already uninstalled and installed v1.20, this one will not go over it — uninstall once more
and install v1.21, and that is the end of it.

**What this does and does not fix.** The signing key is no longer committed to this repository and
the file is gitignored, so a fresh clone does not hand it out. But it is still in this repository's
git history and always will be, so treat it as public: anyone determined enough can still build an
APK this phone would accept as an update. Closing that for real needs an APK Signature Scheme v3
rotation — signing with a new key while carrying a proof-of-rotation signed by the old one, which
Android accepts as a normal update — and that is a separate change, done carefully, not bundled in
behind an uninstall.

Everything else in v1.20 stands and is still here.

## BrightSports v1.20 — a new signing key, and one reinstall to take it

**Withdrawn.** The key change described below was reverted in v1.21; see the top of this file. The rest of this release stands.
**You have to uninstall BrightSports and install it again.** Not an update — a full
uninstall first. Android identifies an app by its package name *and* the certificate it was
signed with, so a build signed with a different key is a different app as far as the phone
is concerned. Installing this one over the old one fails with a bare `Failure: Invalid` and
no explanation. Uninstall, then install; it is a one-time cost and no release after this one
asks for it again.

Uninstalling clears the app's data, which here means your followed teams, alert settings and
the score snapshot the background poller keeps. Note down what you follow before you start.

**Why.** The release key was committed to this repository with its password written three
lines under it in `app/build.gradle.kts`. Anyone who cloned it could build an APK that
Android would accept as an update to the one on your phone — which is the entire protection
Android offers, handed out with the source. The old key is retired. The new one is a CI
secret: the workflow decodes it at build time, `keystore/*.jks` is gitignored so a checkout
cannot commit it back, and the certificate the release actually carries is checked against
`signing-fingerprint.txt` before anything is published.

A build without the secret — a branch check, a local clone — still compiles and still
produces an APK. It just is not signed with the release key and will not install over one.
That is the right way for it to fail.

**Also in this build.** Every GitHub Action the workflows use is pinned to a commit SHA
rather than a moving tag, so a retagged or compromised action cannot quietly change what
builds your APK. `check.yml` declares read-only permissions. And the release body is these
notes now rather than an auto-generated commit list — `RELEASE_NOTES.md` was being written
every version and read by nothing, which is why a release this disruptive could otherwise
have shipped with no warning on it at all.

Scores, leagues, alerts and the notification janitor are untouched.

## BrightSports v1.19 — a delay has to mean it before it interrupts you

**The phone was buzzing during innings where nothing happened.** Twice, usually: "delayed", then
"back on", a minute apart, in the middle of a baseball game nobody had scored in.

Nothing was wrong with the score logic. Baseball has never marked innings — `markPeriods` is false
for MLB and all four MiLB levels and always has been — and a score alert needs a run to actually
change. The buzzes were the delay pair, and v1.18 is what let them through.

ESPN does not report a delay as its own state. It rides on top of an ordinary live game: `state:
"in"`, `completed: false`, and a *name* of `STATUS_RAIN_DELAY` or `STATUS_DELAYED`. Anything
carrying one of those names reads as OFF, which is right — it is how a rain delay gets announced at
all. What changed is how often the app looks. Until v1.18 background polling sat on Doze's
nine-minute floor, so a delay lasting ninety seconds was invisible: the game was live at one poll
and live at the next, and nothing was ever said. v1.18 dropped that to thirty to sixty seconds
during a live game, and baseball generates these constantly — a replay review, a pitching change, a
groundskeeper on the tarp. Each one now landed squarely inside a poll interval, and each one was
two notifications about nothing.

**A delay now has to survive two consecutive polls before it is worth interrupting for.** The first
sighting is recorded and nothing is said. If it is still there on the next poll it is announced
exactly as before; if it has cleared, neither half ever fires — no "delayed", and no "back on"
either, because the end of something you were never told about is not news.

That last part is why this is two pieces of state rather than a counter. "Resumed" is keyed on
whether the delay was actually *announced*, not on the previous state, so a blip cannot leave an
orphaned all-clear behind it. Both are written to the snapshot file, because the alarm path detects
a delay inside a broadcast receiver that dies seconds later — a count held in memory would restart
at zero on every poll and never confirm anything. For the same reason the watcher now stores the
*advanced* snapshot rather than the raw one: the poll that increments this count is, by definition,
the poll that stays silent, and the old code only wrote back a snapshot when something had fired.

The threshold costs a real rain delay one poll of lateness, which during a live game is thirty to
sixty seconds. That is the right way round: the fault is a phone buzzing about nothing, and a rain
delay is still a rain delay a minute later. Scores, finals, starts and period marks are untouched —
only the delay pair waits.

Reported by Giovanni: baseball notifications arriving for innings with no runs in them.
