"""The single dispatcher cron (architecture §4.2 "Health & probes", §4.18 m2/m6/m8/m16).

Modal's Starter plan allows 5 crons; RideMate uses exactly one, deployed only in the `main` environment, every
15 minutes. Each run:
- every time: metrics snapshot + hourly rollups, spend meter compaction + snapshot, metadata audit records of
  sessions that went idle (audit.py), replay of audit/events parked while the Firestore quota was gone, erasures
  Firestore refused at request time, alert evaluation;
- once per IST day (from a set hour): Firestore/hot-store cleanup (Spark has no TTL), the synthetic golden probe
  (one demo image read through the internal router as Purpose.PROBE, never over HTTP), billing reconciliation;
- once per week: the drift run (Template Doctor in report mode, within the evals sub-budget);
- finally: its own compute is metered and the healthchecks.io dead-man switch is pinged.

Every job is isolated (a failure or timeout never stops the others) and time-limited; the whole run stays inside
the function's 600 s timeout. Daily/weekly jobs are gated by `cron:last:{job}` in the hot store and retried at
most 3 times per period. Hot-store and Firestore calls run in worker threads, so a job's timeout can always fire.
"""

from __future__ import annotations

import asyncio
import importlib
import inspect
import json
import logging
import re
from collections.abc import Awaitable, Callable, Mapping, Sequence
from dataclasses import asdict, dataclass, field
from datetime import UTC, datetime, time, timedelta
from pathlib import Path
from typing import Any, Literal

import httpx

from .alerts import Alert, AlertDelivery, build_delivery, run_alerts
from .clock import IST, ist_day
from .firestore import OpKind, quota_day
from .interfaces import ApiError, Firestore, FirestoreQuotaExhausted, Purpose
from .metrics import MetricsView
from .settings import CRON_CPU_CORES, CRON_MEMORY_MIB, SUB_BUDGET_EVAL_RUN_USD
from .store import HotStore, StoreUnavailable

log = logging.getLogger(__name__)

RUN_BUDGET_S = 540.0  # inside the 600 s function timeout
RESERVE_S = 20.0  # kept for metering + the heartbeat ping
IDLE_AFTER_RUN_S = 10.0  # the dispatcher's scaledown window, also billed
MAX_FAILURES_PER_PERIOD = 3
HEARTBEAT_TIMEOUT_S = 5.0
MODEL_CHECK_TIMEOUT_S = 10.0
STOP_MARGIN_S = 3.0  # one more Firestore call (3 s deadline) may still be in flight when a job's time is up

LAST_PREFIX = "cron:last:"
FAIL_PREFIX = "cron:fail:"
PROBE_KEY = "cron:probe:last"
DRIFT_KEY = "billing:drift"  # the last reconciliation (read by alerts.py)
RECON_UNAVAILABLE_FLAG = "alert:once:reconciliation_unavailable"

Every = Literal["run", "daily", "weekly"]


@dataclass(frozen=True)
class JobSpec:
    name: str
    every: Every
    timeout_s: float
    not_before_ist: time = time(0, 0)
    weekday: int = 0  # weekly jobs: Monday


# Run order matters: snapshots first (alerts read the fresh metrics view), alerts last.
JOBS: tuple[JobSpec, ...] = (
    JobSpec("metrics", "run", 60),
    JobSpec("spend_snapshot", "run", 45),
    JobSpec("audit", "run", 60),  # before the replay: its documents may be parked when the quota is gone
    JobSpec("replay_buffer", "run", 60),
    JobSpec("pending_erasures", "run", 45),
    JobSpec("cleanup", "daily", 150, time(3, 0)),
    JobSpec("probe", "daily", 60, time(7, 0)),
    JobSpec("reconcile", "daily", 90, time(10, 0)),  # Modal's billing data for yesterday has settled by then
    JobSpec("drift", "weekly", 240, time(4, 0)),
    JobSpec("alerts", "run", 60),
)


# ---- hooks -------------------------------------------------------------------------------------------------


@dataclass(frozen=True)
class ProbeResult:
    status: Literal["pass", "fail", "skipped"]
    reason: str = ""
    latency_ms: int | None = None
    cost_usd: float | None = None


