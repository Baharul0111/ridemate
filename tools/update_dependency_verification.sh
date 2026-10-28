#!/usr/bin/env bash
# update_dependency_verification.sh: regenerate android/gradle/verification-metadata.xml (Gradle dependency
# verification, architecture §4.2 CI/CD).
#
# NOTE: this downloads every dependency of every task below, runs the unit/Robolectric tests and builds the
# debug and release APKs. It takes a while and needs the network. Run it from a trusted machine and network.
#
# What it does
#   1. Moves the current metadata file aside, then runs (in android/):
#        ./gradlew --write-verification-metadata sha256 :core:test :app:assembleDebug :app:assembleRelease help
#      plus the other tasks CI runs, so that every artifact CI resolves gets a checksum:
#        :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest :core:compileKotlin
#        :app:compileDebugKotlin :app:generateLicenseReport (when the licence-report plugin is applied)
#      The previous file is restored if anything fails.
#   2. Post-processes the new file (python3, stdlib only):
#      - adds aapt2 checksums for the OTHER host platforms. AGP resolves aapt2 per host OS
#        (com.android.tools.build:aapt2:<v>:osx|linux|windows), so a file written on macOS would otherwise
#        break Linux CI. The jars are downloaded from Google's Maven (dl.google.com, the same source Gradle
#        uses), hashed locally, and cross-checked against the repository's published .sha1 when available.
#      - trusts *-sources.jar / *-javadoc.jar so IDE source downloads don't fail verification (they never
#        reach a build).
#   3. Re-runs `./gradlew --dependency-verification=strict help` to prove the file parses.
#
# How CI enforces it
#   - Gradle reads android/gradle/verification-metadata.xml automatically on EVERY build; verification is
#     strict by default: an artifact whose SHA-256 is missing or different fails the build.
#   - The Android workflows also pass --dependency-verification=strict explicitly, and android.yml fails
#     early if the file is missing.
#   - Consequence: a Dependabot (or manual) Gradle version bump fails CI until this script is re-run on that
#     branch and the new checksums are reviewed in the diff. That review is the point: new artifacts enter
#     the build only through a reviewed checksum change.
#
# Usage: tools/update_dependency_verification.sh [--keep-existing]
#   --keep-existing  merge into the existing file instead of regenerating from scratch (stale entries stay)
set -euo pipefail

keep_existing=false
while (($#)); do
  case "$1" in
    --keep-existing) keep_existing=true ;;
    -h | --help) awk 'NR == 1 { next } /^#/ { sub(/^# ?/, ""); print; next } { exit }' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "update_dependency_verification: unknown argument $1" >&2; exit 2 ;;
  esac
  shift
done

if [[ "${CI:-}" == "true" ]]; then
  echo "update_dependency_verification: run this on a developer machine, not in CI" >&2
  exit 2
fi

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
android="$repo/android"
metadata="$android/gradle/verification-metadata.xml"
backup=""
command -v python3 >/dev/null 2>&1 || { echo "python3 is required" >&2; exit 2; }
[[ -x "$android/gradlew" ]] || { echo "android/gradlew not found" >&2; exit 2; }

restore() {
  local status=$?
  if ((status != 0)) && [[ -n "$backup" && -f "$backup" ]]; then
    mv -f "$backup" "$metadata"
    echo "update_dependency_verification: FAILED (exit $status); restored the previous metadata file" >&2
  elif [[ -n "$backup" && -f "$backup" ]]; then
    rm -f "$backup"
  fi
  exit "$status"
}
trap restore EXIT

if [[ -f "$metadata" ]]; then
  backup="$(mktemp "${TMPDIR:-/tmp}/verification-metadata.XXXXXX")"
  cp "$metadata" "$backup"
  if ! $keep_existing; then rm -f "$metadata"; fi
fi

tasks=(:core:test :app:assembleDebug :app:assembleRelease help
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
  :core:compileKotlin :app:compileDebugKotlin)

cd "$android"
# The licence-report task only exists when the plugin is applied (root build.gradle.kts).
if ./gradlew -q --dependency-verification=off :app:tasks --all 2>/dev/null | grep -q '^generateLicenseReport'; then
  tasks+=(:app:generateLicenseReport)
fi

