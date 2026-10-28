#!/usr/bin/env bash
# check_no_secrets.sh: fail if anything secret is tracked by git.
#
# What it checks (only files git tracks or has staged, from `git ls-files`; never anything else on disk):
#   1. Forbidden paths: .env and .env.* (except *.example / *.sample / *.template), keystores (*.jks, *.keystore,
#      *.p12, *.pfx), service-account key files (service-account*.json, *-sa-key.json, keys/), and the private
#      golden set (eval/private/, ridemate-golden/, and any non-code file under a golden/ directory; golden
#      tooling such as eval/golden/generate_golden.py and README.md files stay allowed).
#   2. Forbidden content in tracked text files:
#      - PEM / OpenSSH / PGP private keys ("-----BEGIN ... PRIVATE KEY-----")
#      - Google service-account JSON ("type": "service_account" in a file that also has "private_key")
#      - Modal proxy-token parts (wk-... / ws-...) and Modal CLI token parts (ak-... / as-...)
#      - GitHub tokens (ghp_ / gho_ / ghu_ / ghs_ / ghr_ / github_pat_)
#
# It never prints a matched value: findings name the file, line and rule only, so CI logs stay clean.
#
# False positives: append the marker `secret-scan: allow` (in a comment) to that line, or add a path regex
# to tools/ci/secret_scan_allowlist.txt (one ERE per line, '#' comments). Prefer the inline marker.
#
# Usage:
#   tools/ci/check_no_secrets.sh [--include-untracked] [--root DIR]
#     --include-untracked  also scan untracked, non-ignored files (what `git add -A` would pick up)
#     --root DIR           repository to scan (default: the repo containing the current directory)
#
# As a git pre-push hook (runs before every push; git passes <remote> <url>, which are ignored):
#   ln -sf ../../tools/ci/check_no_secrets.sh .git/hooks/pre-push
# In CI: .github/workflows/repo-hygiene.yml runs it on every push and pull request.
#
# Exit codes: 0 clean, 1 findings, 2 usage or environment error.
set -euo pipefail
set +x # never trace: nothing secret is printed, but be explicit about it.

include_untracked=false
root=""

usage() { awk 'NR == 1 { next } /^#/ { sub(/^# ?/, ""); print; next } { exit }' "${BASH_SOURCE[0]}"; }

while (($#)); do
  case "$1" in
    --include-untracked) include_untracked=true ;;
    --root)
      [[ $# -ge 2 ]] || { echo "check_no_secrets: --root needs a directory" >&2; exit 2; }
      root="$2"
      shift
      ;;
    -h | --help) usage; exit 0 ;;
    --*) echo "check_no_secrets: unknown option $1" >&2; exit 2 ;;
    *) ;; # positional args from git's pre-push hook (<remote> <url>) are ignored
  esac
  shift
done

if [[ -z "$root" ]]; then
  root="$(git rev-parse --show-toplevel 2>/dev/null)" || {
    echo "check_no_secrets: not inside a git repository" >&2
    exit 2
  }
fi
cd "$root"
git rev-parse --git-dir >/dev/null 2>&1 || { echo "check_no_secrets: $root is not a git repository" >&2; exit 2; }

