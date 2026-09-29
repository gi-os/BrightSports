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


# ---------------------------------------------------------------- basketball

HOOPS = {
    # ESPN league uid prefix -> (CDN host, league id, site origin)
    "s:40~l:59~": ("cdn.wnba.com", "10", "https://www.wnba.com"),   # WNBA
    "s:40~l:46~": ("cdn.nba.com", "00", "https://www.nba.com"),     # NBA
}


def hoops_headers(origin):
    # The league CDNs answer 403 or an HTML page unless the request looks like their own site.
    return {"User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) "
                          "Version/17.0 Safari/605.1.15",
            "Accept": "application/json, text/plain, */*", "Accept-Language": "en-US,en;q=0.9",
            "Origin": origin, "Referer": origin + "/",
            "Sec-Fetch-Site": "same-site", "Sec-Fetch-Mode": "cors", "Sec-Fetch-Dest": "empty"}


def clock(iso):
    """"PT05M12.00S" -> "5:12"; under a minute, "17.4"."""
    import re
    m = re.match(r"PT(\d+)M([\d.]+)S", iso or "")
    if not m:
        return ""
    mins, secs = int(m.group(1)), float(m.group(2))
    return f"{mins}:{int(secs):02d}" if mins else (f"{secs:.1f}" if secs < 10 else f"{int(secs)}")


def ordinal(n):
    return {1: "1st", 2: "2nd", 3: "3rd", 4: "4th"}.get(n, "OT" if n == 5 else f"{n - 4}OT")


def hoops_overlay(ev, box, last_action=None):
    """ESPN event with the league CDN's boxscore (and newest play) laid over it."""
    ev = copy.deepcopy(ev)
    g = box.get("game") or {}
    comp = (ev.get("competitions") or [{}])[0]
    st = comp.setdefault("status", {})
    t = st.setdefault("type", {})
    status, text, period = g.get("gameStatus"), g.get("gameStatusText") or "", g.get("period") or 0
    clk = clock(g.get("gameClock"))
    if status == 3:
        t.update(state="post", name="STATUS_FINAL", completed=True, shortDetail="Final" + ("/OT" if period > 4 else ""))
    elif status == 2:
        if text.lower().startswith("half"):
            t.update(state="in", name="STATUS_HALFTIME", completed=False, shortDetail="Halftime")
        elif clk in ("0:00", "0.0", "") and text.lower().startswith("end"):
            t.update(state="in", name="STATUS_END_PERIOD", completed=False, shortDetail=f"End of {ordinal(period)}")
        else:
            t.update(state="in", name="STATUS_IN_PROGRESS", completed=False, shortDetail=f"{clk} - {ordinal(period)}")
    st["period"] = period
    st["displayClock"] = clk
    sides = {"home": g.get("homeTeam") or {}, "away": g.get("awayTeam") or {}}
    league_team = {}
    for c in comp.get("competitors") or []:
        s = sides.get(c.get("homeAway")) or {}
        league_team[s.get("teamId")] = (c.get("team") or {}).get("id")
        score = s.get("score")
        # The play-by-play often has a basket (or free throw) a second or two before the
        # boxscore does; scores only go up, so take whichever is ahead.
        if last_action:
            try:
                pbp = int(last_action.get("scoreHome" if c.get("homeAway") == "home" else "scoreAway") or 0)
                score = max(int(score or 0), pbp)
            except (TypeError, ValueError):
                pass
        if score is not None:
            c["score"] = str(score)
    sit = comp.setdefault("situation", {})
    for side, s in sides.items():
        if isinstance(s.get("timeoutsRemaining"), int):
            sit[f"{side}Timeouts"] = s["timeoutsRemaining"]
        sit[f"{side}Bonus"] = bool(s.get("inBonus") in (True, 1, "1"))
        fouls = (s.get("statistics") or {}).get("foulsTeam")
        if isinstance(fouls, int):
            sit[f"{side}Fouls"] = fouls
    if last_action:
        if last_action.get("description"):
            sit.setdefault("lastPlay", {})["text"] = last_action["description"]
        poss = league_team.get(last_action.get("possession"))
        if poss:
            sit["possession"] = poss
    return ev