ProbeFn = Callable[[Any], Awaitable[ProbeResult]]
BillingReportFn = Callable[[datetime, datetime], Sequence[Mapping[str, Any]]]
DriftFn = Callable[[Any], Awaitable[Mapping[str, Any]]]


@dataclass
class DispatcherHooks:
    probe: ProbeFn | None = None
    billing_report: BillingReportFn | None = None
    drift: DriftFn | None = None
    delivery: AlertDelivery | None = None
    http_client: httpx.Client | None = None


class BillingUnavailable(Exception):
    """Modal's billing report couldn't be read (plan, permissions or API)."""


# ---- results -----------------------------------------------------------------------------------------------


@dataclass
class JobOutcome:
    name: str
    status: Literal["ok", "skipped", "failed", "timeout", "not_due"]
    detail: dict[str, Any] = field(default_factory=dict)
    duration_ms: int = 0


@dataclass
class DispatchReport:
    started_at: str
    outcomes: list[JobOutcome] = field(default_factory=list)
    heartbeat: bool = False
    compute_usd: float | None = None

    def outcome(self, name: str) -> JobOutcome | None:
        return next((o for o in self.outcomes if o.name == name), None)

    def as_dict(self) -> dict[str, Any]:
        return json.loads(json.dumps(asdict(self), default=str))


@dataclass
class _Run:
    deps: Any
    now: datetime
    hooks: DispatcherHooks
    view: MetricsView | None = None
    extra_alerts: list[Alert] = field(default_factory=list)
    job_deadline: float = float("inf")  # monotonic; set per job

    def should_stop(self) -> bool:
        """For jobs running in a worker thread, which a timeout can't cancel: they check this between calls."""
        return self.deps.clock.monotonic() >= self.job_deadline - STOP_MARGIN_S


# ---- the dispatcher ----------------------------------------------------------------------------------------


async def run_dispatcher(
    deps: Any, now: datetime | None = None, *, hooks: DispatcherHooks | None = None
) -> DispatchReport:
    hooks = hooks or DispatcherHooks()
    clock = deps.clock
    now = now or clock.now_utc()
    started = clock.monotonic()
    deadline = started + RUN_BUDGET_S
    run = _Run(deps=deps, now=now, hooks=hooks)
    report = DispatchReport(started_at=now.isoformat())
    try:
        for spec in JOBS:
            report.outcomes.append(await _run_job(run, spec, deadline))
    finally:
        report.compute_usd = await asyncio.to_thread(
            _record_compute, deps, clock.monotonic() - started + IDLE_AFTER_RUN_S
        )
        report.heartbeat = await asyncio.to_thread(
            _ping_heartbeat, deps.settings.healthchecks_heartbeat_url, hooks
        )
    return report


async def _run_job(run: _Run, spec: JobSpec, deadline: float) -> JobOutcome:
    clock = run.deps.clock
    period = period_id(spec, run.now)
    if period is not None and not await asyncio.to_thread(_due, run.deps.store, spec, period, run.now):
        return JobOutcome(spec.name, "not_due")
    timeout = min(spec.timeout_s, deadline - clock.monotonic() - RESERVE_S)
    if timeout <= 0:
        return JobOutcome(spec.name, "skipped", {"reason": "no_time"})
    began = clock.monotonic()
    run.job_deadline = began + timeout
    outcome = JobOutcome(spec.name, "ok")
    try:
        async with asyncio.timeout(timeout):
            detail = dict(await _JOB_FUNCS[spec.name](run) or {})
        outcome.status = detail.pop("status", "ok")
        outcome.detail = detail
    except TimeoutError:
        outcome.status = "timeout"
    except Exception as e:  # noqa: BLE001 - isolation: one job's failure never stops the others
        outcome.status = "failed"
        outcome.detail = {"error_type": type(e).__name__}
        log.warning("cron job failed", extra={"job": spec.name, "error_type": type(e).__name__})
    outcome.duration_ms = int((clock.monotonic() - began) * 1000)
    if outcome.status in ("failed", "timeout"):
        run.extra_alerts.append(Alert(f"cron_job_failed_{spec.name}"))
    if period is not None:
        await asyncio.to_thread(_settle_period, run.deps.store, spec, period, outcome.status)
    log.info(
        "cron job", extra={"job": spec.name, "outcome": outcome.status, "duration_ms": outcome.duration_ms}
    )
    return outcome


