# RideMate — Setup (only what YOU must provide)
> Everything below needs your identity, your browser, your phone or your approval. Claude Code cannot do these for you. Everything else (installing tools, running commands, creating files, creating cloud resources once you're signed in) Claude Code does itself, and none of that is listed here. Finish this file once and the build never asks you for anything, except the one sign-in moment in item 3.

## Your money rule (read first)
You pay **nothing** out of your pocket. Your only funding is the **$100 Modal credit**, and every paid thing in RideMate runs on it. Everything else is free. Throughout setup:
- **Google Cloud, Firebase and Meta: never add a card.** Stay on Firebase's free Spark plan and never upgrade to Blaze. You also don't need a Meta Model API key: Meta's API has no free tier, so RideMate uses Meta's open-weights model on Modal instead.
- **Modal is the one exception.** Modal's own billing rules say "you must have a payment method on file in order to use Modal." So if Modal asks for a card, you add one, **and you set Modal's workspace spend limit to $0 (or the lowest it allows)**. At that limit Modal stops the app instead of charging you. Item 1 walks you through this and through getting it confirmed in writing.
- RideMate also meters all its own Modal spending (AI and server) and pauses AI reading well before the credit runs out.

## Checklist
- [ ] 1. Modal: spend limit at $0, billing email sent, endpoint URL + proxy token (your $100 credit ✓)
- [ ] 2. Firebase project on the free Spark plan, with Google and Anonymous sign-in turned on
- [ ] 3. Approve four browser sign-ins when Claude Code starts
- [ ] 4. Your Android phone ready for testing
- [ ] 5. Free alert service (healthchecks.io)
- [ ] 6. Your fixed choices (names, email, keystore password, credit expiry)
- [ ] 7. `.env` created and complete

---

## 1. Modal: spend limit, billing confirmation, Muse Spark Open endpoint + proxy token
**Why the project needs it:** Modal runs both RideMate's AI (Meta's open-weights **Muse Spark Open**, which reads fare screens, understands notifications and matches places) and RideMate's small server. It's the only thing that costs money, and it's paid from your $100 credit. It also qualifies you for the $10K Best Open Weight award.
**Cost:** from your $100 Modal credit (RideMate budgets it as AI $80 · server $12 · margin $8). **Time:** ~15 minutes.
**Steps:**
1. Go to https://modal.com/apps/ibaharul567/main and check you're in workspace **ibaharul567**. The **Credits** badge at the top should show your credit.
2. Open **Settings → Usage and billing**. Note: (a) whether a payment method is on file, (b) your credit balance and its **expiry date**, (c) whether the free $30/month Starter credit is active. If Modal asks for a card before you can use your credit, add one, then go straight to the next step.
3. On the same page (or **Settings → Budgets**), set the **workspace spend limit to $0** (or the lowest value it accepts). Leave the usage limit at about **$100**. Modal stops workloads at the spend limit instead of charging you. Never raise either value.
4. Send this email to **billing@modal.com** (from your Modal login email): *"Hi, my workspace ibaharul567 has a $100 hackathon credit. Please confirm: (1) with the workspace spend limit at $0, will I ever be charged beyond the credit, including for shared-endpoint token usage (Muse Spark Open)? (2) What happens when the credit reaches $0? (3) When does the credit expire? Thanks, Baharul."* Keep the reply. **Don't share the app publicly until Modal confirms.** Testing yourself and the hackathon demo are fine while you wait, because the spend limit is already protecting you.
5. Open https://modal.com/library/meta/muse-spark-open and click **Get started**. If asked, choose **Shared endpoint** for `meta-models/Muse-Spark-Open-816B-A42B` and confirm. You'll land on the endpoint's page (also under **Endpoints** in the left menu).
6. On that page, copy the **endpoint URL** from the request example (it starts with `https://`; the example calls `/v1/chat/completions` on it).
7. Click the **gear icon** (Settings, top right) → **Proxy Auth Tokens** → **New token** → name it `ridemate-prod` → create. (Use it only for the server; your laptop uses its own sign-in from item 3.)
8. Copy both values now; the secret is shown only once: the **Token ID** (starts `wk-`) and the **Token Secret** (starts `ws-`).

