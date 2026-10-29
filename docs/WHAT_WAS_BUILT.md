# What was built — RideMate build log

A plain-English record of everything done to build RideMate from `goal.md` + `architecture.md`, written so the
owner (Baharul) can look back later and see what exists, where it lives, how it was checked, and what is still his
to do. Build window: **28 Oct 2026 20:25 IST → 29 Oct 2026 ~09:00 IST**, by Claude Code (Opus 5.5) orchestrating
many parallel agents. Every commit is in this repo's history (`git log`).

> Final numbers (test totals, release checksum, spend) are in **§9 Final status** at the end.

---

## 1. Setup that was done for you
| Item | Result |
|---|---|
| Tools installed | Modal CLI, Firebase CLI, gcloud, JDK 17 (Homebrew), k6. Android SDK + gh were already present |
| Sign-ins | `modal setup` (workspace ibaharul567), `firebase login`, `gcloud auth login`, GitHub (already signed in as Baharul0111) |
| Release keystore | `~/.ridemate/release.jks` (alias `ridemate`, password from `.env`). **Back it up** to your password manager + an encrypted offline copy. Certificate SHA-256: `94:F2:DD:5F:DB:02:AD:ED:46:31:CB:0A:09:4A:0B:EE:81:05:32:C1:C8:AE:22:23:C1:7D:1C:7A:A4:22:7A:A0` |
| Firebase | Android app `in.ridemate.app` registered in `ridemate-ab211` with debug + release SHA-1/SHA-256; `google-services.json` committed. Both projects verified **billingEnabled = false** (Spark, no card) |
| Firestore | `(default)` database in **asia-south1**, free tier; deny-by-default rules + indexes deployed |
| Remote Config | Template published to `ridemate-dev-9a10d` then `ridemate-ab211` (api_base_url → the Modal web app) |
| Google Cloud | Only free APIs enabled (Firestore, IAM, IAM Credentials, Remote Config, FCM). Two least-privilege service accounts (`ridemate-web`, `ridemate-ops`); their keys live only in Modal Secrets (local copies shredded) |
| Modal | Environments `main` + `dev`; Dict `ridemate-hot`; Volume `ridemate-golden`; Secrets `ridemate-model`, `ridemate-core`, `ridemate-web-sa`, `ridemate-ops-sa`, `ridemate-alerts`; app **`ridemate-api`** deployed (web function + one dispatcher cron every 15 min + a test-alert function) |
| GitHub | Public `Baharul0111/ridemate` (MIT) and private `Baharul0111/ridemate-golden` (165-screen synthetic golden set). Dependabot labels created |

## 2. How the work was organised
* **Contracts first** (`docs/contracts.md`): exact API, Remote Config JSON shapes, module map and fixture formats,
  so parallel builders could work independently.
* **Phase 1 (parallel):** backend platform (auth, quotas, spend meter), backend ops (Firestore/RC clients, alerts,
  cron, Modal app, deploy script, Firestore rules), backend AI + ONDC, Kotlin core fare-reading (formats, masking,
  parser, verification gate), Kotlin core ride guardian (RideWatch pipeline, statechart, guardian, invariants,
  replay tests), config + demo screens + eval fixtures, docs, CI/release tooling, Template Doctor + load test.
* **Phase 2 (parallel):** Android platform layer, then five Android feature areas (trip/launch, capture/compare,
  guardian, demo/ONDC, UI shell), integrated into one app.
* **Every builder was followed by an independent reviewer** that re-checked the requirements and fixed real bugs.
* **Goal verification sweep:** 8 independent verifiers checked all 29 goal items + a security review → 85 findings
  (3 blockers, 30 major, 52 minor) → fixed by area in parallel, with a second round for the ones the first fixer
  could not see.
* **On-phone end-to-end tests** on your moto g57 power (Android 17).
* **UI redesign** (your request): research → `docs/design/ui-redesign.md` → design system → screens.
* **Map pin-point** (your request): MapLibre + OpenFreeMap (free, no key, no card).

## 3. What exists, by part

