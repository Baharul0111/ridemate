# Runbook: a platform objects to RideMate

RideMate is designed to stay within the rules: it never acts inside Uber, Ola or Rapido, never uses their private
APIs, never scrapes automatically, and reads a screen only when the user taps. Even so, a ride company, Google, Meta or
the hackathon organisers may object. The answer is to **respond the same day, calmly, and switch off the part they
object to**, keeping everything that protects users.

## Symptoms

- An email, legal notice or message from Uber, Ola, Rapido (or their lawyers) about RideMate.
- Google Play Protect warning about the APK, or a Google policy notice.
- A message from Meta about the Muse Spark Open licence or Acceptable Use Policy.
- A question from the hackathon organisers about rule compliance.
- A ride app starts blocking screen capture (black frames) or changes its terms to mention RideMate.

## Checks

1. **What exactly do they object to?** Reading their fare screens? Opening their app with a route? Watching their
   notifications? Use of their name or logo? Write it down in one sentence.
2. **Is there a deadline?** Note it.
3. **What does RideMate actually do with that app?** Check against the design:
   - opening the app: a plain launch (Ola, Rapido) or Uber's public universal link with no `client_id`;
   - reading: one user-tapped capture of the user's own screen, masked on the phone;
   - watching: a notification listener limited to three packages, masked, nothing stored;
   - never: taps, typing, accessibility actions, private APIs, automated scraping, hidden displays.

## Fix (same day)

1. **Switch off fare reading for that app** with its per-app kill switch (`reader_enabled_uber`, `reader_enabled_ola`
   or `reader_enabled_rapido` = `false`), see [Flipping a kill switch](README.md#flipping-a-kill-switch-remote-config).
   **Launching the app and the guardian stay on**: users can still compare by typing that app's price, and RideMate
   still reminds them to cancel extra bookings, which protects users and the drivers.
2. If the objection is about **opening the app with a route** (the Uber universal link): change that app's entry in
   `launch_templates` to a plain launch with the paste chip (`"mode": "launch_paste"`, as Ola and Rapido already use),
   and publish. No app update is needed.
3. If the objection is about **watching notifications** for that app: this needs an app update, because the package
   allowlist is in code by design (so a remote config can never widen it). Remove the package from the allowlist and
   from `<queries>` in the manifest, release a new version (see [rollback.md](rollback.md) for the release steps), and
   in the meantime keep that app's reader off.
4. If the objection is about **names or logos**: RideMate uses the app names only to describe compatibility and shows
   no logos. Offer to adjust wording in the README and app.
5. **Reply in writing within 24 hours** from ibaharul567@gmail.com: thank them, say what you switched off and when,
   explain briefly how RideMate works (read-only, user-tapped, no automation, no private APIs), and ask what they
   would need. Do not argue in public. Keep copies of everything.
6. **Google Play Protect warnings:** check the APK's SHA-256 and certificate fingerprint match the release
   (see the README's "Install the signed app"), and run a MobSF scan. RideMate isn't on the Play Store, so there is no
   listing to update; reply through Google's appeal form if one is offered.
7. **Meta licence / AUP:** confirm the "Built with Muse Spark Open" label and the 18+ gate are present, and that every
   consequential action has a human tap (invariant I1). If Meta asks you to stop, set `ai_reader_enabled` = `false`
   (manual mode) while you talk.

## Rollback

If the matter is resolved, turn the switch back on (`reader_enabled_<app>` = `true`) or restore the launch template,
and note the agreement in your records.

## Who to tell

- **The objecting party:** a written reply within 24 hours (step 5).
- **Users:** a short, neutral line in the README status and release notes ("Reading prices from Ola is switched off
  for now; you can still type Ola's price and RideMate still guards your bookings").
- **Hackathon organisers:** if it happens during judging, tell them what was switched off and that the demo (fictional
  providers) is unaffected.
- Get legal advice before agreeing to anything beyond switching a feature off.
