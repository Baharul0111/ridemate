#!/usr/bin/env python3
"""Record real Channel A (Muse Spark Open) reads of labelled screens as <out-dir>/<sha>.json.

The demo labels record into eval/recorded/channel_a/; any other (private) labels file records next to itself,
and reads of private screens are never written where the public repo's git would track them (L6).

Every live call is metered by RideMate's all-spend meter, which is never bypassed:
- http mode: calls the deployed API's POST /v1/read-screen; the server meters and caps every call.
    RIDEMATE_ID_TOKEN=<Firebase ID token> python eval/harness/record_channel_a.py --api-base-url URL
- gateway mode: imports the backend in-process (ridemate_api.deps_prod + router) with the production spend
  meter under Purpose.EVAL, so the meter itself enforces the evals sub-budget and the $0.50 per-run cap, and
  aborts if a call does not show up on the meter. Run it inside the backend environment:
    cd backend && uv run python ../eval/harness/record_channel_a.py --mode gateway

Both modes pause before the next call could pass --max-usd (default and maximum $0.50, the per-run eval cap
from architecture §4.2), judged by the worst-case cost of that call computed from the backend's own settings.
A pause is a clean stop (exit 0): rerunning the same command resumes, because screens that already have a
recording are skipped. --dry-run lists what would be read and the worst-case spend, with no network and no
token. The token is read from the environment only and never printed or written.
"""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
from dataclasses import dataclass, field
from datetime import UTC, datetime
from pathlib import Path
from types import ModuleType
from typing import Any, Protocol

EVAL_DIR = Path(__file__).resolve().parent.parent
REPO_ROOT = EVAL_DIR.parent
DEFAULT_LABELS = EVAL_DIR / "demo_labels.json"
DEFAULT_OUT = EVAL_DIR / "recorded/channel_a"
TOKEN_ENV = "RIDEMATE_ID_TOKEN"
BACKEND_SETTINGS_FILE = REPO_ROOT / "backend/ridemate_api/settings.py"
TIMESTAMP_FORMAT = "%Y-%m-%dT%H:%M:%SZ"  # recorded_at; ISO-8601 UTC, so strings order like the times