**You'll end with:** the spend limit set, the Modal email sent, the credit expiry date noted, an endpoint URL, a `wk-…` id and a `ws-…` secret.
**Where it goes:** `MODAL_SPARK_OPEN_URL=`, `MODAL_PROXY_TOKEN_ID=` and `MODAL_PROXY_TOKEN_SECRET=` in `.env`; the credit expiry date goes in the build prompt (not a secret).
**Official guide (if a button has moved):** https://modal.com/docs/guide/endpoints

## 2. Firebase project on the free Spark plan
**Why the project needs it:** free Google sign-in (so nobody can burn your Modal credit anonymously), a free database for usage limits and ONDC records, remote on/off switches, crash reports and push alerts to your phone. All on the no-card Spark plan.
**Cost:** free (no card). **Time:** ~10 minutes.
**Steps:**
1. Go to https://console.firebase.google.com/ → **Create a project** (or **Add project**).
2. Name it `ridemate`, note the **Project ID** shown under the name (for example `ridemate-4f2a1`), turn Google Analytics **off**, and click **Create project**.
3. **Stay on the Spark (no-cost) plan.** If any screen offers Blaze, skip it.
4. In the left menu open **Build → Authentication** → **Get started** → **Sign-in method** tab → **Google** → turn **Enable** on → choose your **support email** → **Save**.
5. Still on **Sign-in method** → **Add new provider** → **Anonymous** → **Enable** → **Save**. Anonymous is needed for **Try the demo** without Google sign-in (demo reads come from the cache or from recorded outputs; a demo screen that is not cached yet gets one metered live read, charged to the demo budget, and the answer is then cached) and for manual mode, which makes no AI calls.
6. Repeat steps 1–3 for a second project named `ridemate-dev` (for safe testing of remote config changes). No sign-in setup is needed there; Claude Code configures it.

**You'll end with:** two Project IDs (`ridemate-…` and `ridemate-dev-…`), both on Spark, with Google + Anonymous sign-in on the main one.
**Where it goes:** `FIREBASE_PROJECT_ID=` and `FIREBASE_DEV_PROJECT_ID=` in `.env`.
**Official guide:** https://firebase.google.com/docs/projects/learn-more · https://firebase.google.com/docs/auth/android/google-signin

## 3. Approve four browser sign-ins (at the very start of the build)
**Why the project needs it:** Claude Code creates the Modal app and secrets, the Firestore database, the Android app registration, the private golden-set repository and the public GitHub repository, all in **your** accounts and for free. For that it needs you signed in on your computer.
**Cost:** free. **Time:** ~5 minutes.
**Steps:** right after Claude Code installs the tools, it runs these. Each opens your browser; approve each:
1. `modal setup` → sign in to Modal → pick workspace **ibaharul567** → **Approve**.
2. `firebase login` → your Google account → **Allow**.
3. `gcloud auth login` → the same Google account → **Allow**. This is only used to create the free service account that lets RideMate's server reach Firestore; no billing is involved.
4. `gh auth login` → GitHub.com → HTTPS → login with a web browser → enter the one-time code → **Authorize** (account **Baharul0111**).

**You'll end with:** all four tools signed in. Nothing to paste.
**Where it goes:** nothing; the tools remember it.

## 4. Your Android phone, ready for testing
**Why the project needs it:** Claude Code installs and checks RideMate on a real phone over USB. Later you record real Uber, Ola and Rapido behaviour with RideMate's Recorder (developer mode). The build never touches the ride apps itself.
**Cost:** free. **Time:** ~5 minutes.
**Steps:**
1. On the phone: **Settings → About phone** → tap **Build number** 7 times until it says you're a developer.
2. **Settings → System → Developer options** → turn on **USB debugging**. On Xiaomi/Redmi/POCO also turn on **Install via USB** and **USB debugging (Security settings)**.
3. Connect the phone to your computer by USB. Tap **Allow** on the "Allow USB debugging?" prompt (tick "Always allow from this computer").
4. Make sure **Uber, Ola and Rapido** are installed and signed in with your own accounts (needed later for your real-ride checks).

