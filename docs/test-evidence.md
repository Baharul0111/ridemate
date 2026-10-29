# On-phone test evidence

Run on 29 October 2026 (IST) against RideMate's deployed backend on Modal (Remote Config `api_base_url`), with
Muse Spark Open answering the demo reads. Counts, rates and enums only: no fare values appear here.

## Device

| | |
|---|---|
| Phone | Motorola moto g57 power (`mumba`), connected over USB |
| Android | 17 (API 37), 1080 × 2400 px, 390 dpi, locale en-IN |
| Build | `in.ridemate.app` debug, versionCode 1 (`1.0.0-hackathon`), installed fresh by the run |

Every shell command the tests ran touched RideMate only: `pm grant` (POST_NOTIFICATIONS, ACCESS_FINE_LOCATION,
ACCESS_COARSE_LOCATION, RECORD_AUDIO), `appops set in.ridemate.app SYSTEM_ALERT_WINDOW allow`,
`cmd notification allow_listener in.ridemate.app/app.ridemate.watch.RideWatchService`,
`dumpsys deviceidle whitelist +in.ridemate.app`. No other app was launched, queried or automated, and no account was
signed in to (the demo used RideMate's own anonymous token).

## Instrumented tests (`:app:connectedDebugAndroidTest`, final run 07:01–07:07 IST)

The suite `app.ridemate.e2e.DeviceTestSuite` runs in this order, so onboarding sees the fresh install.

| Test | Result | Duration |
|---|---|---|
| `FreshInstallOnboardingTest.freshInstall_walksEveryOnboardingStep_healthCheckSeesItsOwnNotification_reachesHome` | pass | 22.7 s |
| `DemoEndToEndTest.keepSearchingTrack_betterRideOffer_yes_cancelsTheOthers_payHandoff` | pass | 125.2 s |
| `DemoEndToEndTest.mainTrack_liveReads_cancelChainWithProofAndReminders_payHandoff` | **fail** (one collected check, below) | 210.5 s |
| `DemoOcrRecorderTest.recordsMlKitOcrForEveryBundledDemoScreen` | pass | 6.1 s |
| `MigrationTest.migrate1To2_validatesSchemaAndKeepsEveryRow` | pass | 0.1 s |
| `MigrationTest.appDatabaseOpensV1FileThroughTheMigration` | pass | 0.1 s |

5 of 6 pass (suite time 366.9 s). What each one covered on the phone:

- **Onboarding**: 18+ gate → privacy notice → "Continue without signing in" (manual mode; anonymous token) →
  notifications → notification access → display over other apps → location → battery exemption + Motorola guidance →
  the health check saw RideMate's own `rm_selftest` notification (PASSED) → all five permissions "Allowed" → Home.
  The restricted-settings step was correctly skipped.
- **Keep-searching walk**: first driver (Pepper) → Keep searching → better-ride offer (Tamarind, closer) → Yes, switch
  → Pepper guided first; Tamarind's driver is close, so "Cancel the others?" for the still-searching Kestrel → Yes →
  Pepper and Kestrel each proven cancelled by rung 2 (their own cancelled notification) → pay hand-off in Tamarind.
- **Main walk**: three fare screens read through ML Kit + the deployed backend + the two-reader gate (source `cache`
  for all three in the final run; Pepper and Tamarind were `live` model reads on their first read of the day) →
  Pepper's driver (9 min, 2.8 km) → "Cancel the others?" → Yes → cancel card (1 of 2) → reminder 1 fired while Kestrel
  and Tamarind were pending → Kestrel and Tamarind proven by rung 2 → Pepper's ride ends → pay hand-off. Every step
  passed; every non-verified row showed no number and "cheapest" appeared only on VERIFIED rows.
- **OCR recorder**: ML Kit Text Recognition v2 over all 16 bundled demo PNGs → contracts §6 JSON
  (`engine: "mlkit-text-v2"`, `image_sha256` of the exact bytes), pulled into `eval/recorded/ocr/`. Two separate
  recordings produced identical tokens.

### The one failing check

`DEMO_KESTREL: no VERIFIED price (source CACHE); gate: BIKE=[NO_MODEL_OPTION], AUTO=[PRICE_MISMATCH, LABEL_MISMATCH],
CAB_ECONOMY=[PRICE_MISMATCH, LABEL_MISMATCH]`

Pepper (3/3 rows) and Tamarind (4/4 rows) were VERIFIED. For Kestrel, Channel B now reads all three prices
correctly, but the backend's cached Muse Spark Open read of `demo/kestrel/fare_options.png` (sha256 `23ef930b…`,
the same read as `eval/recorded/channel_a/23ef930b…json`) places each option's box 60–80 grid units (about 2.5–3.3% of
the frame) below its real row, so the gate's §4.3(b) position check (payable centre inside A's box ± 4%) pairs each
price with the wrong option and refuses all three rows. The app shows "Couldn't read · check in app" for Kestrel,
which is the gate working as designed. Making this pass needs a better Channel A read in the backend's demo cache for
that one image (an owner/backend action: evict or re-record that cache entry), not a looser gate.

