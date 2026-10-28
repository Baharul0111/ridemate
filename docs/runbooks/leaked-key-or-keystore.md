# Runbook: a key, token or the release keystore may have leaked

Assume a secret is leaked if it was pasted anywhere public (a commit, issue, chat, screenshot, log), if GitHub secret
scanning flags it, if spend moves without traffic, or if your laptop or password manager may be compromised. **Rotate
first, investigate second.** Where each secret lives is listed in [../secrets-inventory.md](../secrets-inventory.md).
If personal data may be involved, also start the [incident plan](../incident-plan.md) now (the CERT-In clock is
6 hours).

Setup for the commands: [README.md](README.md#setup-used-by-the-commands). After every Modal Secret change, **redeploy**
so running containers pick up the new values:

```bash
cd ~/Desktop/ridemate && ./backend/deploy.sh        # or: cd backend && uv run modal deploy modal_app.py --env main
curl -fsS "$RIDEMATE_API/readyz"; echo
```

Modal Secrets are replaced as a whole (`--force`), so always pass **every** value that secret holds, taken from your
`.env` and password manager. Never type secret values on a command line you will paste anywhere.

## Symptoms

- GitHub secret-scanning or push-protection alert on `Baharul0111/ridemate`.
- A token visible in a screenshot, video, chat or log.
- Spend on Modal's usage page that the RideMate meter didn't record (drift alert > 10%).
- Unknown keys listed on a Google service account, or Firestore activity you can't explain.
- Laptop lost or stolen; password manager compromised.

## Checks

- Which secret, since when, and where was it visible? (public repo history counts as leaked forever, even after a
  force-push.)
- Modal dashboard → Usage: any spend you can't explain? Settings → Proxy Auth Tokens: any token you don't recognise?
- `gcloud iam service-accounts keys list` (below): any key you didn't create?

## Fix, per secret

### 1. Modal production proxy token (`ridemate-model`)
1. Modal dashboard → **Settings → Proxy Auth Tokens** → **New token** → name `ridemate-prod-YYYYMMDD` → copy the Token
   ID (`wk-…`) and Token Secret (`ws-…`) into `.env` (`MODAL_PROXY_TOKEN_ID`, `MODAL_PROXY_TOKEN_SECRET`) and your
   password manager.
2. Update the secret and redeploy:

   ```bash
   set -a; . ./.env; set +a
   modal secret create --force ridemate-model --env main \
     MODAL_SPARK_OPEN_URL="$MODAL_SPARK_OPEN_URL" \
     MODAL_PROXY_TOKEN_ID="$MODAL_PROXY_TOKEN_ID" \
     MODAL_PROXY_TOKEN_SECRET="$MODAL_PROXY_TOKEN_SECRET"
   ./backend/deploy.sh
   ```

3. Check that a **live** read works (a fare read on your own phone, not a cached demo screen, which never reaches the
   model), then **delete the old token** in the dashboard. (If it's actively being abused,
   delete the old token first and accept a few minutes of manual mode.)
4. The laptop token is separate; rotate it the same way if the laptop is involved.

### 2. HMAC key for usage counters (`ridemate-core`)
The HMAC key turns Google account ids into one-way codes for quotas. A leak lets someone link codes to known accounts;
it does not let anyone sign in or spend credit. Rotate with the **dual-key** method so quotas and account ages survive:

1. Generate a new key without printing it: `export NEW_HMAC_KEY="$(openssl rand -hex 32)"`.
2. Update `ridemate-core` so `RIDEMATE_HMAC_KEY` = the new key and `RIDEMATE_HMAC_KEY_PREVIOUS` = the old key, together
   with all other `ridemate-core` values (see the inventory), using `modal secret create --force ridemate-core …`
   or `./backend/deploy.sh` with those variables set. Save both keys in your password manager.
3. Redeploy. The server reads both keys for 30 days, then the previous key can be removed (set it empty and redeploy).

### 3. Firestore service-account keys (`ridemate-web-sa`, `ridemate-ops-sa`)

```bash
P="$FIREBASE_PROJECT_ID"
gcloud iam service-accounts list --project "$P"                       # find ridemate-web@… and ridemate-ops@…
SA="ridemate-web@$P.iam.gserviceaccount.com"                           # repeat everything for the ops account
gcloud iam service-accounts keys list --iam-account "$SA" --managed-by=user --project "$P"
umask 077
gcloud iam service-accounts keys create /tmp/web-sa.json --iam-account "$SA" --project "$P"
jq -n --rawfile k /tmp/web-sa.json '{FIRESTORE_WEB_SA_JSON: $k}' > /tmp/web-sa-secret.json
modal secret create --force ridemate-web-sa --env main --from-json /tmp/web-sa-secret.json
rm -P /tmp/web-sa.json /tmp/web-sa-secret.json 2>/dev/null || rm -f /tmp/web-sa.json /tmp/web-sa-secret.json
./backend/deploy.sh
curl -fsS "$RIDEMATE_API/readyz"; echo                                # Firestore must be reachable
gcloud iam service-accounts keys delete <OLD_KEY_ID> --iam-account "$SA" --project "$P"
```

For the ops account use `ridemate-ops@…`, `FIRESTORE_OPS_SA_JSON` and the `ridemate-ops-sa` secret (which also holds
`TEMPLATE_DOCTOR_GITHUB_TOKEN`, so include it in the JSON). If your Modal CLI has no `--from-json`, pass
`NAME="$(cat file)"` pairs instead.

### 4. GitHub fine-grained token (Template Doctor)
GitHub → Settings → Developer settings → Fine-grained tokens → **Revoke**. Create a new one limited to
`Baharul0111/ridemate-golden` (and the config repo, if separate) with only contents + pull-request permissions and a
90-day expiry; update `ridemate-ops-sa` and redeploy.

### 5. healthchecks.io ping URLs (`ridemate-alerts`)
Anyone with a ping URL can send fake "up" or "fail" signals. In healthchecks.io create new checks with the same
settings (`ridemate-heartbeat`: period 15 min, grace 15 min; `ridemate-alerts`: period 1 day, grace 1 hour), update
`.env` and `ridemate-alerts`, redeploy, then delete the old checks.

### 6. Owner FCM device token (`ridemate-alerts`)
Low risk (it only lets someone send pushes to your phone). Reinstall or clear RideMate's data on your phone to get a
new token; the app re-registers it with `/v1/owner/fcm-token`.

### 7. ONDC signing and encryption keys (`ridemate-core`)
During the hackathon these sign only calls to the in-process demo seller. Generate a new Ed25519/X25519 pair, update
`ridemate-core`, redeploy. Once on ONDC Pre-Prod/Prod, also update the keys in the ONDC registry.

### 8. Laptop, CLI logins and password manager
If the laptop is lost or compromised: from another device, change the Google, GitHub and Modal passwords; sign out all
sessions; revoke Modal tokens (Settings → Tokens), GitHub tokens and OAuth apps, and gcloud/Firebase CLI grants
(<https://myaccount.google.com/permissions>); then rotate every secret above.

### 9. The release keystore (`~/.ridemate/release.jks`)
The keystore signs every update. If **lost** (not leaked): restore it from the password manager or the encrypted
offline copy. If **leaked** (someone else may have the file *and* its password), they could sign fake "updates":

1. **Warn users at once:** README status + a GitHub release note: "Only install RideMate from
   github.com/Baharul0111/ridemate/releases and check the fingerprint shown there."
2. Create a new keystore (`keytool -genkeypair -v -keystore ~/.ridemate/release-2.jks -alias ridemate -keyalg RSA
   -keysize 4096 -validity 10000`), with a new strong password saved in the password manager first.
3. Use APK Signature Scheme v3 **key rotation** so existing installs accept the new key. First build the release
   **unsigned** (run `./gradlew :app:assembleRelease` in `android/` with `RIDEMATE_KEYSTORE_PASSWORD` unset; the
   output is `app/build/outputs/apk/release/app-release-unsigned.apk`), then:

   ```bash
   apksigner rotate --out ~/.ridemate/lineage.bin \
     --old-signer --ks ~/.ridemate/release.jks --ks-key-alias ridemate \
     --new-signer --ks ~/.ridemate/release-2.jks --ks-key-alias ridemate
   apksigner sign --ks ~/.ridemate/release.jks --ks-key-alias ridemate \
     --next-signer --ks ~/.ridemate/release-2.jks --ks-key-alias ridemate \
     --lineage ~/.ridemate/lineage.bin --rotation-min-sdk-version 28 \
     --out RideMate-<version>.apk app-release-unsigned.apk
   apksigner verify --print-certs RideMate-<version>.apk
   ```

   `--rotation-min-sdk-version 28` makes phones on Android 10–12 accept the rotation too (newer `apksigner` versions
   otherwise apply it only from Android 13).
4. Publish the new certificate SHA-256 in the README and the About screen, and release the update (publishing always
   waits for Baharul's own go-ahead).
5. Back up the new keystore (password manager + encrypted offline copy). Keep the lineage file with it.
6. If rotation isn't possible, users must uninstall and reinstall. Their on-phone data is short-lived (deleted a day
   after the ride), so little is lost.

## Rollback

Rotation is not rolled back. If a new key breaks the server, check `/readyz` and the logs, fix the secret, and redeploy;
old keys stay valid until you delete them, so delete them only after the new ones are confirmed working.

## Who to tell

- **Users:** only if they need to act (keystore leak) or if personal data was exposed (follow the
  [incident plan](../incident-plan.md) user notice).
- **Modal / Google / GitHub support:** if you see misuse on their side (unexpected spend, unknown keys).
- **CERT-In and the Data Protection Board:** per the [incident plan](../incident-plan.md) when the incident qualifies.
