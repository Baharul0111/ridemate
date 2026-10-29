# Runbook: judging week

From submission (**5 November 2026, 07:30 IST**) until the organisers announce the end of judging, judges may try
RideMate at any hour. The goals: judges are never cut off, the demo always works, the credit is protected, and nothing
risky is deployed.

Setup for the commands: [README.md](README.md#setup-used-by-the-commands).

## Before submission (by 4 Nov evening)

1. **Switch to the judging profile:** Remote Config `cap_profile` = `judging`
   ([how](README.md#flipping-a-kill-switch-remote-config)). This only puts a **ceiling of $3/day** on the public's
   daily AI cap: the computed cap (about $1.33/day on the 90-day horizon) is lower, so on its own the profile changes
   nothing for judges. It draws from the same overall budget (AI $80).
2. **Judge allowlist: this is what protects judges.** The young-account tier and the 70% tier pause apply in every
   profile, and a judge signing in for the first time is a brand-new account (3 reads a day, paused first). Listed
   judges get 20 reads a day, from the $15 judging sub-budget, and are never paced or paused by the public's day cap.
   If the organisers share judge emails, add their SHA-256 hashes to `judge_allowlist`. How to hash:
   [cap-tripped-or-abuse.md](cap-tripped-or-abuse.md#judges-are-being-cut-off). The dispatcher alerts
   (`judging_allowlist_empty`) while `cap_profile` = `judging` and the list is empty. If no emails are shared, point
   judges to Try the demo, which needs no sign-in and answers from the cache or the recorded outputs when its own
   small budget is used up.
3. **Decide on `min_containers=1`** (a warm server, so the first request isn't slowed by a cold start):
   - Cost: about $0.016 an hour, so about **$0.38 a day** or **$2.70 a week**, from the $12 compute budget.
   - Benefit: no cold start (target ≤ 4 s anyway) on the first read after a quiet period.
   - Recommendation: **on during judging week**, off afterwards. It's your decision; write it down.
   - How: set `min_containers=1` on the web function in `backend/modal_app.py`, commit, `./backend/deploy.sh`.
     Afterwards set it back to `0` and deploy again.
4. **Check the demo end to end** on your own phone, signed out: Try the demo → reading through Muse Spark Open + ML Kit
   → driver found → "Cancel the others?" → guided cancel with proof and reminders → better-ride offer → pay handoff →
   ONDC demo seller. Then set `ai_reader_enabled` = `false` briefly and confirm the demo still works (answers come from
   the demo cache, or from **recorded** outputs, marked as such); set it back to `true`.
5. **Tag and freeze:** the submitted code is tagged `v1.0.0-hackathon`. During judging, deploy only fixes for real
   problems, each with a rollback ready ([rollback.md](rollback.md)).
6. **Alerts are on and tested:** dead-man heartbeat green; one test alert fired this week
   (see [../slo-and-alerts.md](../slo-and-alerts.md)).

## Every day during judging (5 minutes)

- healthchecks.io: both checks green.
- Developer mode → budget panel: today's spend vs the $3 cap; AI and compute spent vs $80 / $12.
- Email: any alert, any message from organisers or judges.
- `curl -fsS "$RIDEMATE_API/readyz"`.
- Crashlytics: any new crash.

## If something goes wrong

| Problem | Go to |
|---|---|
| Judges see "AI reading resumes at…" | [cap-tripped-or-abuse.md](cap-tripped-or-abuse.md): allowlist them; remind them Try the demo needs no budget |
| Server down / dead-man email | [modal-down-or-credit-used-up.md](modal-down-or-credit-used-up.md) |
| A deploy broke something | [rollback.md](rollback.md) |
| Model missing | [model-removed.md](model-removed.md) |

## After judging ends

1. `cap_profile` = `normal`.
2. Clear `judge_allowlist` (empty list) unless the organisers ask you to keep access open.
3. `min_containers` back to `0` in `backend/modal_app.py` and deploy.
4. Note the AI and compute spent during judging.

## Who to tell

- **Organisers:** reply quickly to any judge problem; if the live AI is paused, say Try the demo works with recorded
  outputs.
- **Users:** nothing special.
