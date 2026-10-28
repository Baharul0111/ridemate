# Runbook: roll back a bad deploy or a bad app release

Two different things can be rolled back: the **server** (`ridemate-api` on Modal, plus Firebase rules and Remote
Config) and the **Android app**. The server is rolled back by **redeploying the last good git tag**. The app is rolled
back by **republishing the previous code with a higher versionCode**, because Android never installs a lower version
over a higher one.

Setup for the commands: [README.md](README.md#setup-used-by-the-commands).

## Symptoms

- 5xx alert, `/readyz` failing, read p95 > 15 s, or many crashes right after a deploy.
- A new Remote Config publish made reading or watching worse.
- A new app release crashes or misbehaves on phones (Crashlytics velocity alert, user reports).

## Checks

1. What changed last, and when? `git log --oneline -10`, `git tag --sort=-creatordate | head`, the Remote Config
   version list, and the latest GitHub release.
2. Which tag was the last good one? Tags look like `v1.0.0-hackathon`, `v1.0.1`, `v1.1.0`.
3. Is it really the deploy? Check status.modal.com first (see
   [modal-down-or-credit-used-up.md](modal-down-or-credit-used-up.md)).

## Fix

### Remote Config only (fastest, try first if the last change was config)

```bash
firebase remoteconfig:versions:list --project "$FIREBASE_PROJECT_ID"
firebase remoteconfig:rollback --project "$FIREBASE_PROJECT_ID" --version-number <last good N>
```

### Server (`ridemate-api`)

```bash
cd ~/Desktop/ridemate
git fetch --tags
git switch --detach <last-good-tag>          # e.g. v1.0.0-hackathon
set -a; . ./.env; set +a
./backend/deploy.sh                           # redeploys the web app + cron, secrets, Firebase rules/indexes, RC template
curl -fsS "$RIDEMATE_API/healthz"; echo
curl -fsS "$RIDEMATE_API/readyz"; echo
git switch main                               # go back to your branch afterwards
```

If you only need the Modal app (not Firebase), from the same checkout:

```bash
cd backend && uv run modal deploy modal_app.py --env main
```

Notes:

- `deploy.sh` also redeploys the Remote Config template from the tag. If you flipped kill switches in the console
  since then, re-apply them after the rollback (or deploy only the Modal app as above).
- Firestore rules only: `git checkout <last-good-tag> -- firebase/firestore.rules` then
  `firebase deploy --config firebase/firebase.json --only firestore:rules --project "$FIREBASE_PROJECT_ID"`
  (from the repo folder), then `git checkout main -- firebase/firestore.rules` to put your working copy back.
- Data is not rolled back. The API is additive-only (breaking changes go to `/v2`), so an older server reads newer
  records safely.

### Android app

Phones can't downgrade, so "rollback" is a new release made from the old code:

1. `git switch -c rollback-<date> <last-good-tag>`
2. In `android/app/build.gradle.kts`, set `versionCode` to **one more than the bad release's** versionCode, and
   `versionName` to something like `1.0.2-rollback`.
3. Build, sign and check:

   ```bash
   cd android
   ./gradlew :core:test :app:testDebugUnitTest
   ./gradlew :app:assembleRelease                              # needs RIDEMATE_KEYSTORE_PASSWORD and ~/.ridemate/release.jks
   apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk   # fingerprint must match the README
   cp app/build/outputs/apk/release/app-release.apk RideMate-1.0.2-rollback.apk
   shasum -a 256 RideMate-1.0.2-rollback.apk > SHA256SUMS.txt
   ```

4. Install it on your own phone over USB (`adb install -r RideMate-1.0.2-rollback.apk`) and run the quick checks in
   [../LAUNCH.md](../LAUNCH.md) (at least: onboarding works, a demo read works, a reminder fires).
5. **Publishing the GitHub release waits for Baharul's explicit go-ahead.** When given, upload the APK and
   `SHA256SUMS.txt` to a new release, then update Remote Config `latest_version` (version code, name, URL, SHA-256) so
   phones are told to update. A forced update never interrupts an active ride session.
6. If the bad release must not be used at all, mark it as "pre-release" on GitHub with a note pointing to the new one.
   Raise `min_supported_version` only after at least 7 days' in-app notice (design rule J7). If a release is
   dangerous right now, switch the affected feature off with its kill switch instead of forcing an update.

## Rollback of the rollback

Redeploy the newer tag the same way once it's fixed.

## Who to tell

- **Users:** release notes for any app rollback ("1.0.2 restores 1.0.0's behaviour after a crash in 1.0.1").
- **During judging:** tell the organisers if the judged build or the live server changed.
