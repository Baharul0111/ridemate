# RideMate build contracts

This file pins the interfaces between the parts of RideMate. `architecture.md` says *what* to build; this says
*exactly how the parts talk*. Change a contract here first, then the code on both sides.

---------------------------------------------------------------------------------------------------------------

## 1. HTTP API (`ridemate-api`, Modal web app)

Base URL: delivered to the phone by Remote Config key `api_base_url` (never hard-coded in the APK).

### 1.1 Common request headers
| Header | Required | Meaning |
|---|---|---|
| `Authorization: Bearer <Firebase ID token>` | all `/v1/*` | Google-provider token for AI endpoints; anonymous allowed only where noted |
| `X-Request-Id` | all `/v1/*` | UUIDv4 from the phone. **Quota** counting is idempotent on it (a retried request id is not counted twice). **Spend is not**: see below |
| `X-Session-Id` | AI + event | Phone session id (UUID). Also groups the request's metadata audit record: one `audit` document per session |
| `X-App-Version` | all | Android versionCode (int) |
| `X-Tap-Deadline-Ms` | read-screen, reread (optional) | Milliseconds left of the phone's 20 s tap budget when the request is sent (§4.12). The server clamps it to 0–20000; absent or not an integer means 20000. The model call, and the decision to retry a read once (≥ 6 s must remain), are bounded by it. With ≥ 13 s left the first read attempt stops at (budget − 7 s), so a timed-out read is also tried once more |

Retries and money. The spend meter is **not** idempotent on `X-Request-Id`: every model call the server makes is
metered, because a timed-out or repeated call was still paid for. To keep a replayed id from buying unlimited calls
for one quota unit, each (user, quota kind, `X-Request-Id`) gets at most **2 model-backed runs per IST day** (the
first try and the phone's one retry, §4.12; a read run may include the server's own single retry). The quota kinds
are *read* (read-screen, reread and point share it) and *parse* (parse-notification, place-pick). A third request
with the same id is refused with 422 `invalid` and makes no model call, and so is a request sent while another one
with the same id is still being counted. A unit is given back only when the run that counted it fails (timeout,
provider error, cancelled) and no later run of its id has started; a replay of an answered id that fails keeps the
unit. A retry therefore reuses the request id; a new user action sends a new one.

### 1.2 Error envelope (every non-2xx)
```json
{"error": {"code": "ai_paused", "resume_at_ist": "13:30", "retry_after_s": 3600, "reason": "day_cap",
           "request_id": "…"}}
```
Every field except `code` may be `null`. `Retry-After` (seconds) is also sent as a header whenever
`retry_after_s` is set. `reason` is an **additive** field that says why, when the code alone doesn't:
`ai_paused` → `budget|day_cap|tier_pause`; `ai_unavailable` → `kill_switch|breaker|model_missing` (or `null`
for a provider error); `meter_unavailable` → `meter`; `ondc_unavailable` → `timeout|nack` (or `null`). The phone
may show or log it but must tolerate `null` and values it doesn't know; it never changes the phone behaviour below.
| HTTP | `code` | Phone behaviour |
|---|---|---|
| 401 | `auth_required`, `auth_invalid` | refresh token once, else manual mode |
| 403 | `google_required` | offer Google sign-in; manual mode |
| 403 | `blocked` | manual mode |
| 403 | `forbidden` | (ONDC object not owned by caller; owner-only endpoint) |
| 404 | `not_found` | |
| 405 | `method_not_allowed` | wrong HTTP method (a client bug; not retried) |
| 413 | `too_large` | image > 1 MB or JSON > 64 KB |
| 415 | `bad_media` | magic bytes not JPEG/PNG |
| 422 | `invalid` | also a request id replayed past its 2 model-backed runs, or sent again while it is still being counted (§1.1) |
| 429 | `rate_limited` | per-IP bucket (`retry_after_s`), and the per-user daily caps on endpoints that write Firestore: `/v1/erase` (10 a day, `resume_at_ist`; the outbox retries after it). `/v1/event` past its cap (30 a day) answers 202 and stores nothing |
| 429 | `quota_exhausted` | per-user daily fair share; `resume_at_ist` |
| 429 | `ai_paused` | credit budget / daily pacing cap / tier pause; `resume_at_ist` |
| 500 | `internal` | unexpected server error; no detail is ever returned. AI endpoints: manual mode; others: retry later |
| 503 | `ai_unavailable` | kill switch, breaker open, model id missing |
| 503 | `auth_unavailable` | Google's token certificates or the revocation lookup couldn't be reached: the token wasn't judged. Retry once after a few seconds (don't refresh the token); AI → manual mode |
| 503 | `meter_unavailable` | spend meter unreadable → fail closed for AI |
| 503 | `storage_unavailable` | the hot store or Firestore couldn't take a non-AI write (`/v1/erase`, `/v1/owner/fcm-token`): retry later (erase stays in the outbox) |
| 503 | `ondc_unavailable` | ONDC off or Firestore quota exhausted |
| 504 | `model_timeout` | model call exceeded its deadline |

