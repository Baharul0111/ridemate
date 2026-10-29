# Runbook: a spending cap tripped, or someone is abusing the AI

The AI budget is small ($80 for the whole life of the credit), so RideMate protects it with layers: Google sign-in
for AI, per-IP limits (60 requests/min), a fair share per person (6 reads + 10 notification parses a day; 3 reads for
accounts younger than 24 hours), a daily pacing cap with tiers, and a meter that fails closed. This runbook is for when
those layers fire, or when you suspect someone is getting around them.

Setup for the commands: [README.md](README.md#setup-used-by-the-commands).

## Symptoms

- Alert **spend > 70% of the day's cap** (young and heavy accounts are already paused) or **> 80% of any budget**.
- AI paused for everyone early in the day (`/v1/config-check` reason `day_cap`, `tier_pause` or `budget`).
- Alert **meter-vs-billing drift > 10%** (caps were tightened automatically).
- Many `429 rate_limited` from one network, or many new Google accounts each using their first-day allowance.
- Logs dominated by one `uid_hash`.

## Checks

1. **Budget state:** RideMate developer mode → budget panel (AI spent / $80, compute / $12, today's cap and spend,
   horizon end 2027-01-26 while the credit expiry is unknown).
2. **Live counters** in the Modal Dict `ridemate-hot` (read-only look):

   ```bash
   modal dict items ridemate-hot 50 --env main      # first 50 entries; keys look like quota:<hash>:<day>, ip:<bucket>, spend:meter:<budget>
   modal dict get ridemate-hot spend:meter:ai --env main
   ```

3. **Who is using it:** logs carry `uid_hash`, endpoint, outcome and cost, never names or emails:

   ```bash
   modal app logs ridemate-api --env main | grep -E '"uid_hash"' | head -200
   ```

   The `uid_hash` is one-way by design; you cannot (and should not) turn it back into a person. Fair-share quotas
   already limit any single account to a few cents a day, so a single heavy user is rarely an emergency.
4. **Is it judges?** During judging week, a surge of new accounts may simply be judges. Check the dates and
   [judging-week.md](judging-week.md) before blocking anyone.

## Fix

### Protect the budget first
- Normal pacing already stops AI at 100% of the day's cap; nothing more is needed if the alert is just "busy day".
- If spend is rising faster than pacing explains, or drift > 10% is unexplained: set `ai_reader_enabled` = `false`
  ([how](README.md#flipping-a-kill-switch-remote-config)). Manual mode and the guardian keep working.

### Block an abusive account
You can block only an account you can identify by its Firebase **UID** (for example, someone who reported themselves,
or an obviously fake account in Firebase console → **Authentication → Users**, sorted by created date).

1. Firestore console → collection `blocked_uids` → document `list` → field `uids` (array) → **add** the UID → Save.
   The server caches this list for 60 seconds; after that every AI call from that account gets `403 blocked`.
2. Optionally also **disable** the account: Firebase console → Authentication → Users → ⋮ → **Disable account**
   (reversible). ONDC confirm/cancel also checks for revoked sessions.

### Abuse from many accounts or one network
- Per-IP limits apply automatically (IPv6 is grouped by /64).
- Young-account tier: accounts younger than 24 hours already get only 3 reads/day. In the judging profile this tier is
  always on.
- If needed, keep AI off until the next day's pacing; do **not** raise any limit.

### Judges are being cut off
- Add judges to the **judge allowlist** (20 reads/day). It is the Firestore document
  `server_config/judge_allowlist`, which no phone can read (never Remote Config: every install can download that, and
  a hash can be checked against guessed emails). Hash each judge's **lower-cased** email:

  ```bash
  printf '%s' 'Judge.Name@Example.com' | tr '[:upper:]' '[:lower:]' | shasum -a 256 | cut -d' ' -f1
  ```

  Firebase console → project `ridemate` → **Firestore Database** → collection `server_config` → document
  `judge_allowlist` → field `schema` (string) = `1.0` and field `sha256_emails` (array of strings) = the hashes → **Save**.
  The server picks the change up within a minute.
- Make sure `cap_profile` = `judging` during judging week (see [judging-week.md](judging-week.md)).
- Remind judges that **Try the demo** needs no sign-in and no AI budget.

### Choosing the cap profile
- `cap_profile` = `normal`: daily cap = remaining AI budget ÷ days left × 1.5; tiers at 70% and 100%.
- `cap_profile` = `judging`: daily cap fixed at **$3**, young-account tier on, judge allowlist on. It draws from the same
  overall budget.

## Rollback

- Unblock: remove the UID from `blocked_uids/list.uids`; re-enable the account in Authentication.
- Undo Remote Config changes with `firebase remoteconfig:rollback` (see [README.md](README.md#flipping-a-kill-switch-remote-config)).
- Turn AI back on: `ai_reader_enabled` = `true`.

## Who to tell

- **Users:** automatic in-app message ("AI reading resumes at hh:mm (IST)"). No public statement needed for routine
  caps.
- **A blocked user who emails:** reply from ibaharul567@gmail.com, explain the fair-use limit, and unblock if it was a
  mistake.
- **During judging:** if judges were cut off, tell the organisers that Try the demo works without AI budget and that
  the limit has been adjusted.
- **Modal billing** (billing@modal.com) only if Modal's bill doesn't match the meter after you've checked.
