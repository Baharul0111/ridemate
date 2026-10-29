#!/usr/bin/env bash
# Deploy RideMate's server side (architecture §4.2 "Deploy", §4.11 Secrets, §4.18 m2).
#
#   set -a; source .env; set +a          # the caller loads the values; this script never prints one
#   ./backend/deploy.sh [--env dev|main] [--skip-firebase] [--skip-secrets] [--rotate-sa-keys] [--dry-run]
#
# Idempotent: re-running converges on the same state. Steps:
#   a) Modal environments 'main' and 'dev'; Modal Secrets from the environment. RIDEMATE_HMAC_KEY and the ONDC
#      keys are generated once, in memory, and live only in the ridemate-core secret.
#   b) gcloud on FIREBASE_PROJECT_ID: free APIs only (billing is never linked); service accounts ridemate-web and
#      ridemate-ops (ridemate-web-dev / ridemate-ops-dev for --env dev, so cleaning up one environment's old keys
#      never revokes the other's) with least-privilege roles; keys created in a private temp dir only when a
#      secret lacks one (or with --rotate-sa-keys), pushed into Modal Secrets, then shredded. Key age is recorded
#      for the alert.
#   c) Firestore '(default)' in asia-south1 if absent; rules + indexes with the Firebase CLI.
#   d) modal deploy backend/modal_app.py to the chosen environment (the cron exists only in 'main').
#   e) the Remote Config template, built with the web URL from (d), to the dev project first, then prod.
#   (Modal goes before Remote Config because the template carries the URL that `modal deploy` prints.)
set -euo pipefail

usage() {
  sed -n '2,18p' "$0" | sed 's/^# \{0,1\}//'
  cat <<'EOF'

Options:
  --env dev|main      Modal environment to deploy to (default: main)
  --skip-firebase     skip gcloud, Firestore, rules/indexes and Remote Config
  --skip-secrets      don't write Modal Secrets or service-account keys
  --rotate-sa-keys    create new service-account keys even if the secrets have one; old keys are deleted after
                      the deploy
  --dry-run           print the plan only; nothing is run or written
EOF
}

ENV_NAME="main"
SKIP_FIREBASE=0
SKIP_SECRETS=0
ROTATE_SA_KEYS=0
DRY_RUN=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --env)
      [[ $# -ge 2 ]] || { echo "error: --env needs a value (dev or main)" >&2; exit 64; }
      ENV_NAME="$2"; shift 2 ;;
    --env=*) ENV_NAME="${1#--env=}"; shift ;;
    --skip-firebase) SKIP_FIREBASE=1; shift ;;
    --skip-secrets) SKIP_SECRETS=1; shift ;;
    --rotate-sa-keys) ROTATE_SA_KEYS=1; shift ;;
    --dry-run) DRY_RUN=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "error: unknown option $1 (see --help)" >&2; exit 64 ;;
  esac
done
case "$ENV_NAME" in
  dev|main) ;;
  *) echo "error: --env must be dev or main" >&2; exit 64 ;;
esac

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$SCRIPT_DIR"
REPO_DIR="$(cd "$BACKEND_DIR/.." && pwd)"
FIREBASE_CONFIG="$REPO_DIR/firebase/firebase.json"
GOOGLE_SERVICES="$REPO_DIR/android/app/google-services.json"
RC_BUILDER="$REPO_DIR/config/build_template.py"
FIRESTORE_LOCATION="asia-south1"
OWNER_EMAILS_DEFAULT="ibaharul567@gmail.com"
FREE_APIS=(firestore.googleapis.com iam.googleapis.com iamcredentials.googleapis.com
  firebaseremoteconfig.googleapis.com fcm.googleapis.com)
WEB_ROLES=(roles/datastore.user roles/cloudconfig.viewer)
OPS_ROLES=(roles/datastore.user roles/firebasecloudmessaging.admin roles/cloudconfig.viewer)
HELPER=(uv run --project "$BACKEND_DIR" --frozen --quiet python "$BACKEND_DIR/scripts/modal_secrets.py")
MODAL=(uv run --project "$BACKEND_DIR" --frozen --quiet modal)

# ---- required configuration (names only are ever printed) -------------------------------------------------------
required=(FIREBASE_PROJECT_ID)
if [[ $SKIP_SECRETS -eq 0 ]]; then
  required+=(MODAL_SPARK_OPEN_URL MODAL_PROXY_TOKEN_ID MODAL_PROXY_TOKEN_SECRET
    HEALTHCHECKS_HEARTBEAT_URL HEALTHCHECKS_ALERT_URL)