## Fixed during the run

1. **Fare parser vs real ML Kit output** (`core/fare/FareParser.kt`, `core/format/RupeeParser.kt`). On the phone, ML
   Kit drops the ₹ glyph or reads it as `{`, `T` or a leading `7`, so the demo fare screens carried no ₹ token and no
   row could be VERIFIED. A labelled row without a ₹ payable may now take a bare amount from the price column: right
   half of the frame, right edge aligned (± 3% of the width) with another labelled row's price, at the ₹ payables'
   height when there are any; amounts on the label's own line first (struck prices sit below). A lone bare number is
   never a price, and Channel A must still agree. Unit tests added; zero wrong-shown in every accuracy suite.
2. **Second demo run lost rung-2 proof** (`core/watch/RideWatchPipeline.kt`, `session/GuardianRuntime.kt`). RideWatch's
   exact-repeat dedupe outlived the session, so a second Try-the-demo run in the same process dropped "Trip cancelled"
   as a repeat and fell back to rung 3. Dedupe memory now resets at each session start; a JVM regression test fails
   without the fix, and on the phone the second run proved its cancels by rung 2.
3. **Test harness**: Compose sends accessibility change events only while an accessibility service is enabled, so
   UiAutomator's node cache went stale between screens; every lookup now clears the cache first (API 34+), taps wait
   for scrolling to settle and confirm their effect, and sheet buttons are matched in RideMate's window only (the
   heads-up notification carries the same labels).

## Accuracy on recorded fixtures (`:core:test -PrequireFixtures=true`, then `eval/harness/summarize.py`)

All 527 core tests pass (AccuracyTest included). VERIFIED rate per suite; wrong-shown is 0 everywhere.

| Suite | Pairs (screens) | Label rows | VERIFIED | Wrong-shown | Driver facts ok/wrong |
|---|---|---|---|---|---|
| demo (synthetic + noisy + recorded OCR × honest reads) | 244 | 560 | 60.0% | 0 | 40/0 |
| injection (adversarial screens) | 8 | 28 | 64.3% | 0 | 0/0 |
| recorded (phone ML Kit OCR × recorded Muse Spark Open) | 16 | 10 | 70.0% | 0 | 4/0 |

Recorded suite by app: Kestrel 0.0% (the box offset above), Pepper 100%, Tamarind 100%. Before the parser fix the
recorded suite was 10.0% and the demo suite 51.8%.

## Live AI reads

12 `/v1/read-screen` demo reads went through the deployed backend during these runs (limit 40): 2 answered by the
model (`live`: Pepper and Tamarind, first reads) and 10 from the backend's demo cache. The keep-searching walk books
without reading; onboarding, the OCR recorder and the migration tests make no AI calls.

## Release smoke

`RIDEMATE_KEYSTORE_PASSWORD` was not set, so the signed release APK was not built here (release signing stays with
the coordinator). Instead the debug build was installed fresh and cold-started with
`adb shell am start -W -n in.ridemate.app/app.ridemate.MainActivity`: status ok, cold start 2.4 s, the 18+ gate on
screen, no crash in RideMate's log.

## Reproduce

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=$HOME/Library/Android/sdk
adb uninstall in.ridemate.app   # onboarding needs a fresh install
cd android
./gradlew --no-daemon -Pkotlin.compiler.execution.strategy=in-process --console=plain :app:connectedDebugAndroidTest
# JUnit XML: android/app/build/outputs/androidTest-results/connected/debug/

# One class or method:
./gradlew --no-daemon --console=plain :app:connectedDebugAndroidTest \
  "-Pandroid.testInstrumentationRunnerArguments.class=app.ridemate.e2e.DemoEndToEndTest#keepSearchingTrack_betterRideOffer_yes_cancelsTheOthers_payHandoff"

# Recorded OCR (the suite copies it to the shell's folder, which survives the post-run uninstall):
adb pull /data/local/tmp/ridemate-ocr/. ../eval/recorded/ocr/ && adb shell rm -rf /data/local/tmp/ridemate-ocr

# Accuracy on the recorded fixtures:
./gradlew --no-daemon -Pkotlin.compiler.execution.strategy=in-process --console=plain :core:test -PrequireFixtures=true
python3 ../eval/harness/summarize.py

# Release smoke (debug build, fresh):
adb uninstall in.ridemate.app; ./gradlew --no-daemon --console=plain :app:installDebug
adb shell am start -W -n in.ridemate.app/app.ridemate.MainActivity
```