Every AI error means the same thing on the phone: **manual mode** (type the price) with the text
"AI reading resumes at hh:mm (IST)" when `resume_at_ist` is present. The guardian keeps working.

### 1.3 Endpoints

**`POST /v1/read-screen`** — Channel A. Google token, *or* any token (incl. anonymous) when the image's
SHA-256 is in the demo allowlist. A demo image is answered from the cache, else by a live call (at most 64 live demo
calls a day across all users, charged to the demo sub-budget and its own $0.50 daily cap, never to the public's day
cap), else from the recorded output. `multipart/form-data`:
- `meta` (JSON string): `{"app": "uber|ola|rapido|demo_kestrel|demo_pepper|demo_tamarind", "frame_w": 1080, "frame_h": 2400, "demo": false}`
- `image`: JPEG or PNG, ≤ 1 MB, already masked on the phone.

200 response:
```json
{"read": <ChannelARead>, "source": "live|cache|recorded", "model": "meta-models/Muse-Spark-Open-816B-A42B",
 "latency_ms": 2310, "request_id": "…"}
```
`ChannelARead` (every field nullable except `options`, which may be empty; `options` ≤ 12, extra truncated):
```json
{"app": "uber", "screen_type": "fare_options|searching|driver_assigned|cancel_flow|cancelled|home|other|unreadable",
 "options": [{"vehicle_class": "bike|auto|cab_economy|cab_premium|cab_xl|other", "label_text": "Auto",
              "price_min": 110, "price_max": 130, "price_struck_text": "₹150", "eta_min": 3,
              "bbox_0_1000": [x0, y0, x1, y1]}],
 "fee_text": null, "driver_eta_min": 4, "driver_distance_text": "1.2 km"}
```
Prices are integers in rupees. `bbox_0_1000` is the row's box on a 0–1000 grid of the image (x right, y down).

**`POST /v1/reread`** — row crops for disagreeing rows, one model call. Google token, always (crops of demo images
are not a demo path: the app never rereads demo frames). multipart: `meta` = `{"app": "...", "rows": [{"row_id": "r1"}]}`,
files `crop_<row_id>` (total ≤ 1 MB).
200: `{"rows": [{"row_id": "r1", "vehicle_class": "auto", "label_text": "Auto", "price_min": 110, "price_max": 110,
"eta_min": 3}], "source": "live", "request_id": "…"}`

**`POST /v1/point`** — "Show me" grounding. Google token, *or* any token when the image is on the demo allowlist
(cached like read-screen, without a recorded fallback: past the day's 64 live demo calls a miss answers 429
`ai_paused`/`day_cap`). multipart `meta` = `{"app": "...", "target": "cancel_button"}`, `image`. `target` must be one
of `cancel_button`, `confirm_cancel_button`, `cancel_reason` (anything else: 422 `invalid`, before any model call).
200: `{"supported": true, "box_0_1000": [x0,y0,x1,y1] | null}` (`supported=false`, with no model call
and no quota used, whenever Remote Config `point_supported` (§2.7) is false; the phone then hides Show me).

