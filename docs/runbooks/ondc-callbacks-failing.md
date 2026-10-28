# Runbook: ONDC callbacks are failing

On ONDC, RideMate's server acts as a buyer app: it sends signed `search/select/init/confirm/status/cancel` calls and
the seller answers asynchronously with signed `on_*` callbacks, which the server stores in Firestore **before**
acknowledging. The phone listens to `ondc_txn/{txnId}` for quotes and status.

**During the hackathon the seller is the in-process "ONDC demo seller"** (`/mock-bpp/*`, mounted only when
`ONDC_MODE=mock`). No real ONDC ride is booked. At public launch `ONDC_MODE=off` until production onboarding.

Setup for the commands: [README.md](README.md#setup-used-by-the-commands).

## Symptoms

- In the app, ONDC shows "No ONDC results" or stays on "Searching the network…".
- `ondc_txn` documents stuck in `SEARCHING_NET` or `INIT`; `ondc_event` documents with `parse_error`.
- 5xx or 401 on `/ondc/on_*` in the logs; "signature verification failed".
- `503 ondc_unavailable` from `/v1/ondc/*`.

## Checks

1. **Is ONDC meant to be on?** Remote Config `ondc_enabled` = `true`? Server `ONDC_MODE` (in `ridemate-core`) =
   `mock` for the hackathon build? If `off`, the mock routes are not mounted, by design.
2. **Firestore quota.** On the free plan Firestore allows 20,000 writes a day; if exhausted, ONDC is shown as
   unavailable until the quota resets (about 13:30 IST). Firebase console → Firestore → **Usage**.
3. **Logs:**

   ```bash
   modal app logs ridemate-api --env main | grep -i -E 'ondc|mock-bpp|signature'
   ```

4. **Recent events:** Firebase console → Firestore → `ondc_event`, newest first: is `on_search` arriving? Any
   `parse_error`?
5. **Is the web app healthy?** `curl -fsS "$RIDEMATE_API/readyz"` (Firestore must be reachable).
6. **Clock skew / keys:** signature failures usually mean the signing key in `ridemate-core` doesn't match what the
   seller expects (for the mock, both sides read the same key, so a mismatch means a bad secret update), or a request
   timestamp outside the allowed window.

## Fix

1. **Hide ONDC while you fix it:** `ondc_enabled` = `false` ([how](README.md#flipping-a-kill-switch-remote-config)).
   Nothing else in RideMate depends on ONDC.
2. **Firestore quota exhausted:** wait for the reset (~13:30 IST). Do not upgrade to Blaze. Low-value writes are
   already shed near 80% of the quota.
3. **Bad deploy or secret:** check `ONDC_MODE` and the ONDC keys in `ridemate-core`; fix and redeploy
   (`./backend/deploy.sh`), or roll back to the last good tag ([rollback.md](rollback.md)).
4. **Stuck transactions:** they don't affect other users. Once callbacks work again, the user can start a new ONDC
   search. Leave old `ondc_txn` / `ondc_event` documents; the daily cleanup removes them after 180 days.
5. **Later, with real ONDC (Pre-Prod/Prod, after organisation registration):** also check the registry subscription
   (`on_subscribe`), that the seller's endpoint is up, that the callback URL is reachable, and the seller's
   ONDC network status. Contact the seller's support with the `transaction_id` and `message_id`.

## Rollback

Turn ONDC back on (`ondc_enabled` = `true`) only after a full demo run: search → quotes appear → book → status updates
→ soft cancel shows the fee → confirm cancel → `CANCEL_CONFIRMED`.

## Who to tell

- **Users / judges:** ONDC is a demo during the hackathon; a short README status line is enough if it's off for long.
- **ONDC seller / network support:** only once on real ONDC.