### Android app (`android/`, Kotlin + Jetpack Compose, minSdk 29, targetSdk 35)
* **`core/` (pure Kotlin, JVM-tested):** Indian money/number formats (₹1,234 · Rs 1234 · ₹110–130 · 1,23,456 ·
  Devanagari digits · OCR confusions), IST formatting; PII masking (phones, OTP/PIN, plates incl. OCR look-alikes,
  names, status bar); OCR token model; FareParser (Channel B); **VerificationGate** (§4.3 rules a–f, re-read,
  freshness, cheapest-only-verified); Remote Config models with schema + last-good fallback; **RideWatch pipeline**
  (allowlist-before-read, normalisation, structural fingerprints, RE2/J templates, classifier with deadline,
  UNKNOWN_UPDATE fallback); **statechart** (every cell of the §4.4 table, generations, idempotency, ONDC chain);
  **guardian** (first-driver sheet, two-drivers pick, cancel chain, evidence ladder, reminders 30/60/120 s then
  every 2 min, +60 s check, same driver, better-ride rule, kept-ride-lost alerts, pay handoff, snapshot/restore);
  invariant checkers I1–I10; accuracy test on recorded fixtures.
* **`app/`:** Room DB v1→v2 with a hand-written migration + tests; OkHttp API client (deadlines, retry rule, error
  envelope); Google sign-in (Credential Manager) + anonymous; Remote Config repository with kill switches;
  outbox for reminders that survives process death; Erase + My data; Crashlytics without message text; update
  checker; onboarding (18+, DPDP notice, Google sign-in skippable, each permission with a reason, restricted
  settings + OEM battery guidance, self-test); trip input (location, Geocoder search, voice, place-pick, confirm,
  geo: map, **map pin-point**); app launcher (Uber universal link without client_id; Ola/Rapido launch + paste drop;
  Android 15 background-start rule); overlay bubble + pointer arrow; MediaProjection capture (2 frames, consent
  per comparison, FLAG_SECURE fallback to share-a-screenshot); ML Kit OCR + masking; Read fares (two-reader gate,
  re-read, manual entry); comparison list; RideWatch notification listener; foreground session service; guardian
  UI + notifications (English + Hindi); **Try the demo** with three fictional providers; ONDC client (demo seller);
  owner FCM alerts; settings; hidden developer mode with Recorder, capture test and golden labelling.
* **Never acts inside Uber/Ola/Rapido:** no AccessibilityService, no input injection, no shell, no hidden displays
  (manifest audit test enforces it).

### Backend (`backend/`, Python 3.12 FastAPI on Modal)
Firebase token verification without admin keys; HMAC(Google sub) quotas (6 reads + 10 parses/day; young 3; judges
20) idempotent on request id; per-IP limiter (right-most forwarded hop, IPv6 /64); **all-spend meter** (AI $80 ·
compute $12 · margin $8, sub-budgets, daily pacing cap, tiers, judging profile, drift factor, fails closed,
compute heartbeat); Muse Spark Open gateway (json_schema structured output, low reasoning, usage → cost) and
router (deadlines, retry rule, circuit breaker, pinned model id); prompts + strict validation (I5); demo cache
(allowlisted demo images, live → cache → recorded); ONDC buyer with Ed25519 signing + in-process demo seller;
REST Firestore client with quota shedding; server-side Remote Config; audit trail; JSON logs with a scrubber;
metrics; alerts (healthchecks.io + FCM); one dispatcher cron (heartbeat, alerts, snapshots, golden probe, cleanup,
billing reconciliation, weekly Template Doctor drift run); `deploy.sh` (idempotent, never prints secrets).

### Config, eval, tools, docs
* `config/`: every Remote Config key with JSON schema, RE2 validation and a template builder.
* `eval/`: 16 fictional demo screens + labels, synthetic and adversarial fixtures, **real ML Kit OCR recorded on
  your phone**, **real Muse Spark Open outputs recorded through the meter (16/16 matched labels)**, golden
  generator, harness (record, summarise without fare values, sync golden set to Modal).
* `tools/`: Template Doctor (Spark Open agent proposing template/parser fixes, $0.50/run cap, PR only with your
  approval), k6 load test (100 sessions: 0 failures, read p95 2.7 s against a stub), day-1 spike script, release
  script, dependency-verification script, licence report, secrets scan.
