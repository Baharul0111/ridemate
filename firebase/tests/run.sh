#!/usr/bin/env bash
# Runs the Firestore rules tests (firestore.rules.test.mjs) against the Firestore emulator.
#
# With Java 21+ this is `firebase emulators:exec --only firestore` (firebase-tools 15 needs Java 21). With an older
# JDK it starts the last emulator jar that runs on Java 17 directly, pinned by SHA-256 and cached where
# firebase-tools keeps its emulators, so the tests run on either machine.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

PROJECT_ID="demo-ridemate"
HOST="127.0.0.1"
PORT="8181"  # matches ../firebase.json
JAR_VERSION="1.19.8"
JAR_SHA256="9d43599ed6151199e8d604dc87fac51218e49e5f3a48519b1ae560bbe5e3382d"
JAR_URL="https://storage.googleapis.com/firebase-preview-drop/emulator/cloud-firestore-emulator-v${JAR_VERSION}.jar"

java_major() {
  java -version 2>&1 | awk -F'"' '/version/ { split($2, v, "."); print (v[1] == "1" ? v[2] : v[1]); exit }'
}

command -v java >/dev/null 2>&1 || { echo "error: a JDK (17+) is needed for the Firestore emulator" >&2; exit 3; }
major="$(java_major)"
if [[ "${major:-0}" -ge 21 ]] && command -v firebase >/dev/null 2>&1; then
  exec firebase emulators:exec --only firestore --project "$PROJECT_ID" --config ../firebase.json "node --test"
fi

cache="${XDG_CACHE_HOME:-$HOME/.cache}/firebase/emulators"
jar="$cache/cloud-firestore-emulator-v${JAR_VERSION}.jar"
sha256() { shasum -a 256 "$1" | cut -d' ' -f1; }
if [[ ! -f "$jar" || "$(sha256 "$jar")" != "$JAR_SHA256" ]]; then
  mkdir -p "$cache"
  tmp="$(mktemp "$cache/.download.XXXXXX")"
  echo "downloading Firestore emulator v${JAR_VERSION} (~60 MB) for Java ${major}"
  curl -fsSL --retry 2 -o "$tmp" "$JAR_URL"
  if [[ "$(sha256 "$tmp")" != "$JAR_SHA256" ]]; then
    rm -f "$tmp"
    echo "error: emulator jar checksum mismatch" >&2
    exit 4
  fi
  mv "$tmp" "$jar"
fi

log="$(mktemp)"
java -Duser.language=en -jar "$jar" --host "$HOST" --port "$PORT" --project_id "$PROJECT_ID" >"$log" 2>&1 &
emulator=$!
trap 'kill "$emulator" 2>/dev/null || true; wait "$emulator" 2>/dev/null || true; rm -f "$log"' EXIT

for _ in $(seq 1 60); do
  if curl -fsS "http://$HOST:$PORT/" >/dev/null 2>&1; then break; fi
  if ! kill -0 "$emulator" 2>/dev/null; then
    echo "error: the Firestore emulator exited:" >&2
    tail -n 20 "$log" >&2
    exit 5
  fi
  sleep 1
done

FIRESTORE_EMULATOR_HOST="$HOST:$PORT" GCLOUD_PROJECT="$PROJECT_ID" node --test