**`POST /v1/parse-notification`** — rule-miss classifier. Google token. JSON:
```json
{"app": "rapido", "version_code": 1234, "title": "<masked>", "text": "<masked>", "channel_id": "ride_updates",
 "category": "status", "is_ongoing": true, "leg_state": "SEARCHING"}
```
200: `{"label": "searching|assigned|arriving|driver_cancelled|no_drivers|cancelled|ended|trip_started|other|unknown",
"evidence_span": "captain is on the way", "request_id": "…"}`. The server forces `label="unknown"` when
`evidence_span` is not a substring of `title + "\n" + text`. The phone uses a label only to raise MAYBE prompts or
propose SEARCHING/ASSIGNED — never CANCEL_CONFIRMED, never to end a leg.

**`POST /v1/place-pick`** — Google token. JSON `{"transcript": "airport", "lang": "hi-IN",
"candidates": [{"id": "c1", "label": "LGBI Airport, Guwahati"}]}` (≤ 5 candidates) → `{"pick": "c1" | "none"}`.

**`POST /v1/event`** — one per session, Google token (anonymous → 202 no-op). JSON
`{"session_id": "…", "app": "uber", "version_code": 1234, "outcome": "confirmed_all|dismissed|abandoned|demo",
"time_to_confirm_bucket": "lt_1m|1_3m|3_10m|gt_10m|none", "android_version": 34,
"reads": 4, "verified_rows": 9, "unreadable_rows": 1, "rule_hits": 6, "rule_misses": 1, "misses_unclassified": 0,
"demo": false}` → 202. The counters feed the VERIFIED-rate and rule-miss alerts (metadata only, no text). The
event also closes the session's metadata audit record: the AI requests sent with this `X-Session-Id` become one
Firestore `audit` document (endpoint, outcome, latency bucket, cost, app, versionCode, uid hash; never text or
images). Sessions that never send an event are closed by the server after 30 idle minutes.

**`POST /v1/erase`** — Google token. Deletes the caller's server data (ONDC docs, events by uid hash, owner
device registration); marks the usage counter `erased_at` (deleted by cleanup after 48 h). → `{"erased": true}`.

**`GET /v1/config-check`** — any token. →
```json
{"ai_enabled": true, "reason": null, "resume_at_ist": null, "point_supported": false,
 "quota": {"reads_left": 6, "parses_left": 10}, "cap_profile": "normal", "min_supported_version": 1,
 "server_time_utc": "2026-10-28T10:00:00Z",
 "budget": {"ai_spent_usd": 1.23, "ai_budget_usd": 80, "compute_spent_usd": 0.4, "compute_budget_usd": 12,
            "day_cap_usd": 1.3, "day_spent_usd": 0.1, "horizon_end": "2027-01-26"}}
```
`budget` only for owner accounts (email in `OWNER_EMAILS`); `null` otherwise. `reason` ∈
`signed_out|anonymous|kill_switch|budget|day_cap|tier_pause|quota|breaker|meter|model_missing`.

**`POST /v1/owner/fcm-token`** — owner only. `{"token": "…"}` → 204. Used for owner alert pushes.

**`GET /healthz`** → `{"ok": true}`. **`GET /readyz`** → 200 `{"ok": true, "checks": {...}}` or 503.

