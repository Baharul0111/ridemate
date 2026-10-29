# RideMate Privacy Notice

**Version 1.0 · Effective date: 28 October 2026**
[हिन्दी में पढ़ें](privacy-notice-hi.md)

This notice explains, in plain language, what personal data the RideMate app uses, why, where it goes, who processes
it, and how long it is kept. It is written for India's Digital Personal Data Protection Act, 2023 and the Digital
Personal Data Protection Rules, 2025.

## 1. Who we are

RideMate is made by **Baharul Islam**, an individual developer in **Barpeta, Assam, India**. For this notice,
he is the "Data Fiduciary" (the person who decides how your data is used).

**Grievance officer and privacy contact:** Baharul Islam, ibaharul567@gmail.com. We reply within **30 days**.

## 2. RideMate is for adults only (18+)

You must be **18 or older** to use RideMate. The app asks you to confirm this before anything else. RideMate is not
meant for children and does not knowingly process children's data. If we learn that someone under 18 is using
RideMate, we will delete their data. If you believe a child is using RideMate, please email us.

## 3. What RideMate does, in one paragraph

RideMate compares ride prices from Uber, Ola and Rapido when **you** tap its button, watches those three apps'
notifications so it can remind you to cancel extra bookings, and guides you while **you** cancel them. It never taps or
acts inside those apps. To read prices and understand unusual notifications it uses an AI model (Meta's Muse Spark
Open) running on Modal's cloud servers in the USA.

## 4. What we process, where, by whom, and for how long

| Data | Why | Where it goes | Who processes it | How long it is kept |
|---|---|---|---|---|
| **Pickup and drop places**, and places you type | To open the ride apps with your route, and to suggest addresses | Your phone; Android's address lookup (Geocoder); **your destination text is sent to Modal** so the AI can pick the right place from the suggestions | Google (Geocoder), Modal | **On your phone: deleted a day after the ride (24 hours).** Not stored by RideMate's server, except the pickup and drop points of an ONDC ride (next rows) |
| **Screenshots of ride-app fare and status screens** (masked) | To read prices, times and ride status when you tap the button | Kept in your phone's memory only; the **masked** image is sent to Modal, where Muse Spark Open reads it | Modal (USA; data may be processed outside India) | **Screenshots are never stored** by RideMate, on the phone or on our server. Modal processes them under its terms and data-processing agreement |
| **Addresses visible in uploaded screenshots** | They may appear on a ride app's screen | Masked on your phone where detected; otherwise sent with the masked image | Modal | **Processed transiently** (only while reading); not stored by RideMate |
| **Text recognised on screen** (OCR) | The second, independent price check | Your phone only | Google ML Kit, running on your phone (it may send Google anonymous usage metrics) | For the current ride session only |
| **Notification text from Uber, Ola and Rapido** (masked) | To notice a driver accepting, cancelling or arriving, and to remind you about extra bookings | Your phone. Only notifications that RideMate does not recognise, and only during a ride session, are sent to Modal for the AI to understand | Modal | For the current ride session only |
| **Your voice** (only if you tap the mic) | To let you say your destination | Android's speech recognition (on your phone or Google's service, depending on your phone's settings). **Only the text transcript is sent to Modal**, never the audio | Google, Modal | Not stored by RideMate |
| **Google account email, name and photo** (only if you sign in with Google) | To protect the free AI budget from abuse and give each person a fair share | Firebase Authentication | Google | Until you erase your account |
| **Usage counter** linked to a one-way code made from your Google account ID (not your name or email) | Fair-use limits and abuse prevention | Firestore (Mumbai region, asia-south1) and Modal's memory store | Google, Modal | Rolling; **kept 48 hours after you erase** your account, so erasing cannot be used to reset limits |
| **IP address** | Per-network rate limits against abuse | Kept in memory for rate limiting; Modal's request logs | Modal | **1 day** (Modal's log retention) |
| **Ride outcome records** (one per ride session, metadata only: which app, app version, outcome, a time bucket, Android version and simple counts such as how many prices were read; linked to a one-way code of your account; no text, no places, no prices) | To measure accuracy and reliability | Firestore | Google | **1 year**, or until you erase your account |
| **Request metadata logs** (which server function was called, when, how long it took, the outcome and its cost, with a one-way code of your account; no text, no places, no prices, no screenshots) | Security, abuse prevention and the legal duty to keep logs | Firestore | Google | **1 year (required by law), also after you erase your account** |
| **Crash reports** | To fix crashes. They never include screen or notification text | Firebase Crashlytics | Google | Google's default retention; the identifier is cleared when you erase |
| **Pickup and drop points for the open ride network (ONDC)** (only when you include ONDC rides, the demo seller included) | The seller needs them to quote and run your ride | Firestore; for the demo seller also Modal's memory store | Google, Modal | **Cleared within 1 hour if you don't book**; otherwise when the ride ends or is cancelled (the demo seller's own copy of a booked demo ride expires within 7 days) |
| **ONDC name and phone number** (only when RideMate books real rides on the ONDC network; **not collected during the hackathon demo**) | Needed by the ride seller to book your ride | Firestore | Google | 180 days |

RideMate does **not** use advertising IDs, ads, marketing analytics (Firebase Analytics is switched off) or your
contacts, and does not sell or share your data for marketing.

### Masking before upload

Before anything leaves your phone, RideMate **blurs phone numbers, OTPs and PINs, vehicle number plates, names and
your phone's status bar**. It also checks that the screen looks like a fare or ride screen; if it does not, nothing is
uploaded.

### Where your data is processed

**AI processing happens on Modal's cloud servers in the USA**, so the masked screenshots, unrecognised notification
text, destination text and voice transcripts described above may be processed outside India. RideMate's small server
also runs on Modal's cloud, which may be outside India. Firebase sign-in, crash reports and remote settings are Google
services. Firestore records are stored in Google's Mumbai region (asia-south1).

### Notifications from other apps

RideMate's notification access is limited to Uber, Ola and Rapido. Notifications from every other app are dropped
unread.

### Screen capture

RideMate looks at your screen **only when you tap its button**, and Android asks your permission for each
comparison. Capture stops right after the last price is read.

## 5. Your consent, and how to withdraw it

When you first open RideMate you see this notice and choose what to allow. Each Android permission is asked for
separately, with its reason. Signing in with Google is optional: without it, AI features are off and you type prices
yourself.

You can withdraw consent at any time, as easily as you gave it:

- turn off any permission in Android Settings, or turn off notification access for RideMate;
- sign out in RideMate;
- use **Settings → Erase** (see below); or
- email ibaharul567@gmail.com.

Withdrawing consent does not affect processing that already happened lawfully. Some features stop working without
the related permission (for example, without notification access RideMate cannot remind you to cancel).

## 6. Your rights

Under the DPDP Act you have the right to:

- **Access:** see a summary of what RideMate holds about you. Open **Settings → My data**, or email us.
- **Correction and erasure:** ask us to correct or erase your data. Use **Settings → Erase**, or email us.
- **Grievance redressal:** complain to us at ibaharul567@gmail.com. We reply within **30 days**.
- **Nominate** another person to exercise your rights if you die or become unable to do so. Email us.
- **Complain to the Data Protection Board of India** if you are not satisfied with our reply.

### What "Erase" does

**Erase deletes your account, your RideMate data on this phone and on our server, except metadata logs (1 year,
required by law) and an anonymous usage counter (48 h, abuse prevention).**

In practice, Erase: wipes RideMate's database on your phone; asks our server to delete your server data (if the server
is unreachable, the request is kept and retried); deletes your RideMate sign-in account; clears the crash-report
identifier; and signs you out. If you no longer have the app, email us and we will do it by hand.