echo "update_dependency_verification: resolving ${tasks[*]}"
# --no-build-cache / --rerun-tasks: every task really runs, so every configuration it uses is resolved.
# --no-configuration-cache: verification metadata must be written from a full configuration phase.
./gradlew --write-verification-metadata sha256 --no-build-cache --rerun-tasks --no-configuration-cache \
  -PrequireFixtures=true "${tasks[@]}"

[[ -f "$metadata" ]] || { echo "Gradle did not write $metadata" >&2; exit 1; }

python3 -I - "$metadata" <<'PY'
"""Post-process verification-metadata.xml: cross-platform aapt2 + IDE sources/javadoc trust."""
import hashlib
import re
import sys
import urllib.request
import xml.etree.ElementTree as ET

NS = "https://schema.gradle.org/dependency-verification"
ET.register_namespace("", NS)
q = lambda tag: f"{{{NS}}}{tag}"
path = sys.argv[1]
tree = ET.parse(path)
root = tree.getroot()

GOOGLE_MAVEN = "https://dl.google.com/android/maven2"
PLATFORMS = ("linux", "osx", "windows")


def fetch(url: str) -> bytes:
    if not url.startswith("https://dl.google.com/"):
        raise SystemExit(f"refusing to fetch {url}")
    req = urllib.request.Request(url, headers={"User-Agent": "ridemate-update-dependency-verification"})
    with urllib.request.urlopen(req, timeout=120) as resp:  # noqa: S310 (fixed https host above)
        return resp.read()


components = root.find(q("components"))
added = 0
if components is not None:
    for comp in components.findall(q("component")):
        if comp.get("group") != "com.android.tools.build" or comp.get("name") != "aapt2":
            continue
        version = comp.get("version")
        present = {a.get("name") for a in comp.findall(q("artifact"))}
        for platform in PLATFORMS:
            name = f"aapt2-{version}-{platform}.jar"
            if name in present:
                continue
            url = f"{GOOGLE_MAVEN}/com/android/tools/build/aapt2/{version}/{name}"
            data = fetch(url)
            try:
                published_sha1 = fetch(url + ".sha1").decode().split()[0].strip().lower()
            except Exception:  # the .sha1 sidecar is optional on Google's Maven
                published_sha1 = None
            if published_sha1 and hashlib.sha1(data).hexdigest() != published_sha1:  # noqa: S324 (integrity cross-check only)
                raise SystemExit(f"{name}: downloaded bytes do not match the published .sha1")
            art = ET.SubElement(comp, q("artifact"), {"name": name})
            ET.SubElement(art, q("sha256"), {
                "value": hashlib.sha256(data).hexdigest(),
                "origin": "Added by tools/update_dependency_verification.sh (cross-platform aapt2 from dl.google.com)",
            })
            added += 1
            print(f"  + {name}")

config = root.find(q("configuration"))
if config is not None:
    trusted = config.find(q("trusted-artifacts"))
    if trusted is None:
        trusted = ET.SubElement(config, q("trusted-artifacts"))
    have = {(t.get("file"), t.get("regex")) for t in trusted.findall(q("trust"))}
    for pattern in (".*-javadoc[.]jar", ".*-sources[.]jar"):
        if (pattern, "true") not in have:
            ET.SubElement(trusted, q("trust"), {"file": pattern, "regex": "true",
                                                "reason": "IDE-only downloads; never part of a build"})

ET.indent(tree, space="   ")
tree.write(path, encoding="UTF-8", xml_declaration=True)
# ElementTree writes a single-quoted declaration; keep Gradle's usual header style.
text = open(path, encoding="utf-8").read()
text = re.sub(r"^<\?xml[^>]*\?>", '<?xml version="1.0" encoding="UTF-8"?>', text, count=1)
open(path, "w", encoding="utf-8").write(text if text.endswith("\n") else text + "\n")
print(f"post-processed {path}: {added} cross-platform aapt2 artifact(s) added")
PY

echo "update_dependency_verification: checking the new file in strict mode"
./gradlew -q --dependency-verification=strict help >/dev/null

components="$(grep -c '<component ' "$metadata" || true)"
echo "update_dependency_verification: OK: $components components in android/gradle/verification-metadata.xml"
echo "Review the diff (git diff -- android/gradle/verification-metadata.xml) before committing."
