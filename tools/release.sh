#!/usr/bin/env bash
# release.sh: build, sign and verify the RideMate release APK on the OWNER'S MACHINE ONLY.
#
#   ############################################################################################################
#   #  PUBLISHING WAITS FOR THE OWNER'S EXPLICIT GO-AHEAD (goal.md, last paragraph).                            #
#   #  Without --publish this script never touches GitHub: it builds, verifies, writes dist/ and PRINTS the     #
#   #  `gh release create` command. Only Baharul runs it with --publish, and only after deciding to release.    #
#   ############################################################################################################
#
# The keystore (~/.ridemate/release.jks, alias "ridemate") never leaves this machine and never enters CI
# (architecture §4.2 "Release", J4: backed up in the owner's password manager plus an encrypted offline copy).
#
# Steps:
#   1. Refuses to run in CI; requires a clean git work tree (no tracked changes and no untracked files under
#      android/, which would be packaged without being committed; or --allow-dirty), the keystore and
#      RIDEMATE_KEYSTORE_PASSWORD in the environment (read by android/app/build.gradle.kts; never echoed here).
#   2. ./gradlew :app:assembleRelease (R8 + resource shrinking + lintVitalRelease), after deleting earlier
#      outputs; records commit + APK SHA-256 in android/app/build/outputs/apk/release/ridemate-release.stamp.
#   3. apksigner verify --verbose --print-certs (newest $ANDROID_HOME/build-tools/<version>/apksigner):
#      exactly one signer, APK Signature Scheme v2 or v3 verified, and the certificate SHA-256 must equal
#      EXPECTED_CERT_SHA256 below (the fingerprint published in the README and the About screen).
#   4. aapt2 dump badging / xmltree (apkanalyzer as a fallback): the APK must not be debuggable or testOnly,
#      and the manifest must not declare an AccessibilityService or BIND_ACCESSIBILITY_SERVICE (RideMate
#      never acts inside other apps).
#   5. Copies the APK to dist/RideMate-<versionName>.apk, writes dist/SHA256SUMS.txt and dist/RELEASE_NOTES.md,
#      and re-checks the checksum file.
#   6. Prints the `gh release create` command. With --publish (and a typed confirmation, or --yes) it runs it.
#
# Usage:
#   RIDEMATE_KEYSTORE_PASSWORD=... tools/release.sh [--skip-build] [--allow-dirty] [--publish [--yes]]
#     --skip-build   verify and package the APK already in android/app/build/outputs/apk/release/; publishing
#                    it requires its stamp to show this script built it from a clean checkout of HEAD
#     --allow-dirty  allow uncommitted changes (the release is then NOT reproducible from a commit; never publish)
#     --publish      after all checks pass, run `gh release create` (OWNER'S GO-AHEAD ONLY)
#     --yes          with --publish: skip the interactive "type the tag" confirmation
#
# Tip: read the password without echo and without shell history:
#   read -rs RIDEMATE_KEYSTORE_PASSWORD && export RIDEMATE_KEYSTORE_PASSWORD && tools/release.sh
set -euo pipefail
set +x # the keystore password must never be traced

readonly EXPECTED_CERT_SHA256="94:F2:DD:5F:DB:02:AD:ED:46:31:CB:0A:09:4A:0B:EE:81:05:32:C1:C8:AE:22:23:C1:7D:1C:7A:A4:22:7A:A0"
readonly GITHUB_REPO="Baharul0111/ridemate"
readonly KEYSTORE="${HOME}/.ridemate/release.jks"

die() { printf 'release: ERROR: %s\n' "$*" >&2; exit 1; }
info() { printf 'release: %s\n' "$*"; }

# ---- Pure helpers (unit-tested by tools/ci/tests/test_release_sh.py) -----------------------------------------

# normalize_hex "94:F2:dd ..." -> "94f2dd..." (lower case, hex digits only)
normalize_hex() {
  printf '%s' "$1" | tr -d ': \t\r\n' | tr '[:upper:]' '[:lower:]'
}