### 1.4 ONDC (buyer app; hackathon seller is the in-process mock)
All Google token; every confirm/cancel carries the user's `tap_id` (I1) and is checked for revocation. Per user and
IST day: 20 searches and 100 other calls (429 `rate_limited` with `resume_at_ist` past that; 503 when the count can't
be read), so one account can't use up the shared Firestore quota.
| Endpoint | Body | Result |
|---|---|---|
| `POST /v1/ondc/search` | `{"session_id","pickup":{"lat","lng"},"drop":{"lat","lng"}}` | `{"txn_id"}` |
| `POST /v1/ondc/select` | `{"txn_id","item_id"}` | `{"ok":true}` |
| `POST /v1/ondc/init` | `{"txn_id"}` | `{"ok":true}` |
| `POST /v1/ondc/confirm` | `{"txn_id","tap_id"}` | `{"ok":true}` |
| `POST /v1/ondc/status` | `{"txn_id"}` | `{"ok":true}` |
| `POST /v1/ondc/cancel` | `{"txn_id","tap_id","confirm":false}` → soft cancel: any earlier `fee` is cleared at once (null until the seller prices this request), the new fee lands in Firestore; `"confirm":true` → confirm cancel; `{"txn_id","tap_id","keep":true}` → "Keep ride": the transaction returns to its ride state (progress reported meanwhile included) and a late quote is ignored; `{"txn_id","abandon":true}` (no tap) → the phone stopped showing a transaction with no order yet: FAILED, pickup/drop cleared. At most one of `confirm`/`keep`/`abandon` | `{"ok":true}` |

Callbacks `POST /ondc/on_{search,select,init,confirm,status,cancel,update}` (signed by the seller). Mock seller
`POST /mock-bpp/{search,select,init,confirm,status,cancel}` mounted only when `ONDC_MODE=mock`.

Firestore `ondc_txn/{txnId}` (the phone listens; owner-only reads):
```json
{"uid": "…", "bpp_id": "mock-bpp.ridemate", "bpp_uri": "…", "demo": true,
 "state": "SEARCHING_NET|ON_SELECT|INIT|CONFIRMED|ARRIVING|ON_TRIP|ENDED|SOFT_CANCEL|CANCEL_CONFIRMED|FAILED",
 "quotes": [{"item_id": "…", "provider_name": "Demo Seller Autos", "vehicle_class": "auto", "price": 92, "eta_min": 4}],
 "selected_item_id": null, "fee": {"amount": 0, "currency": "INR"} | null, "driver": {"eta_min": 4, "distance_text": "1.1 km"},
 "resume_state": "CONFIRMED|ARRIVING|ON_TRIP" | null, "failure_code": "expired|abandoned|…" | null,
 "created_at": "…", "updated_at": "…"}
```
`fee` is null from a soft-cancel request until the seller prices it (the phone shows no fee while it is null).
`resume_state` is the ride state "Keep ride" returns to. A transaction still before an order (`SEARCHING_NET`,
`ON_SELECT`, `INIT`) and idle for 45 minutes is set to `FAILED` (`expired`) by the dispatcher, with `stops` and
`quotes` cleared: pickup/drop never outlive the hour for a search nobody booked.

---------------------------------------------------------------------------------------------------------------

## 2. Remote Config (client template; the server reads the same template)

Every JSON-valued key has a top-level `"schema": "MAJOR.MINOR"`. The phone accepts a payload only if MAJOR
matches what it understands; otherwise it keeps the last-good cache, then the APK-bundled default.
Breaking changes ship under a new key (`fare_parsers_v2`) (F5). Defaults live in `config/remote_config/*.json`;
`config/build_template.py` assembles them into `config/remoteconfig.template.json` and copies the bundled
defaults into `android/app/src/main/assets/config/`.

Packages are keyed by Android package name; demo providers use `demo.kestrel`, `demo.pepper`, `demo.tamarind`.

### 2.1 `notification_templates`
```json
{"schema": "1.0",
 "apps": {
  "com.rapido.passenger": [
   {"min_version_code": 0,
    "templates": [
      {"id": "rapido.assigned.v1", "event": "ASSIGNED",
       "pattern": "(?i)captain (?P<name>[a-z ]+) is (?:on the way|arriving)(?:.*?(?P<eta>\\d+)\\s*min)?",
       "channel_id": null, "ongoing": null}
    ],
    "redacted": [{"event": "ASSIGNED", "channel_id": "ride_status", "ongoing": true, "category": null}],
    "cancelled_keywords": ["cancelled", "रद्द"],
    "ongoing_channel_ids": ["ride_status"]}
  ]}}
```
- `event` ∈ `SEARCHING, ASSIGNED, ARRIVING, DRIVER_CANCELLED, NO_DRIVERS, CANCELLED, ENDED, TRIP_STARTED`.
- `pattern` is RE2 syntax (named groups `(?P<name>…)`), matched against `title + "\n" + text` of the raw post
  **in memory only**. Optional named groups: `eta` (minutes), `dist` (e.g. `1.2 km`), `plate`. Patterns that
  don't compile under RE2/J are rejected at load.
