#!/usr/bin/env bash
# Local load test (architecture §4.12): ridemate-api on this machine with the stub model provider and in-memory
# state, driven by k6 at 100 concurrent sessions for 2 minutes. Nothing here can reach Modal's model endpoint,
# Firebase or any paid service: the server runs with those credentials unset, in a non-main Modal environment,
# and the stub refuses to load otherwise.
#
#   tools/loadtest/run_local.sh                    # 100 VUs × 2m on 127.0.0.1:8787
#   VUS=10 DURATION=20s tools/loadtest/run_local.sh
#   RESULTS_MD=docs/loadtest-results.md tools/loadtest/run_local.sh   # also write the results table there
#
# Exit codes: 0 all thresholds passed · 99 a k6 threshold failed · 2 setup problem (message says what).
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
PORT="${PORT:-8787}"
VUS="${VUS:-100}"
DURATION="${DURATION:-2m}"
STUB_LATENCY_MS="${STUB_LATENCY_MS:-read=2300,reread=1500,point=1200,parse=600,place=400}"
OUT_DIR="${OUT_DIR:-$HERE/out/$(date -u +%Y%m%dT%H%M%SZ)}"
BASE_URL="http://127.0.0.1:${PORT}"

die() {
  echo "run_local.sh: $*" >&2
  exit 2
}

command -v k6 >/dev/null 2>&1 || die "k6 is not installed (brew install k6)"
command -v uv >/dev/null 2>&1 || die "uv is not installed (brew install uv)"
command -v curl >/dev/null 2>&1 || die "curl is not installed"

echo "== k6 inspect (script validation)"
k6 inspect "$HERE/session.js" >/dev/null || die "k6 inspect rejected tools/loadtest/session.js"

if curl -fsS -o /dev/null "$BASE_URL/healthz" 2>/dev/null; then
  die "something is already listening on $BASE_URL; set PORT to a free port"
fi

# The server's environment: model, Firebase, alerting and FCM credentials removed; stub on; Modal env not main.
server_env=(
  env
  -u MODAL_SPARK_OPEN_URL -u MODAL_PROXY_TOKEN_ID -u MODAL_PROXY_TOKEN_SECRET
  -u FIREBASE_PROJECT_ID -u FIREBASE_WEB_API_KEY -u FIRESTORE_WEB_SA_JSON -u FIRESTORE_OPS_SA_JSON
  -u HEALTHCHECKS_HEARTBEAT_URL -u HEALTHCHECKS_ALERT_URL -u OWNER_FCM_TOKEN -u OWNER_EMAILS
  RIDEMATE_STUB_PROVIDER=1
  MODAL_ENVIRONMENT=loadtest
  ONDC_MODE=off
  RIDEMATE_STUB_LATENCY_MS="$STUB_LATENCY_MS"
  RIDEMATE_STUB_USAGE="${RIDEMATE_STUB_USAGE:-1}"
  PYTHONPATH="$HERE"
)
uv_run=(uv run --frozen --project "$ROOT/backend")

echo "== backend app factory"
if ! "${server_env[@]}" "${uv_run[@]}" python "$HERE/loadtest_app.py"; then
  die "the backend app (ridemate_api.app.create_app with the load-test deps) is not importable yet; the k6 script itself is valid (k6 inspect passed)"
fi

mkdir -p "$OUT_DIR"
SERVER_LOG="$OUT_DIR/server.log"
echo "== starting ridemate-api (stub provider) on $BASE_URL; log: $SERVER_LOG"
"${server_env[@]}" "${uv_run[@]}" uvicorn --factory loadtest_app:build_app --app-dir "$HERE" \
  --host 127.0.0.1 --port "$PORT" --log-level warning --no-access-log --timeout-keep-alive 30 \
  >"$SERVER_LOG" 2>&1 &
SERVER_PID=$!
cleanup() {
  if kill -0 "$SERVER_PID" 2>/dev/null; then
    kill "$SERVER_PID" 2>/dev/null || true
    wait "$SERVER_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT INT TERM

for _ in $(seq 1 60); do
  if curl -fsS -o /dev/null "$BASE_URL/healthz" 2>/dev/null; then
    break
  fi
  kill -0 "$SERVER_PID" 2>/dev/null || die "the server exited during start-up; see $SERVER_LOG"
  sleep 0.5
done
curl -fsS -o /dev/null "$BASE_URL/healthz" || die "the server did not become healthy within 30 s; see $SERVER_LOG"

echo "== k6: $VUS VUs for $DURATION"
set +e
k6 run --summary-export "$OUT_DIR/summary.json" \
  -e BASE_URL="$BASE_URL" -e VUS="$VUS" -e DURATION="$DURATION" \
  "$HERE/session.js" 2>&1 | tee "$OUT_DIR/k6.txt"
K6_STATUS=${PIPESTATUS[0]}
set -e

LISTEN_PID="$(lsof -nP -t -iTCP:"$PORT" -sTCP:LISTEN 2>/dev/null | head -n 1 || true)"
SERVER_RSS_KB="$( [[ -n "$LISTEN_PID" ]] && ps -o rss= -p "$LISTEN_PID" 2>/dev/null | tr -d ' ' || true)"
SERVER_ERRORS="$(grep -c -E 'Traceback|ERROR' "$SERVER_LOG" || true)"

summary_args=(--vus "$VUS" --duration "$DURATION" --stub-latency "$STUB_LATENCY_MS" --k6-exit "$K6_STATUS"
  --server-rss-kb "${SERVER_RSS_KB:-0}" --server-errors "${SERVER_ERRORS:-0}")
python3 "$HERE/summarize.py" "$OUT_DIR/summary.json" "${summary_args[@]}" >"$OUT_DIR/results.md"
if [[ -n "${RESULTS_MD:-}" ]]; then
  python3 "$HERE/summarize.py" "$OUT_DIR/summary.json" "${summary_args[@]}" --document >"$RESULTS_MD"
  echo "== results written to $RESULTS_MD"
fi
echo "== results: $OUT_DIR/results.md"
cat "$OUT_DIR/results.md"
exit "$K6_STATUS"