def period_id(spec: JobSpec, now: datetime) -> str | None:
    if spec.every == "daily":
        return ist_day(now)
    if spec.every == "weekly":
        year, week, _ = now.astimezone(IST).isocalendar()
        return f"{year}-W{week:02d}"
    return None


def _due(store: HotStore, spec: JobSpec, period: str, now: datetime) -> bool:
    local = now.astimezone(IST)
    if spec.every == "weekly":
        if local.weekday() < spec.weekday:
            return False
        if local.weekday() == spec.weekday and local.time() < spec.not_before_ist:
            return False
    elif local.time() < spec.not_before_ist:
        return False
    try:
        return store.get(LAST_PREFIX + spec.name) != period
    except StoreUnavailable:
        return False  # without the gate a daily job could run every 15 minutes (and spend credit)


def _settle_period(store: HotStore, spec: JobSpec, period: str, status: str) -> None:
    try:
        if status in ("ok", "skipped"):
            store.put(LAST_PREFIX + spec.name, period)
            store.delete(FAIL_PREFIX + spec.name)
            return
        record = store.get(FAIL_PREFIX + spec.name) or {}
        count = int(record.get("count", 0)) + 1 if record.get("period") == period else 1
        store.put(FAIL_PREFIX + spec.name, {"period": period, "count": count})
        if count >= MAX_FAILURES_PER_PERIOD:
            store.put(LAST_PREFIX + spec.name, period)
    except StoreUnavailable:
        log.warning("cron: could not record job period", extra={"job": spec.name})


def _record_compute(deps: Any, seconds: float) -> float | None:
    try:
        return deps.spend.record_compute(
            cpu_cores=CRON_CPU_CORES, memory_mib=CRON_MEMORY_MIB, seconds=seconds, source="cron"
        )
    except Exception:  # noqa: BLE001 - the heartbeat must still go out
        log.warning("cron: compute not recorded (meter unavailable)")
        return None


def _ping_heartbeat(url: str, hooks: DispatcherHooks) -> bool:
    if not url:
        return False
    try:
        if hooks.http_client is not None:
            response = hooks.http_client.post(url, content=b"", timeout=HEARTBEAT_TIMEOUT_S)
        else:
            with httpx.Client() as client:
                response = client.post(url, content=b"", timeout=HEARTBEAT_TIMEOUT_S)
    except httpx.HTTPError as e:
        log.warning("cron: heartbeat ping failed (%s)", type(e).__name__)
        return False
    return response.status_code < 400


# ---- every-run jobs ----------------------------------------------------------------------------------------


async def _job_metrics(run: _Run) -> dict[str, Any]:
    metrics = run.deps.metrics
    if not hasattr(metrics, "snapshot_to_firestore"):
        return {"status": "skipped", "reason": "no_hot_store_metrics"}
    run.view = await asyncio.to_thread(metrics.snapshot_to_firestore, run.deps.firestore, run.now)
    return {"windows": len(run.view.windows), "hours": len(run.view.hours)}


async def _job_spend_snapshot(run: _Run) -> dict[str, Any]:
    spend = run.deps.spend
    detail: dict[str, Any] = {}
    if callable(getattr(spend, "compact", None)):
        detail["compacted"] = len(await asyncio.to_thread(spend.compact))
    snapshot = getattr(spend, "snapshot_to_firestore", None)
    if not callable(snapshot):
        return {"status": "skipped", "reason": "no_snapshot", **detail}
    if run.deps.firestore is None:
        return {"status": "skipped", "reason": "no_firestore", **detail}
    detail["written"] = await asyncio.to_thread(snapshot, run.deps.firestore)
    return detail


async def _job_audit(run: _Run) -> dict[str, Any]:
    """Write the audit document of every session idle for 30 minutes (≤ 1 write per session, audit.py)."""
    trail = getattr(run.deps, "audit", None)
    if trail is None:
        return {"status": "skipped", "reason": "no_audit"}
    return dict(await asyncio.to_thread(trail.flush_idle, stop=run.should_stop))


