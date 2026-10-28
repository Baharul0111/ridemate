# Runbook: public launch (v1.1.0)

> **GATED. Do not start this runbook until BOTH are true:**
>
> 1. **Baharul has given his explicit go-ahead** for a public launch, in his own words, on the day.
> 2. **Modal billing has confirmed in writing** (reply from billing@modal.com, saved) that with the workspace spend
>    limit at $0 (or the lowest allowed) the card on file will never be charged beyond the credit, including
>    shared-endpoint token usage, and what happens when the credit reaches $0.
>
> If either is missing, stop here. Sharing the APK with judges through the hackathon submission is **not** a public
> launch and doesn't need this runbook.

The hackathon build is `v1.0.0-hackathon`. The public launch is `v1.1.0`, with ONDC switched off, normal caps and the
judging profile off.

Setup for the commands: [README.md](README.md#setup-used-by-the-commands).

## Symptoms (when to use this)

Judging is over, and Baharul wants to share a public link with real users.

## Checks (all must be ticked)

- [ ] Both gates above are met (save the date of the go-ahead and Modal's email).
- [ ] [../LAUNCH.md](../LAUNCH.md) real-phone checklist passed on Baharul's own phone, with his own accounts, within
      the last 7 days.
- [ ] Modal: spend limit at $0 / lowest; usage limit about $100; credit balance and expiry noted. If the expiry is now
      known, set Remote Config `credit_expiry_date` (pacing then uses it instead of the 90-day horizon).
- [ ] Firebase: still on the **Spark** plan, no card anywhere on Google.
- [ ] MobSF scan of the release APK: no high findings; manifest audit clean (README "Auditing the manifest").
- [ ] Security checklist: `allowBackup=false`, not debuggable, R8 on, no exported components beyond the launcher, the
      share target and the notification listener (which only the system can bind to), Firestore rules deny by default
      (rules tests pass).
- [ ] All alerts test-fired once ([../slo-and-alerts.md](../slo-and-alerts.md)); dead-man green.
- [ ] No key-age alert pending: every key is under 80 days old (rotate first if not: [key-rotation.md](key-rotation.md)).
- [ ] Privacy notice (English and Hindi) matches what the app does; version and effective date current; processor
      terms recorded (Modal data-processing terms, request logging off where offered; Firebase terms accepted).
- [ ] Erase, My data and the grievance email work (try Erase on a test account).
- [ ] Load test done: k6 at 100 concurrent sessions against a stub provider, then 20 live reads.
- [ ] Full test suite green (Android, backend, rules, accuracy).

## Fix (the launch steps)

1. **Server settings for launch:**
   - `ONDC_MODE=off` in `ridemate-core` (the mock routes are then not mounted), and Remote Config `ondc_enabled` =
     `false` (ONDC hidden in the app).
   - `cap_profile` = `normal`; `judge_allowlist` empty; `min_containers=0` in `backend/modal_app.py`.
   - Caps as designed: fair share 6 reads + 10 parses per user per day, 3 reads for young accounts, daily pacing cap,
     tiers at 70% / 100%.
   - Deploy: `./backend/deploy.sh`, then `curl -fsS "$RIDEMATE_API/readyz"`.
2. **App release `v1.1.0`:**
   - Bump `versionCode` (higher than every released build) and set `versionName` = `1.1.0` in
     `android/app/build.gradle.kts`.
   - Run all tests, build and sign (see [rollback.md](rollback.md#android-app) for the exact commands), verify the
     certificate fingerprint `94:F2:DD:5F:…:7A:A0`, and write `SHA256SUMS.txt`.
   - Tag: `git tag -a v1.1.0 -m "RideMate 1.1.0 public launch"`.
   - **Publishing the tag and the GitHub release happens only with Baharul's go-ahead** (the gate). Upload the APK and
     `SHA256SUMS.txt`; paste the SHA-256 into the release notes.
   - Update the README release information (the `<!-- RELEASE_INFO -->` block) and test results.
   - Update Remote Config `latest_version` (version code, name, release URL, SHA-256) so existing installs are told.
3. **Watch closely for 48 hours:** budget panel twice a day, alerts, Crashlytics, grievance inbox.

## Rollback

- Too much load or spend: `ai_reader_enabled` = `false` (manual mode) while you look; never raise limits.
- Bad release: [rollback.md](rollback.md).
- Stop sharing: take the public link down and mark the release as pre-release with a note; users who installed keep a
  working app in manual mode.

## Who to tell

- **Users:** release notes for v1.1.0 (what it does, the hard rule, privacy summary, how to verify the APK).
- **Modal:** nothing needed beyond the saved billing confirmation.
- **Hackathon organisers:** only if they asked to be told about launches.