def _backend_settings() -> ModuleType:
    """The backend's settings module: the single source of prices, output ceilings and budgets (settings.py).

    Gateway mode runs inside the backend environment, so this is the very module the router reads. Elsewhere (dry
    runs, http mode, the eval tests) settings.py is loaded from this checkout; it needs only the standard library.
    """
    try:
        from ridemate_api import settings
    except ImportError:
        pass
    else:
        return settings
    name = "_ridemate_backend_settings"
    if name in sys.modules:
        return sys.modules[name]
    spec = importlib.util.spec_from_file_location(name, BACKEND_SETTINGS_FILE)
    if spec is None or spec.loader is None or not BACKEND_SETTINGS_FILE.is_file():
        raise SystemExit(f"backend settings not found at {BACKEND_SETTINGS_FILE}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module  # dataclasses resolve the module's annotations through sys.modules
    spec.loader.exec_module(module)
    return module


_SETTINGS = _backend_settings()
# Worst case for one read, from the backend's constants: every attempt may send up to 6,000 uncached input tokens
# (masked 1080×2400 screenshot + prompt, above the router's own estimate) and use the whole read output ceiling
# MAX_TOKENS_READ (L4, reasoning tokens included) at Muse Spark Open prices (architecture §4.10), and the router
# may make two attempts (one retry inside the tap budget, architecture §4.2).
WORST_CASE_INPUT_TOKENS = 6000
WORST_CASE_OUTPUT_TOKENS: int = _SETTINGS.MAX_TOKENS_READ
WORST_CASE_ATTEMPTS = 2
PRICE_INPUT_PER_M: float = _SETTINGS.PRICE_INPUT_PER_M
PRICE_OUTPUT_PER_M: float = _SETTINGS.PRICE_OUTPUT_PER_M
WORST_CASE_USD = (
    WORST_CASE_ATTEMPTS
    * (WORST_CASE_INPUT_TOKENS * PRICE_INPUT_PER_M + WORST_CASE_OUTPUT_TOKENS * PRICE_OUTPUT_PER_M)
    / 1_000_000
)
# The per-run eval cap the meter enforces on every `eval-run:<run id>` (architecture §4.2).
PER_RUN_CAP_USD: float = _SETTINGS.SUB_BUDGET_EVAL_RUN_USD
STOP_CODES = {
    "ai_paused",
    "quota_exhausted",
    "meter_unavailable",
    "ai_unavailable",
    "auth_required",
    "auth_invalid",
    "google_required",
    "blocked",
    "rate_limited",
}


@dataclass(frozen=True)
class Job:
    sha256: str
    image: Path
    app: str
    frame_w: int
    frame_h: int

    @property
    def media_type(self) -> str:
        return "image/jpeg" if self.image.suffix.lower() in (".jpg", ".jpeg") else "image/png"

    @property
    def meta(self) -> dict[str, Any]:
        return {
            "app": self.app,
            "frame_w": self.frame_w,
            "frame_h": self.frame_h,
            "demo": self.app.startswith("demo_"),
        }


@dataclass
class ReadResult:
    read: dict[str, Any]
    model: str
    source: str
    request_id: str
    # Server-side time: the model call as the router timed it (gateway) or the API's own `latency_ms` (http). None
    # when the server did not say. Never the harness's wall time, which includes the laptop's network and Modal
    # Dict round trips (a 45-60 s wall time is not a 45-60 s model read).
    latency_ms: int | None
    usage: dict[str, int] | None
    cost_usd: float | None
    # What this call cost the run's budget, when more than `cost_usd` is known (e.g. a retried first attempt).
    charged_usd: float | None = None
    # The harness's own wall time for the whole call, kept apart from latency_ms.
    wall_ms: int | None = None

    @property
    def run_charge(self) -> float | None:
        return self.charged_usd if self.charged_usd is not None else self.cost_usd


class StopRun(Exception):
    """The service refused (pause, quota, auth, meter): stop instead of retrying."""


class Reader(Protocol):
    def read(self, job: Job, image: bytes) -> ReadResult: ...


def load_jobs(labels_path: Path) -> list[Job]:
    """Screens from a contracts §6 labels file; `file` resolves against the labels' folder, then the repo."""
    labels = json.loads(labels_path.read_text(encoding="utf-8"))
    jobs = []
    for s in labels["screens"]:
        candidates = [labels_path.parent / s["file"], REPO_ROOT / s["file"]]
        image = next((p for p in candidates if p.is_file()), None)
        if image is None:
            raise SystemExit(f"image not found for {s['sha256'][:12]}: {s['file']}")
        jobs.append(Job(s["sha256"], image, s["app"], s["frame_w"], s["frame_h"]))
    return jobs


def _tracked_in_public_repo(path: Path) -> bool:
    """True when `path` is inside this public repo and git would not ignore it (so it could be committed)."""
    try:
        path.resolve().relative_to(REPO_ROOT)
    except ValueError:
        return False
    ignored = subprocess.run(
        ["git", "-C", str(REPO_ROOT), "check-ignore", "-q", str(path.resolve())], check=False
    )
    return ignored.returncode != 0


def default_out_dir(labels_path: Path) -> Path:
    """The public demo labels record into eval/recorded/channel_a. Any other labels file (a private golden
    set) records next to itself, so its reads never land in the public repo by default."""
    if labels_path.resolve() == DEFAULT_LABELS.resolve():
        return DEFAULT_OUT
    return labels_path.resolve().parent / "recorded/channel_a"


def check_out_dir(jobs: list[Job], out_dir: Path) -> None:
    """L6: reads of private screens (outside the repo, or gitignored) must not be written where git tracks."""
    if not _tracked_in_public_repo(out_dir / "probe.json"):
        return
    private = [j for j in jobs if not _tracked_in_public_repo(j.image)]
    if private:
        raise SystemExit(
            f"{len(private)} screen(s) are private (e.g. {private[0].image}); their recordings must not go "
            f"into the public repo at {out_dir} (L6). Pass an --out-dir outside it, e.g. in the golden repo."
        )


def _multipart(meta: dict[str, Any], image: bytes, media_type: str) -> tuple[bytes, str]:
    boundary = f"ridemate-{uuid.uuid4().hex}"
    ext = "jpg" if media_type == "image/jpeg" else "png"
    parts = [
        f'--{boundary}\r\nContent-Disposition: form-data; name="meta"\r\n'
        "Content-Type: application/json\r\n\r\n".encode()
        + json.dumps(meta).encode()
        + b"\r\n",
        f'--{boundary}\r\nContent-Disposition: form-data; name="image"; filename="screen.{ext}"\r\n'
        f"Content-Type: {media_type}\r\n\r\n".encode()
        + image
        + b"\r\n",
        f"--{boundary}--\r\n".encode(),
    ]
    return b"".join(parts), f"multipart/form-data; boundary={boundary}"


class HttpReader:
    """POST /v1/read-screen on the deployed API (contracts §1.3). Cost comes from the owner budget in
    /v1/config-check (before/after difference) when the token belongs to an owner; otherwise it is unknown."""

    def __init__(self, base_url: str, token: str, timeout_s: float = 25.0) -> None:
        if not base_url.startswith("https://"):
            raise SystemExit("--api-base-url must be https")
        self.base = base_url.rstrip("/")
        self._token = token
        self.timeout_s = timeout_s
        self.session_id = str(uuid.uuid4())

    def _request(
        self, method: str, path: str, body: bytes | None = None, content_type: str | None = None
    ) -> tuple[int, dict[str, Any]]:
        req = urllib.request.Request(self.base + path, data=body, method=method)
        req.add_header("Authorization", f"Bearer {self._token}")
        req.add_header("X-Request-Id", str(uuid.uuid4()))
        req.add_header("X-Session-Id", self.session_id)
        req.add_header("X-App-Version", "1")
        if content_type:
            req.add_header("Content-Type", content_type)
        try:
            with urllib.request.urlopen(req, timeout=self.timeout_s) as resp:  # noqa: S310 - https only, checked above
                return resp.status, json.loads(resp.read() or b"{}")
        except urllib.error.HTTPError as e:
            try:
                return e.code, json.loads(e.read() or b"{}")
            except json.JSONDecodeError:
                return e.code, {}

    def _ai_spent(self) -> float | None:
        status, body = self._request("GET", "/v1/config-check")
        budget = body.get("budget") if status == 200 else None
        return float(budget["ai_spent_usd"]) if budget and "ai_spent_usd" in budget else None

    def read(self, job: Job, image: bytes) -> ReadResult:
        before = self._ai_spent()
        body, ctype = _multipart(job.meta, image, job.media_type)
        started = time.monotonic()
        status, payload = self._request("POST", "/v1/read-screen", body, ctype)
        if status != 200:
            code = payload.get("error", {}).get("code", f"http_{status}")
            raise StopRun(f"read-screen refused: {code}")
        after = self._ai_spent()
        cost = round(max(after - before, 0.0), 6) if before is not None and after is not None else None
        server_ms = payload.get("latency_ms")
        return ReadResult(
            read=payload["read"],
            model=payload.get("model", ""),
            source=payload.get("source", "live"),
            request_id=payload.get("request_id", ""),
            latency_ms=int(server_ms) if isinstance(server_ms, int | float) and server_ms >= 0 else None,
            usage=None,
            cost_usd=cost,
            wall_ms=int((time.monotonic() - started) * 1000),
        )


@dataclass
class RunReport:
    recorded: list[str] = field(default_factory=list)
    skipped: list[str] = field(default_factory=list)
    # A clean pause, not an error: the next call could have passed --max-usd, or --limit was reached. `left` are
    # the screens the next run will read; screens recorded so far are skipped then, so it resumes here.
    paused_reason: str | None = None
    left: list[str] = field(default_factory=list)
    # The service or meter refused (pause, quota, auth, meter bypass): an error, the run stops.
    stopped_reason: str | None = None
    spent_usd: float = 0.0
    unknown_cost_calls: int = 0


def utc_now() -> str:
    return datetime.now(UTC).strftime(TIMESTAMP_FORMAT)


def needs_read(out: Path, redo_before: str | None = None) -> bool:
    """A screen is read when it has no recording yet or, when re-recording, when its recording predates
    `redo_before` (so a re-recording pass that paused at the cap resumes instead of starting over)."""
    if not out.exists():
        return True
    if redo_before is None:
        return False
    try:
        recorded_at = json.loads(out.read_text(encoding="utf-8")).get("recorded_at")
    except (OSError, ValueError, AttributeError):
        return True
    return not isinstance(recorded_at, str) or recorded_at < redo_before


def recorded_doc(job: Job, result: ReadResult) -> dict[str, Any]:
    doc: dict[str, Any] = {
        "image_sha256": job.sha256,
        "model": result.model,
        "recorded_at": utc_now(),
        "read": result.read,
        "usage": result.usage,
        "cost_usd": result.cost_usd,
        "source": result.source,
        "request_id": result.request_id,
    }
    if result.latency_ms is not None:
        doc["latency_ms"] = result.latency_ms
    if result.wall_ms is not None:
        doc["wall_ms"] = result.wall_ms
    return doc


def run(
    jobs: list[Job],
    reader: Reader | None,
    *,
    out_dir: Path,
    max_usd: float,
    worst_case_usd: float = WORST_CASE_USD,
    redo_before: str | None = None,
    limit: int | None = None,
    dry_run: bool = False,
    log: Any = print,
) -> RunReport:
    report = RunReport()
    todo: list[Job] = []
    for job in jobs:
        if needs_read(out_dir / f"{job.sha256}.json", redo_before):
            todo.append(job)
        else:
            report.skipped.append(job.sha256)
    calls = 0
    for i, job in enumerate(todo):
        if limit is not None and calls >= limit:
            report.paused_reason = f"--limit {limit} reached"
        elif report.spent_usd + worst_case_usd > max_usd + 1e-9:
            report.paused_reason = (
                f"budget guard: the next read could cost up to ${worst_case_usd:.4f} and pass "
                f"--max-usd {max_usd:.2f} (spent ${report.spent_usd:.4f})"
            )
        if report.paused_reason:
            report.left = [j.sha256 for j in todo[i:]]
            break
        out = out_dir / f"{job.sha256}.json"
        image = job.image.read_bytes()
        if hashlib.sha256(image).hexdigest() != job.sha256:
            raise SystemExit(f"sha256 mismatch for {job.image}: labels are stale")
        calls += 1
        if dry_run or reader is None:
            report.spent_usd += worst_case_usd
            report.recorded.append(job.sha256)
            log(f"would read {job.sha256[:12]} ({job.app}, {len(image)} bytes)")
            continue
        try:
            result = reader.read(job, image)
        except StopRun as e:
            report.stopped_reason = str(e)
            break
        charge = result.run_charge
        if charge is None:
            report.unknown_cost_calls += 1
        report.spent_usd += charge if charge is not None else worst_case_usd
        if result.source == "recorded":
            log(f"skip {job.sha256[:12]}: server answered from recorded fallback, not a live read")
            continue
        out_dir.mkdir(parents=True, exist_ok=True)
        out.write_text(
            json.dumps(recorded_doc(job, result), ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
        report.recorded.append(job.sha256)
        log(
            f"recorded {job.sha256[:12]} source={result.source} model={result.latency_ms}ms wall={result.wall_ms}ms"
        )
    return report


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--labels", type=Path, default=DEFAULT_LABELS, help="contracts §6 labels file")
    ap.add_argument("--mode", choices=("http", "gateway"), default="http")
    ap.add_argument("--api-base-url", help="https base URL of the deployed ridemate-api (http mode)")
    ap.add_argument(
        "--out-dir",
        type=Path,
        help="default: eval/recorded/channel_a for the demo labels, else next to --labels",
    )
    ap.add_argument("--max-usd", type=float, default=PER_RUN_CAP_USD)
    ap.add_argument("--limit", type=int, help="read at most N screens this run (the next run resumes)")
    redo = ap.add_mutually_exclusive_group()
    redo.add_argument(
        "--force",
        action="store_true",
        help="re-read screens that already have a recording (those recorded before this run started)",
    )
    redo.add_argument(
        "--force-before",
        metavar="UTC_TIME",
        help="re-read screens whose recording is older than UTC_TIME (YYYY-MM-DDTHH:MM:SSZ); "
        "resumes a --force run that paused",
    )
    ap.add_argument("--dry-run", action="store_true", help="no network: list screens and worst-case spend")
    args = ap.parse_args(argv)

    if not WORST_CASE_USD - 1e-9 <= args.max_usd <= PER_RUN_CAP_USD:
        ap.error(
            f"--max-usd must be between ${WORST_CASE_USD:.4f} (one worst-case read) and "
            f"${PER_RUN_CAP_USD:.2f} (the per-run eval cap, architecture §4.2)"
        )
    if args.limit is not None and args.limit < 1:
        ap.error("--limit must be at least 1")
    redo_before: str | None = utc_now() if args.force else args.force_before
    if args.force_before is not None:
        try:
            datetime.strptime(args.force_before, TIMESTAMP_FORMAT)
        except ValueError:
            ap.error("--force-before must look like 2026-10-29T10:00:00Z (UTC)")
    jobs = load_jobs(args.labels)
    out_dir = args.out_dir or default_out_dir(args.labels)
    check_out_dir(jobs, out_dir)
    reader: Reader | None = None
    if not args.dry_run:
        if args.mode == "http":
            token = os.environ.get(TOKEN_ENV, "")
            if not args.api_base_url or not token:
                ap.error(f"http mode needs --api-base-url and the {TOKEN_ENV} environment variable")
            reader = HttpReader(args.api_base_url, token)
        else:
            sys.path.insert(0, str(Path(__file__).resolve().parent))
            from gateway import GatewayReader

            reader = GatewayReader.from_production()
    try:
        report = run(
            jobs,
            reader,
            out_dir=out_dir,
            max_usd=args.max_usd,
            redo_before=redo_before,
            limit=args.limit,
            dry_run=args.dry_run,
        )
    finally:
        close = getattr(reader, "close", None)
        if close is not None:
            close()
    verb = "would record" if args.dry_run else "recorded"
    print(
        f"{verb} {len(report.recorded)}, skipped {len(report.skipped)} existing, "
        f"{'worst-case ' if args.dry_run else ''}spend ${report.spent_usd:.4f} (cap ${args.max_usd:.2f})"
    )
    if report.unknown_cost_calls:
        print(f"{report.unknown_cost_calls} call(s) had no cost report and were counted at the worst case")
    if report.stopped_reason:
        print(f"stopped: {report.stopped_reason}")
        return 2
    if report.paused_reason:
        resume = f"rerun with --force-before {redo_before}" if redo_before else "rerun the same command"
        print(
            f"paused: {report.paused_reason}. {len(report.left)} screen(s) "
            f"{'would be ' if args.dry_run else ''}left for the next run; {resume} to resume "
            "(screens already recorded are skipped)"
        )
    return 0


if __name__ == "__main__":
    sys.exit(main())