* `.github/`: CI (Android unit/replay/migration/accuracy/lint, backend pytest/ruff/bandit, config/eval, Firestore
  rules emulator tests, CodeQL, licence report, dependency review, repo hygiene, monthly base-image check), all
  actions pinned by commit SHA; Dependabot.
* `docs/`: README, privacy notice (English + Hindi), 14 runbooks, incident plan, LAUNCH checklist, secrets
  inventory, SLO/alerts, hackathon notes, 3-minute demo video script, spikes, test evidence, design spec.

## 4. Decisions and deviations (all recorded in `architecture.md` changelog / `docs/spikes.md`)
* **Day-1 spike:** Muse Spark Open cannot switch reasoning off (`low` is the minimum) and reasoning tokens count
  against `max_tokens`. Token ceilings were raised (read 2000, reread 1600, point 700, parse 700, place 600) and
  deadlines re-pinned (point 12 s, parse/place 10 s; phone = server + 5 s). Classifier fallback still fires at the
  deadline, so "never silent" holds (3 s + 10 s bound).
* **"Show me" grounding** works (3/3 demo cancel screens) → `point_supported = true`.
* **Remote Config role** is `roles/cloudconfig.viewer` (the name in the first deploy script was wrong; fixed).
* **Judge/owner allowlists** moved out of the public Remote Config template into Firestore `server_config`
  (security review finding: the client template is readable by anyone).
* **Map pin-point** (owner request) uses MapLibre + OpenFreeMap: free, no key, no card; OSM's own tile servers and
  Google Maps remain unused. Privacy notice updated (tile server sees IP + map area).
* **Kestrel demo screen:** the model's row boxes sit ~7 % below the real rows on that one screen, so the gate shows
  "Couldn't read · check in app" for Kestrel while Pepper and Tamarind verify. This is the safety rule working
  (goal item 5: never guess); the check was deliberately not loosened.

## 5. Money
Only Modal credit was spent, all through the all-spend meter (spikes, recordings, probes, live demo reads, device
tests). Firebase stayed on Spark; no card was added anywhere; no paid Google API was enabled; the paid Meta API was
never used. See §9 for the total.

## 6. Things only you can do (gated by goal.md / setup.md)
1. **Record the 3-minute demo video** — script: `docs/demo-video-script.md`. Real Uber/Rapido parts use your own
   accounts (the build never automates those apps). Ola is not installed on your phone.
2. **Publish the GitHub Release** when you say "go ahead": `RIDEMATE_KEYSTORE_PASSWORD=… tools/release.sh --publish`
   (the signed APK + `SHA256SUMS.txt` are already built in `dist/`).
3. **Public launch** settings (`docs/runbooks/public-launch.md`) and sharing the app publicly wait for your
   go-ahead **and** Modal's written billing confirmation.
4. **Real-app templates:** record real Uber/Ola/Rapido notifications and screens with the in-app Recorder
   (Developer mode, owner sign-in) and add them to the private golden repo; the shipped real-app templates are
   marked "unverified until recorded".
5. **Owner alerts on your phone:** sign in with Google in RideMate and enable Developer mode (7 taps on the
   version) so your FCM token registers; healthchecks.io email alerts already work.
6. Back up `~/.ridemate/release.jks`.

## 7. How to run things
```bash
# Android tests
cd android && ./gradlew :core:test :app:testDebugUnitTest
# On-phone tests (phone connected)
cd android && ./gradlew :app:connectedDebugAndroidTest
# Backend tests
cd backend && uv run pytest -q
# Deploy backend + Firebase (reads .env, never prints it)
set -a; . ./.env; set +a; ./backend/deploy.sh
# Signed release (owner machine only)
RIDEMATE_KEYSTORE_PASSWORD=… tools/release.sh
```

## 8. Where things are
`android/` app + core · `backend/` server · `config/` Remote Config · `firebase/` rules · `eval/` fixtures + harness ·
`tools/` Template Doctor, load test, spikes, release · `docs/` everything written · `architecture.md` design ·
`goal.md` requirements · `docs/test-evidence.md` on-phone results · `docs/spikes.md` live model measurements.

