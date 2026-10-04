"""Tape Delay: the iPhone side of the relay.

iOS will not let an app poll in the background, so on the iPhone the alert has to come from
here. The relay already sees every change FastCast sends; this module watches the same
snapshots, works out what is worth a buzz (the same rules as BrightSports' ScoreDiff, in
miniature), and hands each device its alerts and Live Activity updates *late*: every push is
held for that device's own delay, so the lock screen never gets ahead of the stream.

Also serves `GET /tape/v1/delayed`, the state of a game as it was `delay` seconds ago, so
the app's own screens can stay behind the stream too.

State: devices and their follows in SQLite (`TAPE_DB`); pending pushes and snapshot history
in memory. A restart loses at most the pushes still being held, which is a few minutes.
"""
import asyncio, heapq, json, logging, os, sqlite3, time
from collections import deque

log = logging.getLogger("tape")

DB_PATH = os.environ.get("TAPE_DB", "/data/tape.db")
TOPIC = os.environ.get("APNS_TOPIC", "com.gios.tapedelay")
MAX_DELAY = 15 * 60
HISTORY_SECONDS = MAX_DELAY + 120

# ESPN uid sport ids.
SPORTS = {"20": "football", "1": "baseball", "40": "basketball", "70": "hockey", "600": "soccer"}


def sport_of(uid):
    for part in (uid or "").split("~"):
        if part.startswith("s:"):
            return SPORTS.get(part[2:], "other")
    return "other"


def league_uid(uid):
    """`s:20~l:28~e:401` -> `s:20~l:28`."""
    return "~".join(p for p in (uid or "").split("~") if p[:2] in ("s:", "l:"))


def teams_of(ev):
    """{"home": {...}, "away": {...}} with uid, names and colour, from the raw ESPN event."""
    comp = (ev.get("competitions") or [{}])[0]
    lg = league_uid(ev.get("uid"))
    out = {}
    for c in comp.get("competitors") or []:
        t = c.get("team") or {}
        tid = t.get("id")
        out[c.get("homeAway", "home")] = {
            "uid": t.get("uid") or (f"{lg}~t:{tid}" if lg and tid else None),
            "id": tid,
            "abbr": t.get("abbreviation") or "",
            "name": t.get("shortDisplayName") or t.get("name") or t.get("displayName") or "",
            "color": t.get("color") or "888888",
            "record": ((c.get("records") or [{}])[0] or {}).get("summary") or "",
            "form": c.get("form") or "",
            **probable(c),
        }
    return out


def probable(c):
    """The listed starter (MLB pitcher, NHL goalie), with a short season line when ESPN has one."""
    ps = c.get("probables") or []
    if not ps:
        return {}
    p = ps[0]
    name = ((p.get("athlete") or {}).get("shortName") or (p.get("athlete") or {}).get("displayName") or "")
    stats = {s.get("abbreviation"): s.get("displayValue") for s in p.get("statistics") or [] if s.get("abbreviation")}
    line = ""
    if stats.get("W") is not None and stats.get("L") is not None:
        line = f"{stats['W']}-{stats['L']}" + (f" · {stats['ERA']}" if stats.get("ERA") else "")
    elif p.get("record"):
        line = str(p["record"])
    return {"probable": name[:24], "probableLine": line[:16]}


def watch(ev):
    """Where to watch: national channels first, then the local ones, deduplicated."""
    comp = (ev.get("competitions") or [{}])[0]
    names = []
    for b in sorted(comp.get("broadcasts") or [], key=lambda b: 0 if b.get("market") == "national" else 1):
        for n in b.get("names") or []:
            if n and n not in names:
                names.append(n)
    return names


def start_time(ev):
    from datetime import datetime
    d = ev.get("date") or ((ev.get("competitions") or [{}])[0]).get("date")
    if not d:
        return None
    for fmt in ("%Y-%m-%dT%H:%MZ", "%Y-%m-%dT%H:%M:%SZ", "%Y-%m-%dT%H:%M:%S.%fZ"):
        try:
            return datetime.strptime(d, fmt).replace(tzinfo=__import__("datetime").timezone.utc).timestamp()
        except ValueError:
            pass
    return None


# ---------------------------------------------------------------- the diff

def _score(side):
    return (side or {}).get("sc")


def _delayed(snap):
    nm = (snap.get("nm") or "").upper()
    return any(w in nm for w in ("DELAY", "SUSPEND", "POSTPONE", "CANCEL"))


def _period_end(snap):
    nm = (snap.get("nm") or "").upper()
    dt = (snap.get("dt") or "").lower()
    return nm in ("STATUS_HALFTIME", "STATUS_END_PERIOD", "STATUS_END_OF_PERIOD") \
        or dt.startswith("end ") or dt == "halftime"


