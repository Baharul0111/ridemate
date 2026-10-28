#!/usr/bin/env python3
"""Generate the synthetic golden set (fictional providers only) for the accuracy test and live eval runs.

  cd eval && uv run python golden/generate_golden.py [--out DIR] [--count N] [--seed S] [--no-self-check]

Writes into --out (default eval/private/golden, gitignored; never commit it to the public repo, L6):
  labels.json                      contracts §6 format; "file" is relative to this directory
  images/<name>.png|jpg
  ocr/<sha256>.json                ideal synthetic OCR
  channel_a/<sha256>.json          perfect-reader Channel A output with the expected gate outcome

The self-check runs the Python reference gate over every screen and fails if a fixture contradicts its label.
"""

from __future__ import annotations

import argparse
import shutil
import sys
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from ridemate_eval.golden import DEFAULT_COUNT, DEFAULT_SEED, plan, quota_shortfalls, render  # noqa: E402
from ridemate_eval.jsonio import dump_json  # noqa: E402
from ridemate_eval.paths import DEFAULT_GOLDEN_OUT, REPO_ROOT  # noqa: E402
from ridemate_eval.reference_gate import ReferenceGate  # noqa: E402


def _refuse_tracked_output(out: Path) -> None:
    """Golden output must stay out of the public repo: allow only gitignored paths or paths outside it."""
    try:
        rel = out.resolve().relative_to(REPO_ROOT)
    except ValueError:
        return
    if not (rel.parts[:2] == ("eval", "private") or rel.parts[:1] == ("golden",)):
        raise SystemExit(
            f"refusing to write golden output inside the repo at {rel} (use eval/private/… or a path outside)"
        )


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", type=Path, default=DEFAULT_GOLDEN_OUT)
    ap.add_argument("--count", type=int, default=DEFAULT_COUNT)
    ap.add_argument("--seed", type=int, default=DEFAULT_SEED)
    ap.add_argument("--no-self-check", action="store_true", help="skip the reference-gate consistency check")
    args = ap.parse_args(argv)

    _refuse_tracked_output(args.out)
    specs = plan(args.count, args.seed)
    shortfalls = quota_shortfalls(specs)
    if shortfalls and args.count >= DEFAULT_COUNT:
        print("quota not met: " + "; ".join(shortfalls), file=sys.stderr)
        return 1

    out: Path = args.out
    for sub in ("images", "ocr", "channel_a"):
        shutil.rmtree(out / sub, ignore_errors=True)
        (out / sub).mkdir(parents=True)
    gate = None if args.no_self_check else ReferenceGate()
    labels = []
    failures = []
    for spec in specs:
        g = render(spec, args.seed)
        sha = g.label["sha256"]
        (out / g.label["file"]).write_bytes(g.data)
        (out / "ocr" / f"{sha}.json").write_bytes(dump_json(g.ocr))
        (out / "channel_a" / f"{sha}.json").write_bytes(dump_json(g.channel_a))
        labels.append(g.label)
        if gate is not None:
            outcome = gate.evaluate(g.label, g.ocr, g.channel_a["read"])
            if not ReferenceGate.satisfies(outcome, g.channel_a["expect"]):
                failures.append(f"{spec.name}: {outcome} != {g.channel_a['expect']}")
    (out / "labels.json").write_bytes(dump_json({"schema": "1.0", "screens": labels}))

    tags = Counter(t for s in labels for t in s["tags"])
    print(f"wrote {len(labels)} screens to {out}")
    print("tags: " + ", ".join(f"{t}={n}" for t, n in sorted(tags.items())))
    for line in failures:
        print(f"SELF-CHECK FAIL {line}", file=sys.stderr)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
