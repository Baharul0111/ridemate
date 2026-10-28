# Load test results

Architecture §4.12: *k6 at 100 concurrent sessions with a stub provider, then 20 live reads.* This page records
the stub-provider run. The 20 live reads are a separate, metered step run by the owner/coordinator
([tools/loadtest/live_reads.md](../tools/loadtest/live_reads.md)); their results are appended below when done.

## Stub-provider run (2026-10-28 16:05 UTC)

**How it was run:** `tools/loadtest/run_local.sh` (VUS=100, DURATION=2m) on
Darwin arm64, Python 3.12.8, one uvicorn process.

- **Server:** the real `ridemate_api.app.create_app` with the real endpoints, request validation, body limits,
  quota manager, all-spend meter, demo cache and provider router. Substituted: the model provider
  (`ridemate_api.stub_provider.StubProvider`, deterministic canned replies after a simulated latency of
  `read=2300,reread=1500,point=1200,parse=600,place=400` ms ±20 %), auth (fixed `Bearer loadtest` token, one judge-tier Google user per session),
  the per-IP limiter (open, because every virtual user shares 127.0.0.1), hot state (`MemoryStore` instead of the
  Modal Dict), Remote Config (bundled defaults) and Firestore/ONDC (off). No call left the machine.
- **Each session (one virtual user, one new user id):** config-check → place-pick (half of sessions) → 4
  read-screens (3 unique screenshots on the live path + 1 allowlisted demo screen) → 1 reread of 2 row crops →
  2 parse-notifications → 1 event, with 1–5 s of think time between taps, then the next session.
- **SLO thresholds:** read-screen server p95 ≤ 8 s; failed requests < 1 %; every other endpoint inside the
  phone's timeout.
- **What it does not measure:** Modal cold starts, Modal Dict and Firestore latency, the real model's latency
  and variance (covered by the 20 live reads), and multi-container scaling (`max_containers=3`, `max_inputs=20`
  each in production, against one local process here).

**Result: PASS** (k6 exit code 0; 16/16 thresholds met)

| Measure | Value |
|---|---|
| Virtual users (concurrent sessions) | 100 |
| Duration | 2m (+ up to 60 s graceful stop) |
| Sessions started / completed cleanly | 400 / 400 |
| HTTP requests | 3802 (24.0/s) |
| Failed requests (non-2xx) | 0 (0.00 %) |
| Checks passed | 7202 / 7202 |
| Upload volume | 142.8 MB |
| Server RSS at end | 73 MiB |
| Server log errors / tracebacks | 0 |

Latency per endpoint (k6 `http_req_duration`, measured on the same machine, so ≈ server time):

| Endpoint | avg | median | p90 | p95 | p99 | max |
|---|---|---|---|---|---|---|
| POST /v1/read-screen (all) | 1.76 s | 2.17 s | 2.66 s | 2.71 s | 2.75 s | 2.81 s |
|   live path (unique screenshot → stub model) | 2.31 s | 2.32 s | 2.68 s | 2.72 s | 2.75 s | 2.81 s |
|   demo path (allowlisted demo image → cache) | 115 ms | 3 ms | 16 ms | 40 ms | 2.73 s | 2.74 s |
| POST /v1/reread | 1.48 s | 1.47 s | 1.74 s | 1.78 s | 1.80 s | 1.84 s |
| POST /v1/parse-notification | 670 ms | 692 ms | 697 ms | 705 ms | 736 ms | 796 ms |
| POST /v1/place-pick | 388 ms | 387 ms | 389 ms | 390 ms | 399 ms | 446 ms |
| GET /v1/config-check | 25 ms | 3 ms | 90 ms | 95 ms | 96 ms | 97 ms |
| POST /v1/event | 5 ms | 3 ms | 7 ms | 13 ms | 40 ms | 123 ms |

Server-reported read latency (`latency_ms` in the response): median 2.17 s, p95 2.71 s, max 2.77 s.

Read sources: cache 384, live 1216, recorded 0

Thresholds:

| Metric | Threshold | Result |
|---|---|---|
| `checks` | `rate>0.99` | pass |
| `http_req_duration{endpoint:config_check}` | `p(95)<=3000` | pass |
| `http_req_duration{endpoint:event}` | `p(95)<=3000` | pass |
| `http_req_duration{endpoint:parse_notification}` | `p(95)<=11000` | pass |
| `http_req_duration{endpoint:place_pick}` | `p(95)<=11000` | pass |
| `http_req_duration{endpoint:read_screen,path:demo}` | `p(95)<=8000` | pass |
| `http_req_duration{endpoint:read_screen,path:live}` | `p(95)<=8000` | pass |
| `http_req_duration{endpoint:read_screen}` | `p(95)<=8000` | pass |
| `http_req_duration{endpoint:reread}` | `p(95)<=20000` | pass |
| `http_req_failed` | `rate<0.01` | pass |
| `http_req_failed{endpoint:read_screen}` | `rate<0.01` | pass |
| `read_server_latency_ms` | `p(95)<=8000` | pass |
| `read_source{source:cache}` | `count>=0` | pass |
| `read_source{source:live}` | `count>=0` | pass |
| `read_source{source:recorded}` | `count>=0` | pass |
| `session_completed` | `rate>0.99` | pass |

## Live reads (20, metered)

Pending: see tools/loadtest/live_reads.md.
