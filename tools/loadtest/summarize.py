#!/usr/bin/env python3
"""Turn a k6 `--summary-export` JSON into the Markdown results table (no fare values: k6 never sees any).

    python3 tools/loadtest/summarize.py out/<run>/summary.json [--vus 100 --duration 2m ...] [--document]

`--document` emits the full docs/loadtest-results.md page (method + results); without it, just the results.
"""

from __future__ import annotations

import argparse
import json
import platform
import sys
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

ENDPOINTS = (
    ("read_screen", "POST /v1/read-screen (all)"),
    ("read_screen,path:live", "  live path (unique screenshot → stub model)"),
    ("read_screen,path:demo", "  demo path (allowlisted demo image → cache)"),
    ("reread", "POST /v1/reread"),
    ("parse_notification", "POST /v1/parse-notification"),
    ("place_pick", "POST /v1/place-pick"),
    ("config_check", "GET /v1/config-check"),
    ("event", "POST /v1/event"),
)


def ms(value: Any) -> str:
    if not isinstance(value, int | float):
        return "–"
    return f"{value / 1000:.2f} s" if value >= 1000 else f"{value:.0f} ms"


def thresholds(metrics: dict[str, Any]) -> list[tuple[str, str, bool]]:
    """(metric, expression, passed). In k6's summary export a threshold value of true means it was crossed."""
    out = []
    for name, metric in sorted(metrics.items()):
        for expr, crossed in (metric.get("thresholds") or {}).items():
            out.append((name, expr, not crossed))
    return out


def results(summary: dict[str, Any], args: argparse.Namespace) -> str:
    m = summary["metrics"]
    reqs = m.get("http_reqs", {})
    failed = m.get("http_req_failed", {})
    total = int(reqs.get("count", 0))
    errors = int(failed.get("passes", 0))  # "passes" of http_req_failed = failed requests
    sessions = m.get("session_completed", {})
    checks = m.get("checks", {})
    th = thresholds(m)
    all_pass = all(ok for _, _, ok in th) and args.k6_exit == 0

    lines = [
        (
            f"**Result: {'PASS' if all_pass else 'FAIL'}** (k6 exit code {args.k6_exit}; "
            f"{sum(ok for *_, ok in th)}/{len(th)} thresholds met)"
        ),
        "",
        "| Measure | Value |",
        "|---|---|",
        f"| Virtual users (concurrent sessions) | {args.vus} |",
        f"| Duration | {args.duration} (+ up to 60 s graceful stop) |",
        (
            f"| Sessions started / completed cleanly | {int(m.get('iterations', {}).get('count', 0))} / "
            f"{int(sessions.get('passes', 0))} |"
        ),
        f"| HTTP requests | {total} ({reqs.get('rate', 0):.1f}/s) |",
        f"| Failed requests (non-2xx) | {errors} ({100 * failed.get('value', 0):.2f} %) |",
        f"| Checks passed | {int(checks.get('passes', 0))} / {int(checks.get('passes', 0) + checks.get('fails', 0))} |",
        f"| Upload volume | {m.get('data_sent', {}).get('count', 0) / 1e6:.1f} MB |",
        f"| Server RSS at end | {args.server_rss_kb / 1024:.0f} MiB |",
        f"| Server log errors / tracebacks | {args.server_errors} |",
        "",
        "Latency per endpoint (k6 `http_req_duration`, measured on the same machine, so ≈ server time):",
        "",
        "| Endpoint | avg | median | p90 | p95 | p99 | max |",
        "|---|---|---|---|---|---|---|",
    ]
    for tag, label in ENDPOINTS:
        metric = m.get(f"http_req_duration{{endpoint:{tag}}}")
        if not metric:
            continue
        lines.append(
            f"| {label} | {ms(metric.get('avg'))} | {ms(metric.get('med'))} | {ms(metric.get('p(90)'))} | "
            f"{ms(metric.get('p(95)'))} | {ms(metric.get('p(99)'))} | {ms(metric.get('max'))} |"
        )
    model = m.get("read_server_latency_ms")
    if model:
        lines += [
            "",
            (
                f"Server-reported read latency (`latency_ms` in the response): median {ms(model.get('med'))}, "
                f"p95 {ms(model.get('p(95)'))}, max {ms(model.get('max'))}."
            ),
        ]
    sources = {
        k.split("source:", 1)[1].rstrip("}"): v for k, v in m.items() if k.startswith("read_source{source:")
    }
    if sources:
        lines += [
            "",
            "Read sources: " + ", ".join(f"{s} {int(v.get('count', 0))}" for s, v in sorted(sources.items())),
        ]
    lines += ["", "Thresholds:", "", "| Metric | Threshold | Result |", "|---|---|---|"]
    lines += [f"| `{name}` | `{expr}` | {'pass' if ok else 'FAIL'} |" for name, expr, ok in th]
    return "\n".join(lines) + "\n"


