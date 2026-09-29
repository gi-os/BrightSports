"""The MLB fast lane: MLB's own live feed, ahead of ESPN.

ESPN's feed runs a pitch or two (20-40 s) behind MLB StatsAPI, the feed Gameday itself runs on.
For every live MLB game that someone follows, this polls StatsAPI every two seconds (a ~700-byte
response with `fields=`), lays the moving parts over the last ESPN event for that game, and
hands it to `relay.consider(..., source="statsapi")`. While the fast lane is healthy for a game,
the relay ignores ESPN's copies of it, which are older by definition.

Games are matched ESPN -> gamePk once per day by the two teams' full names.
"""
import asyncio, copy, logging, time
from datetime import datetime, timezone

log = logging.getLogger("fastlane")
SCHEDULE = "https://statsapi.mlb.com/api/v1/schedule?sportId=1&date={date}&hydrate=team"
FEED = ("https://statsapi.mlb.com/api/v1.1/game/{pk}/feed/live?fields="
        "gameData,status,abstractGameState,detailedState,liveData,linescore,currentInning,"
        "currentInningOrdinal,inningHalf,inningState,balls,strikes,outs,offense,first,second,third,"
        "teams,home,away,runs,hits,errors,plays,currentPlay,result,description,matchup,batter,pitcher,fullName,"
        "playEvents,endTime,startTime,isPitch")
HEADERS = {"User-Agent": "curl/8.5", "Accept": "application/json"}
FRESH = 15   # seconds: a fast-lane copy newer than this beats ESPN's


def overlay(ev, feed):
    """The ESPN event with StatsAPI's newer state laid over it (a copy; the original is untouched)."""
    ev = copy.deepcopy(ev)
    comp = (ev.get("competitions") or [{}])[0]
    ls = (feed.get("liveData") or {}).get("linescore") or {}
    status = (feed.get("gameData") or {}).get("status") or {}
    abstract, detailed = status.get("abstractGameState", ""), status.get("detailedState", "")
    t = (comp.setdefault("status", {})).setdefault("type", {})
    if abstract == "Final":
        t.update(state="post", name="STATUS_FINAL", completed=True, shortDetail="Final")
    elif abstract == "Live":
        half = ls.get("inningState") or ls.get("inningHalf") or ""
        t.update(state="in", completed=False,
                 name="STATUS_RAIN_DELAY" if "Delay" in detailed else "STATUS_IN_PROGRESS",
                 shortDetail=f"{half} {ls.get('currentInningOrdinal', '')}".strip() if "Delay" not in detailed else detailed)
    comp["status"]["period"] = ls.get("currentInning") or comp["status"].get("period")
    teams = ls.get("teams") or {}
    for c in comp.get("competitors") or []:
        side = teams.get(c.get("homeAway")) or {}
        if "runs" in side:
            c["score"] = str(side["runs"])
        if "hits" in side:
            c["hits"] = side["hits"]
        if "errors" in side:
            c["errors"] = side["errors"]
    off = ls.get("offense") or {}
    cur = ((feed.get("liveData") or {}).get("plays") or {}).get("currentPlay") or {}
    m = cur.get("matchup") or {}
    sit = comp.setdefault("situation", {})
    sit.update(balls=ls.get("balls", 0), strikes=ls.get("strikes", 0), outs=ls.get("outs", 0),
               onFirst="first" in off, onSecond="second" in off, onThird="third" in off)
    # Replace, don't merge: ESPN's game line ("1-2, RBI") belongs to whoever ESPN thinks is up.
    for role in ("batter", "pitcher"):
        who = (m.get(role) or {}).get("fullName")
        if who:
            old = sit.get(role) or {}
            keep = old.get("summary") if ((old.get("athlete") or {}).get("shortName") == short(who)) else None
            sit[role] = {"athlete": {"shortName": short(who)}, **({"summary": keep} if keep else {})}
    desc = (cur.get("result") or {}).get("description")
    if desc:
        sit.setdefault("lastPlay", {})["text"] = desc
    return ev


def play_time(feed):
    """When the newest thing in the feed actually happened (the last pitch or event's end time,
    stamped by MLB's own scorers). Lets the relay count a delay from the pitch itself instead of
    from when the feed got round to publishing it, which measured 5-22 s later and varies."""
    from datetime import datetime
    cur = ((feed.get("liveData") or {}).get("plays") or {}).get("currentPlay") or {}
    for e in reversed(cur.get("playEvents") or []):
        t = e.get("endTime") or e.get("startTime")
        if t:
            try:
                return datetime.fromisoformat(t.replace("Z", "+00:00")).timestamp()
            except ValueError:
                return None
    return None


def short(name):
    """"Austin Riley" -> "A. Riley", ESPN's style."""
    if not name:
        return name
    parts = name.split()
    return f"{parts[0][0]}. {' '.join(parts[1:])}" if len(parts) > 1 else name


class FastLane:
    def __init__(self, relay):
        self.relay = relay
        self.pks = {}        # ESPN event id -> gamePk
        self.day = None
        self.schedule = []   # (away name, home name, gamePk)
        self.fresh = {}      # ESPN event id -> last successful fast-lane time
        self.polls = 0

    def healthy(self, eid):
        return time.time() - self.fresh.get(eid, 0) < FRESH

    def followed(self, ev):
        tape = self.relay.tape
        if not tape:
            return False
        uids = {((c.get("team") or {}).get("uid")) for c in ((ev.get("competitions") or [{}])[0]).get("competitors") or []}
        return any(d["follows"] & uids for d in tape.store.devices.values())

    async def load_schedule(self):
        day = datetime.now(timezone.utc).astimezone().strftime("%Y-%m-%d")
        if day == self.day and self.schedule:
            return
        async with self.relay.http.get(SCHEDULE.format(date=day), headers=HEADERS) as r:
            doc = await r.json(content_type=None)
        self.schedule = [(g["teams"]["away"]["team"]["name"], g["teams"]["home"]["team"]["name"], g["gamePk"])
                         for d in doc.get("dates") or [] for g in d.get("games") or []]
        self.day = day

    def match(self, ev):
        names = {c.get("homeAway"): (c.get("team") or {}).get("displayName") for c in
                 ((ev.get("competitions") or [{}])[0]).get("competitors") or []}
        for away, home, pk in self.schedule:
            if names.get("away") == away and names.get("home") == home:
                return pk
        return None

    async def run(self):
        await asyncio.sleep(15)
        while True:
            live = [ev for eid, ev in list(self.relay.events.items())
                    if (ev.get("uid") or "").startswith("s:1~l:10~")
                    and self.relay.last.get(eid, {}).get("st") == "in" and self.followed(ev)]
            if not live:
                await asyncio.sleep(10)
                continue
            try:
                await self.load_schedule()
            except Exception as e:
                log.warning("schedule: %s", e)
            for ev in live:
                eid = ev["id"]
                pk = self.pks.get(eid) or self.match(ev)
                if not pk:
                    continue
                self.pks[eid] = pk
                try:
                    async with self.relay.http.get(FEED.format(pk=pk), headers=HEADERS) as r:
                        feed = await r.json(content_type=None)
                    self.polls += 1
                    self.fresh[eid] = time.time()
                    pt = play_time(feed)
                    lag = min(60.0, max(0.0, time.time() - pt)) if pt else None
                    await self.relay.consider(overlay(ev, feed), source="statsapi", lag=lag)
                except Exception as e:
                    log.warning("feed %s: %s", pk, e)
            await asyncio.sleep(1)
