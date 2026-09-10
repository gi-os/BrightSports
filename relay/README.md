# BrightSports relay

ESPN FastCast in, one ntfy topic per game out. Runs on BasilNet at
`/volume1/docker/brightsports-relay`, published at `https://sports.gzl.dev` through a
named Cloudflare tunnel (`brightsports`).

## Shape

```
ESPN FastCast (wss) --> relay (Python) --> ntfy --> cloudflared --> phones
   7 league topics       JSON-patch onto     bs-<eventId>            one websocket each,
                         a checkpoint;       topics, 12 h            subscribed to the games
                         publish on change   cache                   in their own feed
```

The relay knows nothing about users. A phone subscribes to `bs-<eventId>` for the
followed games that are live or about to start, and to `bs-relay` for the heartbeat.

## Files

| Path | What |
| --- | --- |
| `fastcast.py` | FastCast client: websockethost handshake, subscribe, checkpoint fetch, JSON-patch apply |
| `main.py` | Snapshot per game, publish on change, heartbeat, five-minute site-API correction |
| `docker-compose.yml` | `bs-ntfy`, `bs-relay`, `bs-cloudflared` |
| `ntfy/server.yml` | read-only by default, relay user is write-only on `bs-*`, rate limits lifted for the docker network |
| `.env` (not committed) | `NTFY_TOKEN=tk_…` for the relay user |

The tunnel credentials (`cloudflared/<id>.json`) are not committed either.

## Run

```
cd /volume1/docker/brightsports-relay
docker compose up -d --build
docker logs bs-relay --tail 20
curl -s 'https://sports.gzl.dev/bs-relay/json?poll=1&since=2m'   # heartbeat: {"fastcast":true,"live":N,...}
```

## FastCast, as observed

Topics that exist: `scoreboard-football-nfl`, `scoreboard-football-college-football`,
`scoreboard-baseball-mlb`, `scoreboard-basketball-nba`, `scoreboard-basketball-wnba`,
`scoreboard-hockey-nhl`, `scoreboard-soccer` (every league in one). No topic for racing or
tennis; the phone polls those. A checkpoint is the site-API scoreboard document. An update is
a list of JSON-patch operations whose paths start with the event uid
(`s:20~l:28~e:401872661/competitions/0/status/period`), sometimes zlib+base64 (`"~c": 1`).

## Message

```json
{"v":1,"id":"401872661","st":"in","nm":"STATUS_IN_PROGRESS","dt":"3:24 - 2nd","done":false,
 "p":2,"ck":"3:24","home":{"id":"26","ab":"SEA","sc":14,"ls":["7","7"]},"away":{"id":"17","ab":"NE","sc":7},
 "sit":{"poss":"26","dd":"2nd & 7 at NE 16","rz":true,"hto":3,"ato":2,"lp":"...","drive":"7 plays, 58 yards"},
 "ts":1789056342476,"src":"patch"}
```

`st` is `pre`/`in`/`post`; `done` is ESPN's `completed`. Baseball carries `b`, `s`, `o`,
`on1..on3`, `bat`, `pit`, `bats`, `pits` under `sit`. The phone applies this onto the game
it already holds (`data/RelaySnapshot.kt`) and runs its own diff.