def diff(prev, snap, sport, marked_period=None):
    """What happened between two snapshots of one game.

    A list of (kind, arg): kind is start / score / period / final / delay / resume; arg is
    "home"/"away" for a score, the period number for a period end, else None.
    `marked_period` is the last period an end was announced for, so the fallback (period
    number went up) cannot announce the same one twice. A game seen for the first time never
    alerts, so a restart mid-Sunday does not replay the day.
    """
    if prev is None:
        return []
    ev = []
    was, now = prev.get("st"), snap.get("st")
    if was == "pre" and now == "in" and not _delayed(snap):
        ev.append(("start", None))
    if _delayed(snap) and not _delayed(prev):
        ev.append(("delay", None))
    elif _delayed(prev) and not _delayed(snap) and now == "in":
        ev.append(("resume", None))
    if now in ("in", "post"):
        for side in ("away", "home"):
            a, b = _score(prev.get(side)), _score(snap.get(side))
            if a is not None and b is not None and b > a:
                ev.append(("score", side))
    if now == "in" and sport != "baseball":
        p = snap.get("p")
        ended = None
        if _period_end(snap) and not _period_end(prev):
            ended = p
        elif isinstance(p, int) and isinstance(prev.get("p"), int) and p > prev["p"]:
            ended = prev["p"]
        if ended is not None and ended != marked_period:
            ev.append(("period", ended))
    if now == "post" and was == "in":
        ev.append(("final", None))
    return ev


# Basketball scores every forty seconds, so by default it gets period marks and the final,
# not every basket (BrightSports' PERIOD_ONLY rule). Everything else: every score.
DEFAULT_KINDS = {"start", "score", "period", "final", "delay", "resume"}
SPORT_SKIP = {"basketball": {"score"}}


def wanted(kind, sport, prefs):
    if kind in SPORT_SKIP.get(sport, set()) and not prefs.get("everyBasket"):
        return False
    return bool(prefs.get(kind, kind in DEFAULT_KINDS))


def line(snap, teams):
    a, h = snap.get("away") or {}, snap.get("home") or {}
    aa = teams.get("away", {}).get("abbr") or a.get("ab") or "AWAY"
    ha = teams.get("home", {}).get("abbr") or h.get("ab") or "HOME"
    return f"{aa} {a.get('sc') or 0} – {ha} {h.get('sc') or 0}"


def alert_text(kind, side, snap, teams, sport):
    """(title, body) for one event, from the snapshot at the moment it happened."""
    score = line(snap, teams)
    dt = snap.get("dt") or ""
    name = lambda s: teams.get(s, {}).get("name") or (snap.get(s) or {}).get("ab") or s
    matchup = f"{name('away')} at {name('home')}"
    if kind == "start":
        return matchup, "First pitch" if sport == "baseball" else "Under way"
    if kind == "score":
        verb = {"soccer": "Goal", "hockey": "Goal", "baseball": "Run"}.get(sport, "Score")
        lp = (snap.get("sit") or {}).get("lp")
        body = f"{score} · {dt}" if dt else score
        if lp and sport in ("football", "baseball"):
            body += f"\n{lp}"
        return f"{verb}: {name(side)}", body
    if kind == "period":
        return matchup, f"{score} · {dt}" if dt else score
    if kind == "final":
        return "Final", score + (f" · {dt}" if dt and dt.lower() != "final" else "")
    if kind == "delay":
        return matchup, dt or "Delayed"
    if kind == "resume":
        return matchup, f"Back under way · {score}"
    return matchup, score


def content_state(snap):
    """The Live Activity ContentState. Keys must match `GameAttributes.ContentState` in the app."""
    sit = snap.get("sit") or {}
    h, a = snap.get("home") or {}, snap.get("away") or {}
    st = {
        "home": h.get("sc") or 0,
        "away": a.get("sc") or 0,
        "detail": snap.get("dt") or "",
        "state": snap.get("st") or "pre",
        "period": snap.get("p") or 0,
    }
    poss = sit.get("poss")
    if poss and poss in (h.get("id"), a.get("id")):
        st["possession"] = "home" if poss == h.get("id") else "away"
    if sit.get("sdd") or sit.get("dd"):
        st["down"] = sit.get("sdd") or sit.get("dd")
    for k, key in (("o", "outs"), ("b", "balls"), ("s", "strikes")):
        if isinstance(sit.get(k), int):
            st[key] = sit[k]
    if any(k in sit for k in ("on1", "on2", "on3", "o")):
        st["bases"] = [bool(sit.get("on1")), bool(sit.get("on2")), bool(sit.get("on3"))]
    if sit.get("lp"):
        st["lastPlay"] = sit["lp"][:140]
    # Scorebug extras (keys match GameAttributes.ContentState; all optional there).
    if snap.get("ck"):
        st["clock"] = snap["ck"]
    if sit.get("spot"):
        st["spot"] = sit["spot"]
    if sit.get("rz"):
        st["redZone"] = True
    for k, key in (("hto", "homeTimeouts"), ("ato", "awayTimeouts")):
        if isinstance(sit.get(k), int):
            st[key] = sit[k]
    for k, key in (("pit", "pitcher"), ("pits", "pitcherLine"), ("bat", "batter"), ("bats", "batterLine")):
        if sit.get(k):
            st[key] = str(sit[k])[:40]
    for k, key in (("hb", "homeBonus"), ("ab", "awayBonus")):
        if sit.get(k):
            st[key] = True
    for k, key in (("hf", "homeFouls"), ("af", "awayFouls")):
        if isinstance(sit.get(k), int):
            st[key] = sit[k]
    for side, pre in ((h, "home"), (a, "away")):
        if isinstance(side.get("h"), int):
            st[pre + "Hits"] = side["h"]
        if isinstance(side.get("e"), int):
            st[pre + "Errors"] = side["e"]
    return st