- `channel_id` / `ongoing`: optional extra conditions (null = any).
- The newest entry whose `min_version_code` ≤ the installed versionCode is used; an unknown versionCode falls back
  to the newest entry ≤ it (`template=fallback` logged).
- **First match in list order wins**, for `templates` and for `redacted` alike: list specific events first
  (driver-cancelled before cancelled, arriving before assigned).
- `redacted`: structural matches for redacted/empty posts (Android 15 OTP redaction). `event`, `channel_id`,
  `ongoing` and `category` are required (null = any). Optional, additive fields, each an extra condition when
  present and not null:
  - `id`: the rule's name for logs, like a template `id` (default `<package>.redacted.<index>`);
  - `tag`: the post's notification tag must equal it;
  - `notification_id`: the post's notification id (int) must equal it;
  - `id_changed`: `true` matches only when the post's (notification id, tag) differs from the previous post seen
    from the same package (a new notification, not an update of the last one).

### 2.2 `fare_parsers`
```json
{"schema": "1.0",
 "apps": {
  "com.ubercab": [
   {"min_version_code": 0,
    "vehicle_labels": [{"pattern": "(?i)^uber ?moto|bike", "vehicle_class": "bike"},
                       {"pattern": "(?i)auto", "vehicle_class": "auto"}],
    "price_token_pattern": "(?i)(?:₹|rs\\.?|inr)\\s*[0-9०-९][0-9०-९,]*(?:\\s*[-–]\\s*(?:₹|rs\\.?)?\\s*[0-9०-९][0-9०-९,]*)?",
    "eta_pattern": "(?i)(\\d+)\\s*(?:min|mins|मिनट)",
    "strike_height_ratio": 0.8,
    "row_gap_ratio": 1.8,
    "driver_eta_anchors": ["away", "arriving", "pickup", "मिनट", "दूर"],
    "driver_distance_pattern": "(?i)(\\d+(?:\\.\\d+)?)\\s*(km|m)\\b",
    "screen_keywords": {"fare_options": ["choose a ride", "book"], "searching": ["looking for", "finding"],
                        "driver_assigned": ["arriving", "on the way", "otp"], "cancelled": ["cancelled"]}}
  ]}}
```

### 2.3 `sanity_bands`
```json
{"schema": "1.0", "price_min_inr": 15, "price_max_inr": 5000, "eta_max_min": 60,
 "road_factor": 1.35, "base_allowance_inr": 60,
 "per_km_inr": {"bike": [3, 30], "auto": [8, 45], "cab_economy": [8, 60], "cab_premium": [10, 90],
                "cab_xl": [12, 110], "other": [3, 150]}}
```
A price p passes if `price_min_inr ≤ p ≤ price_max_inr` and, when trip distance d (haversine km × road_factor)
is known: `per_km_min·d − base_allowance ≤ p ≤ per_km_max·d + base_allowance`.

### 2.4 `cancel_cards`
```json
{"schema": "1.0", "apps": {"com.ubercab": {"steps": {"en": ["Tap **Cancel ride**", "Choose **Booked another ride**", "Tap **Confirm**"],
  "hi": ["**Cancel ride** पर टैप करें", "**Booked another ride** चुनें", "**Confirm** पर टैप करें"]},
  "point_target": "cancel_button", "cancelled_keywords": ["cancelled", "रद्द"]}}}
```

