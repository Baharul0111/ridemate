# RideMate at the Meta Global AI Developer Hackathon

| | |
|---|---|
| Event | Meta Global AI Developer Hackathon |
| Track | Indie (solo) |
| Theme | Build for Your World |
| Builder | Baharul Islam, Barpeta, Assam, India |
| Build window | 27 Oct – 4 Nov 2026 |
| Submission due | **5 Nov 2026, 07:30 IST** |
| Repository | <https://github.com/Baharul0111/ridemate> (public, MIT) |
| Model | **Muse Spark Open** (`meta-models/Muse-Spark-Open-816B-A42B`, Meta open weights) on Modal's shared endpoint |
| Awards targeted | Best Build on Meta Open Weight Models; Best Agentic Use-Case |

## Why "Build for Your World"

In Assam, as in most of India, people book bikes, autos and cabs across three apps. Prices differ a lot from app to
app and minute to minute, so people book in two apps "just in case", and a forgotten booking costs a cancellation fee
(the Motor Vehicle Aggregator Guidelines 2025 cap it at 10% of the fare, up to ₹100, when there is no valid reason;
state rules may differ, but it is still money) and wastes a driver's trip. RideMate is built for that world: it
compares honestly, keeps the human in charge of every booking, and makes sure no extra booking is forgotten. It speaks
Hindi and English, reads Indian price formats and Devanagari digits, and works on Android 10 and newer.

## How RideMate maps to the judging criteria

The four criteria are weighted equally.

| Criterion | What to look at |
|---|---|
| **Impact** | Saves money (compare three apps in one list) and prevents penalties (a guardian that reminds until every extra booking is **proven** cancelled). Read-only and human-confirmed, so it respects the ride apps' rules, the drivers and Meta's AUP. Works for people without Indian ride apps through Try the demo. Zero cost to run beyond the hackathon credit, and keeps working in manual mode when the AI budget runs out. |
| **Use of Meta AI tooling** | **Muse Spark Open does all the AI work**: reading fare screens into a strict schema (Channel A), re-reading disagreeing rows, notification understanding for unknown wording (with an evidence span that must appear in the text), voice and typed place matching, "Show me" grounding, and the Template Doctor agent that proposes fixes when a ride app changes. Open weights on Modal, model id pinned and verified. "Built with Muse Spark Open" label and 18+ gate per the licence. See the README section "How RideMate uses Meta's models". |
| **Technical depth** | A two-channel verification gate (Muse Spark Open vs on-device ML Kit + per-app parser) that never shows a confident wrong number; a deterministic per-app ride statechart with generation counters and a full transition table; invariants I1–I10 enforced in code and tests; replay tests for drivers cancelling and re-matching; RE2/J linear-time templates delivered by Remote Config; an all-spend credit meter with reservations, daily pacing and billing reconciliation; abuse limits keyed on HMAC of the Google account; an ONDC TRV10 buyer with Ed25519 signing; alerting that survives a Modal outage. |
| **Demo** | Try the demo runs the real pipeline on fictional providers (Kestrel Go, Pepper Rides, Tamarind Cabs) without ride apps, sign-in or AI budget; the 3-minute video also shows the live flow on Baharul's own phone. Script: [demo-video-script.md](demo-video-script.md). |

## Compliance (architecture.md §4.15)

Tick each box at submission time, after checking it in the final build.

- [ ] **Public GitHub repo** under the MIT licence: `Baharul0111/ridemate`.
- [ ] **Only code written during the event.** All code in this repository was written from 28 Oct 2026 (inside the
      27 Oct – 4 Nov window), with dated commits and a "What was built during the hackathon" section in the README.
      Third-party libraries are used through locked dependencies.
- [ ] **Meta models:** Meta's open-weights **Muse Spark Open** via partner inference (Modal) does all the AI work. The
      rules accept "Meta Model API and/or Meta's open-weight models".
- [ ] **Theme:** Build for Your World.
- [ ] **3-minute demo video:** the live flow on Baharul's phone plus Try the demo ([script](demo-video-script.md)).
- [ ] **"Built with Muse Spark Open"** label in the README and the app; **18+ gate** in onboarding.
- [ ] **No rule broken:** RideMate doesn't automate the ride apps, uses no private APIs, keeps a human confirming every
      consequential action, and the build never scripted those apps (real behaviour was recorded by the owner with the
      in-app Recorder).
- [ ] **Live product:** the signed APK on GitHub Releases with SHA-256 checksums (publishing waits for Baharul's
      explicit go-ahead), the backend running on Modal, Firebase on the free plan.

## How to run the demo (judges)

1. Download the APK and `SHA256SUMS.txt` from <https://github.com/Baharul0111/ridemate/releases/latest>; check the
   checksum and certificate as in the README ("Install the signed app").
2. Install on any Android 10+ phone. On first run: confirm 18+, read the privacy notice, skip sign-in if you like.
3. Tap **Try the demo**. You'll see:
   - fares from three fictional companies read by Muse Spark Open + ML Kit, with the cheapest verified option marked
     (and a "Couldn't read · check in app" row if the readers disagree on one);
   - a driver found → **"Cancel the others?"** → the guided cancel chain with proof and reminders;
   - a better-ride offer that changes nothing until you tap Yes;
   - the pay handoff;
   - the ONDC demo seller: search, book, cancel with the fee shown first.
4. Demo reads use a cache keyed by image hash; if the AI budget is paused, recorded Muse Spark Open outputs (served by
   RideMate's server) are used, clearly marked "recorded".

No Uber, Ola or Rapido account is needed. Nothing in the demo touches a real ride app or a real ride.

## Key dates for Baharul

| When | What |
|---|---|
| 31 Oct | Checkpoint: the core flow must work end to end on the phone; otherwise extras are cut in this order: Template Doctor → Show me → better-ride alerts → voice → ONDC mock flow. Never cut: the guardian, the verification gate, Try the demo, the README and the demo video |
| 4 Nov | Judging profile on, demo checked signed-out, `v1.0.0-hackathon` tagged ([runbook](runbooks/judging-week.md)) |
| 5 Nov, 07:30 IST | Submission deadline: repo link, video, APK link |
| Judging period | Daily checks ([runbook](runbooks/judging-week.md)) |
