#!/usr/bin/env python3
"""Generate the fictional demo providers' screens and every fixture derived from them.

  cd eval && uv run python demo/generate_demo.py            # write into the repo
  cd eval && uv run python demo/generate_demo.py --check    # exit 1 if anything on disk differs
  cd eval && uv run python demo/generate_demo.py --out-root /tmp/x   # write the same tree elsewhere

Writes:
- android/app/src/main/assets/demo/<kestrel|pepper|tamarind>/*.png and script.json
- eval/demo_labels.json
- eval/synthetic/{ocr,ocr_noisy,channel_a,channel_a_adversarial}/

Output is byte-for-byte deterministic for the Pillow version pinned in eval/uv.lock, so the SHA-256s in
demo_labels.json (and the backend's demo allowlist built from them) stay stable.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from ridemate_eval.demo_assets import SYNTHETIC_SUBDIRS, build_demo, rebase, stale_files  # noqa: E402
from ridemate_eval.paths import REPO_ROOT, SYNTHETIC_DIR  # noqa: E402


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--check", action="store_true", help="compare with the files on disk instead of writing")
    ap.add_argument("--out-root", type=Path, help="write under this directory instead of the repo root")
    args = ap.parse_args(argv)

    build = build_demo()
    if args.check:
        stale = stale_files(build)
        for path in stale:
            print(f"STALE {path.relative_to(REPO_ROOT)}")
        print(f"{len(build.files)} files, {len(stale)} stale")
        return 1 if stale else 0

    files = build.files
    if args.out_root:
        files = rebase(files, args.out_root.resolve())
    else:
        for sub in SYNTHETIC_SUBDIRS:
            for old in (SYNTHETIC_DIR / sub).glob("*.json"):
                if old not in files:
                    old.unlink()
    for path, data in files.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        if not path.exists() or path.read_bytes() != data:
            path.write_bytes(data)
    for screen in build.labels["screens"]:
        print(f"{screen['sha256'][:12]}  {screen['file']}")
    print(f"wrote {len(files)} files")
    return 0


if __name__ == "__main__":
    sys.exit(main())
