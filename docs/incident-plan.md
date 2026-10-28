# RideMate incident plan

An **incident** is anything that harms users, exposes personal data or secrets, spends money it shouldn't, or makes
RideMate do something it promised never to do (show a wrong price as verified, mark a booking cancelled without proof,
act inside a ride app). Baharul Islam is the incident owner. This plan says what to do in the first hour, the first
6 hours and the first 72 hours. Day-to-day fixes are in the [runbooks](runbooks/README.md).

## 1. First 15 minutes: stop the harm

Use the smallest switch that stops the harm. All of these are reversible.

| Switch | Effect | How |
|---|---|---|
| `ai_reader_enabled` = `false` | All AI off; everyone in manual mode; guardian and reminders keep working | Remote Config ([how](runbooks/README.md#flipping-a-kill-switch-remote-config)) |
| `reader_enabled_uber` / `_ola` / `_rapido` = `false` | Fare reading off for one app; launch and guardian stay | Remote Config |
| `ondc_enabled` = `false` | ONDC hidden | Remote Config |
| `demo_enabled` = `false` | Try the demo hidden | Remote Config |
| `model_route` | Change which provider the server calls | Remote Config |
| `blocked_uids/list` | Block specific accounts from AI (60 s cache) | Firestore console |
| Disable a Firebase Auth user | That account can't sign in or refresh tokens | Firebase console → Authentication |
| **Stop the server** | Web app and cron stop; phones go to manual mode; guardian keeps working on the phone | `modal app stop ridemate-api --env main` |
| `latest_version` | Tell phones a fixed release is available (an update prompt). Raising `min_supported_version` to force updates needs ≥ 7 days' in-app notice (design rule J7), so it is not an emergency switch | Remote Config |

Then:

- **Preserve evidence.** Modal keeps logs for **1 day only**: save what you need now
  (`modal app logs ridemate-api --env main > ~/incident-$(date +%F-%H%M).log`, stop with Ctrl-C after a while). Do not
  delete Firestore `audit` or `events` records (they are kept 1 year). Screenshot dashboards (Modal usage, Firebase
  usage, healthchecks.io). Write a timeline: when it started, when you noticed, what you did, with times in IST.
- **Decide if it is a personal-data breach**: did any personal data (from the privacy notice's table) reach someone
  who shouldn't have it, or get lost or altered? If yes or unsure, the reporting clocks below start **now**.

## 2. Rotate and revoke

If any secret might be involved, rotate it: [runbooks/leaked-key-or-keystore.md](runbooks/leaked-key-or-keystore.md).

- **Rotate:** Modal production proxy token, HMAC key (dual-key), Firestore web and ops service-account keys, ONDC keys,
  healthchecks.io ping URLs, Template Doctor GitHub token.
- **Revoke:** old proxy tokens (Modal → Settings → Proxy Auth Tokens), old service-account keys
  (`gcloud iam service-accounts keys delete`), GitHub tokens and OAuth grants, CLI logins on a lost laptop, and user
  sessions (disable affected accounts; ONDC confirm/cancel checks revocation).
- **Keystore:** if the release keystore leaked, warn users and rotate with APK Signature Scheme v3 (same runbook).

## 3. Report (clocks run from when you became aware)

| Who | When | What | How |
|---|---|---|---|
| **CERT-In** (Indian Computer Emergency Response Team) | **Within 6 hours** of noticing a reportable cyber security incident (for example data breach or leak, unauthorised access, compromise of systems or keys, attacks on applications or servers) | The incident reporting form with what you know so far; you can add details later | Email **incident@cert-in.org.in** (check <https://www.cert-in.org.in> for the current form and phone numbers) |
| **Data Protection Board of India** | **Without delay** after becoming aware of a personal data breach | A brief description: nature, extent, timing, location, likely impact | Through the Board's online platform as published under the DPDP Rules, 2025 |
| **Affected users** | **Without delay** | Plain-language notice (template below) | Email from ibaharul567@gmail.com to affected users where you have their address; in-app notice and README status otherwise |
| **Data Protection Board of India** (detailed report) | **Within 72 hours** of becoming aware (or longer if the Board allows) | Updated facts, cause, mitigation, people/data affected, findings about who caused it, and what was done to notify users | Same platform |
| **Processors involved** (Modal, Google Firebase) | As soon as possible | What you saw on their side | support@modal.com · Firebase support |

RideMate follows the DPDP Rules' breach timelines now, as the Rules phase in. When unsure whether something must be
reported, report it: an over-report costs little; a missed one costs trust.

## 4. User notice template

> **Subject: Important: a security incident affecting your RideMate data**
>
> Hello,
>
> On **[date, time IST]** we found that **[what happened, in one or two plain sentences]**.
>
> **What data was involved:** [for example: the email address and name from your Google sign-in. No screenshots were
> involved, because RideMate never stores them.]
>
> **What it could mean for you:** [likely consequences, for example phishing emails pretending to be RideMate].
>
> **What we have done:** [switched the affected feature off at hh:mm, rotated keys, blocked access, informed CERT-In and
> the Data Protection Board of India].
>
> **What you can do:** [for example: be careful with emails asking for passwords or OTPs; RideMate will never ask for
> them. You can erase your RideMate data at any time in Settings → Erase.]
>
> If you have questions, reply to this email or write to **ibaharul567@gmail.com**. We will answer within 30 days, and
> much sooner for questions about this incident.
>
> Baharul Islam, RideMate

## 5. Fix, recover, learn

1. Fix the cause with a reviewed change, deploy (or [roll back](runbooks/rollback.md)), and switch features back on one
   at a time, watching alerts.
2. Within 7 days, write a short post-incident note (private): timeline, cause, what worked, what to change. Add a test
   or alert so the same thing is caught earlier next time.
3. Update the privacy notice, runbooks or this plan if they were wrong or missing something.

## 6. Severity guide

| Level | Examples | Response |
|---|---|---|
| **SEV-1** | Personal data exposed; secret or keystore leaked; spend beyond the meter; RideMate acting in a ride app; a wrong price shown as verified to many users | Everything above, now. CERT-In 6 h and DPDP clocks if they apply |
| **SEV-2** | AI down for everyone; guardian missing notifications for one app; ONDC demo broken during judging | Kill switch or rollback within the hour; runbook; status note |
| **SEV-3** | One app's reading degraded; a single user's problem; alert noise | Runbook during working hours |
