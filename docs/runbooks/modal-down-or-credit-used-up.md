# Runbook: Modal is down, or the credit is used up

RideMate is built so that losing Modal never breaks a ride: the phone falls back to **manual mode** (users type the
price) and the **guardian and reminders keep working on the phone**. Your job is to find out which case you're in,
make sure no money is spent beyond the credit, and tell people.

Setup for the commands: [README.md](README.md#setup-used-by-the-commands).

## Symptoms

- **healthchecks.io "ridemate-heartbeat is DOWN" email** (the dead-man switch: no ping for 15 minutes plus 15 minutes
  of grace). This is often the *only* alert you get, because RideMate's own alerts run on Modal too.
- Every user is in manual mode; the app says *"AI reading resumes at hh:mm (IST)"*.
- `/v1/config-check` reason is `breaker`, `budget`, `day_cap`, `meter` or `model_missing`.
- An email from Modal about the workspace reaching its spend or usage limit.
- 5xx alert (> 5% of `/v1/*` over 10 minutes) or read p95 > 15 s.

## Checks (5 minutes)

1. **Is Modal itself having an outage?** Open <https://status.modal.com>.
2. **Is our app deployed and running?**

   ```bash
   modal app list --env main                 # ridemate-api: deployed? stopped?
   curl -fsS -m 10 "$RIDEMATE_API/healthz"; echo
   curl -fsS -m 10 "$RIDEMATE_API/readyz"; echo
   modal app logs ridemate-api --env main    # look for errors, 429s from the endpoint, timeouts
   ```

3. **How much credit is left?** Modal dashboard → workspace `ibaharul567` → **Settings → Usage and billing**: credit
   balance, spend limit, usage limit. In RideMate's developer mode, the budget panel shows the meter (AI spent of $80,
   compute spent of $12, today's cap and spend).
4. **Is just the model endpoint failing?** Logs show `model_timeout`, 429 or 5xx from the endpoint while `/healthz` is
   fine → the circuit breaker opens (5 failures in 60 s → open 60 s → one test call) and users are in manual mode
   until it recovers. Check the endpoint with **your laptop proxy token** (never the production one); listing models
   costs nothing:

   ```bash
   curl -fsS -m 10 -H "Authorization: Bearer $MODAL_LAPTOP_TOKEN" "$MODAL_SPARK_OPEN_URL/v1/models"
   ```

   If `meta-models/Muse-Spark-Open-816B-A42B` is missing, go to [model-removed.md](model-removed.md).

## Fix, by case

### A. Modal-wide outage
Nothing to fix on our side. Phones are already in manual mode. Watch status.modal.com. When Modal recovers, the web app
and the dispatcher cron resume by themselves; the heartbeat goes green. Check `/readyz` and one demo read afterwards.

### B. Our deploy is broken (Modal is fine, our app isn't)
Roll back to the last good tag: [rollback.md](rollback.md).

### C. The credit or a RideMate budget is used up
- **Do not raise Modal's spend limit. Do not add money. Do not top up.** The zero-out-of-pocket rule is absolute.
  At the spend limit Modal stops workloads instead of charging the card; that is the system working as designed.
- **`credit_nearly_exhausted` alert** (the meter's AI + compute reached $92, the credit minus the $8 margin): AI stops
  itself at its $80, but **compute is only metered, never stopped by RideMate**, so a container kept warm by traffic
  (health checks included, though they now share the per-network limit) keeps costing money. Stop the app before
  Modal's spend limit has to: `modal app stop ridemate-api --env main` (and `--env dev` if it is deployed), then
  follow the rest of this section. Modal's workspace spend limit stays the last backstop.
- If RideMate's own meter paused AI (AI budget or daily cap reached) but Modal still has credit, that is also by
  design: AI resumes at the time shown in the app (next day's pacing), and the guardian keeps running. Do not reset the
  meter in the Modal Dict to "get more".
- Set `ai_reader_enabled` = `false` in Remote Config so phones stop trying and show a clean manual-mode message
  ([how](README.md#flipping-a-kill-switch-remote-config)). Keep `demo_enabled` = `true`: while `ridemate-api` is still
  running, the demo answers from its cache or from the recorded outputs bundled with the server, marked "recorded".
- If Modal stopped the whole workspace, the web app and crons are down too: ONDC (demo) is unavailable, Try the demo
  cannot read fares either (its cache and recorded outputs are served by `ridemate-api`), and alerts only come through
  the healthchecks.io dead-man email and Crashlytics. The guardian on users' phones keeps working. That is acceptable.
- Next steps are a **decision, not an incident step**: asking Modal or a sponsor for more credit, startup credits, or
  staying in manual mode. Write down the date the credit ran out.

### D. Spend looks wrong (meter vs Modal's bill differs > 10%)
The daily reconciliation already tightened caps automatically. Compare Modal's usage page with the developer-mode
meter. If Modal shows more spend than the meter, keep AI off (`ai_reader_enabled=false`) until you understand why, then
see [cap-tripped-or-abuse.md](cap-tripped-or-abuse.md).

## Rollback

- Turn AI back on when the cause is gone: `ai_reader_enabled` = `true`.
- If you stopped the app on purpose, redeploy (`./backend/deploy.sh` or [rollback.md](rollback.md)) and un-pause
  the `ridemate-heartbeat` check on healthchecks.io.

## Who to tell

- **Users:** the app already says AI is paused and when it resumes. For an outage longer than a few hours or the
  credit running out, add a line to the README status and the latest release notes: "AI reading is paused; RideMate
  still compares when you type prices, and the cancel guardian works as normal."
- **Modal:** support@modal.com for outages that status.modal.com doesn't show; billing@modal.com for anything about the
  credit, charges or limits (keep their written replies).
- **During judging:** tell the organisers if the live AI is down, and point judges to Try the demo (it works with
  recorded outputs as long as `ridemate-api` itself is running; if the whole workspace is stopped, say so plainly
  and point them to the demo video).
