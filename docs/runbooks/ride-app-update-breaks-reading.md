# Runbook: a ride-app update breaks reading or watching

Uber, Ola and Rapido change their screens and notification wording without warning. RideMate never shows a wrong
price because two readers must agree, so a change usually shows up as **more "Couldn't read" rows** or **more "Got a
driver?" questions**, not as wrong numbers. The fix is a Remote Config update (no app release needed), produced from
your own recordings and checked before it reaches everyone.

Setup for the commands: [README.md](README.md#setup-used-by-the-commands).

## Symptoms

- Alert **"VERIFIED < 70%"** (reading success below 70% with at least 20 reads) for one app.
- Alert **"rule miss > 20%"**: notifications from one app no longer match the templates.
- Alert **"rule miss, no classifier" spike**: misses while the AI classifier is unavailable, so users get many
  "Rapido has an update…" questions.
- Users report "Couldn't read · check in app" on every row for one app, or the app was just updated in the Play Store.
- Your phone's developer-mode log shows `template=fallback` for a new `versionCode` (the ride app is newer than any
  template RideMate knows). This is logged on the phone, not on the server.

## Checks

1. **Which app and which version?** In the logs, look at the `app` and `versionCode` fields on failing reads or misses:

   ```bash
   modal app logs ridemate-api --env main | grep -E '"outcome"'
   ```

   In the Firestore console, `events` records show `app`, `versionCode`, `verified_rows`, `unreadable_rows`,
   `rule_misses` per session.
2. **Is it reading (screens) or watching (notifications)?** VERIFIED drop = screens. Rule-miss rise = notifications.
3. **On your own phone:** update that ride app, open RideMate's developer mode (Settings → About → tap the version 7
   times; owner sign-in only) and run the Recorder's **capture test** on the fare screen. It tells you whether the
   screen is now blocked from capture (black frame, `FLAG_SECURE`) and whether the bubble stays visible.
   - **Black frame:** the app now blocks screen capture. RideMate never bypasses that. Turn that app's reader off
     (step 1 below) and rely on manual entry and the share-a-screenshot fallback; the guardian keeps working.

## Fix

1. **If users are hitting it now, turn that app's reader off** (`reader_enabled_<app>` = `false`) as described in
   [Flipping a kill switch](README.md#flipping-a-kill-switch-remote-config). Opening the app and the notification
   guardian stay on; users type that app's price. This is optional when only notifications are affected.
2. **Record the new behaviour with the Recorder (your phone, your accounts, never automated).**
   - Screens: open the ride app's fare screen, tap RideMate's bubble, and export the masked captures with the labelling
     form (app, screen type, the true price and ETA per row).
   - Notifications: book a ride and **cancel it while it is still searching** (usually free) to record searching and
     cancelled posts. Record assigned/arriving/driver-cancelled posts on a ride you actually take. Export the Recorder's
     masked JSON log.
   - Add the exports to the **private** golden repo `Baharul0111/ridemate-golden` (never to the public repo), commit,
     push, and sync to the Modal Volume (see [restore-golden-set.md](restore-golden-set.md) for the `modal volume put`
     command).
3. **Ask the Template Doctor for a proposal.** Run it from `tools/template_doctor/` (see `--help` for the exact
   options). It reads only your masked recordings, proposes new regex templates / parser rules, runs the regression
   suite, and opens a pull request. It uses the evals sub-budget (≤ $0.50 per run, ≤ $5 in total), so run it once,
   not in a loop. You can also write the fix by hand.
   - **Breaking changes go under a new key** (for example `fare_parsers_v2`), and old keys stay served for older app
     versions. New templates get a `min_version_code` equal to the new ride-app versionCode, so older ride-app versions
     keep their old templates.
4. **Regression check (no AI spend):**

   ```bash
   cd ~/Desktop/ridemate/android && ./gradlew :core:test   # parser, gate, replay and accuracy tests on recorded outputs
   cd ~/Desktop/ridemate/backend && uv run pytest          # server-side config parsing
   cd ~/Desktop/ridemate && python3 config/build_template.py   # rebuild the Remote Config template and bundled defaults
   ```

   Then run the eval harness in `eval/` against the golden set on Modal for the affected app only. All must pass, the
   affected app's verified rate on the new screens must be back well above the 70% alert line (close to where it was
   before the update), and there must be **zero wrong prices** (wrong-shown target ≤ 0.5%, 0 in the demo).
5. **Test on the dev project.** Publish the new template to `ridemate-dev` first:

   ```bash
   firebase deploy --config firebase/firebase.json --only remoteconfig --project "$FIREBASE_DEV_PROJECT_ID"
   ```

   On your test phone with a build pointed at `ridemate-dev`, open the updated ride app and read fares, and check the
   recorded notifications are recognised. Look at the developer-mode log: no `template=fallback` for the new version.
6. **Roll out 10% → 100% on production.** Firebase's "Rollouts" feature needs Google Analytics, which RideMate keeps
   off, so use a condition instead:
   - Firebase console → Remote Config → **Add condition** → name `rollout_10` → *User in random percentile* ≤ 10 →
     give the changed parameter the new value under that condition (default value unchanged) → **Publish changes**.
   - Watch for a few hours (a full day if not urgent): the VERIFIED rate and rule-miss rate for that app must improve,
     with no new crashes in Crashlytics.
   - Then set the new value as the default, delete the `rollout_10` condition, and **Publish changes**.
   - Commit the same change to `config/` so the repo and the live template match.
7. **Turn the app's reader back on** (`reader_enabled_<app>` = `true`) if you turned it off in step 1.

## Rollback

```bash
firebase remoteconfig:versions:list --project "$FIREBASE_PROJECT_ID"
firebase remoteconfig:rollback --project "$FIREBASE_PROJECT_ID" --version-number <last good N>
```

Phones also keep the last good config and the defaults bundled in the APK, so a bad payload (wrong schema major) is
ignored automatically.

## Who to tell

- **Users:** if a reader was off for more than a few hours, add a short line to the GitHub release notes / README
  status ("Rapido fare reading paused on 12 Nov after a Rapido update; fixed 13 Nov").
- **During judging:** a one-line note to the organisers is only needed if the demo is affected (it normally isn't:
  the demo uses fictional providers).
