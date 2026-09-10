"""BrightSports relay: ESPN FastCast in, one ntfy topic per game out.

Nothing here knows who follows what. Every live game gets a topic, `bs-<eventId>`, and a
phone subscribes to the topics for the games in its own feed. The relay's work is the same
whether one phone or ten thousand are listening; the fan-out is ntfy's.

A message is a compact snapshot of one game -- the fields the phone's own ScoreDiff reads --
published whenever one of them changes. The phone keeps its own preferences (loudness, red
zone, spoiler hold) and runs its own diff, so the relay never decides what is worth a buzz.
"""
import asyncio, json, logging, os, time
import aiohttp
from fastcast import FastCast

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
log = logging.getLogger("relay")

NTFY_URL = os.environ.get("NTFY_URL", "http://ntfy:80")
NTFY_TOKEN = os.environ.get("NTFY_TOKEN", "")
HEARTBEAT_TOPIC = os.environ.get("HEARTBEAT_TOPIC", "bs-relay")
# The FastCast topics that exist. Soccer is one topic for every league; racing and tennis
# have none, and the phone keeps polling those.
TOPICS = [
    "scoreboard-football-nfl", "scoreboard-football-college-football",
    "scoreboard-baseball-mlb", "scoreboard-basketball-nba", "scoreboard-basketball-wnba",
    "scoreboard-hockey-nhl", "scoreboard-soccer",
]
# Site-API scoreboards re-fetched every few minutes as a correction for anything a patch
# stream missed. Soccer's leagues are many; the FastCast soccer topic covers them all, so
# the correction only covers the ones the app carries.
SITE = "https://site.api.espn.com/apis/site/v2/sports"
CORRECTION_PATHS = [
    "football/nfl", "football/college-football?groups=80", "football/college-football?groups=81",
    "baseball/mlb", "basketball/nba", "basketball/wnba", "hockey/nhl",
    "soccer/usa.1", "soccer/eng.1", "soccer/esp.1", "soccer/ger.1", "soccer/ita.1", "soccer/fra.1",
    "soccer/uefa.champions", "soccer/uefa.europa", "soccer/usa.nwsl",
    "soccer/concacaf.leagues.cup", "soccer/usa.open",
]
CORRECTION_EVERY = int(os.environ.get("CORRECTION_SECONDS", "300"))
HEARTBEAT_EVERY = 60


def snapshot(ev):
    """The fields the phone diffs, and nothing else. Stable key order so equality is cheap."""
    comp = (ev.get("competitions") or [{}])[0]
    status = comp.get("status") or ev.get("status") or {}
    stype = status.get("type") or {}
    sides = {}
    for c in comp.get("competitors") or []:
        team = c.get("team") or {}
        score = c.get("score")
        if isinstance(score, dict):
            score = score.get("value")
        try:
            score = int(float(score)) if score not in (None, "") else None
        except (TypeError, ValueError):
            score = None
        side = {"id": team.get("id"), "ab": team.get("abbreviation"), "sc": score}
        ls = [x.get("displayValue") or x.get("value") for x in (c.get("linescores") or [])]
        if ls:
            side["ls"] = [str(v) for v in ls]
        for k in ("hits", "errors"):
            if c.get(k) is not None:
                side[k[0]] = c.get(k)
        sides[c.get("homeAway", "home")] = side
    sit = comp.get("situation") or {}
    s = {
        "v": 1,
        "id": ev.get("id"),
        "uid": ev.get("uid"),
        "st": stype.get("state"),
        "nm": stype.get("name"),
        "dt": stype.get("shortDetail") or stype.get("detail"),
        "done": bool(stype.get("completed")),
        "p": status.get("period"),
        "ck": status.get("displayClock"),
        "home": sides.get("home"),
        "away": sides.get("away"),
    }
    if sit:
        last = sit.get("lastPlay") or {}
        s["sit"] = {
            "poss": sit.get("possession"),
            "dd": sit.get("downDistanceText"),
            "sdd": sit.get("shortDownDistanceText"),
            "spot": sit.get("possessionText"),
            "down": sit.get("down"),
            "dist": sit.get("distance"),
            "rz": bool(sit.get("isRedZone")),
            "hto": sit.get("homeTimeouts"),
            "ato": sit.get("awayTimeouts"),
            "lp": last.get("text"),
            "drive": (last.get("drive") or {}).get("description"),
            "b": sit.get("balls"), "s": sit.get("strikes"), "o": sit.get("outs"),
            "on1": sit.get("onFirst"), "on2": sit.get("onSecond"), "on3": sit.get("onThird"),
            "bat": ((sit.get("batter") or {}).get("athlete") or {}).get("shortName"),
            "pit": ((sit.get("pitcher") or {}).get("athlete") or {}).get("shortName"),
            "bats": (sit.get("batter") or {}).get("summary"),
            "pits": (sit.get("pitcher") or {}).get("summary"),
        }
        s["sit"] = {k: v for k, v in s["sit"].items() if v not in (None, False, "")}
    return s


