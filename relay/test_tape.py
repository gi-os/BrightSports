"""python3 -m unittest test_tape  (no network, no APNs key)."""
import asyncio, os, tempfile, unittest
import tape
from main import snapshot


def ev(state="in", name="STATUS_IN_PROGRESS", home=0, away=0, period=1, detail="", sport="20", league="28"):
    return {
        "id": "401", "uid": f"s:{sport}~l:{league}~e:401",
        "competitions": [{
            "status": {"period": period, "displayClock": "10:00",
                       "type": {"state": state, "name": name, "completed": state == "post",
                                "shortDetail": detail}},
            "competitors": [
                {"homeAway": "home", "score": str(home),
                 "team": {"id": "26", "uid": f"s:{sport}~l:{league}~t:26", "abbreviation": "SEA",
                          "shortDisplayName": "Seahawks", "color": "002244"}},
                {"homeAway": "away", "score": str(away),
                 "team": {"id": "17", "uid": f"s:{sport}~l:{league}~t:17", "abbreviation": "NE",
                          "shortDisplayName": "Patriots", "color": "002a5c"}},
            ]}]}


class FakeAPNs:
    enabled = True
    sent = failed = 0

    def __init__(self):
        self.log = []

    async def send(self, token, payload, **kw):
        self.log.append((token, payload, kw))
        return 200


DEV = "ab" * 32
LA = "cd" * 40
START = "ef" * 40


class DiffTest(unittest.TestCase):
    def kinds(self, a, b, sport="football", marked=None):
        return [k for k, _ in tape.diff(snapshot(a), snapshot(b), sport, marked)]

    def test_first_sight_is_silent(self):
        self.assertEqual(tape.diff(None, snapshot(ev(home=7)), "football"), [])

    def test_start_score_final(self):
        self.assertEqual(self.kinds(ev("pre", "STATUS_SCHEDULED"), ev()), ["start"])
        self.assertEqual(tape.diff(snapshot(ev()), snapshot(ev(home=7)), "football"), [("score", "home")])
        self.assertEqual(self.kinds(ev(home=7), ev("post", "STATUS_FINAL", home=7)), ["final"])

    def test_rain_delay_is_not_a_start_and_resumes_once(self):
        self.assertEqual(self.kinds(ev("pre", "STATUS_SCHEDULED"), ev(name="STATUS_RAIN_DELAY")), ["delay"])
        self.assertEqual(self.kinds(ev(name="STATUS_RAIN_DELAY"), ev()), ["resume"])

    def test_period_end_by_name_then_not_again_by_number(self):
        a, b = ev(period=2), ev(name="STATUS_HALFTIME", period=2, detail="Halftime")
        got = tape.diff(snapshot(a), snapshot(b), "football")
        self.assertEqual(got, [("period", 2)])
        # Third quarter starts: the number going up would mark period 2 again.
        self.assertEqual(self.kinds(b, ev(period=3), marked=2), [])

    def test_baseball_has_no_period_marks(self):
        self.assertEqual(self.kinds(ev(period=4, sport="1"), ev(period=5, sport="1"), "baseball"), [])

    def test_basketball_scores_off_by_default(self):
        self.assertFalse(tape.wanted("score", "basketball", {}))
        self.assertTrue(tape.wanted("score", "basketball", {"everyBasket": True}))
        self.assertTrue(tape.wanted("period", "basketball", {}))
        self.assertFalse(tape.wanted("score", "hockey", {"score": False}))


class EngineTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.apns = FakeAPNs()
        self.t = tape.Tape(tape.Store(os.path.join(self.dir, "t.db")), self.apns)

    def feed(self, a, b, now):
        return asyncio.run(self.t.on_snapshot(b, snapshot(a), snapshot(b), now=now))

    def test_alert_is_held_for_the_delay(self):
        self.t.store.upsert(DEV, "prod", 45, ["s:20~l:28~t:26"], {}, None)
        self.feed(ev(), ev(home=7, detail="3:24 - 2nd"), now=1000)
        self.assertEqual(asyncio.run(self.t.drain(now=1044)), 0)
        self.assertEqual(asyncio.run(self.t.drain(now=1045)), 1)
        tok, payload, _ = self.apns.log[0]
        self.assertEqual(tok, DEV)
        self.assertEqual(payload["aps"]["alert"]["title"], "Score: Seahawks")
        self.assertIn("NE 0 – SEA 7", payload["aps"]["alert"]["body"])

    def test_unfollowed_team_gets_nothing(self):
        self.t.store.upsert(DEV, "prod", 0, ["s:20~l:28~t:99"], {}, None)
        self.feed(ev(), ev(home=7), now=1000)
        self.assertEqual(asyncio.run(self.t.drain(now=2000)), 0)

    def test_same_team_id_other_league_does_not_match(self):
        self.t.store.upsert(DEV, "prod", 0, ["s:40~l:46~t:26"], {}, None)
        self.feed(ev(), ev(home=7), now=1000)
        self.assertEqual(asyncio.run(self.t.drain(now=2000)), 0)

    def test_live_activity_push_to_start_then_updates_then_ends(self):
        self.t.store.upsert(DEV, "prod", 30, ["s:20~l:28~t:17"], {}, START)
        self.feed(ev("pre", "STATUS_SCHEDULED"), ev(), now=1000)
        asyncio.run(self.t.drain(now=1030))
        starts = [p for tok, p, kw in self.apns.log if tok == START]
        self.assertEqual(len(starts), 1)
        self.assertEqual(starts[0]["aps"]["event"], "start")
        self.assertEqual(starts[0]["aps"]["attributes"]["awayAbbr"], "NE")
        # The phone reports its activity token; the next change updates it, held too.
        self.t.store.set_activity(DEV, "401", LA)
        self.feed(ev(), ev(away=3), now=1100)
        asyncio.run(self.t.drain(now=1129))
        self.assertFalse(any(tok == LA for tok, _, _ in self.apns.log))
        asyncio.run(self.t.drain(now=1130))
        upd = [p for tok, p, kw in self.apns.log if tok == LA]
        self.assertEqual(upd[-1]["aps"]["content-state"]["away"], 3)
        self.feed(ev(away=3), ev("post", "STATUS_FINAL", away=3), now=1200)
        asyncio.run(self.t.drain(now=1300))
        self.assertEqual([p for tok, p, kw in self.apns.log if tok == LA][-1]["aps"]["event"], "end")
        self.assertNotIn(DEV, self.t.store.activities.get("401", {}))
        # Never started twice for the same game.
        self.feed(ev(away=3), ev(away=4), now=1400)
        asyncio.run(self.t.drain(now=2000))
        self.assertEqual(len([1 for tok, _, _ in self.apns.log if tok == START]), 1)

    def test_scorebug_fields_reach_the_live_activity(self):
        e = ev(home=7)
        e["competitions"][0]["situation"] = {"possession": "26", "shortDownDistanceText": "2nd & 7",
                                             "possessionText": "NE 16", "isRedZone": True,
                                             "homeTimeouts": 3, "awayTimeouts": 2}
        e["competitions"][0]["competitors"][0]["records"] = [{"summary": "3-1"}]
        st = tape.content_state(snapshot(e))
        self.assertEqual((st["spot"], st["redZone"], st["homeTimeouts"], st["awayTimeouts"], st["possession"]),
                         ("NE 16", True, 3, 2, "home"))
        a = tape.attributes("401", e, tape.teams_of(e))
        self.assertEqual((a["homeUid"], a["homeRecord"]), ("s:20~l:28~t:26", "3-1"))

    def test_starting_soon_card_goes_up_15_minutes_before_your_stream(self):
        self.t.store.upsert(DEV, "prod", 30, ["s:20~l:28~t:26"], {}, START)
        e = ev("pre", "STATUS_SCHEDULED")
        e["date"] = "2026-10-04T20:25Z"
        e["competitions"][0]["broadcasts"] = [{"market": "home", "names": ["KING"]}, {"market": "national", "names": ["CBS"]}]
        e["competitions"][0]["competitors"][0]["probables"] = [{"athlete": {"shortName": "G. Smith"},
            "statistics": [{"abbreviation": "W", "displayValue": "3"}, {"abbreviation": "L", "displayValue": "1"},
                           {"abbreviation": "ERA", "displayValue": "2.10"}]}]
        start = tape.start_time(e)
        asyncio.run(self.t.on_pregame(e, snapshot(e), now=start - 20 * 60))
        self.assertEqual(asyncio.run(self.t.drain(now=start - 20 * 60)), 0)       # too early
        asyncio.run(self.t.on_pregame(e, snapshot(e), now=start - 15 * 60 + 5))
        asyncio.run(self.t.drain(now=start - 15 * 60 + 29))
        self.assertFalse(any(tok == START for tok, _, _ in self.apns.log))        # held by the delay
        asyncio.run(self.t.drain(now=start - 15 * 60 + 30))
        push = [p for tok, p, _ in self.apns.log if tok == START][0]["aps"]
        self.assertEqual(push["content-state"]["state"], "pre")
        self.assertEqual((push["attributes"]["watch"], push["attributes"]["delay"]), ("CBS · KING", 30))
        self.assertEqual((push["attributes"]["homeProbable"], push["attributes"]["homeProbableLine"]), ("G. Smith", "3-1 · 2.10"))
        # Past start with nothing under way: one "late" update to the running card.
        self.t.store.set_activity(DEV, "401", LA)
        asyncio.run(self.t.on_pregame(e, snapshot(e), now=start + 200))
        asyncio.run(self.t.on_pregame(e, snapshot(e), now=start + 300))
        asyncio.run(self.t.drain(now=start + 400))
        lates = [p for tok, p, _ in self.apns.log if tok == LA]
        self.assertEqual(len(lates), 1)
        self.assertTrue(lates[0]["aps"]["content-state"]["late"])

    def test_no_notifications_while_the_game_is_on_the_lock_screen(self):
        self.t.store.upsert(DEV, "prod", 0, ["s:20~l:28~t:26"], {}, START)
        self.feed(ev("pre", "STATUS_SCHEDULED"), ev(), now=1000)      # starts the card
        self.t.store.set_activity(DEV, "401", LA)
        self.feed(ev(), ev(home=7), now=1100)                          # a score
        asyncio.run(self.t.drain(now=2000))
        alerts = [p for tok, p, kw in self.apns.log if tok == DEV]
        self.assertEqual(alerts, [])
        self.assertTrue(any(tok == LA for tok, _, _ in self.apns.log))

    def test_delayed_view(self):
        self.feed(ev("pre", "STATUS_SCHEDULED"), ev(), now=1000)
        self.feed(ev(), ev(home=7), now=1060)
        self.assertEqual(self.t.delayed("401", 45, now=1070)["home"]["sc"], 0)
        self.assertEqual(self.t.delayed("401", 0, now=1070)["home"]["sc"], 7)
        self.assertEqual(self.t.delayed("401", 600, now=1070)["st"], "pre")
        self.assertIsNone(self.t.delayed("nope", 0))

    def test_first_sighting_live_is_not_reported_as_pregame(self):
        # A relay restart mid-game: no pre-game copy exists, so don't claim it hasn't started.
        asyncio.run(self.t.on_snapshot(ev(home=3), None, snapshot(ev(home=3)), now=5000))
        got = self.t.delayed("401", 30, now=5010)
        self.assertEqual(got["st"], "in")
        self.assertEqual(self.t.latest("401")["ts"], 5000)

    def test_zero_balls_strikes_outs_survive(self):
        e = ev(sport="1", league="10")
        e["competitions"][0]["situation"] = {"balls": 0, "strikes": 2, "outs": 0, "onFirst": False}
        st = tape.content_state(snapshot(e))
        self.assertEqual((st.get("balls"), st.get("strikes"), st.get("outs")), (0, 2, 0))

    def test_store_survives_restart(self):
        self.t.store.upsert(DEV, "dev", 99999, ["x"], {"score": False}, START)
        s2 = tape.Store(os.path.join(self.dir, "t.db"))
        self.assertEqual(s2.devices[DEV]["delay"], tape.MAX_DELAY)
        self.assertEqual(s2.devices[DEV]["env"], "dev")