### 2.5 `fee_rules`
```json
{"schema": "1.0", "default": {"pct": 10, "max_inr": 100, "free_while_searching": true},
 "apps": {"com.olacabs.customer": {"free_within_s_after_accept": 180, "free_if_driver_late_min": 5, "max_inr": 100},
          "com.ubercab": {"free_within_s_after_accept": 120, "max_inr": 100},
          "com.rapido.passenger": {"free_within_s_after_accept": 60, "max_inr": 50}},
 "states": {"AS": {"max_inr": 100}}}
```

### 2.6 `launch_templates`
```json
{"schema": "1.0", "apps": {
 "com.ubercab": {"mode": "uri", "uri": "https://m.uber.com/ul/?action=setPickup&pickup[latitude]={plat}&pickup[longitude]={plng}&pickup[nickname]={pname}&dropoff[latitude]={dlat}&dropoff[longitude]={dlng}&dropoff[nickname]={dname}", "fallback": "launch"},
 "com.olacabs.customer": {"mode": "launch_paste"},
 "com.rapido.passenger": {"mode": "launch_paste"}}}
```
Placeholders are URL-encoded by the phone. Only the three real apps have entries: there are **no demo entries**
(`demo.*`) in `launch_templates`, because DemoSource shows the bundled demo screens itself (§5) and the phone
rejects the whole payload on an unknown launch mode.

### 2.7 Scalar keys
| Key | Type | Default |
|---|---|---|
| `ai_reader_enabled` | bool | true |
| `reader_enabled_uber` / `_ola` / `_rapido` | bool | true |
| `ondc_enabled` | bool | true (hackathon mock) |
| `demo_enabled` | bool | true |
| `point_supported` | bool | true (set by the day-1 spike, `docs/spikes.md`: grounding hit 3/3 demo cancel screens). The server reads it for `/v1/point` and reports it in `/v1/config-check`; its built-in fallback, used only without the bundled defaults, stays false |
| `model_route` | JSON `{"schema":"1.0","read":"modal_spark_open","parse":"modal_spark_open"}` | |
| `latest_version` | JSON `{"schema":"1.0","version_code":1,"version_name":"1.0.0-hackathon","url":"https://github.com/Baharul0111/ridemate/releases/latest","sha256":""}` | |
| `min_supported_version` | int | 1 |
| `cap_profile` | `normal` \| `judging` | normal |
| `credit_expiry_date` | `YYYY-MM-DD` \| `unknown` | unknown |
| `budget_horizon_days` | int | 90 |
| `budget_horizon_start` | `YYYY-MM-DD` | 2026-10-28 |
| `api_base_url` | string | "" (set at deploy) |
| `judge_allowlist` | JSON `{"schema":"1.0","sha256_emails":[…]}` (lower-cased email, SHA-256 hex) | empty |
| `owner_emails_sha256` | JSON list of SHA-256 of owner emails (enables developer mode) | `[]` |

`owner_emails_sha256` stays a **bare JSON list** (no `"schema"` object): the phone parses it directly as a list of
strings, without `resolveLayered`'s schema-major check, and treats a value that doesn't parse, or has any entry
that isn't a SHA-256 hex digest, as `[]` (developer mode off).

---------------------------------------------------------------------------------------------------------------

## 3. Backend module map (`backend/ridemate_api/`)