## 9. Final status
<!-- FINAL_STATUS -->
| Check | Result (29 Oct 2026, ~08:50 IST) |
|---|---|
| Android core tests | **583 passed** |
| Android app tests (Robolectric) | **858 passed** |
| Backend tests | **807 passed** (+ ruff, bandit, secrets scan clean) |
| Config / eval / Template Doctor / CI-tool tests | 313 / 283 / 83 / 63 passed |
| Debug, release (R8) and androidTest builds | all build |
| On-phone instrumented suite (moto g57 power, Android 17) | **6/6 passed** on the final redesigned build (29 Oct 21:37): onboarding, both demo walks incl. real AI reads, cancel proof, reminders, better-ride and pay hand-off, ML Kit OCR, migrations |
| Accuracy test (real ML Kit OCR + real Muse Spark Open, recorded) | zero wrong prices shown; ≥ 75 % VERIFIED floor enforced |
| Live backend | `https://ibaharul567--ridemate-api-web.us-east.modal.run` — `/readyz` all checks OK; demo read through Muse Spark Open OK |
| Signed APK | `dist/RideMate-1.0.0-hackathon.apk` (~55 MB, built from commit 6515190, installed on your phone), SHA-256 `0d7aa0ba611522e1864b021bea4df9ddd304c3e887e9cc2c826172f526876e47`, certificate verified, not debuggable, no AccessibilityService. **Not published** (waits for your go-ahead) |
| GitHub | `Baharul0111/ridemate` main pushed (green commit `a658b4e` and later docs); `Baharul0111/ridemate-golden` private golden set pushed |
| Modal spend | **$0.23 of $100** (AI $0.21, compute $0.01), all through the meter |
| Not done in time | (dependency verification: done 29 Oct 21:59 — 770 components, strict check passes); a few minor redesign items noted by the agents (restricted-settings guide still a card, under-18 screen paragraph, active-ride card timeline) |

## 10. Ready for normal people? (status 29 Oct 2026, evening)
**What already works for any user today** (signed APK in `dist/`, backend live on Modal):
* Install → onboarding (18+, privacy notice, optional Google sign-in, permissions with reasons, self-test).
* Enter a trip by typing, voice, or **pinning pickup/drop on the map**; pick apps and vehicle types.
* RideMate opens Uber with the route filled in and Ola/Rapido with the drop address ready to paste.
* Read fares with the bubble: the two-reader check shows a price only when Muse Spark Open and the on-phone reader
  agree; otherwise "Couldn't read · check in app" and the user can type the price. AI is limited per person (6
  reads + 10 notification checks a day; 3 on day one) and pauses safely before the credit runs out — the guardian
  keeps working without AI.
* After booking, RideWatch watches only Uber/Ola/Rapido notifications; when a driver is found it asks "Cancel the
  others?", guides each cancel, proves it, and keeps reminding (30 s, 1 min, 2 min, then every 2 min).
* If a notification's wording is not recognised, RideMate **asks** ("Rapido has an update. Got a driver?") instead
  of staying silent — so it is safe even before the real-app templates are tuned.
* Erase, My data, English + Hindi, TalkBack labels.

**Evening fix (29 Oct):** a live comparison could replace a running "Try the demo" session, which stopped the demo
before the pay hand-off. A live comparison is now refused while the demo runs (with a clear message), and a demo
session left over after an app restart ends cleanly. Regression tests added; on-phone suite re-run (see
`docs/test-evidence.md`).

**What still needs you before inviting the public** (these are deliberate gates from `goal.md`/`setup.md`):
1. **Modal's written billing confirmation** (reply from billing@modal.com) — no public sharing before it.
2. **Your explicit go-ahead to switch the server to public-launch settings** — one command, documented in
   `docs/runbooks/public-launch.md`: ONDC off in production (the demo seller is for the hackathon only), caps on,
   judging profile off, tag `v1.1.0`.
3. **Your go-ahead to publish the GitHub Release** (`tools/release.sh --skip-build --publish`), which is how normal
   people download the APK with its checksum.
4. **Record real Uber/Ola/Rapido screens and notifications with the in-app Recorder** (Developer mode, your own rides)
   so the real-app templates move from "unverified" to verified; push them through Remote Config — no app update
   needed. Until then real-app reading works but falls back to "Couldn't read · type the price" and "Got a driver?"
   questions more often.
5. Optional: Play Store listing ($25) and Android developer verification (2027) — both your decision.
