#!/usr/bin/env python3
"""Summarise the gate results written by the Kotlin AccuracyTest (android/core/build/accuracy/*.json).

  python eval/harness/summarize.py [--results DIR_OR_FILE ...] [--json] [--max-wrong-rate 0.005]
                                   [--min-verified-rate R]

Prints the VERIFIED rate and the wrong-shown count per suite and app. It prints counts and rates only, never a
fare, ETA or label (L6: CI logs never print fare values), so the output is safe for public CI logs. The exit
code is 1 when a threshold fails, and any wrong-shown row in a demo or synthetic suite always fails (target: 0
in demo).

Result file format (the Kotlin AccuracyTest writes it; see eval/harness/README.md):
  {"schema": "1.0", "suite": "demo|synthetic|golden|recorded", "generated_at": "…",
   "screens": [{"sha256": "…", "app": "demo_pepper", "screen_type": "fare_options",
                "rows": [{"outcome": "VERIFIED|UNREADABLE", "correct": true|false|null}],
                "driver_eta": {"outcome": "VERIFIED|SEE_IN_APP", "correct": true|false|null} | null,
                "driver_distance": {…same…} | null}]}
"""

from __future__ import annotations

import argparse
import json
import sys
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_RESULTS = REPO_ROOT / "android/core/build/accuracy"
ZERO_TOLERANCE_SUITES = {"demo", "synthetic"}


@dataclass
class Tally:
    screens: int = 0
    rows: int = 0
    verified: int = 0
    wrong_shown: int = 0
    unreadable: int = 0
    driver_facts: int = 0
    driver_verified: int = 0
    driver_wrong: int = 0

    @property
    def verified_rate(self) -> float | None:
        return self.verified / self.rows if self.rows else None

    @property
    def wrong_rate(self) -> float | None:
        return self.wrong_shown / self.rows if self.rows else None

    def add(self, other: Tally) -> None:
        for k in asdict(self):
            setattr(self, k, getattr(self, k) + getattr(other, k))


def tally_screen(screen: dict[str, Any]) -> Tally:
    t = Tally(screens=1)
    for row in screen.get("rows") or []:
        t.rows += 1
        if row.get("outcome") == "VERIFIED":
            t.verified += 1
            if row.get("correct") is False:
                t.wrong_shown += 1
        else:
            t.unreadable += 1
    for key in ("driver_eta", "driver_distance"):
        fact = screen.get(key)
        if fact:
            t.driver_facts += 1
            if fact.get("outcome") == "VERIFIED":
                t.driver_verified += 1
                if fact.get("correct") is False:
                    t.driver_wrong += 1
    return t


def result_files(paths: list[Path]) -> list[Path]:
    files: list[Path] = []
    for p in paths:
        files += sorted(p.glob("*.json")) if p.is_dir() else [p] if p.is_file() else []
    return files


def summarise(files: list[Path]) -> dict[str, dict[str, Tally]]:
    """suite → app (plus "all") → Tally. Only enum outcomes and booleans are read from the files."""
    out: dict[str, dict[str, Tally]] = {}
    for f in files:
        doc = json.loads(f.read_text(encoding="utf-8"))
        suite = str(doc.get("suite", f.stem))
        per_app = out.setdefault(suite, {"all": Tally()})
        for screen in doc.get("screens", []):
            t = tally_screen(screen)
            per_app.setdefault(str(screen.get("app", "unknown")), Tally()).add(t)
            per_app["all"].add(t)
    return out


def _pct(v: float | None) -> str:
    return "n/a" if v is None else f"{v * 100:.1f}%"


def failures(
    summary: dict[str, dict[str, Tally]], max_wrong_rate: float, min_verified_rate: float | None
) -> list[str]:
    problems = []
    for suite, per_app in summary.items():
        t = per_app["all"]
        if suite in ZERO_TOLERANCE_SUITES and (t.wrong_shown or t.driver_wrong):
            problems.append(f"{suite}: {t.wrong_shown + t.driver_wrong} wrong value(s) shown (must be 0)")
        if t.wrong_rate is not None and t.wrong_rate > max_wrong_rate:
            problems.append(f"{suite}: wrong-shown rate {_pct(t.wrong_rate)} > {_pct(max_wrong_rate)}")
        if (
            min_verified_rate is not None
            and t.verified_rate is not None
            and t.verified_rate < min_verified_rate
        ):
            problems.append(f"{suite}: VERIFIED rate {_pct(t.verified_rate)} < {_pct(min_verified_rate)}")
    return problems


def render(summary: dict[str, dict[str, Tally]]) -> str:
    lines = [
        f"{'suite':<10} {'app':<14} {'screens':>7} {'rows':>5} {'verified':>9} {'wrong':>6} {'unread':>7} "
        f"{'drv ok/wrong':>13}"
    ]
    for suite in sorted(summary):
        for app in sorted(summary[suite], key=lambda a: (a == "all", a)):
            t = summary[suite][app]
            lines.append(
                f"{suite:<10} {app:<14} {t.screens:>7} {t.rows:>5} {_pct(t.verified_rate):>9} "
                f"{t.wrong_shown:>6} {t.unreadable:>7} {f'{t.driver_verified}/{t.driver_wrong}':>13}"
            )
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--results", type=Path, nargs="*", default=[DEFAULT_RESULTS])
    ap.add_argument("--json", action="store_true", help="print the summary as JSON")
    ap.add_argument(
        "--max-wrong-rate", type=float, default=0.005, help="SLO: wrong-shown ≤ 0.5%% (architecture §4.12)"
    )
    ap.add_argument("--min-verified-rate", type=float, help="optional floor for the VERIFIED rate")
    args = ap.parse_args(argv)

    files = result_files(args.results)
    if not files:
        print("no accuracy results found (run the Kotlin AccuracyTest: ./gradlew :core:test)")
        return 0
    summary = summarise(files)
    if args.json:
        print(
            json.dumps(
                {
                    s: {a: asdict(t) | {"verified_rate": t.verified_rate} for a, t in apps.items()}
                    for s, apps in summary.items()
                },
                indent=2,
            )
        )
    else:
        print(render(summary))
    problems = failures(summary, args.max_wrong_rate, args.min_verified_rate)
    for p in problems:
        print(f"FAIL {p}")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
