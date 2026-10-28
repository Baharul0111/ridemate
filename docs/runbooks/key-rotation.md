# Runbook: routine key rotation (every 90 days)

Every server key is rotated **every 90 days** and immediately on any suspicion of a leak. The dispatcher cron raises
a **key-age alert at 80 days**, so you have 10 days to do it calmly. For keys created on 2026-10-28, the alert is due
around **2027-01-16** and rotation by **2027-01-26**. Where each secret lives: [../secrets-inventory.md](../secrets-inventory.md).

The exact commands for each key are in [leaked-key-or-keystore.md](leaked-key-or-keystore.md); this runbook is the
calm, planned version.

Setup for the commands: [README.md](README.md#setup-used-by-the-commands).

## Symptoms

- Alert **"service-account key age > 80 days"** (healthchecks.io fail email + FCM push).
- Your calendar reminder for the 90-day rotation.
- GitHub emails that the Template Doctor's fine-grained token is about to expire.

## Checks

```bash
P="$FIREBASE_PROJECT_ID"
for SA in "ridemate-web@$P.iam.gserviceaccount.com" "ridemate-ops@$P.iam.gserviceaccount.com"; do
  gcloud iam service-accounts keys list --iam-account "$SA" --managed-by=user --project "$P" \
    --format='table(name.basename(), validAfterTime)'
done
```

Also check the creation dates in Modal → Settings → Proxy Auth Tokens, and the expiry of the GitHub fine-grained token.

## Fix: rotation order (about 45 minutes, outside busy hours)

Do one key at a time, check, then move on. Never delete an old key until the new one is confirmed working.

| # | Key | Modal Secret | How | Check |
|---|---|---|---|---|
| 1 | Firestore web service-account key | `ridemate-web-sa` | create new key → update secret → redeploy → delete old key | `/readyz` OK; an AI read writes its audit record |
| 2 | Firestore ops service-account key | `ridemate-ops-sa` | same, for `ridemate-ops@…` (keep `TEMPLATE_DOCTOR_GITHUB_TOKEN` in the same secret) | next dispatcher run (≤ 15 min) pings healthchecks.io and writes a metrics snapshot |
| 3 | Modal production proxy token | `ridemate-model` | new token `ridemate-prod-YYYYMMDD` → update secret → redeploy → delete old token | a **live** fare read on your own phone works (a cached demo screen never reaches the model) |
| 4 | HMAC key (usage counters) | `ridemate-core` | **dual-key**: new key in `RIDEMATE_HMAC_KEY`, old key in `RIDEMATE_HMAC_KEY_PREVIOUS` → redeploy; after 30 days, clear `RIDEMATE_HMAC_KEY_PREVIOUS` and redeploy | your own quota in developer mode is unchanged after the switch |
| 5 | Template Doctor GitHub token | `ridemate-ops-sa` | new fine-grained token (golden/config repo only, contents + pull requests, 90-day expiry) → update secret → redeploy → revoke old | next weekly drift run can open a PR |
| 6 | Laptop proxy token | not in Modal | new token `ridemate-laptop-YYYYMMDD` → password manager → delete old | `GET /v1/models` works |
| 7 | ONDC signing and encryption keys | `ridemate-core` | new Ed25519/X25519 pair → update secret (with all other `ridemate-core` values) → redeploy (on real ONDC, also update the registry) | an ONDC demo search returns quotes |
| 8 | healthchecks.io ping URLs | `ridemate-alerts` | new checks with the same settings → update `.env` and secret → redeploy → delete old checks | the new `ridemate-heartbeat` check turns green within 15 min; the new `ridemate-alerts` check after its first daily ping |
| 9 | Owner FCM device token | `ridemate-alerts` | clear RideMate's data on your phone (or reinstall) so FCM issues a new token; the app re-registers it with `/v1/owner/fcm-token` | a test push arrives |

All server secrets follow the same 90-day cadence (architecture.md §4.11); the steps for 7–9 are in
[leaked-key-or-keystore.md](leaked-key-or-keystore.md). The one thing that is **never rotated on a schedule** is the
**release keystore**: it must stay the same for updates to install (see the keystore section of
[leaked-key-or-keystore.md](leaked-key-or-keystore.md) if it is ever compromised).

After each step, update the dates in your password manager entries and set the next reminder for 90 days later.

## Rollback

If a new key fails, put the previous value back in the Modal Secret (it's still valid because you haven't deleted it)
and redeploy. Then retry.

## Who to tell

Nobody, unless something broke for users. Record the rotation date in your private notes.
