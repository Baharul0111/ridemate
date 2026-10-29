#!/usr/bin/env python3
"""Assemble the Firebase Remote Config REST template and the APK-bundled defaults.

  uv run --project eval python config/build_template.py --api-base-url https://…modal.run [--out FILE]
  uv run --project eval python config/build_template.py --bundled-only

Writes config/remoteconfig.template.json (or --out) with api_base_url set, and always rewrites
android/app/src/main/assets/config/<key>.json + res/xml/remote_config_defaults.xml (api_base_url empty there).
Every default is schema-validated and RE2-compiled first; nothing is written if any is invalid.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

from ridemate_config.keys import REPO_ROOT, TEMPLATE_PATH
from ridemate_config.template import BuildError, build_template, write_bundled, write_template


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--api-base-url", help="https base URL of the deployed ridemate-api web app")
    ap.add_argument("--out", type=Path, default=TEMPLATE_PATH, help="template output path")
    ap.add_argument("--bundled-only", action="store_true", help="only rewrite the APK-bundled defaults")
    args = ap.parse_args(argv)

    if not args.bundled_only and not args.api_base_url:
        ap.error("--api-base-url is required unless --bundled-only")
    try:
        template = None if args.bundled_only else build_template(args.api_base_url)
        written = write_bundled()
        if template is not None:
            write_template(template, args.out)
            written.append(args.out)
    except BuildError as e:
        print(f"error: {e}", file=sys.stderr)
        return 1
    for path in written:
        try:
            shown = path.resolve().relative_to(REPO_ROOT)
        except ValueError:
            shown = path
        print(f"wrote {shown}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
