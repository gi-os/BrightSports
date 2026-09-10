"""ESPN FastCast client: one websocket, many topics, JSON-patch updates onto a checkpoint.

The protocol, as observed from espn.com (there is no documentation):

  GET https://fastcast.semfs.engsvc.go.com/public/websockethost -> {ip, securePort, token}
  wss://{ip}:{securePort}/FastcastService/pubsub/profiles/12000?TrafficManager-Token={token}
  -> {"op":"C"}                              connect; reply carries "sid" and "hbi" (heartbeat s)
  -> {"op":"S","sid":..,"tc":"scoreboard-football-nfl"}   subscribe
  <- {"op":"H","tc":..,"mid":N,"pl":"<checkpoint url>"}   full document (the site-API scoreboard)
  <- {"op":"R","tc":..,"mid":N,"pl":"<json>"}             update: {"ts","~c",pl:[json-patch ops]}
       ~c == 1 means pl is base64(zlib(json)); paths start with the event uid, e.g.
       "s:20~l:28~e:401872661/competitions/0/status/period".
  <- {"op":"B"}                                           heartbeat
"""
import asyncio, base64, json, logging, ssl, time, zlib
import aiohttp, websockets

log = logging.getLogger("fastcast")
HOST = "https://fastcast.semfs.engsvc.go.com/public/websockethost"
UA = {"User-Agent": "Mozilla/5.0 (BrightSports relay)"}


class FastCast:
    def __init__(self, topics, on_document, on_event_changed):
        self.topics = topics
        self.on_document = on_document          # (topic, doc) after a checkpoint
        self.on_event_changed = on_event_changed  # (topic, event) after patches touched it
        self.docs = {}      # topic -> checkpoint document
        self.index = {}     # topic -> {event uid: event dict}
        self.connected = False
        self.last_message = 0.0

    async def run_forever(self):
        delay = 2
        while True:
            try:
                await self._session()
                delay = 2
            except Exception as e:
                log.warning("fastcast session ended: %s", e)
            self.connected = False
            await asyncio.sleep(delay)
            delay = min(delay * 2, 60)

    async def _session(self):
        async with aiohttp.ClientSession(headers=UA) as http:
            async with http.get(HOST) as r:
                host = await r.json()
            url = (f"wss://{host['ip']}:{host['securePort']}/FastcastService/pubsub/profiles/12000"
                   f"?TrafficManager-Token={host['token']}")
            async with websockets.connect(url, ssl=ssl.create_default_context(), open_timeout=20,
                                          ping_interval=None, max_size=8 * 1024 * 1024) as ws:
                await ws.send(json.dumps({"op": "C"}))
                first = json.loads(await ws.recv())
                sid = first["sid"]
                hbi = int(first.get("hbi", 30))
                for tc in self.topics:
                    await ws.send(json.dumps({"op": "S", "sid": sid, "tc": tc}))
                self.connected = True
                log.info("fastcast connected, %d topics", len(self.topics))
                while True:
                    # Silence for four heartbeat intervals means the socket is dead even if TCP says otherwise.
                    raw = await asyncio.wait_for(ws.recv(), timeout=hbi * 4)
                    self.last_message = time.time()
                    await self._handle(http, json.loads(raw))

    async def _handle(self, http, msg):
        op = msg.get("op")
        tc = msg.get("tc")
        if op == "B":
            return
        if op == "S":
            pl = msg.get("pl")
            if pl and "No such" in str(pl):
                log.error("topic refused: %s", tc)
            return
        if op == "H":
            url = msg.get("pl")
            if not url:
                return
            async with http.get(url) as r:
                doc = await r.json(content_type=None)
            doc = self._unwrap(doc)
            self.docs[tc] = doc
            self.index[tc] = {e.get("uid"): e for e in doc.get("events", []) if e.get("uid")}
            log.info("checkpoint %s: %d events", tc, len(self.index[tc]))
            await self.on_document(tc, doc)
            return
        if op == "R":
            ops = self._ops(msg.get("pl"))
            if not ops or tc not in self.index:
                return
            touched = set()
            for o in ops:
                uid = self._apply(tc, o)
                if uid:
                    touched.add(uid)
            for uid in touched:
                ev = self.index[tc].get(uid)
                if ev is not None:
                    await self.on_event_changed(tc, ev)

    @staticmethod
    def _unwrap(doc):
        # A checkpoint is sometimes the document itself and sometimes {"pl": document}.
        if isinstance(doc, dict) and "events" not in doc and "pl" in doc:
            pl = doc["pl"]
            if isinstance(pl, str):
                if doc.get("~c") == 1:
                    pl = zlib.decompress(base64.b64decode(pl)).decode()
                pl = json.loads(pl)
            return pl
        return doc

    @staticmethod
    def _ops(pl):
        if pl is None:
            return []
        outer = json.loads(pl) if isinstance(pl, str) else pl
        inner = outer.get("pl") if isinstance(outer, dict) else outer
        if isinstance(inner, str):
            if outer.get("~c") == 1:
                inner = zlib.decompress(base64.b64decode(inner)).decode()
            inner = json.loads(inner)
        return inner if isinstance(inner, list) else []

    def _apply(self, tc, o):
        path = o.get("path", "")
        if "/" not in path and not path.startswith("s:"):
            return None
        uid, _, rest = path.partition("/")
        ev = self.index[tc].get(uid)
        op = o.get("op")
        if ev is None:
            # A new event appearing mid-day: "add" at the root with the whole object.
            if op == "add" and rest == "" and isinstance(o.get("value"), dict):
                ev = o["value"]
                self.index[tc][uid] = ev
                self.docs[tc].setdefault("events", []).append(ev)
                return uid
            return None
        if rest == "":
            if op == "remove":
                self.index[tc].pop(uid, None)
                return None
            if op in ("replace", "add") and isinstance(o.get("value"), dict):
                ev.clear(); ev.update(o["value"])
                return uid
            return None
        parts = rest.split("/")
        node = ev
        for p in parts[:-1]:
            nxt = None
            if isinstance(node, list):
                try:
                    nxt = node[int(p)]
                except (ValueError, IndexError):
                    return uid
            elif isinstance(node, dict):
                nxt = node.get(p)
                if nxt is None and op in ("add", "replace"):
                    nxt = node[p] = {}
            if nxt is None:
                return uid
            node = nxt
        last = parts[-1]
        try:
            if op in ("replace", "add"):
                if isinstance(node, list):
                    if last == "-":
                        node.append(o.get("value"))
                    else:
                        i = int(last)
                        if i < len(node):
                            node[i] = o.get("value")
                        else:
                            node.append(o.get("value"))
                elif isinstance(node, dict):
                    node[last] = o.get("value")
            elif op == "remove":
                if isinstance(node, list):
                    i = int(last)
                    if i < len(node):
                        del node[i]
                elif isinstance(node, dict):
                    node.pop(last, None)
        except (ValueError, TypeError):
            pass
        return uid