def attributes(eid, ev, teams):
    h, a = teams.get("home", {}), teams.get("away", {})
    return {
        "gameId": eid,
        "sport": sport_of(ev.get("uid")),
        "homeAbbr": h.get("abbr", ""), "awayAbbr": a.get("abbr", ""),
        "homeName": h.get("name", ""), "awayName": a.get("name", ""),
        "homeColor": h.get("color", "888888"), "awayColor": a.get("color", "888888"),
        "homeUid": h.get("uid") or "", "awayUid": a.get("uid") or "",
        "homeRecord": h.get("record", ""), "awayRecord": a.get("record", ""),
        # Starting-soon card: all optional on the phone.
        "startTime": start_time(ev) or 0,
        "venue": (((ev.get("competitions") or [{}])[0]).get("venue") or {}).get("fullName") or "",
        "watch": " · ".join(watch(ev)[:3]),
        "homeProbable": h.get("probable", ""), "awayProbable": a.get("probable", ""),
        "homeProbableLine": h.get("probableLine", ""), "awayProbableLine": a.get("probableLine", ""),
        "homeForm": h.get("form", ""), "awayForm": a.get("form", ""),
    }


# ---------------------------------------------------------------- the league card

LEAGUES = {"s:1~l:10": "MLB", "s:20~l:28": "NFL", "s:20~l:23": "NCAAF", "s:40~l:46": "NBA",
           "s:40~l:59": "WNBA", "s:40~l:41": "NCAAM", "s:70~l:90": "NHL", "s:600~l:770": "MLS",
           "s:600~l:700": "EPL", "s:600~l:775": "UCL", "s:600~l:740": "LALIGA", "s:600~l:720": "BUNDESLIGA",
           "s:600~l:730": "SERIE A", "s:600~l:710": "LIGUE 1", "s:600~l:19483": "NWSL"}
PER_PAGE = 3          # tiles beside yours on each page of the league card
LEAGUE_GAP = 8        # seconds between league-only pushes to one card (other games' changes)
ORD = {1: "1ST", 2: "2ND", 3: "3RD", 4: "4TH"}


def tile_detail(snap, sport):
    """The short status in a league tile: "▲5", "3RD 14:48", "Q4 4:12", "72'", "FINAL"."""
    st = snap.get("st")
    if st == "post":
        return "FT" if sport == "soccer" else "FINAL"
    if st != "in":
        return ""     # the phone prints the start time
    dt = (snap.get("dt") or "").strip()
    p, ck = snap.get("p") or 0, (snap.get("ck") or "").strip()
    if dt.lower() == "halftime" or (snap.get("nm") or "") == "STATUS_HALFTIME":
        return "HALF"
    if sport == "baseball":
        d = dt.lower()
        return ("▲" if d.startswith("top") else "▼" if d.startswith("bot") else
                "MID " if d.startswith("mid") else "END " if d.startswith("end") else "") + str(p)
    if _period_end(snap):
        return dt.upper()[:10]
    if sport == "soccer":
        return dt or ck
    if sport == "basketball":
        return (f"Q{p}" if p <= 4 else "OT") + (f" {ck}" if ck else "")
    return f"{ORD.get(p, 'OT')} {ck}".strip()


def series_note(ev):
    comp = (ev.get("competitions") or [{}])[0]
    ser = comp.get("series") or {}
    if ser.get("summary"):
        import re
        t = re.sub(r"\s+(leads?|wins?|won)\s+(the\s+)?(series\s+)?", " ", ser["summary"])
        return re.sub(r"^Series tied\s+", "Tied ", t)[:14]
    return ""


def league_title(ev):
    comp = (ev.get("competitions") or [{}])[0]
    notes = comp.get("notes") or ev.get("notes") or []
    head = (notes[0].get("headline") if notes else "") or ""
    if head:
        return head.split(" - ")[0].upper()[:22]
    wk = (ev.get("week") or {}).get("number")
    return f"WEEK {wk}" if wk else "TODAY"


def game_day(ts):
    """The calendar day in New York a game belongs to (a 10pm PT start is still tonight)."""
    from datetime import datetime, timedelta, timezone
    return (datetime.fromtimestamp(ts, timezone.utc) - timedelta(hours=4)).date() if ts else None


# ---------------------------------------------------------------- storage