async def _job_replay(run: _Run) -> dict[str, Any]:
    fs = run.deps.firestore
    if fs is None or not callable(getattr(fs, "replay_buffer", None)):
        return {"status": "skipped", "reason": "no_firestore"}
    return {"count": await asyncio.to_thread(fs.replay_buffer, stop=run.should_stop)}


async def _job_pending_erasures(run: _Run) -> dict[str, Any]:
    """Erasures Firestore refused at request time (api/misc.py parks them in the hot store)."""
    if run.deps.firestore is None:
        return {"status": "skipped", "reason": "no_firestore"}
    try:
        from .api.misc import complete_pending_erasures
    except ImportError:
        return {"status": "skipped", "reason": "no_erase_endpoint"}
    return {"count": await asyncio.to_thread(complete_pending_erasures, run.deps)}


async def _check_model(router: Any) -> None:
    """Ask the endpoint whether the pinned model id is still served (GET /models: no tokens, no spend), so a
    removed model raises its alert within 15 minutes even when no phone has called since."""
    check = getattr(router, "model_available", None)
    if not callable(check):
        return
    try:
        async with asyncio.timeout(MODEL_CHECK_TIMEOUT_S):
            await check()
    except Exception as e:  # noqa: BLE001 - an unanswered check is "unknown", not "missing"
        log.warning("cron: model check failed", extra={"error_type": type(e).__name__})


async def _job_alerts(run: _Run) -> dict[str, Any]:
    deps = run.deps
    await _check_model(deps.router)
    delivery = run.hooks.delivery or build_delivery(
        deps.settings, deps.store, http_client=run.hooks.http_client
    )
    result = await asyncio.to_thread(run_alerts, deps, run.now, run.view, delivery, extra=run.extra_alerts)
    return {"firing": result.firing, "sent": result.sent}


# ---- daily: cleanup ----------------------------------------------------------------------------------------


@dataclass(frozen=True)
class Retention:
    collection: str
    field: str
    max_age: timedelta


RETENTIONS: tuple[Retention, ...] = (
    Retention("events", "created_at", timedelta(days=365)),  # DPDP metadata, 1 year
    Retention("audit", "created_at", timedelta(days=365)),
    Retention("ondc_txn", "created_at", timedelta(days=180)),
    Retention("ondc_event", "received_at", timedelta(days=180)),
    Retention(
        "usage_snapshot", "erased_at", timedelta(hours=48)
    ),  # kept 48 h after erasure (abuse prevention)
)
SPEND_DAY_SHARD_DAYS = 60
METRICS_DAYS = 90
QUOTA_KEY_DAYS = 2
BUDGET_KEY_DAYS = 3
CLEANUP_MAX_DELETES = 2_000
CLEANUP_PAGE = 200
DELETE_QUOTA_STOP = 0.80
_DAY = re.compile(r"^\d{4}-\d{2}-\d{2}$")


class _DeleteBudget:
    def __init__(self, fs: Firestore, limit: int, stop: Callable[[], bool] | None = None) -> None:
        self._fs = fs
        self.left = limit
        self.deleted: dict[str, int] = {}
        self._stop = stop

    def exhausted(self) -> bool:
        if self.left <= 0 or (self._stop is not None and self._stop()):
            return True
        budget = getattr(self._fs, "budget", None)
        return budget is not None and budget.fraction(OpKind.DELETES) >= DELETE_QUOTA_STOP

    def delete(self, label: str, path: str) -> bool:
        if self.exhausted():
            return False
        self._fs.delete(path)
        self.left -= 1
        self.deleted[label] = self.deleted.get(label, 0) + 1
        return True


def _delete_matching(
    budget: _DeleteBudget, label: str, collection: str, field_name: str, op: str, value: Any
) -> None:
    fs = budget._fs
    while not budget.exhausted():
        page = fs.query(collection, field_name, op, value, limit=min(CLEANUP_PAGE, budget.left))
        for path, _ in page:
            if not budget.delete(label, path):
                return
        if len(page) < CLEANUP_PAGE:
            return


