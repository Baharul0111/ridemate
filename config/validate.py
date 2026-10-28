#!/usr/bin/env python3
"""Validate every Remote Config default against its schema and compile every regex with RE2.

Usage (from the repo root): uv run --project eval python config/validate.py
Exit code 0 = all defaults valid and the APK-bundled copies are up to date.
"""

from __future__ import annotations

import sys

from ridemate_config.keys import KEYS, REPO_ROOT
from ridemate_config.template import stale_bundled_files
from ridemate_config.validation import validate_all


def main() -> int:
    results = validate_all()
    failed = False
    for name, errors in results.items():
        if errors:
            failed = True
            for err in errors:
                print(f"FAIL {err}")
        else:
            print(f"ok   {name}")
    stale = stale_bundled_files()
    for path in stale:
        print(f"STALE {path.relative_to(REPO_ROOT)} (run config/build_template.py --bundled-only)")
    print(
        f"{len(KEYS)} keys, {sum(1 for e in results.values() if e)} invalid, {len(stale)} stale bundled files"
    )
    return 1 if failed or stale else 0


if __name__ == "__main__":
    sys.exit(main())