# format_fingerprint "94f2dd..." -> "94:F2:DD:..."
format_fingerprint() {
  local hex upper out="" i
  hex="$(normalize_hex "$1")"
  upper="$(printf '%s' "$hex" | tr '[:lower:]' '[:upper:]')"
  for ((i = 0; i < ${#upper}; i += 2)); do
    out+="${upper:i:2}"
    ((i + 2 < ${#upper})) && out+=":"
  done
  printf '%s' "$out"
}

# signer_sha256_digests <apksigner output> -> one normalized digest per signer line
signer_sha256_digests() {
  printf '%s\n' "$1" | sed -n -E 's/^Signer #[0-9]+ certificate SHA-256 digest: *([0-9A-Fa-f:]+) *$/\1/p' |
    while IFS= read -r d; do normalize_hex "$d"; printf '\n'; done
}

# apksigner_scheme_ok <apksigner output>: 0 if v2 or v3 (or v3.1) verification is "true"
apksigner_scheme_ok() {
  printf '%s\n' "$1" | grep -E -q '^Verified using v(2|3|3\.1) scheme \(APK Signature Scheme v[0-9.]+\): true$'
}

# badging_value <badging> <attr> -> value of package: attr='...' (e.g. versionName, versionCode, name)
badging_value() {
  printf '%s\n' "$1" | sed -n -E "s/^package:.* $2='([^']*)'.*$/\1/p" | head -n 1
}

# badging_is_debuggable <badging>: 0 if aapt2 reports the application as debuggable
badging_is_debuggable() {
  printf '%s\n' "$1" | grep -E -q '^application-debuggable'
}

# badging_is_test_only <badging>: 0 if the APK is marked testOnly
badging_is_test_only() {
  printf '%s\n' "$1" | grep -E -q "^testOnly='?-?1'?"
}

# manifest_is_debuggable <xmltree or apkanalyzer manifest>: 0 if android:debuggable is true
manifest_is_debuggable() {
  printf '%s\n' "$1" | grep -E -q 'android:debuggable(\([^)]*\))?(=\(type 0x12\)0x(f+|1)|="true"|=true)'
}

# manifest_has_accessibility_service <xmltree or manifest text>: 0 if the manifest declares an
# AccessibilityService (permission, intent action or metadata)
manifest_has_accessibility_service() {
  printf '%s\n' "$1" | grep -E -q 'BIND_ACCESSIBILITY_SERVICE|android\.accessibilityservice'
}

# newest_build_tools <sdk dir> -> path of the newest installed build-tools version (stable versions preferred)
newest_build_tools() {
  local sdk="$1" dir stable="" any=""
  [[ -d "$sdk/build-tools" ]] || return 1
  stable="$(find "$sdk/build-tools" -mindepth 1 -maxdepth 1 -type d -exec basename {} \; | grep -E '^[0-9]+(\.[0-9]+)*$' | sort -t. -k1,1n -k2,2n -k3,3n | tail -n 1 || true)"
  if [[ -n "$stable" ]]; then
    dir="$stable"
  else
    any="$(find "$sdk/build-tools" -mindepth 1 -maxdepth 1 -type d -exec basename {} \; | sort | tail -n 1 || true)"
    [[ -n "$any" ]] || return 1
    dir="$any"
  fi
  printf '%s/build-tools/%s' "$sdk" "$dir"
}

# stamp_value <stamp text> <key> -> the value of the first "key=value" line
stamp_value() {
  printf '%s\n' "$1" | sed -n -E "s/^$2=(.*)$/\1/p" | head -n 1
}

# stamp_matches <stamp text> <commit> <apk sha256>: 0 only if release.sh built exactly this APK from a clean
# checkout of <commit> (guards --skip-build against packaging a stale or foreign APK under the wrong commit)
stamp_matches() {
  [[ -n "$2" && "$2" != "unknown" && -n "$3" ]] || return 1
  [[ "$(stamp_value "$1" commit)" == "$2" ]] || return 1
  [[ "$(stamp_value "$1" dirty)" == "false" ]] || return 1
  [[ "$(stamp_value "$1" apk_sha256)" == "$3" ]]
}

# print_usage <script>: the leading comment block, without the '#' prefixes
print_usage() {
  awk 'NR == 1 { next } /^#/ { sub(/^# ?/, ""); print; next } { exit }' "$1"
}

# sha256_of <file> -> lower-case hex digest
sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

# ---- Environment discovery ------------------------------------------------------------------------------------

find_android_sdk() {
  local repo="$1" sdk=""
  if [[ -n "${ANDROID_HOME:-}" && -d "${ANDROID_HOME}" ]]; then
    sdk="$ANDROID_HOME"
  elif [[ -n "${ANDROID_SDK_ROOT:-}" && -d "${ANDROID_SDK_ROOT}" ]]; then
    sdk="$ANDROID_SDK_ROOT"
  elif [[ -f "$repo/android/local.properties" ]]; then
    sdk="$(sed -n -E 's/^sdk\.dir=(.*)$/\1/p' "$repo/android/local.properties" | head -n 1 | sed 's/\\:/:/g; s/\\\\/\\/g')"
  fi
  if [[ -z "$sdk" && -d "$HOME/Library/Android/sdk" ]]; then sdk="$HOME/Library/Android/sdk"; fi
  if [[ -z "$sdk" && -d "$HOME/Android/Sdk" ]]; then sdk="$HOME/Android/Sdk"; fi
  [[ -n "$sdk" && -d "$sdk" ]] || return 1
  printf '%s' "$sdk"
}

# ---- Main -----------------------------------------------------------------------------------------------------

main() {
  local skip_build=false allow_dirty=false publish=false assume_yes=false
  while (($#)); do
    case "$1" in
      --skip-build) skip_build=true ;;
      --allow-dirty) allow_dirty=true ;;
      --publish) publish=true ;;
      --yes) assume_yes=true ;;
      -h | --help) print_usage "${BASH_SOURCE[0]}"; return 0 ;;
      *) die "unknown argument: $1 (see --help)" ;;
    esac
    shift
  done
  if $assume_yes && ! $publish; then die "--yes only makes sense with --publish"; fi

  # 1. Where are we, and is this allowed here?
  if [[ "${CI:-}" == "true" || "${GITHUB_ACTIONS:-}" == "true" ]]; then
    die "release signing is local-only: the keystore never enters CI (architecture §4.2 Release)"
  fi
  local repo
  repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
  cd "$repo"
  [[ -x android/gradlew ]] || die "android/gradlew not found under $repo"

  local commit="unknown" dirty=false
  if git -C "$repo" rev-parse --git-dir >/dev/null 2>&1; then
    commit="$(git -C "$repo" rev-parse HEAD 2>/dev/null || echo unknown)"
    # Tracked changes anywhere, plus untracked (non-ignored) files under android/: those are compiled or
    # packaged into the APK (sources, assets, resources) although no commit contains them.
    if [[ -n "$(git -C "$repo" status --porcelain --untracked-files=no)" ||
      -n "$(git -C "$repo" status --porcelain --untracked-files=all -- android)" ]]; then
      dirty=true
    fi
  fi
  if $dirty; then
    $allow_dirty || die "the git work tree has uncommitted changes; commit first (or pass --allow-dirty for a local test build)"
    $publish && die "refusing to publish a build made from uncommitted changes"
    info "WARNING: building from a dirty work tree (local test build only)"
  fi

  if ! $skip_build; then
    [[ -f "$KEYSTORE" ]] || die "keystore not found at $KEYSTORE (restore it from the password manager / offline copy, J4)"
    [[ -n "${RIDEMATE_KEYSTORE_PASSWORD:-}" ]] || die "RIDEMATE_KEYSTORE_PASSWORD is not set (tip: read -rs RIDEMATE_KEYSTORE_PASSWORD && export RIDEMATE_KEYSTORE_PASSWORD)"
    export RIDEMATE_KEYSTORE_PASSWORD
  fi

  local sdk build_tools apksigner aapt2 apkanalyzer=""
  sdk="$(find_android_sdk "$repo")" || die "Android SDK not found: set ANDROID_HOME"
  build_tools="$(newest_build_tools "$sdk")" || die "no build-tools installed under $sdk/build-tools"
  apksigner="$build_tools/apksigner"
  aapt2="$build_tools/aapt2"
  [[ -x "$apksigner" ]] || die "apksigner not found at $apksigner"
  if [[ -x "$sdk/cmdline-tools/latest/bin/apkanalyzer" ]]; then apkanalyzer="$sdk/cmdline-tools/latest/bin/apkanalyzer"; fi
  [[ -x "$aapt2" || -n "$apkanalyzer" ]] || die "neither aapt2 ($aapt2) nor apkanalyzer is available"
  info "using build-tools $(basename "$build_tools")"

  # 2. Build.
  local out_dir="android/app/build/outputs/apk/release" apk apk_sum stamp_file build_commit="$commit"
  apk="$out_dir/app-release.apk"
  stamp_file="$out_dir/ridemate-release.stamp"
  if ! $skip_build; then
    # Remove earlier outputs first, so a stale signed APK can never stand in for this build's output.
    rm -f "$apk" "$out_dir/app-release-unsigned.apk" "$stamp_file"
    info "building :app:assembleRelease (this takes a few minutes)"
    (cd android && ./gradlew --no-configuration-cache :app:assembleRelease)
  fi
  if [[ ! -f "$apk" ]]; then
    if [[ -f "$out_dir/app-release-unsigned.apk" ]]; then
      die "only an UNSIGNED APK was produced: the release signing config was not applied (keystore or password missing?)"
    fi
    die "release APK not found at $apk"
  fi
  apk_sum="$(sha256_of "$apk")"
  if ! $skip_build; then
    printf 'commit=%s\ndirty=%s\napk_sha256=%s\n' "$commit" "$dirty" "$apk_sum" >"$stamp_file"
  else
    # --skip-build: only an APK this script built from a clean checkout of HEAD keeps its provenance.
    local stamp=""
    [[ -f "$stamp_file" ]] && stamp="$(cat "$stamp_file")"
    if ! stamp_matches "$stamp" "$commit" "$apk_sum"; then
      $publish && die "$apk was not built by release.sh from a clean checkout of HEAD (${commit:0:12}); run tools/release.sh without --skip-build"
      info "WARNING: $apk was not built by release.sh from a clean checkout of HEAD; its source commit is unknown (never publish it)"
      build_commit="unknown"
    fi
  fi
  if $dirty && [[ "$build_commit" != "unknown" ]]; then build_commit="${commit} plus uncommitted changes"; fi

  # 3. Signature and certificate.
  local verify_out digests count expected actual
  verify_out="$("$apksigner" verify --verbose --print-certs "$apk" 2>&1)" || {
    printf '%s\n' "$verify_out" >&2
    die "apksigner verify failed"
  }
  apksigner_scheme_ok "$verify_out" || die "APK is not verified with signature scheme v2 or v3"
  digests="$(signer_sha256_digests "$verify_out")"
  count="$(printf '%s\n' "$digests" | grep -c -E '^[0-9a-f]{64}$' || true)"
  [[ "$count" == "1" ]] || die "expected exactly one signer certificate, found $count"
  expected="$(normalize_hex "$EXPECTED_CERT_SHA256")"
  actual="$(printf '%s\n' "$digests" | grep -E '^[0-9a-f]{64}$' | head -n 1)"
  if [[ "$actual" != "$expected" ]]; then
    die "signing certificate SHA-256 $(format_fingerprint "$actual") does not match the published $EXPECTED_CERT_SHA256"
  fi
  info "signature OK: certificate SHA-256 $EXPECTED_CERT_SHA256"

  # 4. Not debuggable, not testOnly, no AccessibilityService.
  local badging="" manifest="" version_name version_code package
  if [[ -x "$aapt2" ]]; then
    badging="$("$aapt2" dump badging "$apk" 2>/dev/null)" || die "aapt2 dump badging failed"
    manifest="$("$aapt2" dump xmltree --file AndroidManifest.xml "$apk" 2>/dev/null)" || die "aapt2 dump xmltree failed"
    version_name="$(badging_value "$badging" versionName)"
    version_code="$(badging_value "$badging" versionCode)"
    package="$(badging_value "$badging" name)"
    badging_is_debuggable "$badging" && die "the release APK is debuggable"
    badging_is_test_only "$badging" && die "the release APK is marked testOnly"
  else
    manifest="$("$apkanalyzer" manifest print "$apk" 2>/dev/null)" || die "apkanalyzer manifest print failed"
    [[ "$("$apkanalyzer" manifest debuggable "$apk" 2>/dev/null)" == "false" ]] || die "the release APK is debuggable"
    version_name="$("$apkanalyzer" manifest version-name "$apk")"
    version_code="$("$apkanalyzer" manifest version-code "$apk")"
    package="$("$apkanalyzer" manifest application-id "$apk")"
  fi
  manifest_is_debuggable "$manifest" && die "the release manifest sets android:debuggable=true"
  manifest_has_accessibility_service "$manifest" && die "the manifest declares an AccessibilityService / BIND_ACCESSIBILITY_SERVICE"
  [[ "$package" == "in.ridemate.app" ]] || die "unexpected applicationId '$package'"
  [[ -n "$version_name" && -n "$version_code" ]] || die "could not read versionName/versionCode from the APK"
  [[ "$version_name" =~ ^[0-9A-Za-z.+-]+$ ]] || die "versionName '$version_name' is not safe for a file name / tag"
  info "manifest OK: $package $version_name ($version_code), not debuggable, no AccessibilityService"

  # 5. dist/
  local dist="$repo/dist" name sum
  name="RideMate-${version_name}.apk"
  mkdir -p "$dist"
  rm -f "$dist/$name" "$dist/SHA256SUMS.txt" "$dist/SHA256SUMS" "$dist/RELEASE_NOTES.md"   # SHA256SUMS: the pre-.txt name
  cp "$apk" "$dist/$name"
  sum="$(sha256_of "$dist/$name")"
  printf '%s  %s\n' "$sum" "$name" >"$dist/SHA256SUMS.txt"
  if command -v sha256sum >/dev/null 2>&1; then
    (cd "$dist" && sha256sum -c SHA256SUMS.txt >/dev/null) || die "SHA256SUMS.txt self-check failed"
  else
    (cd "$dist" && shasum -a 256 -c SHA256SUMS.txt >/dev/null) || die "SHA256SUMS.txt self-check failed"
  fi
  cat >"$dist/RELEASE_NOTES.md" <<EOF
RideMate ${version_name} (versionCode ${version_code}), built from commit \`${build_commit}\`.

**Verify before installing**
- APK SHA-256: \`${sum}\` (also in \`SHA256SUMS.txt\`: \`sha256sum -c SHA256SUMS.txt\`)
- Signing certificate SHA-256: \`${EXPECTED_CERT_SHA256}\`
  (\`apksigner verify --print-certs ${name}\`; the same fingerprint is in the README and in the app's About screen)

Android 10 or newer. Built with Muse Spark Open. RideMate never taps or types inside Uber, Ola or Rapido.
EOF
  info "wrote dist/$name, dist/SHA256SUMS.txt, dist/RELEASE_NOTES.md"
  info "APK SHA-256: $sum"

  # 6. Publish (owner's go-ahead only).
  local tag="v${version_name}"
  local -a gh_cmd=(gh release create "$tag" "dist/$name" "dist/SHA256SUMS.txt"
    --repo "$GITHUB_REPO" --title "RideMate ${version_name}" --notes-file "dist/RELEASE_NOTES.md"
    --target "$commit" --latest)
  echo
  echo "##############################################################################################"
  echo "#  PUBLISHING WAITS FOR BAHARUL'S EXPLICIT GO-AHEAD. Nothing has been uploaded."
  echo "#  To publish: push commit ${commit:0:12}, then run  tools/release.sh --skip-build --publish"
  echo "#  (the build is verified again), or run this command yourself:"
  echo "##############################################################################################"
  printf '  '
  printf '%q ' "${gh_cmd[@]}"
  printf '\n'

  if ! $publish; then
    return 0
  fi
  command -v gh >/dev/null 2>&1 || die "gh (GitHub CLI) is not installed"
  [[ "$commit" != "unknown" ]] || die "cannot publish without a git commit"
  if [[ -z "$(git -C "$repo" branch -r --contains "$commit" 2>/dev/null)" ]]; then
    die "commit ${commit:0:12} is not on any remote branch; push it before publishing"
  fi
  if gh release view "$tag" --repo "$GITHUB_REPO" >/dev/null 2>&1; then
    die "release $tag already exists; bump versionCode/versionName (J4: rollback = republish with a higher versionCode)"
  fi
  if ! $assume_yes; then
    [[ -t 0 ]] || die "--publish needs an interactive terminal for confirmation (or --yes)"
    local answer
    printf 'Type the tag %s to publish it to github.com/%s: ' "$tag" "$GITHUB_REPO"
    read -r answer
    [[ "$answer" == "$tag" ]] || die "confirmation did not match; nothing was published"
  fi
  "${gh_cmd[@]}"
  info "published $tag. Update Remote Config latest_version (version_code ${version_code}, sha256 ${sum})."
}

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
  main "$@"
fi