class Relay:
    def __init__(self):
        self.last = {}          # event id -> last published snapshot (without ts)
        self.published = 0
        self.http = None
        self.fc = FastCast(TOPICS, self.on_document, self.on_event)

    async def on_document(self, topic, doc):
        # A checkpoint is the truth; publish anything that differs from what we last said,
        # but only for games that are or were live -- a pre-game ticket count is not news.
        for ev in doc.get("events", []):
            await self.consider(ev, source="checkpoint")

    async def on_event(self, topic, ev):
        await self.consider(ev, source="patch")

    async def consider(self, ev, source):
        snap = snapshot(ev)
        eid = snap.get("id")
        if not eid or not snap.get("home") or not snap.get("away"):
            return
        prev = self.last.get(eid)
        live_now = snap["st"] == "in"
        was_live = prev is not None and prev.get("st") == "in"
        # Publish for live games, and for the one transition out of live (the final).
        if not live_now and not was_live:
            self.last[eid] = snap
            return
        if prev == snap:
            return
        self.last[eid] = snap
        await self.publish(f"bs-{eid}", dict(snap, ts=int(time.time() * 1000), src=source))

    async def publish(self, topic, payload):
        body = json.dumps(payload, separators=(",", ":"))
        headers = {"Content-Type": "application/json"}
        if NTFY_TOKEN:
            headers["Authorization"] = f"Bearer {NTFY_TOKEN}"
        # Plain-text body publish: the message *is* the JSON. `Cache: yes` (default) lets a
        # phone that reconnects ask for `since=`. No title, no priority: the phone decides.
        try:
            async with self.http.post(f"{NTFY_URL}/{topic}", data=body, headers=headers,
                                      timeout=aiohttp.ClientTimeout(total=10)) as r:
                if r.status >= 300:
                    log.warning("ntfy %s -> %s %s", topic, r.status, (await r.text())[:200])
                else:
                    self.published += 1
        except Exception as e:
            log.warning("ntfy publish failed: %s", e)

    async def heartbeat(self):
        while True:
            await asyncio.sleep(HEARTBEAT_EVERY)
            live = sum(1 for s in self.last.values() if s.get("st") == "in")
            await self.publish(HEARTBEAT_TOPIC, {
                "v": 1, "ts": int(time.time() * 1000), "fastcast": self.fc.connected,
                "live": live, "published": self.published,
                "silence_s": int(time.time() - self.fc.last_message) if self.fc.last_message else None,
            })

    async def corrections(self):
        # The safety net under the patch stream: a plain scoreboard fetch per league every
        # few minutes. Cheap for a server, and it catches a patch we mis-applied.
        await asyncio.sleep(90)
        while True:
            for path in CORRECTION_PATHS:
                sep = "&" if "?" in path else "?"
                url = f"{SITE}/{path}{sep}limit=300"
                try:
                    async with self.http.get(url, headers={"User-Agent": "Mozilla/5.0 (BrightSports relay)"},
                                             timeout=aiohttp.ClientTimeout(total=20)) as r:
                        if r.status != 200:
                            continue
                        doc = await r.json(content_type=None)
                    for ev in doc.get("events", []):
                        await self.consider(ev, source="correction")
                except Exception as e:
                    log.debug("correction %s failed: %s", path, e)
                await asyncio.sleep(2)
            await asyncio.sleep(CORRECTION_EVERY)

    async def run(self):
        async with aiohttp.ClientSession() as http:
            self.http = http
            await asyncio.gather(self.fc.run_forever(), self.heartbeat(), self.corrections())


if __name__ == "__main__":
    asyncio.run(Relay().run())