fi
if [[ $SKIP_FIREBASE -eq 0 ]]; then
  required+=(FIREBASE_DEV_PROJECT_ID)
fi
missing=()
for name in "${required[@]}"; do
  [[ -n "${!name:-}" ]] || missing+=("$name")
done
if [[ ${#missing[@]} -gt 0 ]]; then
  echo "error: missing required environment variables: ${missing[*]}" >&2
  echo "       load them first, e.g.: set -a; source .env; set +a" >&2
  exit 2
fi

# ---- helpers -----------------------------------------------------------------------------------------------------
step() { printf '\n==> %s\n' "$*"; }
note() { printf '    %s\n' "$*"; }
# Commands never carry secret values as arguments, so printing them in --dry-run is safe.
run() {
  if [[ $DRY_RUN -eq 1 ]]; then
    printf '    would run: %s\n' "$*"
  else
    "$@"
  fi
}
run_quiet() {
  if [[ $DRY_RUN -eq 1 ]]; then
    printf '    would run: %s\n' "$*"
  else
    "$@" >/dev/null
  fi
}
need() {
  command -v "$1" >/dev/null 2>&1 || { echo "error: '$1' is not installed or not on PATH" >&2; exit 3; }
}

KEY_DIRS=()
OLD_KEYS=()  # "email|key_id_to_keep" pairs, cleaned up after the deploy
shred_file() {
  local f="$1" size
  [[ -f "$f" ]] || return 0
  if command -v shred >/dev/null 2>&1; then
    shred -u "$f"
  elif command -v gshred >/dev/null 2>&1; then
    gshred -u "$f"
  else
    size=$(wc -c <"$f" | tr -d ' ')
    dd if=/dev/urandom of="$f" bs=1 count="$size" conv=notrunc 2>/dev/null || true
    rm -f "$f"
  fi
}
cleanup() {
  local d f
  for d in "${KEY_DIRS[@]+"${KEY_DIRS[@]}"}"; do
    for f in "$d"/*; do shred_file "$f"; done
    rmdir "$d" 2>/dev/null || rm -rf "$d"
  done
}
trap cleanup EXIT

if [[ $DRY_RUN -eq 1 ]]; then
  echo "DRY RUN: nothing will be created, changed or deployed."
else
  need uv
  if [[ $SKIP_FIREBASE -eq 0 ]]; then need gcloud; need firebase; fi
fi
echo "Deploying RideMate to Modal environment '$ENV_NAME' (crons only in 'main')."
echo "Required variables present: ${required[*]}"

# ---- (a) Modal environments + secrets ------------------------------------------------------------------------------
step "(a) Modal environments and secrets"
for e in main dev; do
  if [[ $DRY_RUN -eq 1 ]]; then
    note "would make sure Modal environment '$e' exists"
  else
    "${HELPER[@]}" ensure-env "$e"
  fi
done

secret_exists() { "${HELPER[@]}" exists "$1" --env "$ENV_NAME"; }

if [[ $SKIP_SECRETS -eq 1 ]]; then
  note "skipped (--skip-secrets)"
elif [[ $DRY_RUN -eq 1 ]]; then
  note "would write secret ridemate-model: MODAL_SPARK_OPEN_URL, MODAL_PROXY_TOKEN_ID, MODAL_PROXY_TOKEN_SECRET"
  note "would write secret ridemate-core: FIREBASE_PROJECT_ID, FIREBASE_WEB_API_KEY (from google-services.json:" \
    "$([[ -f "$GOOGLE_SERVICES" ]] && echo present || echo MISSING)), OWNER_EMAILS, ONDC_MODE," \
    "BUDGET_HORIZON_START, CREDIT_EXPIRY_DATE; on first creation also generate RIDEMATE_HMAC_KEY and ONDC keys"
  note "would write secret ridemate-alerts: HEALTHCHECKS_HEARTBEAT_URL, HEALTHCHECKS_ALERT_URL" \
    "(+ OWNER_FCM_TOKEN if set), keeping other keys"
else
  [[ -f "$GOOGLE_SERVICES" ]] || { echo "error: $GOOGLE_SERVICES is missing (FIREBASE_WEB_API_KEY)" >&2; exit 2; }
  "${HELPER[@]}" put ridemate-model --env "$ENV_NAME" \
    --keys MODAL_SPARK_OPEN_URL,MODAL_PROXY_TOKEN_ID,MODAL_PROXY_TOKEN_SECRET
  "${HELPER[@]}" core --env "$ENV_NAME" --google-services "$GOOGLE_SERVICES"
  "${HELPER[@]}" put ridemate-alerts --env "$ENV_NAME" --merge \
    --keys HEALTHCHECKS_HEARTBEAT_URL,HEALTHCHECKS_ALERT_URL --optional OWNER_FCM_TOKEN
fi

# ---- (b) gcloud: free APIs, service accounts, keys -----------------------------------------------------------------
P="${FIREBASE_PROJECT_ID}"
# One pair of service accounts per Modal environment: after a new key is pushed, every other key of that account
# is deleted, which must never hit a key the other environment's secrets still hold.
SA_SUFFIX=""
[[ "$ENV_NAME" == "main" ]] || SA_SUFFIX="-$ENV_NAME"
WEB_SA="ridemate-web$SA_SUFFIX"
OPS_SA="ridemate-ops$SA_SUFFIX"
ensure_sa() {  # name display-name roles...
  local name="$1" display="$2" email role
  shift 2
  email="$name@$P.iam.gserviceaccount.com"
  if [[ $DRY_RUN -eq 1 ]]; then
    note "would make sure service account $email exists"
  elif ! gcloud iam service-accounts describe "$email" --project "$P" >/dev/null 2>&1; then
    gcloud iam service-accounts create "$name" --display-name "$display" --project "$P"
  fi
  for role in "$@"; do
    run_quiet gcloud projects add-iam-policy-binding "$P" --member "serviceAccount:$email" --role "$role" \
      --condition=None --quiet
  done
}

push_sa_key() {  # name role secret var
  local name="$1" role="$2" secret="$3" var="$4" email dir file key_id
  email="$name@$P.iam.gserviceaccount.com"
  if [[ $DRY_RUN -eq 1 ]]; then
    note "would create a key for $email only if Modal secret $secret has none (or --rotate-sa-keys): in a" \
      "private temp dir, into $secret ($var + key date), then shred it; older user-managed keys deleted after the deploy"
    return
  fi
  if [[ $ROTATE_SA_KEYS -eq 0 ]] && secret_exists "$secret"; then
    note "secret $secret already holds a key; kept (use --rotate-sa-keys to rotate)"
    return
  fi
  dir="$(mktemp -d)"
  chmod 700 "$dir"
  KEY_DIRS+=("$dir")
  file="$dir/$name.json"
  (umask 077 && gcloud iam service-accounts keys create "$file" --iam-account "$email" --project "$P" >/dev/null 2>&1) \
    || { echo "error: could not create a key for $email" >&2; exit 4; }
  key_id="$("${HELPER[@]}" key-id --key-file "$file")"
  "${HELPER[@]}" sa-key "$secret" --env "$ENV_NAME" --var "$var" --role "$role" --key-file "$file"
  shred_file "$file"
  OLD_KEYS+=("$email|$key_id")
}

delete_old_keys() {
  local pair email keep id
  for pair in "${OLD_KEYS[@]+"${OLD_KEYS[@]}"}"; do
    email="${pair%%|*}"
    keep="${pair##*|}"
    while read -r id; do
      [[ -n "$id" && "$id" != "$keep" ]] || continue
      gcloud iam service-accounts keys delete "$id" --iam-account "$email" --project "$P" --quiet >/dev/null
      note "deleted old key ${id:0:8}… of $email"
    done < <(gcloud iam service-accounts keys list --iam-account "$email" --managed-by=user --project "$P" \
      --format='value(name.basename())')
  done
}

if [[ $SKIP_FIREBASE -eq 1 ]]; then
  step "(b) gcloud / (c) Firestore: skipped (--skip-firebase)"
else
  step "(b) gcloud: free APIs and service accounts in project $P (billing is never linked)"
  run gcloud services enable "${FREE_APIS[@]}" --project "$P"
  ensure_sa "$WEB_SA" "RideMate web app ($ENV_NAME)" "${WEB_ROLES[@]}"
  ensure_sa "$OPS_SA" "RideMate dispatcher ($ENV_NAME)" "${OPS_ROLES[@]}"
  if [[ $SKIP_SECRETS -eq 1 ]]; then
    note "service-account keys not touched (--skip-secrets)"
  else
    push_sa_key "$WEB_SA" web ridemate-web-sa FIRESTORE_WEB_SA_JSON
    push_sa_key "$OPS_SA" ops ridemate-ops-sa FIRESTORE_OPS_SA_JSON
  fi

  step "(c) Firestore database, rules and indexes"
  if [[ $DRY_RUN -eq 1 ]]; then
    note "would create Firestore '(default)' in $FIRESTORE_LOCATION if it doesn't exist"
  elif gcloud firestore databases describe --database='(default)' --project "$P" >/dev/null 2>&1; then
    note "Firestore '(default)' exists"
  else
    gcloud firestore databases create --database='(default)' --location="$FIRESTORE_LOCATION" \
      --type=firestore-native --project "$P"
  fi
  run firebase deploy --only firestore:rules,firestore:indexes --project "$P" --config "$FIREBASE_CONFIG" \
    --non-interactive
fi

# ---- (d) Modal deploy ----------------------------------------------------------------------------------------------
step "(d) modal deploy backend/modal_app.py --env $ENV_NAME"
# Hashed runtime pins for the images (relative paths keep the generated headers stable).
if [[ $DRY_RUN -eq 1 ]]; then
  note "would refresh backend/requirements.lock and backend/requirements-doctor.lock from the uv lock files"
else
  (cd "$BACKEND_DIR" && uv export --frozen --no-dev --no-emit-project --format requirements.txt \
    -o requirements.lock >/dev/null)
  (cd "$REPO_DIR" && uv export --project tools/template_doctor --frozen --no-dev --no-emit-project \
    --no-emit-local --prune ridemate-api --format requirements.txt -o backend/requirements-doctor.lock >/dev/null)
fi
WEB_URL="${RIDEMATE_API_BASE_URL:-}"
if [[ $DRY_RUN -eq 1 ]]; then
  note "would run: ${MODAL[*]} deploy $BACKEND_DIR/modal_app.py --env $ENV_NAME (MIN_CONTAINERS=${MIN_CONTAINERS:-0})"
else
  deploy_log="$(mktemp)"
  # RIDEMATE_DEPLOY_ENV decides whether modal_app.py defines the cron; set it explicitly rather than rely on how
  # the Modal CLI exposes --env.
  (cd "$REPO_DIR" && RIDEMATE_DEPLOY_ENV="$ENV_NAME" "${MODAL[@]}" deploy "$BACKEND_DIR/modal_app.py" \
    --env "$ENV_NAME") | tee "$deploy_log"
  url="$(grep -Eo 'https://[A-Za-z0-9.-]+\.modal\.run' "$deploy_log" | head -n 1 || true)"
  rm -f "$deploy_log"
  WEB_URL="${url:-$WEB_URL}"
fi
if [[ ${#OLD_KEYS[@]} -gt 0 ]]; then
  step "old service-account keys (the new deployment uses the new keys)"
  delete_old_keys
fi

# ---- (e) Remote Config ---------------------------------------------------------------------------------------------
if [[ $SKIP_FIREBASE -eq 1 ]]; then
  step "(e) Remote Config: skipped (--skip-firebase)"
elif [[ ! -f "$RC_BUILDER" ]]; then
  step "(e) Remote Config: skipped (config/build_template.py not found)"
elif [[ -z "$WEB_URL" && $DRY_RUN -eq 0 ]]; then
  step "(e) Remote Config: skipped (no web URL; set RIDEMATE_API_BASE_URL and re-run)"
else
  step "(e) Remote Config template: dev project first, then prod"
  # The template carries no identity list (contracts §2.7): owners stay in the OWNER_EMAILS secret and the phone asks
  # /v1/config-check; judges/testers are the server-only Firestore doc server_config/judge_allowlist.
  run uv run --project "$REPO_DIR/eval" --frozen --quiet python "$RC_BUILDER" \
    --api-base-url "${WEB_URL:-<web URL from step d>}"
  # The Firebase CLI only reads files inside the firebase/ project directory.
  run cp "$REPO_DIR/config/remoteconfig.template.json" "$REPO_DIR/firebase/remoteconfig.template.json"
  # The CLI resolves the template path against the working directory, so deploy from inside firebase/.
  (cd "$REPO_DIR/firebase" && run firebase deploy --only remoteconfig --project "$FIREBASE_DEV_PROJECT_ID" \
    --config "$FIREBASE_CONFIG" --non-interactive)
  if [[ "$ENV_NAME" == "main" ]]; then
    (cd "$REPO_DIR/firebase" && run firebase deploy --only remoteconfig --project "$P" --config "$FIREBASE_CONFIG" \
      --non-interactive)
  else
    note "prod Remote Config left unchanged for a dev deploy"
  fi
fi

step "done"
if [[ -n "$WEB_URL" ]]; then note "web URL: $WEB_URL"; fi
note "check: curl -fsS \"\$RIDEMATE_API/readyz\"; the healthchecks.io heartbeat turns green within 15 minutes"
