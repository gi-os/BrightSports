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