| Module | Owns |
|---|---|
| `settings.py` | `Settings` (env-driven), price constants, budget split, limits |
| `clock.py` | `Clock` protocol (`now_utc()`, `monotonic()`), `SystemClock`, `FakeClock`; IST helpers |
| `store.py` | `HotStore` protocol (`get/put/put_if_absent/delete/keys_with_prefix`), `ModalDictStore`, `MemoryStore` |
| `crypto.py` | versioned HMAC of Google `sub` (current + previous key, 30-day dual read) |
| `auth.py` | `Principal`, `verify_bearer()` (Firebase ID token via google-auth certs), `check_not_revoked()` |
| `limits.py` | `IpLimiter` (right-most trusted hop, IPv6 /64), `QuotaManager` (reads/parses per HMAC(sub)/IST day, young-account tier, judge allowlist, idempotent on request id) |
| `spend.py` | `SpendMeter`: sharded all-spend meter, reservations, settle, budgets/sub-budgets, daily pacing cap, tiers, fail-closed, compute heartbeat; `day_total_usd(day)` and `drift_factor()` for billing reconciliation |
| `firestore.py` | REST Firestore client with service-account auth, 3 s deadlines, quota shedding, Increment |
| `remote_config.py` | server-side read of the RC template (60 s cache, bundled defaults) |
| `model_gateway.py` | `ModelGateway` over the `openai` SDK; structured-output mode detection; usage → cost |
| `router.py` | `ProviderRouter`: deadlines, one retry rule, circuit breaker, pinned model id |
| `prompts.py` | prompts + JSON schemas for read/reread/point/parse/place-pick |
| `demo_cache.py` | demo allowlist (SHA-256 of bundled demo images) + response cache + recorded fallbacks |
| `middleware.py` | body limits (1 MB image / 64 KB JSON before reading), magic bytes, request/session ids |
| `logging_setup.py` | JSON logs + scrubber (drops tokens, keys, free text) |
| `metrics.py` | counters/histograms aggregated in the Dict, snapshot helper |
| `audit.py` | `AuditTrail`: per-request metadata records aggregated per `X-Session-Id` in the Dict, one Firestore `audit` doc per session (on `/v1/event` or by the dispatcher) |
| `offload.py` | `off_loop` / `off_loop_shielded`: every blocking Dict/Firestore call on the request path runs in a worker thread, never on the event loop |
| `alerts.py` | threshold evaluation, healthchecks.io pings, FCM HTTP v1 push |
| `cron.py` | the single dispatcher (15 min): heartbeat, alerts, snapshots, idle sessions' audit docs, golden probe, cleanup, billing reconciliation, weekly drift |
| `api/ai.py` | `/v1/read-screen`, `/v1/reread`, `/v1/point`, `/v1/parse-notification`, `/v1/place-pick` |
| `api/misc.py` | `/v1/event`, `/v1/erase`, `/v1/config-check`, `/v1/owner/fcm-token`, `/healthz`, `/readyz` |
| `api/ondc.py` + `ondc/` | BAP endpoints, callbacks, Ed25519 signing, mock seller |
| `deps.py` | `Deps` container wiring everything (tests build it with fakes) |
| `app.py` | `create_app(deps) -> FastAPI` |
| `../modal_app.py` | Modal image, secrets, `@modal.asgi_app`, dispatcher `modal.Cron`, compute heartbeat |

---------------------------------------------------------------------------------------------------------------

## 4. Android module map

`android/core` (pure Kotlin, JVM tests) — package `app.ridemate.core`:
| Package | Owns |
|---|---|
| `model` | shared enums/data (already written: `Provider`, `VehicleClass`, `ScreenType`, `RideState`, …) |
| `format` | Indian number parsing (₹1,234 · Rs 1234 · ₹110–130 · 1,23,456 · Devanagari digits · OCR confusions 2/Z/R/%), IST 12-hour formatting, Indian grouping |
| `mask` | PII detection on text spans: phone numbers, OTP/PIN, plates, names after anchor words; returns spans to blur |
| `ocr` | `OcrToken(text, box, lineId)`, `OcrFrame(width, height, tokens)` — the ML Kit-independent token model |
| `fare` | `FareParser` (Channel B) over `OcrFrame` + parser config → rows (label, class, payable price token, eta token), driver ETA/distance by anchors, screen keywords, K3 "looks like a fare screen" check |
| `gate` | `ChannelARead` (kotlinx-serialization of §1.3), `VerificationGate` (§4.3 a–f, reread decision, freshness), `SanityBands` |
| `config` | kotlinx models + loaders for every §2 payload, schema-major check, last-good fallback |
| `watch` | `PostSnapshot`, normalisation + structural fingerprint (§4.5.9), `TemplateEngine` (RE2/J), `RideWatchPipeline` (dedupe, classifier cache/in-flight, deadlines, UNKNOWN_UPDATE fallback) |
| `state` | `RideEvent`, `Statechart` transition function exactly per the §4.4 table, generation + idempotency keys |
| `guardian` | `Guardian`: sessions/legs, first-driver sheet (bound to leg+generation, two-within-10 s pick), cancel-chain queue, evidence ladder, reminder schedule (30/60/120 s then every 120 s), +60 s follow-up, same-driver plate hash, better-ride rule, kept-leg-lost alerts; emits UI intents; driven by an injected clock |
| `invariants` | I1–I10 checkers used by tests and by debug builds |