def cleanup_firestore(
    fs: Firestore,
    now: datetime,
    *,
    max_deletes: int = CLEANUP_MAX_DELETES,
    stop: Callable[[], bool] | None = None,
) -> dict[str, int]:
    """Delete what Firestore TTL would have (TTL needs billing on Spark). Bounded per run, stopped near the
    daily delete quota or when `stop()` says the job's time is up; the next day continues where this one
    stopped."""
    budget = _DeleteBudget(fs, max_deletes, stop)
    try:
        for r in RETENTIONS:
            cutoff = now - r.max_age
            # Timestamps are the norm; ISO strings are matched too so a writer's choice can't block expiry.
            for value in (cutoff, cutoff.astimezone(UTC).isoformat()):
                _delete_matching(budget, r.collection, r.collection, r.field, "<", value)
        # 'lifetime' and 'rollup' sort after any date, so only day shards match.
        cutoff_day = ist_day(now - timedelta(days=SPEND_DAY_SHARD_DAYS))
        _delete_matching(budget, "spend_shards", "spend_shards", "period", "<", cutoff_day)
        metrics_cutoff = ist_day(now - timedelta(days=METRICS_DAYS))
        while not budget.exhausted():
            days = fs.query("metrics", "day", "<", metrics_cutoff, limit=20)
            for path, data in days:
                _delete_matching(budget, "metrics", f"{path}/shards", "day", "==", data.get("day", ""))
                if not budget.delete("metrics_days", path):
                    break
            if len(days) < 20:
                break
    except FirestoreQuotaExhausted:
        log.warning("cleanup: Firestore delete quota exhausted; continuing tomorrow")
    return budget.deleted


def cleanup_hot_store(store: HotStore, now: datetime) -> dict[str, int]:
    """Old per-user quota slots ('quota:{hmac}:{day}:…', kept 48 h) and old Firestore budget counters."""
    removed = {"quota": 0, "fs": 0}
    quota_cutoff = ist_day(now - timedelta(days=QUOTA_KEY_DAYS))
    for key in store.keys_with_prefix("quota:"):
        day = next((part for part in key.split(":")[2:4] if _DAY.match(part)), None)
        if day is not None and day < quota_cutoff:
            store.delete(key)
            removed["quota"] += 1
    fs_cutoff = quota_day(now - timedelta(days=BUDGET_KEY_DAYS))
    for key in store.keys_with_prefix("fs:"):
        day = next((part for part in key.split(":") if _DAY.match(part)), None)
        if day is not None and day < fs_cutoff:
            store.delete(key)
            removed["fs"] += 1
    return removed


async def _job_cleanup(run: _Run) -> dict[str, Any]:
    detail: dict[str, Any] = {
        "hot_store": await asyncio.to_thread(cleanup_hot_store, run.deps.store, run.now)
    }
    if run.deps.firestore is not None:
        detail["firestore"] = await asyncio.to_thread(
            cleanup_firestore, run.deps.firestore, run.now, stop=run.should_stop
        )
    return detail


# ---- daily: golden probe -----------------------------------------------------------------------------------


async def _job_probe(run: _Run) -> dict[str, Any]:
    if run.deps.router is None:
        return {"status": "skipped", "reason": "no_router"}
    if run.hooks.probe is None:
        return {"status": "skipped", "reason": "no_probe"}
    result = await run.hooks.probe(run.deps)
    record = {**asdict(result), "at": run.now.isoformat()}
    try:
        await asyncio.to_thread(run.deps.store.put, PROBE_KEY, record)
    except StoreUnavailable:
        log.warning("probe: result not recorded")
    if result.status == "fail":
        run.extra_alerts.append(Alert("probe_failed"))
    return {"probe": result.status, "reason": result.reason}


def compare_read(read: Mapping[str, Any], label: Mapping[str, Any]) -> str | None:
    """None when the read matches the labelled screen, else what differed (an enum, never a value)."""
    if read.get("app") is not None and label.get("app") and read.get("app") != label.get("app"):
        return "app"
    if read.get("screen_type") != label.get("screen_type"):
        return "screen_type"
    options = [o for o in read.get("options") or [] if isinstance(o, Mapping)]
    for row in label.get("rows") or []:
        if not any(
            o.get("vehicle_class") == row.get("vehicle_class")
            and o.get("price_min") == row.get("price_min")
            and o.get("price_max") == row.get("price_max")
            for o in options
        ):
            return "price"
    return None


