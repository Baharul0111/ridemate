# RideMate runbooks

Step-by-step fixes for the things most likely to go wrong. Written for Baharul (the owner and only operator) so they
can be followed calmly, from a laptop, without remembering the design. Each runbook has the same parts:
**Symptoms → Checks → Fix → Rollback → Who to tell.**

| Runbook | Use it when |
|---|---|
| [ride-app-update-breaks-reading.md](ride-app-update-breaks-reading.md) | Uber, Ola or Rapido changed their screens or notifications and reading or watching got worse |
| [modal-down-or-credit-used-up.md](modal-down-or-credit-used-up.md) | Dead-man email, everyone in manual mode, Modal outage, or the credit/spend limit reached |
| [cap-tripped-or-abuse.md](cap-tripped-or-abuse.md) | Spend alerts, AI paused early, one account or network using too much |
| [platform-objection.md](platform-objection.md) | A ride company, Google, Meta or the organisers object to RideMate |
| [leaked-key-or-keystore.md](leaked-key-or-keystore.md) | A token, key, password or the release keystore may be exposed |
| [ondc-callbacks-failing.md](ondc-callbacks-failing.md) | ONDC quotes or status updates stop arriving |
| [model-removed.md](model-removed.md) | Muse Spark Open's model id disappeared or changed on Modal |
| [manual-erasure-request.md](manual-erasure-request.md) | Someone emails asking to delete their data |
| [rollback.md](rollback.md) | A deploy or app release made things worse |
| [key-rotation.md](key-rotation.md) | The 90-day rotation is due, or the key-age alert fired (80 days) |
| [judging-week.md](judging-week.md) | From submission (5 Nov 2026, 07:30 IST) until judging ends |
| [public-launch.md](public-launch.md) | Before sharing RideMate publicly (**gated**) |
| [restore-golden-set.md](restore-golden-set.md) | The private golden set is lost or damaged, or it's time for the restore test (required once) |

Bigger events (data breach, security incident) follow the [incident plan](../incident-plan.md).

## Ground rules for every runbook

- **Never spend your own money.** Never raise Modal's spend limit, never add a card to Google or Firebase, never
  upgrade Firebase to Blaze. If a fix needs money, stop: that is a decision for later, not an incident step.
- **Never automate Uber, Ola or Rapido**, not even to test a fix. Use your own phone, your own accounts and the
  in-app Recorder.
- **Never paste secret values** into chats, issues, commits or logs. Load them into your terminal and use them by name.
- **Kill switches first, code second.** A Remote Config switch reaches phones within about a minute and is fully
  reversible. Code changes come after the bleeding stops.
- Write a short note of what you did and when (a private note is fine). It makes the follow-up and any report easier.

## Setup used by the commands

Run these once in a terminal before using any runbook:

```bash
cd ~/Desktop/ridemate
set -a; . ./.env; set +a      # loads your values into this terminal only; never echo or print them
export MODAL_ENV=main         # production; use "dev" when testing
export RIDEMATE_API="https://<the web URL printed by 'modal deploy'>"   # also in Remote Config key api_base_url
# Only when a runbook asks for it: your LAPTOP proxy token (never the production one), from your password manager,
# written as wk-<id>.ws-<secret>. Typing it with a leading space keeps it out of shell history in most shells.
#  export MODAL_LAPTOP_TOKEN='wk-….ws-…'
```

Quick health check:

```bash
modal app list --env "$MODAL_ENV"                       # ridemate-api should be "deployed"
modal app logs ridemate-api --env "$MODAL_ENV"          # live logs (Modal keeps only 1 day); Ctrl-C to stop
curl -fsS "$RIDEMATE_API/healthz"; echo
curl -fsS "$RIDEMATE_API/readyz"; echo                  # 503 means a secret, the Dict or Firestore is not reachable
```

## Flipping a kill switch (Remote Config)

Kill switches and other live settings are Remote Config parameters in the main Firebase project
(`$FIREBASE_PROJECT_ID`):

| Parameter | What `false` / the change does |
|---|---|
| `ai_reader_enabled` | All AI reading off: everyone in manual mode (type prices). Guardian and reminders keep working |
| `reader_enabled_uber`, `reader_enabled_ola`, `reader_enabled_rapido` | Fare reading off for one app only. Opening that app and watching its notifications stay on |
| `ondc_enabled` | ONDC hidden in the app |
| `demo_enabled` | Try the demo hidden |
| `cap_profile` | `normal` or `judging` (judging: $3/day cap, young-account tier on, judge allowlist on) |
| `judge_allowlist` | SHA-256 of lower-cased judge emails who get 20 reads/day |
| `model_route` | Which provider the server uses for reading and parsing |
| `latest_version`, `min_supported_version` | Update prompts |

**Fastest way (works from a phone browser):** Firebase console → project `ridemate` → **Remote Config** → click
the parameter → change the value → **Save** → **Publish changes**. Phones listen for kill switches in real time and pick
the change up within about a minute.

**From the terminal:**

```bash
# 1. Save the live template first (so you can compare and roll back)
firebase remoteconfig:get --project "$FIREBASE_PROJECT_ID" -o /tmp/rc-live.json
# 2. Make the change in config/ (config/remote_config/*.json, then rebuild the template)
python3 config/build_template.py
# 3. Make sure console-only edits (for example a switch you flipped in the console) are also in the file,
#    or this deploy will undo them. Compare:
diff <(python3 -m json.tool /tmp/rc-live.json) <(python3 -m json.tool config/remoteconfig.template.json)
# 4. Publish (firebase.json lives in firebase/, so point the CLI at it)
firebase deploy --config firebase/firebase.json --only remoteconfig --project "$FIREBASE_PROJECT_ID"
```

**Undo any Remote Config change:**

```bash
firebase remoteconfig:versions:list --project "$FIREBASE_PROJECT_ID"
firebase remoteconfig:rollback --project "$FIREBASE_PROJECT_ID" --version-number <N>
```

Always try a non-urgent Remote Config change on the `ridemate-dev` project first
(`--project "$FIREBASE_DEV_PROJECT_ID"`), and roll out risky ones to 10% before 100%
(see [ride-app-update-breaks-reading.md](ride-app-update-breaks-reading.md)).

## The big red button

If something is badly wrong on the server and kill switches are not enough:

```bash
modal app stop ridemate-api --env main
```

This stops the web app **and** its cron. Phones fall back to manual mode and the guardian keeps working on the phone.
The healthchecks.io dead-man email will fire within about 30 minutes, which is expected: pause the
`ridemate-heartbeat` check on healthchecks.io if you stopped the app on purpose. To start again, redeploy
(`./backend/deploy.sh`, or see [rollback.md](rollback.md)).

## Who to tell (contacts used across runbooks)

| Who | How |
|---|---|
| Users | In the app (AI-paused messages are automatic), the GitHub release notes, and the status note at the top of the README |
| Hackathon organisers (only during judging) | The event's official channel listed in the participant guide |
| Modal support / billing | support@modal.com · billing@modal.com |
| Firebase / Google | Firebase support form in the console |
| CERT-In / Data Protection Board | See the [incident plan](../incident-plan.md) |
