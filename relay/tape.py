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
        }
    return out


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
    return st


def attributes(eid, ev, teams):
    h, a = teams.get("home", {}), teams.get("away", {})
    return {
        "gameId": eid,
        "sport": sport_of(ev.get("uid")),
        "homeAbbr": h.get("abbr", ""), "awayAbbr": a.get("abbr", ""),
        "homeName": h.get("name", ""), "awayName": a.get("name", ""),
        "homeColor": h.get("color", "888888"), "awayColor": a.get("color", "888888"),
    }


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
        else:
            self.db.execute("DELETE FROM activities WHERE device=? AND game=?", (device, game))
            self.activities.get(game, {}).pop(device, None)
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
            return 200
        self.failed += 1
        log.warning("apns %s %s… -> %s %s", push_type, device_token[:8], r.status_code, r.text[:200])
        return 410 if r.status_code == 410 or "BadDeviceToken" in r.text else r.status_code


# ---------------------------------------------------------------- the engine

class Tape:
    def __init__(self, store=None, apns=None):
        self.store = store or Store()
        self.apns = apns or APNs()
        self.queue = []          # heap of (due, seq, fn, args)
        self.seq = 0
        self.history = {}        # game -> deque[(ts, snap)]
        self.meta = {}           # game -> {"teams", "sport", "attrs"}
        self.marked = {}         # game -> last period an end was announced for
        self.swept = 0.0
        self.wake = asyncio.Event()

    async def on_snapshot(self, ev, prev, snap, now=None):
        """Called by the relay for every snapshot that changed, live or the final."""
        now = time.time() if now is None else now
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
            due = now + d["delay"]
            for kind, arg in events:
                if not wanted(kind, sport, d["prefs"]):
                    continue
                title, body = alert_text(kind, arg if kind == "score" else None, snap, teams, sport)
                self.schedule(due, self._alert, d["token"], eid, kind, title, body)
            if d["prefs"].get("liveActivities", True):
                la = self.store.activities.get(eid, {}).get(d["token"])
                if la:
                    self.schedule(due, self._la_update, d["token"], eid, la, snap, bool(events))
                elif snap.get("st") == "in" and d.get("start") and (d["token"], eid) not in self.store.started:
                    self.store.mark_started(d["token"], eid)
                    self.schedule(due, self._la_start, d["token"], eid, snap)
        # An activity started by hand for a team this device does not follow still updates.
        for dev, la in list(self.store.activities.get(eid, {}).items()):
            if dev not in seen and dev in self.store.devices:
                self.schedule(now + self.store.devices[dev]["delay"], self._la_update, dev, eid, la,
                              snap, bool(events))
        if snap.get("st") == "post":
            self.marked.pop(eid, None)

    def schedule(self, due, fn, *args):
        self.seq += 1
        heapq.heappush(self.queue, (due, self.seq, fn, args))
        self.wake.set()

    async def drain(self, now=None):
        """Send everything due by `now`. The run loop calls this; tests call it directly."""
        now = time.time() if now is None else now
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

    async def _la_start(self, dev, eid, snap):
        d, m = self.store.devices.get(dev), self.meta.get(eid)
        if not d or not d.get("start") or not m:
            return
        a = m["attrs"]
        payload = {"aps": {"timestamp": int(time.time()), "event": "start",
                           "content-state": content_state(snap),
                           "attributes-type": "GameAttributes", "attributes": a,
                           "alert": {"title": f"{a['awayName']} at {a['homeName']}",
                                     "body": "Live on your lock screen"}}}
        await self.apns.send(d["start"], payload, env=d["env"], push_type="liveactivity",
                             topic=TOPIC + ".push-type.liveactivity", priority=10)

    async def _la_update(self, dev, eid, la, snap, important):
        d = self.store.devices.get(dev)
        if not d:
            return
        final = snap.get("st") == "post"
        aps = {"timestamp": int(time.time()), "event": "end" if final else "update",
               "content-state": content_state(snap)}
        if final:
            aps["dismissal-date"] = int(time.time()) + 3600
        st = await self.apns.send(la, {"aps": aps}, env=d["env"], push_type="liveactivity",
                                  topic=TOPIC + ".push-type.liveactivity",
                                  priority=10 if important or final else 5)
        if st == 410 or final:
            self.store.set_activity(dev, eid, None)

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
        return best if best is not None else {"id": eid, "st": "pre", "held": True}


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
        return web.json_response({"ok": True})

    async def delayed(req):
        ids = [i for i in req.query.get("ids", "").split(",") if i][:60]
        try:
            delay = max(0, min(int(req.query.get("delay", "0")), MAX_DELAY))
        except ValueError:
            delay = 0
        out = {i: tape.delayed(i, delay) for i in ids}
        return web.json_response({k: v for k, v in out.items() if v is not None},
                                 headers={"Cache-Control": "no-store"})

    async def health(req):
        return web.json_response({"ok": True, "apns": tape.apns.enabled, "devices": len(tape.store.devices),
                                  "queued": len(tape.queue), "games": len(tape.history),
                                  "sent": tape.apns.sent, "failed": tape.apns.failed})

    app = web.Application(client_max_size=64 * 1024)
    app.add_routes([web.post("/tape/v1/device", register), web.post("/tape/v1/activity", activity),
                    web.get("/tape/v1/delayed", delayed), web.get("/tape/v1/health", health)])
    return app


async def serve(tape, port=int(os.environ.get("TAPE_PORT", "8094"))):
    from aiohttp import web
    runner = web.AppRunner(routes(tape))
    await runner.setup()
    await web.TCPSite(runner, "0.0.0.0", port).start()
    log.info("tape api on :%d, apns %s", port, "on" if tape.apns.enabled else "DRY (no key yet)")
    await tape.run()