allowlist_file="tools/ci/secret_scan_allowlist.txt"
allow_regexes=()
if [[ -f "$allowlist_file" ]]; then
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%%#*}"
    line="${line#"${line%%[![:space:]]*}"}"
    line="${line%"${line##*[![:space:]]}"}"
    [[ -n "$line" ]] && allow_regexes+=("$line")
  done <"$allowlist_file"
fi

path_allowed() {
  local p="$1" re
  for re in "${allow_regexes[@]+"${allow_regexes[@]}"}"; do
    [[ "$p" =~ $re ]] && return 0
  done
  return 1
}

# ---- 1. Collect the files to scan (NUL-safe). --------------------------------------------------------------
files=()
while IFS= read -r -d '' f; do
  files+=("$f")
done < <(
  if $include_untracked; then
    git ls-files -z --cached --others --exclude-standard
  else
    git ls-files -z --cached
  fi
)

findings=0
report() { # report <file> <line-or-dash> <rule>
  findings=$((findings + 1))
  if [[ "$2" == "-" ]]; then
    printf 'SECRET-SCAN: %s: %s\n' "$1" "$3"
  else
    printf 'SECRET-SCAN: %s:%s: %s\n' "$1" "$2" "$3"
  fi
  if [[ "${GITHUB_ACTIONS:-}" == "true" ]]; then
    printf '::error file=%s%s::%s\n' "$1" "$([[ "$2" == "-" ]] || printf ',line=%s' "$2")" "$3"
  fi
}

# ---- 2. Forbidden paths. -------------------------------------------------------------------------------------
forbidden_path_rule() { # prints the rule name if the path is forbidden
  local p="$1" base
  base="${p##*/}"
  case "$base" in
    .env) echo "environment file (.env) must never be committed"; return ;;
    .env.example | .env.sample | .env.template | .env.*.example | .env.*.sample | .env.*.template) return ;;
    .env.*) echo "environment file ($base) must never be committed"; return ;;
    *.jks | *.keystore | *.p12 | *.pfx) echo "keystore/certificate bundle must never be committed"; return ;;
    service-account*.json | *-sa-key.json | *service_account*.json)
      echo "service-account key file must never be committed"; return ;;
  esac
  case "$p" in
    keys/* | */keys/*) echo "keys/ directory must never be committed"; return ;;
    eval/private/* | ridemate-golden/* | */ridemate-golden/*)
      echo "private golden set must stay out of the public repo (L6)"; return ;;
    golden/* | */golden/*)
      # Golden-set tooling (e.g. eval/golden/generate_golden.py) is code and may be public; anything else under
      # a golden/ directory is treated as golden-set data (screens, labels, OCR, model outputs).
      case "$base" in
        *.py | README.md) ;;
        *) echo "private golden set must stay out of the public repo (L6)"; return ;;
      esac
      ;;
  esac
}

for f in "${files[@]+"${files[@]}"}"; do
  path_allowed "$f" && continue
  rule="$(forbidden_path_rule "$f")"
  [[ -n "$rule" ]] && report "$f" - "$rule"
done

# ---- 3. Forbidden content. -----------------------------------------------------------------------------------
# Patterns are POSIX ERE. Token patterns require 16+ characters after the prefix. Real tokens are random
# base62, so a match counts only if it has a digit or mixes upper and lower case: single-case words such as
# "ws-connectionhandlers" are dropped, while a digit-free random token is still caught by its mixed case.
readonly RE_PRIVATE_KEY='-----BEGIN[A-Z ]*PRIVATE KEY( BLOCK)?-----'
readonly RE_SA_TYPE='"type"[[:space:]]*:[[:space:]]*"service_account"'
readonly RE_MODAL='(^|[^A-Za-z0-9_-])(wk|ws|ak|as)-[A-Za-z0-9]{16,}'
readonly RE_GITHUB='(^|[^A-Za-z0-9_])(gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,})'
readonly ALLOW_MARKER='secret-scan: allow'

# Files that may be scanned for content: tracked text files that still exist in the work tree.
content_files=()
for f in "${files[@]+"${files[@]}"}"; do
  [[ -f "$f" && ! -L "$f" ]] || continue
  path_allowed "$f" && continue
  # No exclusions: the scanner's own patterns never match themselves, and its tests assemble every
  # secret-looking value at runtime, so a real secret pasted into either is still caught.
  content_files+=("$f")
done

scan_rule() { # scan_rule <ERE> <rule> <needs-digit:true|false>
  local ere="$1" rule="$2" needs_digit="$3" file lineno text token
  ((${#content_files[@]})) || return 0
  # -I skips binary files; -n gives line numbers; output is "file:line:text" (text stays in memory only).
  while IFS= read -r hit; do
    file="${hit%%:*}"
    hit="${hit#*:}"
    lineno="${hit%%:*}"
    text="${hit#*:}"
    [[ "$text" == *"$ALLOW_MARKER"* ]] && continue
    if [[ "$needs_digit" == true ]]; then
      local has_token=false
      while IFS= read -r token; do
        token="${token#*-}" # the random part after the wk-/ws-/ak-/as- prefix
        if [[ "$token" =~ [0-9] && "$token" =~ [A-Za-z].*[A-Za-z] ]] ||
          [[ "$token" =~ [A-Z] && "$token" =~ [a-z] ]]; then
          has_token=true
          break
        fi
      done < <(grep -oE "$ere" <<<"$text" || true)
      $has_token || continue
    fi
    report "$file" "$lineno" "$rule"
  done < <(printf '%s\0' "${content_files[@]}" | xargs -0 grep -I -n -H -E -- "$ere" 2>/dev/null || true)
}

scan_rule "$RE_PRIVATE_KEY" "private key material" false
scan_rule "$RE_MODAL" "Modal token (wk-/ws-/ak-/as-)" true
scan_rule "$RE_GITHUB" "GitHub token" false

# Service-account JSON: the type marker plus a private_key field in the same file.
if ((${#content_files[@]})); then
  while IFS= read -r f; do
    [[ -n "$f" ]] || continue
    if grep -q -E '"private_key"[[:space:]]*:' -- "$f" 2>/dev/null &&
      ! grep -E "$RE_SA_TYPE" -- "$f" | grep -q -F "$ALLOW_MARKER"; then
      report "$f" - "Google service-account key JSON"
    fi
  done < <(printf '%s\0' "${content_files[@]}" | xargs -0 grep -I -l -E -- "$RE_SA_TYPE" 2>/dev/null || true)
fi

scope="tracked"
$include_untracked && scope="tracked + untracked"
if ((findings)); then
  printf 'check_no_secrets: %d finding(s) in %d %s files. Remove the secret, rotate it if it was ever pushed,\n' \
    "$findings" "${#files[@]}" "$scope" >&2
  printf 'and see docs/runbooks for the leaked-key procedure.\n' >&2
  exit 1
fi
printf 'check_no_secrets: OK (%d %s files scanned)\n' "${#files[@]}" "$scope"