class Store:
    def __init__(self, path=DB_PATH):
        d = os.path.dirname(path)
        if d:
            os.makedirs(d, exist_ok=True)
        self.db = sqlite3.connect(path, check_same_thread=False)
        self.db.executescript("""
            CREATE TABLE IF NOT EXISTS devices(
              token TEXT PRIMARY KEY, env TEXT, delay INTEGER, follows TEXT, prefs TEXT,
              start_token TEXT, updated REAL);
            CREATE TABLE IF NOT EXISTS activities(
              device TEXT, game TEXT, token TEXT, updated REAL, PRIMARY KEY(device, game));
            CREATE TABLE IF NOT EXISTS started(device TEXT, game TEXT, PRIMARY KEY(device, game));
            CREATE TABLE IF NOT EXISTS starts(
              device TEXT, game TEXT, tries INTEGER, at REAL, dismissed INTEGER,
              PRIMARY KEY(device, game));
        """)
        self.db.commit()
        self.devices = {}
        for tok, env, delay, follows, prefs, start, _ in self.db.execute("SELECT * FROM devices"):
            self.devices[tok] = {"token": tok, "env": env or "prod", "delay": delay or 0,
                                 "follows": set(json.loads(follows or "[]")),
                                 "prefs": json.loads(prefs or "{}"), "start": start}
        self.activities = {}   # game -> {device: live activity token}
        for dev, game, tok, _ in self.db.execute("SELECT * FROM activities"):
            self.activities.setdefault(game, {})[dev] = tok
        self.started = set(self.db.execute("SELECT device, game FROM started"))
        # Push-to-start bookkeeping: (device, game) -> [tries, last try at, dismissed].
        self.starts = {(d, g): [t or 0, a or 0, bool(x)] for d, g, t, a, x in
                       self.db.execute("SELECT * FROM starts")}

    def upsert(self, token, env, delay, follows, prefs, start):
        delay = max(0, min(int(delay or 0), MAX_DELAY))
        follows = sorted({str(f) for f in follows or []})[:300]
        prefs = prefs if isinstance(prefs, dict) else {}
        self.db.execute("INSERT OR REPLACE INTO devices VALUES(?,?,?,?,?,?,?)",
                        (token, env, delay, json.dumps(follows), json.dumps(prefs), start, time.time()))
        self.db.commit()
        self.devices[token] = {"token": token, "env": env, "delay": delay, "follows": set(follows),
                               "prefs": prefs, "start": start}

    def drop_device(self, token):
        self.db.execute("DELETE FROM devices WHERE token=?", (token,))
        self.db.execute("DELETE FROM activities WHERE device=?", (token,))
        self.db.commit()
        self.devices.pop(token, None)
        for g in self.activities.values():
            g.pop(token, None)

    def set_activity(self, device, game, token):
        if token:
            self.db.execute("INSERT OR REPLACE INTO activities VALUES(?,?,?,?)", (device, game, token, time.time()))
            self.activities.setdefault(game, {})[device] = token
            # The card showed up: no more push-to-starts for this game, even once it's gone.
            r = self.starts.setdefault((device, game), [0, 0, False])
            r[0] = max(r[0], START_TRIES)
            self.db.execute("INSERT OR REPLACE INTO starts VALUES(?,?,?,?,?)", (device, game, r[0], r[1], int(r[2])))
        else:
            self.db.execute("DELETE FROM activities WHERE device=? AND game=?", (device, game))
            self.activities.get(game, {}).pop(device, None)
        self.db.commit()

    def note_start(self, device, game, *, sent=None, dismissed=None, now=None):
        """Record a push-to-start try (`sent`=True/False) or the card being swiped away."""
        r = self.starts.setdefault((device, game), [0, 0, False])
        if sent is not None:
            r[0] += 1
            # A refused push can go again in 30 s; one Apple took gets 3 minutes to show up.
            r[1] = (time.time() if now is None else now) - (0 if sent else START_WAIT - 30)
        if dismissed is not None:
            r[2] = dismissed
        self.db.execute("INSERT OR REPLACE INTO starts VALUES(?,?,?,?,?)", (device, game, r[0], r[1], int(r[2])))
        self.db.commit()

    def mark_started(self, device, game):
        self.db.execute("INSERT OR IGNORE INTO started VALUES(?,?)", (device, game))
        self.db.commit()
        self.started.add((device, game))


# ---------------------------------------------------------------- APNs

class APNs:
    """Token-based APNs over HTTP/2. Without a key it logs what it would have sent."""

    def __init__(self):
        self.key_id = os.environ.get("APNS_KEY_ID", "")
        self.team_id = os.environ.get("APNS_TEAM_ID", "")
        path = os.environ.get("APNS_KEY_FILE", "/data/apns.p8")
        self.key = open(path).read() if os.path.exists(path) else ""
        self.enabled = bool(self.key_id and self.team_id and self.key)
        self._jwt, self._jwt_at = None, 0
        self.client = None
        self.sent = 0
        self.failed = 0

    def token(self):
        import jwt  # PyJWT
        if not self._jwt or time.time() - self._jwt_at > 45 * 60:
            self._jwt = jwt.encode({"iss": self.team_id, "iat": int(time.time())}, self.key,
                                   algorithm="ES256", headers={"kid": self.key_id})
            self._jwt_at = time.time()
        return self._jwt

    async def send(self, device_token, payload, *, env="prod", push_type="alert", topic=TOPIC,
                   priority=10, collapse=None):
        """HTTP status; 410 also for BadDeviceToken; 0 when disabled or on a network error."""
        if not self.enabled:
            log.info("dry %s %s… %s", push_type, device_token[:8], json.dumps(payload)[:200])
            return 0
        import httpx
        if self.client is None:
            self.client = httpx.AsyncClient(http2=True, timeout=15)
        host = "api.push.apple.com" if env == "prod" else "api.sandbox.push.apple.com"
        headers = {"authorization": f"bearer {self.token()}", "apns-topic": topic,
                   "apns-push-type": push_type, "apns-priority": str(priority)}
        if collapse:
            headers["apns-collapse-id"] = collapse[:64]
        try:
            r = await self.client.post(f"https://{host}/3/device/{device_token}", json=payload, headers=headers)
        except Exception as e:
            self.failed += 1
            log.warning("apns %s failed: %s", push_type, e)
            return 0
        if r.status_code == 200:
            self.sent += 1
            if push_type == "liveactivity":
                aps = payload.get("aps", {})
                cs = aps.get("content-state", {})
                log.info("la %s %s… %s %s-%s %s", aps.get("event"), device_token[:8], cs.get("state"),
                         cs.get("away"), cs.get("home"), cs.get("detail"))
            return 200
        self.failed += 1
        log.warning("apns %s %s… -> %s %s", push_type, device_token[:8], r.status_code, r.text[:200])
        return 410 if r.status_code == 410 or "BadDeviceToken" in r.text else r.status_code


