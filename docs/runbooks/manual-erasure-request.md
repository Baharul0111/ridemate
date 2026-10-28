# Runbook: someone emails asking to erase their data

The easiest path for a user is **Settings → Erase** in the app. It wipes the phone database, calls `/v1/erase`
(retried if the server is down), deletes the Firebase sign-in account, clears the crash-report id and signs out. This
runbook is for requests that arrive **by email** to ibaharul567@gmail.com, for example from someone who already
uninstalled the app. **Reply within 30 days** (aim for a week).

What Erase covers, in the privacy notice's words: *"Erase deletes your account, your RideMate data on this phone and
on our server, except metadata logs (1 year, required by law) and an anonymous usage counter (48 h, abuse
prevention)."*

## Symptoms

An email asking to delete, erase or "remove my data / my account", or a DPDP request for access or correction.

## Checks

1. **Verify it's really them.** The request should come from the same Google email they used to sign in to RideMate.
   If it comes from a different address, reply asking them to write from the sign-in address, or to use
   Settings → Erase in the app. Don't ask for ID documents.
2. **What do they want?** Erasure, a copy/summary of their data (access), correction, or to nominate someone?
3. **Did they ever sign in?** If they never signed in with Google, RideMate holds nothing on the server about them;
   on-phone data is deleted a day after each ride, and uninstalling removes the rest.

## Fix: erasure

Do the steps **in this order**. The sign-in account is deleted **last**, because the server erase needs two ids that
are only available while the account still exists: the Firebase **User UID** (ride outcome records and ONDC records
are linked to it) and the **Google account id** (the usage counter is keyed on a one-way code of it, not on the UID).

1. **Find both ids.** Firebase console → project `ridemate` → **Authentication → Users** → search their email → copy
   the **User UID** into your private erasure note. The console does not show the Google account id, so read it from
   a short-lived export (it contains every user's email, so it is deleted straight away):

   ```bash
   umask 077
   firebase auth:export /tmp/rm-users.json --format=json --project "$FIREBASE_PROJECT_ID"
   jq --arg e 'their.address@gmail.com' '.users[]
       | select((.email // "" | ascii_downcase) == ($e | ascii_downcase))
       | {uid: .localId, google_id: ([.providerUserInfo[]? | select(.providerId == "google.com") | .rawId][0])}' \
       /tmp/rm-users.json
   rm -P /tmp/rm-users.json 2>/dev/null || rm -f /tmp/rm-users.json
   ```

2. **Server data.** Ride outcome records and the usage counter are stored under one-way hashes, so they can only be
   found with the server's key. Run the **same erase routine `/v1/erase` uses**, from inside a Modal shell that has the
   server's secrets (nothing is copied to your laptop):

   ```bash
   cd ~/Desktop/ridemate/backend
   # opens a shell in a container of the web app function, with its secrets (function name: see modal_app.py)
   uv run modal shell 'modal_app.py::<web function>' --env main
   # inside the shell: call the erase routine in ridemate_api (see api/misc.py, /v1/erase) with that UID and Google
   # account id, then exit. It deletes their ONDC records and ride outcome records, and marks the usage counter
   # erased_at (the daily cleanup removes it after 48 h).
   ```

3. **Check ONDC records are gone** (they only exist if the person used ONDC). Firestore console → `ondc_txn` → filter
   `uid == <UID>`. If anything is left, delete it by hand, with its events:

   ```bash
   firebase firestore:delete "ondc_txn/<txnId>" --project "$FIREBASE_PROJECT_ID"
   # ondc_event documents: filter txnId == <txnId> in the console, then delete each
   firebase firestore:delete "ondc_event/<eventId>" --project "$FIREBASE_PROJECT_ID"
   ```

4. **Firebase sign-in account (last).** Firebase console → **Authentication → Users** → that user → ⋮ →
   **Delete account**.
5. **Owner device registration / FCM:** only exists for the owner; nothing to do for users.
6. **Crash reports:** Crashlytics reports carry no name or email (custom keys are enums only). They expire under
   Google's default retention. Nothing to do.
7. **What is kept, and why** (tell them): metadata audit logs for 1 year (required by the DPDP Rules) and the anonymous
   usage counter for 48 hours (abuse prevention). Modal's request logs with IP addresses expire after 1 day.
8. **Reply** (template below) and record the date received and date completed in your private note. Don't keep a copy
   of their data.

## Fix: access ("what do you hold about me?")

Reply with a summary: whether a sign-in account exists (email, name, photo from Google), whether any ONDC records exist,
that ride data on the phone is deleted a day after each ride, that screenshots are never stored, and the retention
list from the privacy notice. Point them to **Settings → My data** if they still have the app.

## Reply template

> Subject: Your RideMate data request
>
> Hello,
>
> Thank you for writing. On [date] we erased your RideMate data: your sign-in account, [your ONDC booking records,]
> and the records linked to your account on our server. Data on your phone is removed when you uninstall RideMate
> (ride data is also deleted automatically a day after each ride).
>
> As our privacy notice explains, we keep metadata logs for 1 year because the law requires it, and an anonymous usage
> counter for 48 hours to prevent abuse. Neither contains your name, email, places or screenshots.
>
> If you have any other question, reply to this email. If you're not satisfied with our answer, you can complain to
> the Data Protection Board of India.
>
> Baharul Islam, RideMate
> ibaharul567@gmail.com

## Rollback

Erasure can't be undone. If you erased the wrong account by mistake, tell that person promptly; treat it as an
incident if their data was disclosed to someone else ([incident plan](../incident-plan.md)).

## Who to tell

Only the person who asked. Keep your private erasure note (dates only) for your records.