def _sniff_mime(data: bytes) -> str | None:
    if data.startswith(b"\x89PNG\r\n\x1a\n"):
        return "image/png"
    if data.startswith(b"\xff\xd8\xff"):
        return "image/jpeg"
    return None


def _probe_candidates(sources: Sequence[tuple[Path, Path]]) -> list[tuple[Path, dict[str, Any]]]:
    """(image path, label) for every labelled fare screen whose image exists, from the first usable labels file
    (the private golden set on the Volume first, then the bundled demo labels)."""
    for labels_path, root in sources:
        try:
            screens = json.loads(labels_path.read_text(encoding="utf-8")).get("screens", [])
        except (OSError, ValueError, AttributeError):
            continue
        found = []
        for screen in screens:
            if (
                not isinstance(screen, dict)
                or screen.get("screen_type") != "fare_options"
                or not screen.get("rows")
            ):
                continue
            image = root / str(screen.get("file", ""))
            if image.is_file():
                found.append((image, screen))
        if found:
            return found
    return []


def make_golden_probe(sources: Sequence[tuple[Path, Path]]) -> ProbeFn:
    """A probe reading one labelled screen per day (rotating) through the internal router as Purpose.PROBE.
    `sources` are (labels.json, image root) pairs in order of preference."""

    async def probe(deps: Any) -> ProbeResult:
        from .model_gateway import ImagePart
        from .prompts import read_request, validate_read
        from .router import TaskKind

        candidates = _probe_candidates(sources)
        if not candidates:
            return ProbeResult("skipped", "no_labelled_screen")
        now = deps.clock.now_utc()
        image_path, label = candidates[now.astimezone(IST).timetuple().tm_yday % len(candidates)]
        data = image_path.read_bytes()
        mime = _sniff_mime(data)
        if mime is None:
            return ProbeResult("skipped", "bad_media")
        try:
            result = await deps.router.call(
                read_request(ImagePart(data=data, mime=mime)),
                task=TaskKind.READ,
                purpose=Purpose.PROBE,
                principal=None,
                request_id=f"probe-{ist_day(now)}",
            )
        except ApiError as e:
            if e.code in ("ai_paused", "meter_unavailable", "quota_exhausted"):
                return ProbeResult("skipped", e.code)  # budget pauses are the spend alerts' business
            return ProbeResult("fail", e.reason or e.code)
        mismatch = compare_read(validate_read(result.data), label)
        return ProbeResult(
            "fail" if mismatch else "pass",
            mismatch or "",
            latency_ms=result.latency_ms,
            cost_usd=result.cost_usd,
        )

    return probe


# ---- daily: billing reconciliation -------------------------------------------------------------------------

BILLING_MATCH: tuple[str, ...] = ("ridemate", "muse-spark", "spark-open", "meta-models")
DRIFT_ALERT = 0.10
DRIFT_NOISE_FLOOR_USD = 0.05
MAX_DRIFT_FACTOR = 3.0


def billing_window(day: str) -> tuple[datetime, datetime]:
    """Modal reports whole UTC hours; IST midnight is at :30, so the IST day is approximated by the 24 hours
    starting at the UTC hour that contains it (a 30-minute skew, well inside the 10% threshold)."""
    start = datetime.strptime(day, "%Y-%m-%d").replace(tzinfo=IST).astimezone(UTC)
    start = start.replace(minute=0, second=0, microsecond=0)
    return start, start + timedelta(hours=24)


def is_ridemate_cost(row: Mapping[str, Any]) -> bool:
    """RideMate's own apps plus the shared Muse Spark Open endpoint's token usage."""
    text = " ".join(str(row.get(k, "")) for k in ("description", "object_id")).lower()
    tags = row.get("tags") or {}
    text += " " + " ".join(f"{k}={v}" for k, v in tags.items()).lower() if isinstance(tags, Mapping) else ""
    return any(marker in text for marker in BILLING_MATCH)


