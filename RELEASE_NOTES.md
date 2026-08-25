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