class MilbTest(unittest.TestCase):
    def game(self, pk=821809, abstract="Preview", detailed="Scheduled", inning=None):
        g = {"gamePk": pk, "gameDate": "2026-08-01T23:05:00Z",
             "status": {"abstractGameState": abstract, "detailedState": detailed},
             "teams": {"home": {"score": 2, "team": {"id": 453, "abbreviation": "BKC", "teamName": "Cyclones"}},
                       "away": {"score": 1, "team": {"id": 1, "abbreviation": "HV", "teamName": "Renegades"}}}}
        if inning:
            g["linescore"] = {"currentInning": inning, "currentInningOrdinal": "5th", "inningState": "Top",
                              "balls": 1, "strikes": 2, "outs": 1, "offense": {"first": {"id": 9}}}
        return g

    def test_postponed_duplicate_keeps_the_postponement(self):
        import milb
        doc = {"dates": [{"games": [self.game(abstract="Final", detailed="Postponed")]},
                         {"games": [self.game()]}]}
        evs = milb.events_from(doc)
        self.assertEqual(len(evs), 1)
        self.assertEqual(evs[0]["competitions"][0]["status"]["type"]["name"], "STATUS_POSTPONED")

    def test_live_game_reads_like_espn(self):
        import milb
        ev = milb.events_from({"dates": [{"games": [self.game(abstract="Live", detailed="In Progress", inning=5)]}]})[0]
        s = snapshot(ev)
        self.assertEqual((s["id"], s["st"], s["dt"]), ("milb-821809", "in", "Top 5th"))
        self.assertTrue(s["sit"]["on1"])
        self.assertEqual(tape.teams_of(ev)["home"]["uid"], "milb:453")
        self.assertEqual(tape.sport_of(ev["uid"]), "baseball")


if __name__ == "__main__":
    unittest.main()