class HoopsLane:
    """NBA/WNBA from the leagues' own live CDN: the boxscore every second (state, clock, score,
    timeouts, bonus, fouls) and the play-by-play every three (last play, possession, and each
    play's real timestamp, so delays count from the play)."""

    def __init__(self, relay):
        self.relay = relay
        self.ids = {}         # ESPN event id -> league game id
        self.fresh = {}
        self.boards = {}      # league prefix -> (fetched at, games)
        self.last = {}        # ESPN event id -> (fetched at, newest action)
        self.sent = {}        # ESPN event id -> (what was last sent, when)
        self.polls = 0

    def healthy(self, eid):
        return time.time() - self.fresh.get(eid, 0) < FRESH

    async def board(self, prefix):
        host, lid, origin = HOOPS[prefix]
        at, games = self.boards.get(prefix, (0, []))
        if time.time() - at < 60:
            return games
        url = f"https://{host}/static/json/liveData/scoreboard/todaysScoreboard_{lid}.json"
        async with self.relay.http.get(url, headers=hoops_headers(origin)) as r:
            doc = await r.json(content_type=None)
        games = (doc.get("scoreboard") or {}).get("games") or []
        self.boards[prefix] = (time.time(), games)
        return games

    async def match(self, prefix, ev):
        names = {c.get("homeAway"): (c.get("team") or {}).get("displayName") for c in
                 ((ev.get("competitions") or [{}])[0]).get("competitors") or []}
        for g in await self.board(prefix):
            h, a = g.get("homeTeam") or {}, g.get("awayTeam") or {}
            if f"{h.get('teamCity')} {h.get('teamName')}" == names.get("home") and \
               f"{a.get('teamCity')} {a.get('teamName')}" == names.get("away"):
                return g.get("gameId")
        return None

    def near_tip(self, ev, eid):
        """Pre-game, from 5 minutes before the listed start: watch the league feed so tip-off
        lands the moment it happens instead of when ESPN gets round to flipping the state."""
        import tape
        if self.relay.last.get(eid, {}).get("st") != "pre":
            return False
        t = tape.start_time(ev)
        return bool(t) and t - 300 <= time.time() <= t + 3 * 3600

    async def run(self):
        await asyncio.sleep(20)
        tick = 0
        while True:
            tick += 1
            live = [(p, ev) for eid, ev in list(self.relay.events.items()) for p in HOOPS
                    if (ev.get("uid") or "").startswith(p) and self.relay.fast.followed(ev)
                    and (self.relay.last.get(eid, {}).get("st") == "in" or self.near_tip(ev, eid))]
            if not live:
                await asyncio.sleep(10)
                continue
            for prefix, ev in live:
                eid = ev["id"]
                host, _, origin = HOOPS[prefix]
                try:
                    gid = self.ids.get(eid) or await self.match(prefix, ev)
                    if not gid:
                        continue
                    self.ids[eid] = gid
                    hdr = hoops_headers(origin)
                    async with self.relay.http.get(f"https://{host}/static/json/liveData/boxscore/boxscore_{gid}.json",
                                                   headers=hdr) as r:
                        box = await r.json(content_type=None)
                    at, action = self.last.get(eid, (0, None))
                    if tick % 2 == 0 or action is None:
                        async with self.relay.http.get(
                                f"https://{host}/static/json/liveData/playbyplay/playbyplay_{gid}.json", headers=hdr) as r:
                            if r.status == 200:
                                acts = ((await r.json(content_type=None)).get("game") or {}).get("actions") or []
                                if acts:
                                    action = acts[-1]
                                    self.last[eid] = (time.time(), action)
                    self.polls += 1
                    self.fresh[eid] = time.time()
                    # The clock moves every second; don't spend a push on every tick. Send when
                    # something real changed (score, period, a new play, timeouts, fouls), or
                    # every 10 s so the clock on the card keeps roughly up.
                    gm = box.get("game") or {}
                    txt = (gm.get("gameStatusText") or "").lower()
                    key = (gm.get("gameStatus"), txt if txt.startswith(("half", "end", "final")) else "", gm.get("period"),
                           (gm.get("homeTeam") or {}).get("score"), (gm.get("awayTeam") or {}).get("score"),
                           (gm.get("homeTeam") or {}).get("timeoutsRemaining"), (gm.get("awayTeam") or {}).get("timeoutsRemaining"),
                           (gm.get("homeTeam") or {}).get("inBonus"), (gm.get("awayTeam") or {}).get("inBonus"),
                           action.get("actionNumber") if action else None)
                    prev_key, sent_at = self.sent.get(eid, (None, 0))
                    if key == prev_key and time.time() - sent_at < 10:
                        continue
                    new_play = not prev_key or (action and action.get("actionNumber") != prev_key[-1])
                    self.sent[eid] = (key, time.time())
                    lag = 3.0   # the boxscore itself runs a second or three behind
                    if new_play and action and action.get("timeActual"):
                        from datetime import datetime
                        try:
                            import re as _re
                            pt = datetime.fromisoformat(_re.sub(r"\.\d+", "", action["timeActual"]).replace("Z", "+00:00")).timestamp()
                            lag = min(60.0, max(0.0, time.time() - pt))
                        except ValueError:
                            pass
                    await self.relay.consider(hoops_overlay(ev, box, action), source="statsapi", lag=lag)
                except Exception as e:
                    log.warning("hoops %s: %s", eid, e)
            await asyncio.sleep(1)