def modal_billing_report(start: datetime, end: datetime) -> list[dict[str, Any]]:
    """Hourly cost rows from Modal's billing API (needs a Modal client context: inside a Modal container)."""
    import modal

    try:
        workspace = getattr(modal, "Workspace", None)
        if workspace is not None:
            items = workspace.from_context().billing.report(start=start, end=end, resolution="h")
            return [
                {
                    "description": i.description,
                    "object_id": i.object_id,
                    "environment_name": i.environment_name,
                    "cost": float(i.cost),
                    "tags": dict(i.tags),
                }
                for i in items
            ]
        rows = modal.billing.workspace_billing_report(start=start, end=end, resolution="h")
        return [{**dict(r), "cost": float(r["cost"])} for r in rows]
    except Exception as e:  # noqa: BLE001 - plan, permission or API errors all mean "unavailable"
        raise BillingUnavailable(type(e).__name__) from None


def reconcile(store: HotStore, spend: Any, now: datetime, report: BillingReportFn) -> dict[str, Any]:
    """Compare yesterday's Modal bill with what the spend meter counted (`spend.day_total_usd`: AI + compute) and,
    above 10% drift, tighten the meter's drift factor. The result is recorded under `billing:drift` for alerts."""
    day = ist_day(now - timedelta(days=1))
    start, end = billing_window(day)
    try:
        rows = report(start, end)
    except BillingUnavailable:
        raise
    except Exception as e:  # noqa: BLE001
        raise BillingUnavailable(type(e).__name__) from None
    billing = round(sum(float(r.get("cost", 0) or 0) for r in rows if is_ridemate_cost(r)), 6)
    meter = round(float(spend.day_total_usd(day)), 6)
    high = max(billing, meter)
    drift = abs(billing - meter) / high if high >= DRIFT_NOISE_FLOOR_USD else 0.0
    current = float(spend.drift_factor())
    factor = current
    if drift > DRIFT_ALERT:
        # The meter already charges at the current factor; scale it so the meter matches the bill again. Only
        # ever upwards: a bill that looks low may just be rows the matcher missed, and loosening the meter on
        # that evidence could spend past the credit. The alert still fires; lowering it is the owner's call.
        target = current * billing / meter if meter > 0 else MAX_DRIFT_FACTOR
        factor = max(current, min(MAX_DRIFT_FACTOR, target))
        if factor > current:
            factor = float(spend.set_drift_factor(factor))
    record = {
        "day": day,
        "billing_usd": billing,
        "meter_usd": meter,
        "drift": round(drift, 4),
        "factor": factor,
        "at": now.isoformat(),
    }
    store.put(DRIFT_KEY, record)
    return record


async def _job_reconcile(run: _Run) -> dict[str, Any]:
    deps = run.deps
    report = run.hooks.billing_report
    if report is None:
        return {"status": "skipped", "reason": "no_billing_report"}
    try:
        record = await asyncio.to_thread(reconcile, deps.store, deps.spend, run.now, report)
    except BillingUnavailable as e:
        if await asyncio.to_thread(deps.store.put_if_absent, RECON_UNAVAILABLE_FLAG, run.now.isoformat()):
            run.extra_alerts.append(Alert("reconciliation_unavailable"))
        return {"status": "skipped", "reason": "billing_unavailable", "error_type": str(e)}
    await asyncio.to_thread(deps.store.delete, RECON_UNAVAILABLE_FLAG)
    return {"drift": record["drift"], "factor": record["factor"]}


# ---- weekly: drift run -------------------------------------------------------------------------------------


DOCTOR_MODULES: tuple[str, ...] = ("tools.template_doctor", "template_doctor")
DOCTOR_STATUS_MAX = 40
_DOCTOR_STATUS = re.compile(r"^[a-z][a-z0-9_]*$")
_LONG_NUMBER = re.compile(r"\d{5,}")


def _import_doctor() -> Any:
    """The Template Doctor package, or None when it isn't in the image. A package that is there but can't be
    imported (a dependency missing from the image) raises, so the run fails and alerts instead of skipping
    silently every week."""
    for name in DOCTOR_MODULES:
        try:
            return importlib.import_module(name)
        except ModuleNotFoundError as e:
            if e.name is not None and (name == e.name or name.startswith(e.name + ".")):
                continue  # this location simply doesn't exist
            raise
    return None


