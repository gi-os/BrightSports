"""Minor League Baseball for the relay: MLB StatsAPI polled, reshaped as ESPN events.

FastCast has no minor-league topic and ESPN no minor-league scoreboard, so for the four MiLB
levels (StatsAPI sportId 11-14) the relay polls StatsAPI itself and hands each game to the same
`consider()` path as everything else, dressed as an ESPN event so `snapshot()` and the Tape
pusher read it unchanged. Ids are `milb-<gamePk>`, team uids `milb:<teamId>`, matching the app.

Only polled while some iPhone follows a MiLB team: every 30 s with a game live, 5 min otherwise.
Traps (BrightSports README): postponed is `abstractGameState: Final`, suspended is `Live`, so the
detailed state is read first; a postponed game with a make-up date is listed twice under one
gamePk, so games are deduped by id with the more specific state winning.
"""
import asyncio, logging, time
from datetime import datetime, timedelta, timezone

log = logging.getLogger("milb")
SCHEDULE = "https://statsapi.mlb.com/api/v1/schedule"
SPORT_IDS = (11, 12, 13, 14)
OFF_WORDS = ("Postpon", "Suspend", "Delay", "Cancel")


def to_event(g):
    """One StatsAPI schedule game -> an ESPN-shaped event, or None."""
    pk = g.get("gamePk")
    teams = g.get("teams") or {}
    if not pk or "home" not in teams or "away" not in teams:
        return None
    st = g.get("status") or {}
    detailed = st.get("detailedState") or ""
    abstract = st.get("abstractGameState") or "Preview"
    ls = g.get("linescore") or {}
    if any(w in detailed for w in OFF_WORDS):
        state = "post" if "Postpon" in detailed or "Cancel" in detailed else "in"
        name = "STATUS_POSTPONED" if "Postpon" in detailed else "STATUS_CANCELED" if "Cancel" in detailed \
            else "STATUS_RAIN_DELAY" if "Delay" in detailed else "STATUS_SUSPENDED"
    elif abstract == "Live":
        state, name = "in", "STATUS_IN_PROGRESS"
    elif abstract == "Final":
        state, name = "post", "STATUS_FINAL"
    else:
        state, name = "pre", "STATUS_SCHEDULED"
    inning = ls.get("currentInning") or 0
    if state == "in" and name == "STATUS_IN_PROGRESS" and ls.get("inningState"):
        detail = f"{ls['inningState']} {ls.get('currentInningOrdinal', inning)}"
    elif name == "STATUS_FINAL":
        detail = "Final" if inning <= 9 else f"Final/{inning}"
    else:
        detail = detailed
    innings = ls.get("innings") or []

    def comp(side):
        s = teams[side]
        t = s.get("team") or {}
        tid = t.get("id")
        return {
            "homeAway": side,
            "score": None if state == "pre" else s.get("score"),
            "team": {"id": str(tid), "uid": f"milb:{tid}", "abbreviation": t.get("abbreviation") or "",
                     "shortDisplayName": t.get("teamName") or t.get("name") or "", "color": "888888"},
            "linescores": [{"value": (i.get(side) or {}).get("runs")} for i in innings
                           if (i.get(side) or {}).get("runs") is not None],
        }

    off = ls.get("offense") or {}
    comp_ = {
        "status": {"period": inning, "displayClock": "",
                   "type": {"state": state, "name": name, "completed": state == "post", "shortDetail": detail}},
        "competitors": [comp("home"), comp("away")],
    }
    if state == "in":
        comp_["situation"] = {"balls": ls.get("balls"), "strikes": ls.get("strikes"), "outs": ls.get("outs"),
                              "onFirst": "first" in off, "onSecond": "second" in off, "onThird": "third" in off}
    return {"id": f"milb-{pk}", "uid": f"s:1~l:milb~e:{pk}", "date": g.get("gameDate"), "competitions": [comp_]}


RANK = {"pre": 0, "in": 1, "post": 2}


def events_from(doc):
    """Every game in a schedule document, one per id, the most specific state kept."""
    best = {}
    for d in doc.get("dates") or []:
        for g in d.get("games") or []:
            ev = to_event(g)
            if not ev:
                continue
            t = ev["competitions"][0]["status"]["type"]
            r = RANK[t["state"]] + (3 if t["name"] in ("STATUS_POSTPONED", "STATUS_CANCELED") else 0)
            old = best.get(ev["id"])
            if old is None or r >= old[0]:
                best[ev["id"]] = (r, ev)
    return [ev for _, ev in best.values()]


async def poll_forever(relay):
    """Feed MiLB games into `relay.consider` while anyone follows a MiLB team."""
    headers = {"User-Agent": "curl/8.5", "Accept": "application/json"}
    await asyncio.sleep(10)
    while True:
        wait = 300
        wanted = relay.tape and any(any(u.startswith("milb:") for u in d["follows"])
                                    for d in relay.tape.store.devices.values())
        if wanted:
            now = datetime.now(timezone.utc)
            start = (now - timedelta(hours=14)).strftime("%Y-%m-%d")
            end = (now + timedelta(hours=10)).strftime("%Y-%m-%d")
            q = "&".join(f"sportId={s}" for s in SPORT_IDS)
            url = f"{SCHEDULE}?{q}&startDate={start}&endDate={end}&hydrate=linescore,team"
            try:
                async with relay.http.get(url, headers=headers) as r:
                    doc = await r.json(content_type=None)
                evs = events_from(doc)
                for ev in evs:
                    await relay.consider(ev, source="milb")
                if any(e["competitions"][0]["status"]["type"]["state"] == "in" for e in evs):
                    wait = 30
            except Exception as e:
                log.warning("milb poll failed: %s", e)
                wait = 120
        await asyncio.sleep(wait)
