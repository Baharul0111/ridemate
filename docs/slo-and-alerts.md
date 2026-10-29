# Service levels and alerts

What "working well" means for RideMate, how it is measured, and how Baharul hears about problems, even when Modal
itself is down. Source: architecture.md §4.2 (Observability) and §4.12 (Reliability).

## 1. Service level objectives (SLOs)

| SLO | Target | Measured from |
|---|---|---|
| Server availability | `/v1/*` responses that are **not 5xx** ≥ **99.0%** over 30 days | server metrics (Modal Dict → Firestore `metrics` snapshots) |
| Read speed on the server | `/v1/read-screen` server-side **p95 ≤ 8 s** | server latency metrics |

The 1% error budget is about 7 hours of full outage in 30 days. If more than half of it is used in a week, stop
feature deploys and spend the time on reliability until the trend is back under control.

**Product targets** (what users feel; measured from per-session `events` records and tests):

| Target | Value |
|---|---|
| Wrong price shown as verified | ≤ **0.5%** of shown rows (**0** in the demo) |
| Booking marked cancelled without proof ("false cancelled") | **0** |
| Extra bookings confirmed cancelled within 3 minutes of "Yes, cancel the others" | ≥ **98%** |

## 2. Per-operation time budgets

| Operation | Budget |
|---|---|
| Read fares: tap → rows on screen | phone deadline **20 s**; p50 ≤ 6 s, p95 ≤ 10 s, p99 ≤ 20 s |
| Voice → place candidates | p95 ≤ 6 s (on-device speech + one Muse Spark Open call) |
| Ride-app notification → RideMate event or question | ≤ **3 s** (invariant I9); rule misses ≤ 3 s + the 10 s classifier deadline (13 s; day-1 spike, [spikes.md](spikes.md)), with a question to the user as the fallback |
| "Show me" arrow | p95 ≤ 8 s |
| Server cold start | ≤ 4 s |

**Server deadlines** (per call): read-screen 12 s, with one retry only if ≥ 6 s remain in the tap's 20 s budget;
place-pick and notification parse 10 s; point 12 s; ONDC outbound calls 5 s; Firestore calls on the request path 3 s.
**Phone timeouts** = server deadline + 5 s: parse / place-pick 15 s, point 17 s (read-screen and reread share the
tap's 20 s budget). The text-call deadlines were raised by the day-1 spike: reasoning cannot be switched off, so one
classification takes 5–8 s ([spikes.md](spikes.md)). One retry with
1–2 s jitter only if the remaining budget is longer than the server deadline. Circuit breaker: 5 failures in 60 s →
open for 60 s → one test call.

**Recovery:** phone RPO 0, RTO = restart the app. ONDC (when live) RPO 24 h, RTO 1 h. Golden set RPO 1 week.

## 3. How alerts reach Baharul

Alerting must survive a Modal failure, so there are three independent paths:

| Path | What it carries | Works when Modal is down? |
|---|---|---|
| **healthchecks.io dead-man switch** (`ridemate-heartbeat`: period 15 min, grace 15 min) | The dispatcher cron pings it every 15 minutes. If pings stop (Modal down, workspace stopped, credit exhausted, cron broken), healthchecks.io **emails** Baharul | **Yes**, this is its purpose |
| **healthchecks.io "fail" pings** (`ridemate-alerts`: period 1 day, grace 1 hour) | Every threshold alert below is sent as a "fail" ping → email. The cron also sends a daily success ping, so a silent day also emails | No (sent from Modal) |
| **FCM push to the owner's phone** | The same threshold alerts as a push notification | No (sent from Modal) |
| **Crashlytics email alerts** | New fatal issues, regressed issues and crash-velocity spikes in the app | **Yes** (sent by Google) |

All are free with no card.

## 4. Alert thresholds

Evaluated by the dispatcher cron every 15 minutes over the recent window.

| # | Alert | Fires when | First runbook |
|---|---|---|---|
| 1 | Server errors | **5xx > 5%** of `/v1/*` requests over **10 minutes** | [rollback](runbooks/rollback.md), [modal-down](runbooks/modal-down-or-credit-used-up.md) |
| 2 | Slow reads | read-screen **p95 > 15 s** | [modal-down](runbooks/modal-down-or-credit-used-up.md) |
| 3 | Reading accuracy | **VERIFIED < 70%** of rows, with **≥ 20 reads** in the window (per app) | [ride-app-update](runbooks/ride-app-update-breaks-reading.md) |
| 4 | Notification templates | **rule miss > 20%** of posts (per app) | [ride-app-update](runbooks/ride-app-update-breaks-reading.md) |
| 5 | Misses with no classifier | spike in "rule miss, no classifier" (users getting extra questions because AI is unavailable) | [cap-tripped](runbooks/cap-tripped-or-abuse.md), [modal-down](runbooks/modal-down-or-credit-used-up.md) |
| 6 | Spend | spend **> 70% of the day's cap**, or **> 80% of any budget** (AI $80, compute $12, sub-budgets) | [cap-tripped](runbooks/cap-tripped-or-abuse.md) |
| 7 | Billing drift | RideMate's meter vs Modal's billing report differ by **> 10%** (caps tighten automatically) | [cap-tripped](runbooks/cap-tripped-or-abuse.md) |
| 8 | Firestore quota | Firestore writes **> 70%** of the free daily quota (20,000 writes/day); low-value writes are shed at 80% | [ondc-callbacks](runbooks/ondc-callbacks-failing.md) (ONDC is first to be affected) |
| 9 | Probe failure | the daily synthetic golden-image read fails, or the pinned model id is missing | [model-removed](runbooks/model-removed.md), [modal-down](runbooks/modal-down-or-credit-used-up.md) |
| 10 | Key age | a service-account key is **older than 80 days** | [key-rotation](runbooks/key-rotation.md) |
| 11 | Dead-man | no heartbeat for **15 min + 15 min grace** | [modal-down](runbooks/modal-down-or-credit-used-up.md) |
| 12 | App crashes | Crashlytics new fatal / regressed / velocity alert | [rollback](runbooks/rollback.md) |

## 5. Test-fire every alert once

Each alert is test-fired once before submission and again before the public launch. Record the date.

- **healthchecks.io email path:**

  ```bash
  curl -fsS -m 10 "$HEALTHCHECKS_ALERT_URL/fail"   # expect "ridemate-alerts is DOWN" by email
  curl -fsS -m 10 "$HEALTHCHECKS_ALERT_URL"        # back to up
  ```

- **Dead-man email:** healthchecks.io → Integrations → Email → **Test!** (or, in the `dev` environment, stop the dev
  app and wait 30 minutes).
- **FCM push:** Firebase console → Messaging → send a test message to the owner's FCM token; and the dispatcher's own
  test path for threshold alerts, if available in developer mode.
- **Thresholds 1–10:** run the backend's alert-evaluation tests (`cd backend && uv run pytest -k alert`) and, once, a
  real evaluation in the `dev` Modal environment with a lowered threshold.
- **Crashlytics:** a test crash from a debug build (Firebase's "test your Crashlytics implementation" guide); confirm
  the email alert is enabled in the Firebase console's alert settings.

| Alert | Test-fired on (submission) | Test-fired on (public launch) |
|---|---|---|
| healthchecks.io fail email | | |
| Dead-man email | | |
| FCM push | | |
| Thresholds 1–10 (tests) | | |
| Crashlytics email | | |
