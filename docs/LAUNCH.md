# LAUNCH.md: the real-phone checklist

**Baharul runs this himself, on his own Android phone, with his own Uber, Ola and Rapido accounts, before any public
link is shared.** Nothing here is automated: the build and the tests never script, tap or drive Uber, Ola or Rapido.
Real-app behaviour is captured only with RideMate's in-app **Recorder** (developer mode).

Run it again after any release that changes capture, reading, the guardian or onboarding. It takes about an hour.
Booking and cancelling a real ride is your own decision each time; cancelling while the app is still **searching**
is usually free, but check the ride app's own cancellation terms first.

Write down for the record: date, RideMate version, phone model, Android version, and the version of each ride app
(Settings → Apps → <app> → version).

## 0. Prepare

- [ ] Install the **release-signed** APK you intend to publish (not a debug build):
      `adb install -r RideMate-<version>.apk`, or download it from the draft release on the phone.
- [ ] Check its checksum and certificate: `shasum -a 256 RideMate-<version>.apk` matches `SHA256SUMS.txt`;
      `apksigner verify --print-certs RideMate-<version>.apk` shows
      `94f2dd5fdb02aded4631cb0a094a0bee810532c1c8ae2223c17d1c7aa4227aa0`. Settings → About shows the same fingerprint.
- [ ] Clear RideMate's data first (Settings → Apps → RideMate → Storage → Clear storage) so onboarding starts fresh.
- [ ] Turn developer mode on afterwards (Settings → About → tap the version 7 times; owner sign-in only) so you can
      see the Recorder, the evidence rung and the budget panel.

## 1. Onboarding

- [ ] 18+ gate appears first.
- [ ] Privacy notice shows version 1.0, effective 2026-10-28 (English and Hindi), with the USA processing sentence.
- [ ] Google sign-in works; "Skip" is offered and leads to manual mode.
- [ ] Each permission is asked with its reason: notifications → notification access → display over other apps →
      (Android 13+: the guided "Allow restricted settings" step) → location while in use → battery exemption, plus
      the autostart guidance for your phone brand.
- [ ] The health check passes: a test notification is seen by RideWatch.
- [ ] Settings shows My data, Erase, the grievance email, kill-switch status, version + update check, the signing
      fingerprint and "Built with Muse Spark Open".

## 2. Read fares in all three apps

For one real trip (enter the destination by typing once, and by voice once):

- [ ] Address suggestions appear; you confirm the address; "View on map" opens your maps app.
- [ ] **Uber** opens with pickup and drop filled in (or plainly, if the prefill isn't supported).
- [ ] **Ola** and **Rapido** open; the bubble's "Paste drop" chip works.
- [ ] On each app's fare screen, tap the bubble → Android asks for capture permission → rows appear in RideMate.
- [ ] **Every number shown matches the ride app's screen exactly.** Any row RideMate wasn't sure of says
      "Couldn't read · check in app" with no number. Struck-out prices are not shown as the price.
- [ ] Each row says "seen in <app> at hh:mm:ss" and greys out after 60 seconds.
- [ ] Cheapest is marked only on a verified row.
- [ ] Capture stops after the last read (the system capture indicator disappears).
- [ ] In the Recorder, export the masked captures and label them for the private golden set (never the public repo).

## 3. Book one

- [ ] Book one ride yourself in one app (tap Book in the ride app). Tap "Booked here" on the bubble if asked.
- [ ] RideMate shows the booking as searching within a few seconds of the ride app's notification.

## 4. Cancel while searching

RideMate's guided cancel chain starts only when a driver is found and you answer **"Cancel the others?"**, so there
are two ways to run this step. The basic one is enough for the checklist; the full chain is optional. (The chain
logic itself is also covered by Try the demo and the replay tests.)

**Basic (one booking, usually free):**

- [ ] While the ride is still **searching**, cancel it yourself in the ride app (Cancel → reason → Confirm).
- [ ] Within a few seconds RideMate shows that booking as ended by the app's own cancelled notification. It does not
      stay "searching", and no reminder keeps running for it.

**Full chain (optional, only if you choose to take a real ride):**

- [ ] Book in two apps. When the first driver is found, RideMate shows the driver's distance and ETA and asks
      "Cancel the others?". Tap **Yes**.
- [ ] RideMate opens the other app (still searching, so usually free), shows the instruction card and the fee note;
      "Show me" draws an arrow **beside** the Cancel button (if supported) without covering it.
- [ ] Wait before cancelling: reminders fire at 30 s, 1 min and 2 min while the cancel isn't proven.
- [ ] You tap Cancel in the ride app yourself.

## 5. Confirm the evidence rung

- [ ] RideMate marks the booking cancelled only after proof. In developer mode, note what proved it: the app's exact
      cancelled notification (rung 2) or the ride notification removed plus a capture showing the cancelled state
      (rung 3).
- [ ] Full chain only: tap "I cancelled it" on the cancel card **before** actually cancelling. RideMate must not count
      it as proven; it re-checks after 60 s and warns loudly that the ride still looks active. Then cancel for real.
- [ ] Recorder: export the notification log for this ride (masked) for the template set.

## 6. Check ONDC hidden and the kill switches

- [ ] With `ONDC_MODE=off` on the server and `ondc_enabled` = `false`, the ONDC option does not appear anywhere.
- [ ] Set `reader_enabled_rapido` = `false` in Remote Config → within about a minute, reading Rapido fares is off
      (manual entry offered) while opening Rapido and the guardian still work. Set it back to `true`.
- [ ] Set `ai_reader_enabled` = `false` → manual mode with a clear message; Try the demo still works with recorded
      outputs. Set it back to `true`.

## 7. Test-fire one alert

- [ ] Send a test failure to the alerts check and confirm the email arrives (and the FCM push on your phone if
      configured), then reset it:

      ```bash
      curl -fsS -m 10 "$HEALTHCHECKS_ALERT_URL/fail"   # email "ridemate-alerts is DOWN" should arrive
      curl -fsS -m 10 "$HEALTHCHECKS_ALERT_URL"        # back to up
      ```

- [ ] The dead-man check `ridemate-heartbeat` shows recent pings every 15 minutes.

## 8. Clean up

- [ ] Erase works: Settings → Erase on a test account → signed out, My data empty.
- [ ] No ride is left active by mistake in any ride app.
- [ ] Record the result (pass / what failed) with the date in your private notes. A failed item blocks the public link
      until fixed and re-checked.
