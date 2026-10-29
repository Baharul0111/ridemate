# The 20 live reads (metered)

Architecture §4.12 asks for *k6 at 100 concurrent sessions with a stub provider, then 20 live reads*. The stub run
is `run_local.sh` (results in [docs/loadtest-results.md](../../docs/loadtest-results.md)). This page is the second
step: 20 reads through the deployed backend and the real model (Muse Spark Open on Modal). It spends Modal
credit, so the owner or coordinator runs it once, on purpose, after the deploy.

## What it does

`session.js` with `SCENARIO=live_reads` sends 20 `POST /v1/read-screen` requests, two at a time. Each image is one
of the bundled fictional demo fare screens with a unique PNG text chunk added, so its SHA-256 is never on the demo
allowlist and every read goes through the full live path: auth, quota, spend reservation, router, model,
validation. No Uber, Ola or Rapido screen is used. The script refuses to start unless `CONFIRM_SPEND=yes`, the
base URL is `https://` and a token is given.

## Cost and limits

| Item | Value |
|---|---|
| Model calls | 20 (plus at most one retry each, inside the 20 s tap budget) |
| Worst case reserved per read | about $0.0133 (≈ 3,800 input tokens + the 2,000-token read ceiling, reasoning included since the [day-1 spike](../../docs/spikes.md), at $1.25 / $4.25 per 1M) |
| Expected total | about $0.14 (the day-1 spike measured ≈ $0.007 per demo fare-screen read); at most $0.53 even if every read is retried and uses its whole ceiling; drawn from the AI budget like app traffic |
| Fair share | the tester account needs 20 reads that day: put it on the judge allowlist (20 reads/day), or split the run over several Google tester accounts (6 reads/day each, 3 for accounts younger than 24 h) |
| Per-IP limit | 60 requests/min; 20 reads is well under |

If `GET /v1/config-check` says `ai_enabled: false` (budget, day cap, kill switch, breaker), stop: the reads would
all fail with 429/503 and prove nothing.

## Run it

1. Deploy is done and healthy: `curl -fsS https://<ridemate-api URL>/healthz`.
2. Get a Firebase ID token for a **Google-signed-in tester** (for example from a debug build of the app on your
   phone, or the Firebase Auth REST sign-in for a test account). It expires after an hour. Never commit it, paste
   it into chat or put it in a URL.
3. Note the budget before (owner token shows `budget` in config-check):

   ```bash
   curl -fsS -H "Authorization: Bearer $RIDEMATE_ID_TOKEN" -H "X-Request-Id: $(uuidgen)" \
     https://<ridemate-api URL>/v1/config-check | python3 -m json.tool
   ```

4. Run the 20 reads from the repository root:

   ```bash
   k6 run --summary-export tools/loadtest/out/live_reads.json \
     -e SCENARIO=live_reads -e CONFIRM_SPEND=yes \
     -e BASE_URL=https://<ridemate-api URL> -e TOKEN="$RIDEMATE_ID_TOKEN" \
     tools/loadtest/session.js
   ```

5. Read `config-check` again and note the change in `ai_spent_usd` (the metered cost of the run).

## Pass criteria

- 20/20 reads return 200 with `source: "live"` (no 429/503/504).
- Server-reported `latency_ms`: p95 ≤ 8 s (the read-screen SLO, §4.12). The k6 threshold
  `read_server_latency_ms p(95)<=8000` checks it.
- The metered spend for the run matches the expected total above (within the 10% drift alert).

## Record the result

Append to [docs/loadtest-results.md](../../docs/loadtest-results.md) under "Live reads": date, backend URL
environment (`dev` or `main`), the k6 table (`python3 tools/loadtest/summarize.py tools/loadtest/out/live_reads.json
--vus 2 --duration "20 iterations"`), the latency p50/p95/max, the number of retries if any, and the metered cost.
Record **no fare values** (the response bodies carry prices from the demo screens; they are not needed here).