# ---------------------------------------------------------------- the engine

START_WAIT = 180    # seconds a push-started card has to report its update token before a retry
START_TRIES = 2


class Tape:
    def __init__(self, store=None, apns=None):
        self.store = store or Store()
        self.apns = apns or APNs()
        self.queue = []          # heap of (due, seq, fn, args)
        self.seq = 0
        self.starting = set()    # (device, game) push-to-starts queued, not yet sent
        self.history = {}        # game -> deque[(ts, snap)]
        self.meta = {}           # game -> {"teams", "sport", "attrs"}
        self.marked = {}         # game -> last period an end was announced for
        self.swept = 0.0
        self.late_sent = set()
        self.la_seq = 0
        self.la_latest = {}      # (device, game) -> newest queued update's sequence number
        self.la_sent_at = {}     # (device, game) -> when the card was last pushed   # (device, game) already told "past start, not under way"
        self.wake = asyncio.Event()
        # League card. `events` is the relay's raw ESPN events and `snap_of` its snapshot(),
        # both set by main.py; `views` is which card shows the league, and on which page.
        self.events = {}
        self.snap_of = None
        self.views = {}          # (device, game) -> page shown (absent: the card shows the game)

    async def on_snapshot(self, ev, prev, snap, now=None, lag=0.0):
        """Called by the relay for every snapshot that changed, live or the final. `lag` is how
        far this source trails the play; the delay counts from the play, not from arrival."""
        arrived = time.time() if now is None else now
        now = arrived - lag
        eid = snap["id"]
        teams = teams_of(ev)
        sport = sport_of(ev.get("uid"))
        self.meta[eid] = {"teams": teams, "sport": sport, "attrs": attributes(eid, ev, teams)}
        h = self.history.setdefault(eid, deque())
        if not h and prev is not None:
            h.append((0.0, prev))
        h.append((now, snap))
        while len(h) > 1 and h[1][0] < now - HISTORY_SECONDS:
            h.popleft()
        # A game's last word (usually its final) is kept for 12 hours, then forgotten.
        if now - self.swept > 600:
            self.swept = now
            for gid in [g for g, hh in self.history.items() if hh[-1][0] < now - 12 * 3600]:
                self.history.pop(gid, None)
                self.meta.pop(gid, None)
                self.marked.pop(gid, None)

        events = diff(prev, snap, sport, self.marked.get(eid))
        for kind, arg in events:
            if kind == "period":
                self.marked[eid] = arg
        uids = {t.get("uid") for t in teams.values() if t.get("uid")}
        followers = [d for d in self.store.devices.values() if d["follows"] & uids]
        seen = set()
        for d in followers:
            seen.add(d["token"])
            due = max(arrived, now + d["delay"])
            on_card = False
            if d["prefs"].get("liveActivities", True):
                la = self.store.activities.get(eid, {}).get(d["token"])
                if la:
                    on_card = True
                    self._queue_la(due, d["token"], eid, la, snap, bool(events))
                else:
                    ok, pending = self.can_start(d, eid, now)
                    if ok and snap.get("st") in ("in", "pre"):
                        pending = True
                        self.starting.add((d["token"], eid))
                        self.schedule(due, self._la_start, d["token"], eid, snap)
                    on_card = pending   # a card is on its way: don't alert on top of it
            # With the game on the lock screen, the card is the alert: no notifications on top.
            # Notifications are the fallback for a device that has no Live Activity for it.
            if on_card:
                continue
            for kind, arg in events:
                if not wanted(kind, sport, d["prefs"]):
                    continue
                title, body = alert_text(kind, arg if kind == "score" else None, snap, teams, sport)
                self.schedule(due, self._alert, d["token"], eid, kind, title, body)
        # An activity started by hand for a team this device does not follow still updates.
        for dev, la in list(self.store.activities.get(eid, {}).items()):
            if dev not in seen and dev in self.store.devices:
                self._queue_la(max(arrived, now + self.store.devices[dev]["delay"]), dev, eid, la,
                              snap, bool(events))
        # Cards showing this game's league: refresh their grid too, held to each device's delay
        # and no more often than every LEAGUE_GAP seconds (a full slate changes constantly).
        lg = league_uid(snap.get("uid") or ev.get("uid"))
        for (dev, card), _page in list(self.views.items()):
            if card == eid or dev not in self.store.devices:
                continue
            la = self.store.activities.get(card, {}).get(dev)
            mine = self.events.get(card)
            ch = self.history.get(card)
            if not la or not mine or league_uid(mine.get("uid")) != lg:
                continue
            delay = self.store.devices[dev]["delay"]
            due = max(arrived, now + delay, self.la_sent_at.get((dev, card), 0) + LEAGUE_GAP)
            card_snap = self.delayed(card, 0, due - delay) if ch else None
            if card_snap is None or card_snap.get("held"):
                card_snap = self.snap_of(mine) if self.snap_of else None
            if card_snap:
                self._queue_la(due, dev, card, la, card_snap, False)
        if snap.get("st") == "post":
            self.marked.pop(eid, None)

    PREGAME = 15 * 60

    async def on_pregame(self, ev, snap, now=None):
        """A game that hasn't started: put a starting-soon card up 15 minutes before it reaches
        each follower's stream, and mark it late once start time has passed with nothing under
        way. Called for every pre-game sighting (checkpoints, corrections, MiLB polls)."""
        now = time.time() if now is None else now
        start = start_time(ev)
        if not start or now < start - self.PREGAME - 3600 or now > start + 4 * 3600:
            return
        eid = snap["id"]
        teams = teams_of(ev)
        self.meta[eid] = {"teams": teams, "sport": sport_of(ev.get("uid")), "attrs": attributes(eid, ev, teams)}
        uids = {t.get("uid") for t in teams.values() if t.get("uid")}
        for d in self.store.devices.values():
            if not (d["follows"] & uids) or not d["prefs"].get("liveActivities", True):
                continue
            show_at = start - self.PREGAME + d["delay"]
            late = now > start + d["delay"] + 60
            la = self.store.activities.get(eid, {}).get(d["token"])
            if la:
                if late and (d["token"], eid) not in self.late_sent:
                    self.late_sent.add((d["token"], eid))
                    cs = self.compose(d["token"], eid, snap)
                    cs["late"] = True
                    self.schedule(now, self._la_push, d["token"], eid, la, cs)
            elif now >= show_at - 30 and self.can_start(d, eid, now)[0]:
                self.starting.add((d["token"], eid))
                self.schedule(max(now, show_at), self._la_start, d["token"], eid, snap, late)

    async def _la_push(self, dev, eid, la, cs):
        d = self.store.devices.get(dev)
        if not d:
            return
        st = await self.apns.send(la, {"aps": {"timestamp": int(time.time()), "event": "update", "content-state": cs}},
                                  env=d["env"], push_type="liveactivity",
                                  topic=TOPIC + ".push-type.liveactivity", priority=10)
        if st == 410:
            self.store.set_activity(dev, eid, None)

    def schedule(self, due, fn, *args):
        self.seq += 1
        heapq.heappush(self.queue, (due, self.seq, fn, args))
        self.wake.set()

    async def drain(self, now=None):
        """Send everything due by `now`. The run loop calls this; tests call it directly."""
        now = time.time() if now is None else now
        self.now = now
        n = 0
        while self.queue and self.queue[0][0] <= now:
            _, _, fn, args = heapq.heappop(self.queue)
            try:
                await fn(*args)
            except Exception:
                log.exception("tape push failed")
            n += 1
        return n

    async def run(self):
        while True:
            await self.drain()
            timeout = max(0.05, min(1.0, self.queue[0][0] - time.time())) if self.queue else 5
            self.wake.clear()
            try:
                await asyncio.wait_for(self.wake.wait(), timeout)
            except asyncio.TimeoutError:
                pass

    async def _alert(self, dev, eid, kind, title, body):
        d = self.store.devices.get(dev)
        if not d:
            return
        aps = {"alert": {"title": title, "body": body}, "thread-id": eid,
               "interruption-level": "time-sensitive" if kind in ("score", "final") else "active"}
        if kind in ("score", "final", "start"):
            aps["sound"] = "default"
        st = await self.apns.send(dev, {"aps": aps, "game": eid, "kind": kind}, env=d["env"])
        if st == 410:
            self.store.drop_device(dev)

    def can_start(self, d, eid, now):
        """(start one now?, is one pending?). No card yet, a push-to-start token, not swiped
        away, not already queued, and either never tried or the last try never showed up."""
        key = (d["token"], eid)
        if key in self.starting:
            return False, True
        if not d.get("start") or self.store.activities.get(eid, {}).get(d["token"]):
            return False, False
        tries, at, dismissed = self.store.starts.get(key, (0, 0, False))
        if dismissed:
            return False, False
        if tries and now - at < START_WAIT:
            return False, True
        return tries < START_TRIES, False

    async def _la_start(self, dev, eid, snap, late=False):
        self.starting.discard((dev, eid))
        d, m = self.store.devices.get(dev), self.meta.get(eid)
        if not d or not d.get("start") or not m:
            log.warning("la start %s skipped: %s", eid, "no device" if not d else "no start token" if not d.get("start") else "no meta")
            return
        if self.store.activities.get(eid, {}).get(dev):
            return   # the app put it up itself in the meantime
        a = dict(m["attrs"], delay=d["delay"])
        cs = self.compose(dev, eid, snap)
        if late:
            cs["late"] = True
        payload = {"aps": {"timestamp": int(time.time()), "event": "start",
                           "content-state": cs,
                           "attributes-type": "GameAttributes", "attributes": a,
                           "input-push-token": 1,
                           "alert": {"title": f"{a['awayName']} at {a['homeName']}",
                                     "body": "Starting soon" if snap.get("st") == "pre" else "Live on your lock screen"}}}
        st = await self.apns.send(d["start"], payload, env=d["env"], push_type="liveactivity",
                                  topic=TOPIC + ".push-type.liveactivity", priority=10)
        self.store.note_start(dev, eid, sent=st == 200, now=getattr(self, "now", None))
        if st == 410:
            # The push-to-start token is dead; the app sends a fresh one next time it opens.
            d["start"] = None

    def _queue_la(self, due, dev, eid, la, snap, important):
        """Queue a Live Activity update. Each carries a sequence number; when it comes due, only
        the newest one for that card is sent, so a burst (a pitch, a foul, a ball) collapses
        into the latest state instead of spending Apple's update budget on stale ones."""
        self.la_seq += 1
        self.la_latest[(dev, eid)] = self.la_seq
        self.schedule(due, self._la_update, dev, eid, la, snap, important, self.la_seq)

    async def _la_update(self, dev, eid, la, snap, important, seq=None):
        d = self.store.devices.get(dev)
        if not d:
            return
        final = snap.get("st") == "post"
        if seq is not None and not final and self.la_latest.get((dev, eid)) != seq:
            # A newer update is queued; it will go when due. Only let this one through if the
            # card would otherwise sit unchanged for more than two seconds.
            if time.time() - self.la_sent_at.get((dev, eid), 0) < 2:
                return
        self.la_sent_at[(dev, eid)] = time.time()
        aps = {"timestamp": int(time.time()), "event": "end" if final else "update",
               "content-state": self.compose(dev, eid, snap)}
        if final:
            aps["dismissal-date"] = int(time.time()) + 3600
            self.views.pop((dev, eid), None)
        st = await self.apns.send(la, {"aps": aps}, env=d["env"], push_type="liveactivity",
                                  topic=TOPIC + ".push-type.liveactivity",
                                  priority=10)   # 5 lets iOS sit on it for minutes; the card is the point
        if st == 410 or final:
            self.store.set_activity(dev, eid, None)

    def league_games(self, eid):
        """The other games in this game's league on the same day, in tile order: live, then
        upcoming, then finished."""
        mine = self.events.get(eid) or {}
        lg = league_uid(mine.get("uid"))
        day = game_day(start_time(mine))
        if not lg or day is None:
            return []
        out = []
        for oid, ev in list(self.events.items()):
            if oid == eid or league_uid(ev.get("uid")) != lg or game_day(start_time(ev)) != day:
                continue
            st = (((ev.get("competitions") or [{}])[0].get("status") or {}).get("type") or {}).get("state")
            out.append(({"in": 0, "pre": 1}.get(st, 2), start_time(ev) or 0, oid))
        out.sort(key=lambda t: (t[0], t[1] if t[0] < 2 else -t[1]))
        return [oid for _, _, oid in out]

    def tile(self, oid, snap, delay, now):
        """One small scorebug. `snap` is the game as your stream has it (None: work it out)."""
        ev = self.events.get(oid) or {}
        teams = teams_of(ev)
        sport = sport_of(ev.get("uid"))
        if snap is None:
            snap = self.delayed(oid, delay, now)
            if snap is None or snap.get("held"):
                snap = self.snap_of(ev) if self.snap_of and ev else (snap or {})
                if snap.get("st") == "in":
                    snap = {"st": "pre"}     # live, but not on your stream yet
        h, a = teams.get("home", {}), teams.get("away", {})
        t = {"id": oid, "a": a.get("abbr", ""), "h": h.get("abbr", ""),
             "ac": a.get("color", "888888"), "hc": h.get("color", "888888"),
             "st": snap.get("st") or "pre", "d": tile_detail(snap, sport)}
        if t["st"] != "pre":
            t["as"] = ((snap.get("away") or {}).get("sc")) or 0
            t["hs"] = ((snap.get("home") or {}).get("sc")) or 0
        else:
            t["t"] = int(start_time(ev) or 0)
        sit = snap.get("sit") or {}
        if sport == "baseball" and t["st"] == "in":
            t["b"] = (1 if sit.get("on1") else 0) | (2 if sit.get("on2") else 0) | (4 if sit.get("on3") else 0)
            if isinstance(sit.get("o"), int):
                t["o"] = sit["o"]
        note = series_note(ev)
        if note:
            t["n"] = note
        tv = watch(ev)
        if tv and t["st"] != "post":
            t["tv"] = tv[0][:10]
        return t

    def compose(self, dev, eid, snap, now=None):
        """The whole ContentState for one card: the game, plus the league pill, plus the
        league grid when this card is showing it. Every other game is held to this device's
        delay, the same as the card's own game."""
        now = time.time() if now is None else now
        cs = content_state(snap)
        mine = self.events.get(eid)
        if not mine:
            return cs
        others = self.league_games(eid)
        lg = league_uid(mine.get("uid"))
        if others:
            cs["leagueName"] = LEAGUES.get(lg, "LEAGUE")
            cs["leagueMore"] = len(others)
        page = self.views.get((dev, eid))
        if page is None or not others:
            return cs
        d = self.store.devices.get(dev) or {}
        delay = d.get("delay", 0)
        pages = max(1, -(-len(others) // PER_PAGE))
        page %= pages
        cs.update(view="league", page=page, pages=pages, leagueTitle=league_title(mine),
                  tiles=[self.tile(eid, snap, delay, now)] +
                        [self.tile(o, None, delay, now) for o in others[page * PER_PAGE:(page + 1) * PER_PAGE]])
        return cs

    def set_view(self, dev, eid, league, page=0, step=0):
        """Flip a card between its game and the league, or page through the league. Returns
        the card's new ContentState so the phone can show it at once."""
        key = (dev, eid)
        if not league:
            self.views.pop(key, None)
        else:
            others = self.league_games(eid)
            pages = max(1, -(-len(others) // PER_PAGE))
            cur = self.views.get(key, 0)
            self.views[key] = (cur + step if step else page) % pages
        d = self.store.devices.get(dev) or {}
        snap = self.delayed(eid, d.get("delay", 0))
        if snap is None or snap.get("held"):
            ev = self.events.get(eid)
            snap = self.snap_of(ev) if (self.snap_of and ev) else {"st": "pre"}
            if snap.get("st") == "in":
                snap = dict(snap, st="pre")
        return self.compose(dev, eid, snap)

    def delayed(self, eid, delay, now=None):
        """The game as it stood `delay` seconds ago, or None if the relay has not seen it live."""
        now = time.time() if now is None else now
        h = self.history.get(eid)
        if not h:
            return None
        cut = now - max(0, delay)
        best = None
        for ts, snap in h:
            if ts <= cut:
                best = snap
            else:
                break
        if best is not None:
            return best
        # Nothing old enough. If the relay saw the game before it started, the honest answer is
        # "not started yet on your stream". If its first sighting was already live (a relay
        # restart mid-game), there is no older copy: give the earliest one rather than pretend
        # the game hasn't started.
        first_ts, first = h[0]
        if first_ts == 0.0 or first.get("st") == "pre":
            return {"id": eid, "st": "pre", "held": True}
        return first

    def latest(self, eid):
        """The newest snapshot and when the relay got it, for syncing a delay to a stream."""
        h = self.history.get(eid)
        if not h:
            return None
        ts, snap = h[-1]
        return {"ts": ts, "snap": snap}


# ---------------------------------------------------------------- HTTP

def routes(tape):
    from aiohttp import web

    def hexok(s):
        return 32 <= len(s) <= 400 and all(c in "0123456789abcdefABCDEF" for c in s)

    async def register(req):
        try:
            b = await req.json()
        except Exception:
            return web.json_response({"error": "json"}, status=400)
        tok = str(b.get("token", ""))
        start = b.get("startToken") or None
        if not hexok(tok) or (start and not hexok(str(start))):
            return web.json_response({"error": "token"}, status=400)
        env = "dev" if b.get("env") == "dev" else "prod"
        tape.store.upsert(tok, env, b.get("delay", 0), b.get("follows", []), b.get("prefs", {}), start)
        d = tape.store.devices[tok]
        return web.json_response({"ok": True, "delay": d["delay"], "follows": len(d["follows"]),
                                  "apns": tape.apns.enabled})

    async def activity(req):
        try:
            b = await req.json()
        except Exception:
            return web.json_response({"error": "json"}, status=400)
        dev, game, tok = str(b.get("device", "")), str(b.get("game", "")), b.get("token") or None
        if dev not in tape.store.devices or not game or (tok and not hexok(str(tok))):
            return web.json_response({"error": "unknown device"}, status=404)
        tape.store.set_activity(dev, game, tok)
        tape.store.mark_started(dev, game)
        if not tok:
            tape.store.note_start(dev, game, dismissed=True)   # swiped away: don't put it back
        return web.json_response({"ok": True})

    async def view(req):
        try:
            b = await req.json()
        except Exception:
            return web.json_response({"error": "json"}, status=400)
        dev, game = str(b.get("device", "")), str(b.get("game", ""))
        if dev not in tape.store.devices or not game:
            return web.json_response({"error": "unknown device"}, status=404)
        try:
            page, step = int(b.get("page") or 0), max(-1, min(1, int(b.get("step") or 0)))
        except (TypeError, ValueError):
            page, step = 0, 0
        cs = tape.set_view(dev, game, b.get("view") == "league", page, step)
        return web.json_response({"state": cs}, headers={"Cache-Control": "no-store"})

    async def delayed(req):
        ids = [i for i in req.query.get("ids", "").split(",") if i][:60]
        try:
            delay = max(0, min(int(req.query.get("delay", "0")), MAX_DELAY))
        except ValueError:
            delay = 0
        out = {i: tape.delayed(i, delay) for i in ids}
        return web.json_response({k: v for k, v in out.items() if v is not None},
                                 headers={"Cache-Control": "no-store"})

    async def latest(req):
        eid = req.query.get("id", "")
        got = tape.latest(eid)
        body = {"now": time.time(), **(got or {})}
        return web.json_response(body, headers={"Cache-Control": "no-store"})

    async def health(req):
        return web.json_response({"ok": True, "apns": tape.apns.enabled, "devices": len(tape.store.devices),
                                  "queued": len(tape.queue), "games": len(tape.history),
                                  "sent": tape.apns.sent, "failed": tape.apns.failed})

    app = web.Application(client_max_size=64 * 1024)
    app.add_routes([web.post("/tape/v1/device", register), web.post("/tape/v1/activity", activity),
                    web.post("/tape/v1/view", view),
                    web.get("/tape/v1/delayed", delayed), web.get("/tape/v1/latest", latest),
                    web.get("/tape/v1/health", health)])
    return app


async def serve(tape, port=int(os.environ.get("TAPE_PORT", "8094"))):
    from aiohttp import web
    runner = web.AppRunner(routes(tape))
    await runner.setup()
    await web.TCPSite(runner, "0.0.0.0", port).start()
    log.info("tape api on :%d, apns %s", port, "on" if tape.apns.enabled else "DRY (no key yet)")
    await tape.run()
