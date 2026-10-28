"""Build the demo allowlist and bundle the recorded demo reads for the backend (architecture §4.18 M9).

Scans android/app/src/main/assets/demo/<provider>/**/*.png, writes ridemate_api/demo_manifest.json
({"images": {"<sha256>": {"app": "demo_<provider>", "screen": "<path without .png>"}}}), and mirrors
eval/recorded/channel_a/<sha256>.json into ridemate_api/recorded/ as the fallback outputs.

Run from anywhere:  uv run python scripts/build_demo_manifest.py [--root <repo>]
"""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
DEMO_ASSETS = Path("android/app/src/main/assets/demo")
RECORDED_SRC = Path("eval/recorded/channel_a")
PACKAGE = Path("backend/ridemate_api")


def build_manifest(demo_dir: Path) -> dict[str, dict[str, str]]:
    images: dict[str, dict[str, str]] = {}
    if not demo_dir.is_dir():
        return images
    for png in sorted(demo_dir.rglob("*.png")):
        rel = png.relative_to(demo_dir)
        if len(rel.parts) < 2:
            continue  # images must live under a provider folder
        sha = hashlib.sha256(png.read_bytes()).hexdigest()
        screen = rel.relative_to(rel.parts[0]).with_suffix("").as_posix()
        images[sha] = {"app": f"demo_{rel.parts[0]}", "screen": screen}
    return dict(sorted(images.items()))


def mirror_recorded(src: Path, dst: Path, allowlist: set[str]) -> tuple[int, list[str]]:
    """Copy well-formed recorded outputs for allowlisted images; remove stale ones. Returns (copied, skipped)."""
    dst.mkdir(parents=True, exist_ok=True)
    wanted: set[str] = set()
    skipped: list[str] = []
    for path in sorted(src.glob("*.json")) if src.is_dir() else []:
        sha = path.stem.lower()
        try:
            doc = json.loads(path.read_text(encoding="utf-8"))
        except ValueError:
            skipped.append(f"{path.name}: not JSON")
            continue
        if (
            not isinstance(doc, dict)
            or doc.get("image_sha256") != sha
            or not isinstance(doc.get("read"), dict)
        ):
            skipped.append(f"{path.name}: image_sha256/read missing or mismatched")
            continue
        if sha not in allowlist:
            skipped.append(f"{path.name}: not a bundled demo image")
            continue
        shutil.copyfile(path, dst / f"{sha}.json")
        wanted.add(sha)
    for old in dst.glob("*.json"):
        if old.stem not in wanted:
            old.unlink()
    return len(wanted), skipped


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--root", type=Path, default=REPO_ROOT, help="repository root")
    args = parser.parse_args(argv)
    root: Path = args.root.resolve()

    images = build_manifest(root / DEMO_ASSETS)
    manifest_path = root / PACKAGE / "demo_manifest.json"
    manifest_path.write_text(
        json.dumps({"images": images}, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    copied, skipped = mirror_recorded(root / RECORDED_SRC, root / PACKAGE / "recorded", set(images))

    print(f"demo manifest: {len(images)} images -> {manifest_path.relative_to(root)}")
    print(f"recorded outputs: {copied} copied")
    for reason in skipped:
        print(f"  skipped {reason}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
