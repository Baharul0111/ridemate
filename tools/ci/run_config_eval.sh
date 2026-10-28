#!/usr/bin/env bash
# run_config_eval.sh: validate the Remote Config defaults and run the eval + config test suites.
# Used by .github/workflows/config-eval.yml; runs the same way on a laptop.
#
# It adapts to what exists, and fails if there is nothing to check (a silently green job would hide a
# missing suite):
#   - eval/ uv project (eval/pyproject.toml): `uv sync --frozen` (lock required), then `uv run pytest -q`
#     when eval/tests or config/tests exist (config/README.md: the config tests run from eval/).
#   - config/validate.py: run in the eval environment (`uv run --project eval python config/validate.py`),
#     or in config's own uv project if config/pyproject.toml exists. It checks every default against its
#     schema, compiles every regex with RE2 and fails if the APK-bundled copies are stale.
#   - config/ as its own uv project (config/pyproject.toml): `uv sync` + pytest for config/tests.
#
# Public CI only: recorded and synthetic fixtures; never the private golden set, never secrets, never a model
# call (architecture L6). pytest runs with -q and no -v so fare values from fixtures are not echoed per test.
set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
cd "$repo"
command -v uv >/dev/null 2>&1 || { echo "run_config_eval: uv is required" >&2; exit 2; }

ran=0
step() { printf '\n==> %s\n' "$*"; }

sync_project() { # sync_project <dir>
  if [[ -f "$1/uv.lock" ]]; then
    (cd "$1" && uv sync --frozen)
  else
    echo "run_config_eval: $1/uv.lock is missing; CI requires a committed lock file" >&2
    exit 1
  fi
}

has_tests() { # has_tests <dir>: true if the dir has test_*.py / *_test.py files
  [[ -d "$1" ]] && [[ -n "$(find "$1" -type f \( -name 'test_*.py' -o -name '*_test.py' \) -print -quit)" ]]
}

eval_env=false
if [[ -f eval/pyproject.toml ]]; then
  step "eval: uv sync --frozen"
  sync_project eval
  eval_env=true
fi

config_env=false
if [[ -f config/pyproject.toml ]]; then
  step "config: uv sync --frozen"
  sync_project config
  config_env=true
fi

if [[ -f config/validate.py ]]; then
  if $config_env; then
    step "config: validate Remote Config defaults (config project)"
    (cd config && uv run --frozen python validate.py)
  elif $eval_env; then
    step "config: validate Remote Config defaults (eval project)"
    uv run --frozen --project eval python config/validate.py
  else
    echo "run_config_eval: config/validate.py exists but there is no uv project to run it in" >&2
    exit 1
  fi
  ran=$((ran + 1))
fi

if $config_env && has_tests config/tests; then
  step "config: pytest"
  (cd config && uv run --frozen pytest -q -p no:cacheprovider)
  ran=$((ran + 1))
fi

if $eval_env && { has_tests eval/tests || { ! $config_env && has_tests config/tests; }; }; then
  step "eval (+ config tests): pytest"
  (cd eval && uv run --frozen pytest -q -p no:cacheprovider)
  ran=$((ran + 1))
fi

if ((ran == 0)); then
  echo "run_config_eval: nothing to run (no config/validate.py and no eval/config tests found)" >&2
  exit 1
fi
printf '\nrun_config_eval: OK (%d step(s))\n' "$ran"