async def template_doctor_drift(deps: Any) -> Mapping[str, Any]:
    """Template Doctor (tools/template_doctor) in report mode, capped at one eval run's sub-budget. Skipped
    cleanly when the tool isn't in the image or has no `run_report`."""
    run_report = getattr(_import_doctor(), "run_report", None)
    if not callable(run_report):
        return {"status": "skipped", "reason": "template_doctor_absent"}
    if deps.router is None:
        return {"status": "skipped", "reason": "no_router"}
    kwargs: dict[str, Any] = {
        "router": deps.router,
        "spend": deps.spend,
        "max_usd": SUB_BUDGET_EVAL_RUN_USD,
        "purpose": Purpose.DOCTOR,
    }
    try:
        if "deps" in inspect.signature(run_report).parameters:
            kwargs["deps"] = deps  # live Remote Config rules as the baseline, and the run metric
    except (TypeError, ValueError):
        pass
    result = run_report(**kwargs)
    if inspect.isawaitable(result):
        result = await result
    summary = result if isinstance(result, Mapping) else {}
    numbers = {k: v for k, v in summary.items() if isinstance(v, int | float | bool)}
    if summary.get("status") == "skipped":
        # e.g. no golden set on the Volume yet; the Doctor's own reason may be free text, so it isn't passed on
        return {"status": "skipped", "reason": "template_doctor_skipped", **numbers}
    # The Doctor's own result (no_misses, proposed, rejected, ...) under its own key: the job's `status` is the
    # dispatcher's ok/failed/... outcome.
    if "status" in summary:
        return {**numbers, "doctor_status": doctor_status(summary["status"])}
    return numbers


def doctor_status(raw: Any) -> str:
    """The Template Doctor's run status as an enum-like token (a lower-case letter, then letters, digits and '_';
    ≤ 40 chars; '-' read as '_'), so the job report and the log scrubber keep it. Anything else becomes 'other':
    free text (it has spaces), and long digit runs that could be a phone number or a price."""
    text = str(raw).strip().lower().replace("-", "_")
    if len(text) <= DOCTOR_STATUS_MAX and _DOCTOR_STATUS.match(text) and not _LONG_NUMBER.search(text):
        return text
    return "other"


async def _job_drift(run: _Run) -> dict[str, Any]:
    if run.hooks.drift is None:
        return {"status": "skipped", "reason": "no_drift_hook"}
    return dict(await run.hooks.drift(run.deps))


_JOB_FUNCS: Mapping[str, Callable[[_Run], Awaitable[Mapping[str, Any] | None]]] = {
    "metrics": _job_metrics,
    "spend_snapshot": _job_spend_snapshot,
    "audit": _job_audit,
    "replay_buffer": _job_replay,
    "pending_erasures": _job_pending_erasures,
    "cleanup": _job_cleanup,
    "probe": _job_probe,
    "reconcile": _job_reconcile,
    "drift": _job_drift,
    "alerts": _job_alerts,
}


def default_hooks(
    *, root: Path, golden_dir: Path | None, http_client: httpx.Client | None = None
) -> DispatcherHooks:
    """Production hooks: the golden probe (private set on the Volume first, bundled demo labels second),
    Modal's billing report and the Template Doctor drift run."""
    sources: list[tuple[Path, Path]] = []
    if golden_dir is not None:
        sources.append((golden_dir / "labels.json", golden_dir))
    sources.append((root / "eval" / "demo_labels.json", root))
    return DispatcherHooks(
        probe=make_golden_probe(sources),
        billing_report=modal_billing_report,
        drift=template_doctor_drift,
        http_client=http_client,
    )


__all__ = [
    "JOBS",
    "BillingUnavailable",
    "DispatchReport",
    "DispatcherHooks",
    "JobOutcome",
    "ProbeResult",
    "cleanup_firestore",
    "cleanup_hot_store",
    "compare_read",
    "default_hooks",
    "doctor_status",
    "make_golden_probe",
    "reconcile",
    "run_dispatcher",
]