**You'll end with:** the phone connected and trusted.
**Where it goes:** nothing to paste.
**Official guide:** https://developer.android.com/studio/debug/dev-options

## 5. Free alert service (healthchecks.io)
**Why the project needs it:** if Modal itself goes down or your credit stops the app, RideMate's own alerts would go quiet too. This free outside service emails you when RideMate's 15-minute check-in stops, and also carries its threshold alerts by email.
**Cost:** free Hobbyist plan. If sign-up asks for a card, stop and skip this item; alerts then come only by push to your phone. **Time:** ~5 minutes.
**Steps:**
1. Go to https://healthchecks.io/ → **Sign Up** with your email (or Google).
2. In your project, click **Add Check** → name it `ridemate-heartbeat` → set **Period** to **15 minutes** and **Grace** to **15 minutes** → **Save**. Copy its **ping URL** (looks like `https://hc-ping.com/<uuid>`).
3. Click **Add Check** again → name it `ridemate-alerts` → Period **1 day**, Grace **1 hour** → **Save** → copy its ping URL.
4. Open **Integrations** and make sure **Email** to your address is on (it's on by default).

**You'll end with:** two `https://hc-ping.com/…` URLs.
**Where it goes:** `HEALTHCHECKS_HEARTBEAT_URL=` and `HEALTHCHECKS_ALERT_URL=` in `.env`.
**Official guide:** https://healthchecks.io/docs/

## 6. Your fixed choices
**Why the project needs it:** names and contacts that appear in the app, the repository and the privacy notice. These aren't secrets, so say them in the build prompt, except the keystore password.
**Cost:** free. **Time:** ~5 minutes.
**Steps:** decide each value (defaults in brackets):
1. **App name:** [RideMate]
2. **Android package name** (can never change later): [`in.ridemate.app`]
3. **GitHub repositories:** [`Baharul0111/ridemate`, public, MIT licence] and [`Baharul0111/ridemate-golden`, **private**, for your labelled test screens]
4. **Privacy/grievance email:** [your Gmail]
5. **Budget split of your Modal credit:** [AI $80 · server $12 · margin $8]
6. **Modal credit expiry date** (from item 1, step 2): [e.g. 2027-01-31; write "unknown" if not shown]
7. **Release keystore password:** make a strong password (16+ characters) and **save it in your password manager first**. It signs every future update. After the build, also back up the keystore file Claude Code creates (`~/.ridemate/release.jks`) to your password manager or an encrypted drive.

**You'll end with:** six plain choices + one secret password.
**Where it goes:** choices 1–6 in the build prompt; the password on the `RIDEMATE_KEYSTORE_PASSWORD=` line of `.env`.

## 7. The .env file
1. In the project folder, create a file named exactly `.env`.
2. Paste this template into it, then fill every line with the values from the items above:

```env
MODAL_SPARK_OPEN_URL=
MODAL_PROXY_TOKEN_ID=
MODAL_PROXY_TOKEN_SECRET=
FIREBASE_PROJECT_ID=
FIREBASE_DEV_PROJECT_ID=
HEALTHCHECKS_HEARTBEAT_URL=
HEALTHCHECKS_ALERT_URL=
RIDEMATE_KEYSTORE_PASSWORD=
```

3. Three rules: `.env` is never committed or shared · never paste its values into any chat · Claude Code never reads or prints it; it uses the values from the environment while building (it copies the Modal token into a Modal Secret, never into the app).

---

### Later, not needed for the hackathon (each would cost money, so it's your decision then)
- **ONDC Pre-Prod access:** needs your registered company, a domain and the ONDC portal.
- **Google Play Console** ($25 one-time): only for a Play Store listing.
- **More AI credit:** if users love it and the AI budget runs out, RideMate keeps working in manual mode until you add credit or find a sponsor.
- **Android developer verification:** register before it becomes mandatory in 2027 (https://developer.android.com/developer-verification).