## 7. Who processes data for RideMate

| Processor | What it does for RideMate | Where |
|---|---|---|
| **Google Firebase** (Authentication, Cloud Firestore, Remote Config, Crashlytics, Cloud Messaging) | Sign-in, fair-use counters and records, remote settings and switches, crash reports, owner alerts | Google's infrastructure; Firestore in Mumbai (asia-south1) |
| **Google ML Kit** (on device) | Text recognition on your phone, as the second price check | On your phone |
| **Android Geocoder and Android speech recognition** (Google services on your phone) | Address suggestions; turning your voice into text | On your phone or Google's service, per your phone's settings |
| **Modal** | Runs the Muse Spark Open AI model and RideMate's server | Modal's cloud, USA |

## 8. How long data is kept, in short

- Ride data on your phone: **deleted a day after the ride.**
- Screenshots: **never stored.**
- Server logs with IP addresses: **1 day.**
- Usage counter: rolling, and **48 hours** after erasure.
- Request metadata logs: **1 year** (required by law), also after erasure.
- Ride outcome records: **1 year**, or until you erase your account.
- Your Google sign-in account: until you erase it.
- ONDC pickup and drop points: **cleared within 1 hour** if you don't book; otherwise at the ride's end or cancel.
- ONDC booking details (only for real ONDC rides, not during the hackathon): 180 days.

## 9. Security

Keys and passwords live only in protected server storage, never in the app. Server data is protected by
deny-by-default rules and the app cannot write to it directly. Data in transit is encrypted (HTTPS). If a personal
data breach happens, we will inform the Data Protection Board of India and affected users without delay, as the law
requires.

## 10. Changes to this notice

If this notice changes, the version number and effective date above change. The current notice is always available
in the app (Settings → Privacy notice) and in the public repository, whose history keeps older versions. If a change
needs your fresh consent, we will ask for it before relying on it.

## 11. Contact

Baharul Islam, Barpeta, Assam, India
Grievance officer and privacy contact: **ibaharul567@gmail.com** (reply within 30 days)
