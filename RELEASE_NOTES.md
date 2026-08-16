## BrightSports v1.18 — Scores while the game is still on

**During a game the app now checks every 30 to 60 seconds instead of every nine minutes.**

The old delay was not the two-minute interval the code said it was. Background polling runs
on `setAndAllowWhileIdle`, the one alarm that fires while the phone is asleep, and the
system throttles that to roughly one firing every nine minutes once the screen has been off
a while. So the interval was two minutes on paper and nine in a pocket, and the spoiler
delay stacked on top of it: a run scored to open an inning could land on the phone after
the inning had ended. Settings said so out loud, which made it honest but no faster.

A foreground service is not throttled. So the alarm chain still keeps the schedule, and the
moment a followed team is actually playing, a service takes over and polls properly. It
stops itself at the final whistle. Nothing changes on an evening with no games: still one
check every three hours, still one fifteen minutes before the next start.

The cadence is tiered rather than flat, because a poll is the expensive thing — a cold
radio, two or three fetches, a wakelock. A minute apart during an ordinary game. Thirty
seconds when one is close and late: past regulation always counts, and inside regulation
what counts as close is per sport, since one goal in the third period is the whole sport
and six runs in the ninth is over. Up to five minutes while waiting on a first pitch that
has not happened yet, closing in as the scheduled time arrives — a start time is a plan,
not a promise.

A foreground service has to show a card, so the card was made worth having: it carries the
live score for as long as the game runs, and goes when it ends. **Unless the spoiler delay
is on** — then it shows the matchup and the period and no score at all. The entire point of
that setting is that the phone must not get ahead of a stream running two minutes behind,
and a card sitting in the shade with the current score on it would walk straight through
it.

Three things stop it being a battery leak. It only runs for games that are allowed to
interrupt, so a team you have silenced never raises it. It gives up after six hours and
hands back to the alarms — a provider leaving a game stuck at LIVE is not hypothetical
here, ESPN did it to two Grands Prix for a whole season. And the wakelock is held across
the fetch only, never across a sleep.

Handing over works in both directions. While the service is up, the alarm stops fetching
and just keeps a fifteen-minute backstop in the diary, so a service the system kills for
memory is picked up rather than lost; and however the service dies, the last thing it does
is re-arm the alarm. The slow path is still there underneath, unchanged, which is what
makes the fast one safe to attempt: Android refuses background foreground-service starts
outside a short list of exemptions, and a refusal here is logged and ignored, not crashed
on.

**Live updates** in Settings turns the whole thing off and puts the app back on alarms
alone, card and all.
