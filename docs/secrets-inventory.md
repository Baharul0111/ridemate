# Secrets inventory

Every secret RideMate uses, where it lives, who can read it, and how often it is rotated. **Values are never written
here or anywhere in the repository.** How to rotate: [runbooks/key-rotation.md](runbooks/key-rotation.md); if one may
have leaked: [runbooks/leaked-key-or-keystore.md](runbooks/leaked-key-or-keystore.md).

`backend/modal_app.py` is the authoritative list of which variables each Modal Secret holds and which function each
secret is attached to; update this file whenever that changes.

## Ground rules

- **`.env` is local only.** It lives in the project folder on Baharul's laptop, is listed in `.gitignore` (with
  `.env.*`, `*.jks`, `*.keystore`, `service-account*.json`, `*-sa-key.json`, `keys/`), and is **never committed, shared
  or pasted** into any chat. Build tools read it from the environment; they never print it.
- **Server secrets live only in Modal Secrets** in workspace `ibaharul567`. Modal doesn't show secret values back after
  creation, so treat them as write-only and keep the source of each value in `.env` or the password manager.
- **The app (APK) contains no secrets.** The Firebase config in `google-services.json` and the API base URL are not
  secrets; the server is protected by sign-in checks, limits and deny-by-default Firestore rules.
- Each secret is attached **only to the Modal functions that need it** (least privilege).
- Key files (service-account JSON) are deleted from the laptop right after upload.
- Owner accounts (Google, GitHub, Modal) use a passkey or 2-step verification. A sealed break-glass note in the
  password manager holds recovery codes, so the project can be recovered if Baharul's phone is lost.
- GitHub secret scanning and push protection are on for the public repo (check once in the repo's Settings → Code
  security).

## Modal Secrets (workspace `ibaharul567`)

| Modal Secret | Holds (variable names) | Attached to | Source of the value / backup | Rotation |
|---|---|---|---|---|
| `ridemate-model` | `MODAL_SPARK_OPEN_URL` (endpoint URL, not secret), `MODAL_PROXY_TOKEN_ID` (`wk-…`), `MODAL_PROXY_TOKEN_SECRET` (`ws-…`): the dedicated **production proxy token** `ridemate-prod` for the Muse Spark Open shared endpoint | web app, dispatcher cron | `.env`; password manager | 90 days; on suspicion |
| `ridemate-core` | `RIDEMATE_HMAC_KEY`, `RIDEMATE_HMAC_KEY_PREVIOUS` (versioned HMAC for usage counters; previous kept 30 days after rotation); ONDC Ed25519 signing and X25519 encryption private keys; non-secret settings kept together: `FIREBASE_PROJECT_ID`, `FIREBASE_WEB_API_KEY`, `OWNER_EMAILS`, `ONDC_MODE`, `CREDIT_EXPIRY_DATE`, `BUDGET_HORIZON_START`, `BUDGET_HORIZON_DAYS`, `RIDEMATE_PUBLIC_BASE_URL` | web app, dispatcher cron | password manager (HMAC and ONDC keys); `.env` / build prompt (settings) | 90 days and on suspicion (HMAC with the dual-key method; ONDC keys also renewed when moving to Pre-Prod) |
| `ridemate-web-sa` | `FIRESTORE_WEB_SA_JSON`: key of the **web** service account (role: Datastore user only) | web app only | created with `gcloud`, uploaded, then deleted locally (no copy kept) | 90 days; key-age alert at 80 days |
| `ridemate-ops-sa` | `FIRESTORE_OPS_SA_JSON`: key of the **ops** service account (roles: FCM sender + Datastore user); `TEMPLATE_DOCTOR_GITHUB_TOKEN`: fine-grained GitHub token limited to pull requests on the private golden/config repo | dispatcher cron only | SA key: created with `gcloud`, uploaded, deleted locally. GitHub token: created on GitHub, password manager | 90 days (SA key-age alert at 80 days; GitHub token expiry 90 days) |
| `ridemate-alerts` | `HEALTHCHECKS_HEARTBEAT_URL`, `HEALTHCHECKS_ALERT_URL` (healthchecks.io ping URLs), `OWNER_FCM_TOKEN` (the owner's phone, for alert pushes) | dispatcher cron | `.env`; the phone registers its FCM token | 90 days and on suspicion (anyone with a ping URL can send fake signals). FCM token: renewed by clearing RideMate's data on the owner's phone (or reinstalling), which re-registers it |

No Firebase Auth-admin role exists anywhere. Firebase ID tokens are verified with Google's public certificates, so the
server needs no admin key for sign-in.

## Local secrets (Baharul's laptop)

| Secret | Where | Who can read it | Rotation / backup |
|---|---|---|---|
| `.env` (Modal endpoint URL + production proxy token, Firebase project ids, healthchecks.io URLs, keystore password) | `~/Desktop/ridemate/.env` | Baharul's user account only | Values rotate with the secrets above; the file itself is never backed up to the cloud unencrypted |
| **Release keystore** | `~/.ridemate/release.jks`, alias `ridemate` | Baharul only; never in CI, never in Modal, never in the repo | **Not rotated** (updates must be signed by the same key). Backed up in the **password manager** and an **encrypted offline copy**. Certificate SHA-256: `94:F2:DD:5F:DB:02:AD:ED:46:31:CB:0A:09:4A:0B:EE:81:05:32:C1:C8:AE:22:23:C1:7D:1C:7A:A4:22:7A:A0` |
| Keystore password `RIDEMATE_KEYSTORE_PASSWORD` | `.env` and the password manager (saved there **first**) | Baharul only | With the keystore |
| Laptop proxy token `ridemate-laptop` (for local checks and evals; separate from production) | Password manager; exported into a terminal only when needed | Baharul only | 90 days |
| Modal CLI login | `~/.modal.toml` (from `modal setup`) | Baharul's user account | Revoke in Modal → Settings → Tokens if the laptop is lost |
| Firebase CLI login | Firebase CLI config in `~/.config/` (from `firebase login`) | Baharul's user account | Revoke at myaccount.google.com/permissions if the laptop is lost |
| gcloud login | `~/.config/gcloud/` (from `gcloud auth login`) | Baharul's user account | `gcloud auth revoke`; revoke at myaccount.google.com/permissions |
| GitHub CLI login | system keychain (from `gh auth login`) | Baharul's user account | Revoke in GitHub → Settings → Applications |
| HMAC and ONDC keys (source copies) | Password manager | Baharul only | With the Modal Secret |

## Not secrets (listed so nobody worries about them)

- `android/app/google-services.json` (Firebase project identifiers; safe in a public repo).
- The `ridemate-api` base URL (delivered by Remote Config `api_base_url`).
- The release certificate **fingerprint** (published on purpose).
- Firebase ID tokens on users' phones (short-lived, about 1 hour, per user).

## Rotation calendar

| Due | What |
|---|---|
| 80 days after each key's creation (first: about 2027-01-16) | Key-age alert fires |
| 90 days after creation (first: about 2027-01-26) | Rotate every server secret (architecture.md §4.11): proxy token, HMAC key, both service-account keys, GitHub token, ONDC keys, healthchecks.io ping URLs, owner FCM token; plus the laptop token |
| 30 days after each HMAC rotation | Remove `RIDEMATE_HMAC_KEY_PREVIOUS` |
| Any suspicion | Rotate immediately ([runbook](runbooks/leaked-key-or-keystore.md)) |
