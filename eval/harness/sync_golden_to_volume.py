#!/usr/bin/env python3
"""Copy a private golden set into the Modal Volume `ridemate-golden` for live evals (owner's machine only).

  cd backend && uv run python ../eval/harness/sync_golden_to_volume.py --src ~/ridemate-golden [--dry-run]

The source is the owner's clone of the private `ridemate-golden` repo, or the output of
eval/golden/generate_golden.py. Before anything is uploaded:
- labels.json must be valid,
- every labelled image must exist and match its sha256,
- the source must not be a tracked part of the public repo (L6: golden data never enters it).
The upload uses the Modal SDK (`modal` is in the backend dev dependencies). Storing files in a Volume makes no
model calls. --dry-run prints the plan without importing modal.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
VOLUME = "ridemate-golden"
ALLOWED_SUFFIXES = {".json", ".png", ".jpg", ".jpeg"}
SUBDIRS = ("images", "ocr", "channel_a")


@dataclass(frozen=True)
class Upload:
    local: Path
    remote: str
    size: int


def _inside_public_repo_and_tracked(src: Path) -> bool:
    try:
        src.resolve().relative_to(REPO_ROOT)
    except ValueError:
        return False
    ignored = subprocess.run(
        ["git", "-C", str(REPO_ROOT), "check-ignore", "-q", str(src.resolve() / "labels.json")], check=False
    )
    return ignored.returncode != 0


def plan(src: Path, remote_dir: str = "/golden") -> list[Upload]:
    """Validate the golden directory and list the files to upload. Raises SystemExit on any problem."""
    labels_path = src / "labels.json"
    if not labels_path.is_file():
        raise SystemExit(f"{src} has no labels.json")
    if _inside_public_repo_and_tracked(src):
        raise SystemExit(
            f"{src} is inside the public repo and not gitignored; golden data must stay private (L6)"
        )
    labels = json.loads(labels_path.read_text(encoding="utf-8"))
    if labels.get("schema", "").split(".")[0] != "1" or not labels.get("screens"):
        raise SystemExit("labels.json is not a contracts §6 labels file")
    for s in labels["screens"]:
        image = src / s["file"]
        if not image.is_file():
            raise SystemExit(f"missing image {s['file']}")
        if hashlib.sha256(image.read_bytes()).hexdigest() != s["sha256"]:
            raise SystemExit(f"sha256 mismatch for {s['file']}")
    files = [labels_path] + [
        p for sub in SUBDIRS for p in sorted((src / sub).glob("*")) if p.suffix.lower() in ALLOWED_SUFFIXES
    ]
    root = remote_dir.rstrip("/")
    return [Upload(p, f"{root}/{p.relative_to(src).as_posix()}", p.stat().st_size) for p in files]


def upload(uploads: list[Upload], volume: str, environment: str | None) -> None:
    import modal  # only on the owner's machine, inside the backend environment

    vol = modal.Volume.from_name(volume, create_if_missing=True, environment_name=environment)
    with vol.batch_upload(force=True) as batch:
        for u in uploads:
            batch.put_file(str(u.local), u.remote)


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--src", type=Path, required=True, help="golden directory containing labels.json")
    ap.add_argument("--volume", default=VOLUME)
    ap.add_argument("--remote-dir", default="/golden")
    ap.add_argument("--environment", default=None, help="Modal environment (default: the profile's)")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args(argv)

    uploads = plan(args.src.expanduser(), args.remote_dir)
    total = sum(u.size for u in uploads)
    print(f"{len(uploads)} files, {total / 1e6:.1f} MB → volume {args.volume}:{args.remote_dir}")
    if args.dry_run:
        return 0
    upload(uploads, args.volume, args.environment)
    print("uploaded")
    return 0


if __name__ == "__main__":
    sys.exit(main())