def document(summary: dict[str, Any], args: argparse.Namespace) -> str:
    now = datetime.now(UTC).strftime("%Y-%m-%d %H:%M UTC")
    head = f"""# Load test results

Architecture §4.12: *k6 at 100 concurrent sessions with a stub provider, then 20 live reads.* This page records
the stub-provider run. The 20 live reads are a separate, metered step run by the owner/coordinator
([tools/loadtest/live_reads.md](../tools/loadtest/live_reads.md)); their results are appended below when done.

## Stub-provider run ({now})

**How it was run:** `tools/loadtest/run_local.sh` (VUS={args.vus}, DURATION={args.duration}) on
{platform.system()} {platform.machine()}, Python {platform.python_version()}, one uvicorn process.

- **Server:** the real `ridemate_api.app.create_app` with the real endpoints, request validation, body limits,
  quota manager, all-spend meter, demo cache and provider router. Substituted: the model provider
  (`ridemate_api.stub_provider.StubProvider`, deterministic canned replies after a simulated latency of
  `{args.stub_latency}` ms ±20 %), auth (fixed `Bearer loadtest` token, one judge-tier Google user per session),
  the per-IP limiter (open, because every virtual user shares 127.0.0.1), hot state (`MemoryStore` instead of the
  Modal Dict), Remote Config (bundled defaults) and Firestore/ONDC (off). No call left the machine.
- **Each session (one virtual user, one new user id):** config-check → place-pick (half of sessions) → 4
  read-screens (3 unique screenshots on the live path + 1 allowlisted demo screen) → 1 reread of 2 row crops →
  2 parse-notifications → 1 event, with 1–5 s of think time between taps, then the next session.
- **SLO thresholds:** read-screen server p95 ≤ 8 s; failed requests < 1 %; every other endpoint inside the
  phone's timeout.
- **What it does not measure:** Modal cold starts, Modal Dict and Firestore latency, the real model's latency
  and variance (covered by the 20 live reads), and multi-container scaling (`max_containers=3`, `max_inputs=20`
  each in production, against one local process here).

"""
    return (
        head
        + results(summary, args)
        + "\n## Live reads (20, metered)\n\nPending: see tools/loadtest/live_reads.md.\n"
    )


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("summary", type=Path)
    ap.add_argument("--vus", default="?")
    ap.add_argument("--duration", default="?")
    ap.add_argument("--stub-latency", default="default")
    ap.add_argument("--k6-exit", type=int, default=0)
    ap.add_argument("--server-rss-kb", type=int, default=0)
    ap.add_argument("--server-errors", type=int, default=0)
    ap.add_argument("--document", action="store_true")
    args = ap.parse_args(argv)
    try:
        summary = json.loads(args.summary.read_text(encoding="utf-8"))
    except (OSError, ValueError) as e:
        print(f"summarize.py: cannot read {args.summary}: {e}", file=sys.stderr)
        return 2
    sys.stdout.write(document(summary, args) if args.document else results(summary, args))
    return 0


if __name__ == "__main__":
    sys.exit(main())
