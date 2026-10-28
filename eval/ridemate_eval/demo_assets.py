"""Builds every committed demo artefact in memory: the PNG screens, demo_labels.json, the synthetic OCR and
Channel A fixtures, and script.json. `generate_demo.py` writes them, and the tests compare them with the disk.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Any

from . import screens as sc
from .demo_script import build_script
from .fixtures import (
    adversarial_reads,
    all_verified,
    channel_a_doc,
    label_entry,
    noisy_ocr,
    ocr_doc,
    perfect_read,
    png_bytes,
    sha256,
)
from .jsonio import dump_json
from .paths import DEMO_ASSETS_DIR, DEMO_LABELS, DEMO_SCRIPT, REPO_ROOT, SYNTHETIC_DIR
from .providers import DEMO_TRIP, PEPPER, PEPPER_BETTER_DRIVER, PROVIDERS

SYNTHETIC_SUBDIRS = ("ocr", "ocr_noisy", "channel_a", "channel_a_adversarial")


@dataclass
class DemoBuild:
    files: dict[Path, bytes]  # absolute path → exact bytes
    labels: dict[str, Any]
    script: dict[str, Any]

    def screens_by_file(self) -> dict[str, dict[str, Any]]:
        return {s["file"]: s for s in self.labels["screens"]}


def demo_screens() -> list[tuple[str, sc.Screen]]:
    """(asset path relative to assets/demo, screen) for all 16 demo screens, in a stable order."""
    out: list[tuple[str, sc.Screen]] = []
    for p in PROVIDERS:
        out.append(
            (f"{p.key}/fare_options.png", sc.fare_options(p, p.fare_rows, DEMO_TRIP, selected=p.booked_row))
        )
        out.append((f"{p.key}/searching.png", sc.searching(p, DEMO_TRIP)))
        out.append((f"{p.key}/driver_assigned.png", sc.driver_assigned(p, p.driver)))
        out.append((f"{p.key}/cancel_flow.png", sc.cancel_flow(p)))
        out.append((f"{p.key}/cancelled.png", sc.cancelled(p, DEMO_TRIP)))
    better = sc.driver_assigned(PEPPER, PEPPER_BETTER_DRIVER)
    better.extra_tags.append("better_driver")
    out.append((f"{PEPPER.key}/driver_assigned_better.png", better))
    return out


def build_demo() -> DemoBuild:
    files: dict[Path, bytes] = {}
    entries: list[dict[str, Any]] = []
    for rel, screen in demo_screens():
        data = png_bytes(screen.canvas.img)
        digest = sha256(data)
        asset = DEMO_ASSETS_DIR / rel
        files[asset] = data
        label = label_entry(screen, asset.relative_to(REPO_ROOT).as_posix(), digest, DEMO_TRIP.straight_km)
        label["tags"].insert(0, "demo")
        entries.append(label)
        files[SYNTHETIC_DIR / "ocr" / f"{digest}.json"] = dump_json(ocr_doc(digest, screen.tokens))
        for variant, noisy in noisy_ocr(screen).items():
            doc = ocr_doc(digest, noisy["tokens"]) | {"variant": variant, "expect": noisy["expect"]}
            files[SYNTHETIC_DIR / "ocr_noisy" / f"{digest}.{variant}.json"] = dump_json(doc)
        read = perfect_read(label, [r.struck_token for r in screen.rows])
        files[SYNTHETIC_DIR / "channel_a" / f"{digest}.json"] = dump_json(
            channel_a_doc(digest, read, all_verified(label))
        )
        for variant, adv in adversarial_reads(screen, label, read).items():
            doc = channel_a_doc(digest, adv["read"], adv["expect"]) | {"variant": variant}
            files[SYNTHETIC_DIR / "channel_a_adversarial" / f"{digest}.{variant}.json"] = dump_json(doc)
    labels = {"schema": "1.0", "screens": entries}
    files[DEMO_LABELS] = dump_json(labels)
    script = build_script()
    files[DEMO_SCRIPT] = dump_json(script)
    return DemoBuild(files, labels, script)


def rebase(files: dict[Path, bytes], root: Path) -> dict[Path, bytes]:
    """The same artefacts under another repo root (used to regenerate into a scratch directory)."""
    return {root / path.relative_to(REPO_ROOT): data for path, data in files.items()}


def stale_files(build: DemoBuild) -> list[Path]:
    """Committed artefacts that differ from a fresh build, plus synthetic files a fresh build would delete."""
    stale = [path for path, data in build.files.items() if not path.exists() or path.read_bytes() != data]
    for sub in SYNTHETIC_SUBDIRS:
        stale += [p for p in (SYNTHETIC_DIR / sub).glob("*.json") if p not in build.files]
    return stale