`android/app` — package `app.ridemate` (namespace; applicationId `in.ridemate.app`). Areas own their package and
their own string files `res/values/strings_<area>.xml` + `res/values-hi/strings_<area>.xml`.

---------------------------------------------------------------------------------------------------------------

## 5. Demo providers (fictional, labelled DEMO everywhere)
| Provider | Package key | Vehicles |
|---|---|---|
| Kestrel Go | `demo.kestrel` | bike, auto, cab_economy |
| Pepper Rides | `demo.pepper` | auto, cab_economy, cab_premium |
| Tamarind Cabs | `demo.tamarind` | bike, auto, cab_economy, cab_xl |

Assets: `android/app/src/main/assets/demo/<provider>/<screen>.png` + `eval/demo_labels.json` (ground truth),
`eval/recorded/channel_a/<sha256>.json` (recorded Spark Open outputs), `eval/recorded/ocr/<sha256>.json`
(recorded ML Kit tokens from the owner's phone). Demo notification script: `android/app/src/main/assets/demo/script.json`.

---------------------------------------------------------------------------------------------------------------

## 6. Eval fixtures (shared by the Kotlin accuracy test and the Python harness)
All images are identified by the SHA-256 (lower-case hex) of the exact bytes uploaded.

`eval/demo_labels.json` (ground truth; the private golden repo uses the same format in `labels.json`):
```json
{"schema": "1.0", "screens": [
  {"file": "android/app/src/main/assets/demo/kestrel/fare_options.png", "sha256": "…", "app": "demo_kestrel",
   "screen_type": "fare_options", "frame_w": 1080, "frame_h": 2400, "trip_km": 6.2,
   "rows": [{"vehicle_class": "auto", "label": "Auto", "price_min": 110, "price_max": 110, "eta_min": 3,
             "struck_price": 140}],
   "driver_eta_min": null, "driver_distance_text": null, "tags": ["strikethrough", "eta"]}]}
```
`trip_km` is the **straight-line (haversine) km** between pickup and drop, never a road distance: the gate
applies `road_factor` (§2.3) itself, exactly as it does on the phone.
`eval/synthetic/ocr/<sha256>.json` — ideal OCR from the generator (exact text boxes);
`eval/synthetic/ocr_noisy/<sha256>.<variant>.json` — noisy variants of it (₹ read as R or Z, merged ETA tokens, …);
`eval/recorded/ocr/<sha256>.json` — real ML Kit output recorded on the owner's phone. Same shape:
```json
{"image_sha256": "…", "width": 1080, "height": 2400, "engine": "synthetic|mlkit-text-v2", "recorded_at": "…",
 "tokens": [{"text": "₹110", "box": [l, t, r, b], "line": 12, "block": 3}]}
```
Boxes are pixels in the image, `line` groups tokens ML Kit put on one line.
`eval/recorded/channel_a/<sha256>.json` — real Muse Spark Open output recorded through the metered gateway:
```json
{"image_sha256": "…", "model": "meta-models/Muse-Spark-Open-816B-A42B", "recorded_at": "…",
 "read": <ChannelARead>, "usage": {"input_tokens": 1500, "cached_input_tokens": 0, "output_tokens": 300}, "cost_usd": 0.003}
```
`eval/synthetic/channel_a/<sha256>.json` — same shape, produced from labels (a "perfect reader") plus adversarial
variants under `eval/synthetic/channel_a_adversarial/<sha256>.<variant>.json`.
