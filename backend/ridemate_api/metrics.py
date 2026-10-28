"""Server metrics (architecture §4.2 "Observability", §4.18 m3).

Counters and latency histograms are aggregated in process per 5-minute window and flushed every few seconds to
the hot store under `metrics:{window}:{shard}`, one key per container, so no key ever has two writers. The
dispatcher merges the shards: each finished window becomes one Firestore doc `metrics/{day}/shards/{window}`
(sharded by window, never one hot doc; low-value, so it is shed near the Firestore quota), and an hourly rollup
`metrics:hour:{yyyymmddhh}` that the alert rules read for 24-hour rates.

Recording never raises: a metrics failure must not fail a request.
"""

from __future__ import annotations

import hashlib
import json
import logging
import os
import re
import threading
import uuid
from collections.abc import Iterable, Mapping
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta
from typing import Any

from .clock import Clock, ist_day
from .interfaces import Firestore, FirestoreUnavailable, Metrics
from .store import HotStore, StoreUnavailable

log = logging.getLogger(__name__)

WINDOW_S = 300
FLUSH_INTERVAL_S = 10.0
BUCKETS_MS: tuple[int, ...] = (
    50,
    100,
    250,
    500,
    1000,
    2000,
    4000,
    6000,
    8000,
    10000,
    12000,
    15000,
    20000,
    30000,
    60000,
)
MAX_SERIES_PER_WINDOW = 500
LOCAL_KEEP_S = 3600  # finished windows kept in memory after a successful flush
LATE_FLUSH_S = (
    2 * 3600
)  # windows this recent are re-merged on every snapshot (late flushes from idle containers)
SNAPSHOT_GRACE_S = 120  # a window is snapshotted once it has been closed this long
KEEP_WINDOWS_S = 26 * 3600
KEEP_HOURS_S = 49 * 3600

KEY_PREFIX = "metrics:"
SNAPSTATE_KEY = "metrics:snapstate"
_WINDOW_KEY = re.compile(r"^metrics:(\d{12}):(.+)$")
_HOUR_KEY = re.compile(r"^metrics:hour:(\d{10})$")
_LABEL_VALUE = re.compile(r"[^A-Za-z0-9_./:\-]")

# Metric names shared with the request path. Endpoint code records through the helpers below.
HTTP_REQUESTS = "http_requests"  # labels: route, status_class (2xx/4xx/5xx)
HTTP_LATENCY_MS = "http_latency_ms"  # labels: route
EXCEPTIONS = "exceptions"  # labels: type (exception class name)
FIRESTORE_EVENTS = "firestore_events"  # labels: kind (shed, buffered, writes_exhausted, ...)
DROPPED = "metrics_dropped"
EVENT_PREFIX = "event_"
# /v1/event counters (contracts §1.3) that feed the VERIFIED-rate and rule-miss alerts.
SESSION_EVENT_FIELDS: tuple[str, ...] = (
    "reads",
    "verified_rows",
    "unreadable_rows",
    "rule_hits",
    "rule_misses",
    "misses_unclassified",
)
READ_ROUTE = "/v1/read-screen"


def instance_id() -> str:
    """A shard id unique to this container: Modal's task id when present, else a random one."""
    task = os.environ.get("MODAL_TASK_ID", "")
    return _LABEL_VALUE.sub("_", task)[:40] if task else uuid.uuid4().hex[:12]


def window_id(dt: datetime) -> str:
    utc = dt.astimezone(UTC)
    floored = utc - timedelta(minutes=utc.minute % 5, seconds=utc.second, microseconds=utc.microsecond)
    return floored.strftime("%Y%m%d%H%M")


def window_start(window: str) -> datetime:
    return datetime.strptime(window, "%Y%m%d%H%M").replace(tzinfo=UTC)


def hour_start(hour: str) -> datetime:
    return datetime.strptime(hour, "%Y%m%d%H").replace(tzinfo=UTC)


def series_key(name: str, labels: Mapping[str, str]) -> str:
    if not labels:
        return name
    inner = ",".join(f"{k}={_LABEL_VALUE.sub('_', str(v))[:48]}" for k, v in sorted(labels.items()))
    return f"{name}{{{inner}}}"


def parse_series(key: str) -> tuple[str, dict[str, str]]:
    if "{" not in key:
        return key, {}
    name, _, rest = key.partition("{")
    labels = dict(pair.split("=", 1) for pair in rest.rstrip("}").split(",") if "=" in pair)
    return name, labels


@dataclass
class Histogram:
    buckets: list[int] = field(default_factory=lambda: [0] * (len(BUCKETS_MS) + 1))
    count: int = 0
    sum_ms: float = 0.0
    max_ms: float = 0.0

    def observe(self, value_ms: float) -> None:
        value_ms = max(0.0, float(value_ms))
        index = next((i for i, bound in enumerate(BUCKETS_MS) if value_ms <= bound), len(BUCKETS_MS))
        self.buckets[index] += 1
        self.count += 1
        self.sum_ms += value_ms
        self.max_ms = max(self.max_ms, value_ms)

    def merge(self, other: Histogram) -> None:
        self.buckets = [a + b for a, b in zip(self.buckets, other.buckets, strict=True)]
        self.count += other.count
        self.sum_ms += other.sum_ms
        self.max_ms = max(self.max_ms, other.max_ms)

    def quantile(self, q: float) -> float | None:
        """Upper bound of the bucket holding the q-quantile (the maximum seen for the overflow bucket)."""
        if self.count == 0:
            return None
        rank = q * self.count
        seen = 0
        for i, n in enumerate(self.buckets):
            seen += n
            if seen >= rank and n:
                return float(BUCKETS_MS[i]) if i < len(BUCKETS_MS) else self.max_ms
        return self.max_ms

    def to_dict(self) -> dict[str, Any]:
        return {
            "b": list(self.buckets),
            "n": self.count,
            "s": round(self.sum_ms, 3),
            "m": round(self.max_ms, 3),
        }

    @classmethod
    def from_dict(cls, data: Mapping[str, Any]) -> Histogram:
        buckets = [int(x) for x in data.get("b", [])]
        if len(buckets) != len(BUCKETS_MS) + 1:
            buckets = [0] * (len(BUCKETS_MS) + 1)
        return cls(buckets, int(data.get("n", 0)), float(data.get("s", 0.0)), float(data.get("m", 0.0)))


@dataclass
class Aggregate:
    counters: dict[str, int] = field(default_factory=dict)
    hists: dict[str, Histogram] = field(default_factory=dict)

    @property
    def series(self) -> int:
        return len(self.counters) + len(self.hists)

    def merge(self, other: Aggregate) -> None:
        for key, n in other.counters.items():
            self.counters[key] = self.counters.get(key, 0) + n
        for key, hist in other.hists.items():
            self.hists.setdefault(key, Histogram()).merge(hist)

    def counter(self, name: str, **labels: str) -> int:
        return sum(n for key, n in self.counters.items() if _matches(key, name, labels))

    def histogram(self, name: str, **labels: str) -> Histogram:
        merged = Histogram()
        for key, hist in self.hists.items():
            if _matches(key, name, labels):
                merged.merge(hist)
        return merged

    def to_dict(self) -> dict[str, Any]:
        return {"c": dict(self.counters), "h": {k: v.to_dict() for k, v in self.hists.items()}}

    @classmethod
    def from_dict(cls, data: Any) -> Aggregate:
        if not isinstance(data, Mapping):
            return cls()
        counters = {str(k): int(v) for k, v in dict(data.get("c", {})).items()}
        hists = {str(k): Histogram.from_dict(v) for k, v in dict(data.get("h", {})).items()}
        return cls(counters, hists)

    def digest(self) -> str:
        return hashlib.sha256(json.dumps(self.to_dict(), sort_keys=True).encode()).hexdigest()[:16]

    @classmethod
    def merged(cls, parts: Iterable[Aggregate]) -> Aggregate:
        out = cls()
        for part in parts:
            out.merge(part)
        return out


def _matches(key: str, name: str, labels: Mapping[str, str]) -> bool:
    key_name, key_labels = parse_series(key)
    return key_name == name and all(key_labels.get(k) == v for k, v in labels.items())


@dataclass
class MetricsView:
    """What one scan of the hot store saw: recent windows (merged across shards) and hourly rollups."""

    now: datetime
    windows: dict[str, Aggregate] = field(default_factory=dict)
    hours: dict[str, Aggregate] = field(default_factory=dict)
    window_keys: dict[str, list[str]] = field(default_factory=dict)
    hour_keys: dict[str, str] = field(default_factory=dict)

    def recent(self, minutes: int) -> Aggregate:
        """Windows that overlap the last `minutes` (the current, still-open window included)."""
        since = self.now - timedelta(minutes=minutes)
        return Aggregate.merged(
            agg for w, agg in self.windows.items() if window_start(w) + timedelta(seconds=WINDOW_S) > since
        )

    def last_hours(self, hours: int) -> Aggregate:
        """Hourly rollups of the last `hours` hours, the current hour included."""
        since = self.now.replace(minute=0, second=0, microsecond=0) - timedelta(hours=hours - 1)
        return Aggregate.merged(agg for h, agg in self.hours.items() if hour_start(h) >= since)

    def hourly(self, hours: int, name: str, **labels: str) -> list[int]:
        """Counter totals per hour, oldest first, for the last `hours` hours (current hour last)."""
        top = self.now.astimezone(UTC).replace(minute=0, second=0, microsecond=0)
        out = []
        for i in range(hours - 1, -1, -1):
            key = (top - timedelta(hours=i)).strftime("%Y%m%d%H")
            agg = self.hours.get(key)
            out.append(agg.counter(name, **labels) if agg else 0)
        return out


class DictMetrics:
    """`interfaces.Metrics` backed by the hot store (see the module docstring)."""

    def __init__(
        self,
        store: HotStore,
        clock: Clock,
        *,
        shard: str | None = None,
        flush_interval_s: float = FLUSH_INTERVAL_S,
        background_flush: bool = True,
    ) -> None:
        self._store = store
        self._clock = clock
        self.shard = shard or instance_id()
        self._flush_interval_s = flush_interval_s
        # Periodic flushes triggered by a recording run on their own thread, so a slow hot store never stalls the
        # request that happened to record (endpoints record from the event loop).
        self._background = background_flush
        self._flushing = False
        self._lock = threading.Lock()
        self._flush_lock = threading.Lock()
        self._windows: dict[str, Aggregate] = {}
        self._dirty: set[str] = set()
        self._last_flush = clock.monotonic()

    # ---- interfaces.Metrics --------------------------------------------------------------------------------

    def incr(self, name: str, n: int = 1, **labels: str) -> None:
        try:
            self._record(name, labels, counter=int(n))
        except Exception:
            log.debug("metrics: incr failed", exc_info=True)

    def observe_ms(self, name: str, value_ms: float, **labels: str) -> None:
        try:
            self._record(name, labels, value_ms=float(value_ms))
        except Exception:
            log.debug("metrics: observe failed", exc_info=True)

    # ---- flushing ------------------------------------------------------------------------------------------

    def flush(self) -> bool:
        """Write this shard's dirty windows to the hot store. False when the store was unavailable (kept for the
        next flush)."""
        with self._flush_lock:
            with self._lock:
                dirty = {w: Aggregate.from_dict(self._windows[w].to_dict()) for w in self._dirty}
                self._dirty.clear()
                self._last_flush = self._clock.monotonic()
            ok = True
            for window, agg in dirty.items():
                try:
                    self._store.put(f"{KEY_PREFIX}{window}:{self.shard}", agg.to_dict())
                except StoreUnavailable:
                    ok = False
                    with self._lock:
                        self._dirty.add(window)
            self._forget_old()
            return ok

    def _record(
        self, name: str, labels: Mapping[str, str], *, counter: int = 0, value_ms: float | None = None
    ) -> None:
        window = window_id(self._clock.now_utc())
        key = series_key(name, labels)
        with self._lock:
            agg = self._windows.setdefault(window, Aggregate())
            is_new = key not in agg.counters and key not in agg.hists
            if is_new and agg.series >= MAX_SERIES_PER_WINDOW:
                key, value_ms, counter = DROPPED, None, 1
            if value_ms is None:
                agg.counters[key] = agg.counters.get(key, 0) + counter
            else:
                agg.hists.setdefault(key, Histogram()).observe(value_ms)
            self._dirty.add(window)
            due = not self._flushing and self._clock.monotonic() - self._last_flush >= self._flush_interval_s
            if due and self._background:
                self._flushing = True
        if not due:
            return
        if self._background:
            threading.Thread(target=self._flush_in_background, name="metrics-flush", daemon=True).start()
        else:
            self.flush()

    def _flush_in_background(self) -> None:
        try:
            self.flush()
        finally:
            with self._lock:
                self._flushing = False

    def _forget_old(self) -> None:
        cutoff = window_id(self._clock.now_utc() - timedelta(seconds=LOCAL_KEEP_S))
        with self._lock:
            for window in [w for w in self._windows if w < cutoff and w not in self._dirty]:
                del self._windows[window]

    # ---- reading (dispatcher) ------------------------------------------------------------------------------

    def collect(self, now: datetime | None = None) -> MetricsView:
        """One scan of the hot store: windows from the last ~2 whole hours merged across shards, plus the hourly
        rollups of the last 25 hours."""
        now = now or self._clock.now_utc()
        view = MetricsView(now=now)
        for key in self._store.keys_with_prefix(KEY_PREFIX):
            if m := _WINDOW_KEY.match(key):
                view.window_keys.setdefault(m.group(1), []).append(key)
            elif m := _HOUR_KEY.match(key):
                view.hour_keys[m.group(1)] = key
        since = (now - timedelta(seconds=LATE_FLUSH_S)).replace(minute=0, second=0, microsecond=0)
        for window, keys in view.window_keys.items():
            if window_start(window) >= since:
                view.windows[window] = Aggregate.merged(Aggregate.from_dict(self._store.get(k)) for k in keys)
        hours_since = now - timedelta(hours=25)
        for hour, key in view.hour_keys.items():
            if hour_start(hour) >= hours_since:
                view.hours[hour] = Aggregate.from_dict(self._store.get(key))
        return view

    def snapshot_to_firestore(self, firestore: Firestore | None, now: datetime | None = None) -> MetricsView:
        """Snapshot finished windows to Firestore, refresh hourly rollups and prune the hot store. Returns the
        view (with fresh rollups) for the alert rules."""
        now = now or self._clock.now_utc()
        self.flush()
        view = self.collect(now)
        state = self._store.get(SNAPSTATE_KEY) or {}
        digests: dict[str, str] = dict(state.get("digests", {}))
        days: set[str] = set(state.get("days", []))
        changed_hours: set[str] = set()
        closed_before = now - timedelta(seconds=SNAPSHOT_GRACE_S + WINDOW_S)
        for window, agg in sorted(view.windows.items()):
            if window_start(window) > closed_before:
                continue
            digest = agg.digest()
            if digests.get(window) == digest:
                continue
            changed_hours.add(window[:10])
            if firestore is not None and not self._write_window(firestore, window, agg, now, days):
                continue  # retried next run
            digests[window] = digest
        for hour in changed_hours:
            rollup = Aggregate.merged(
                agg
                for w, agg in view.windows.items()
                if w.startswith(hour) and window_start(w) <= closed_before
            )
            self._store.put(f"{KEY_PREFIX}hour:{hour}", rollup.to_dict())
            view.hours[hour] = rollup
        self._prune(view, now)
        keep_after = window_id(now - timedelta(seconds=KEEP_WINDOWS_S))
        state = {
            "digests": {w: d for w, d in digests.items() if w >= keep_after},
            "days": sorted(d for d in days if d >= ist_day(now - timedelta(days=2))),
        }
        self._store.put(SNAPSTATE_KEY, state)
        return view

    def _write_window(
        self, firestore: Firestore, window: str, agg: Aggregate, now: datetime, days: set[str]
    ) -> bool:
        start = window_start(window)
        day = ist_day(start)
        try:
            if day not in days:
                # The day doc lets the cleanup job find the day's shards (a parent doc must exist to be queried).
                firestore.set(f"metrics/{day}", {"day": day, "created_at": now}, merge=True)
                days.add(day)
            firestore.set(f"metrics/{day}/shards/{window}", window_doc(day, window, agg, now))
        except FirestoreUnavailable as e:
            log.warning("metrics snapshot: Firestore unavailable (%s)", type(e).__name__)
            return False
        return True

    def _prune(self, view: MetricsView, now: datetime) -> None:
        old_window = window_id(now - timedelta(seconds=KEEP_WINDOWS_S))
        old_hour = (now - timedelta(seconds=KEEP_HOURS_S)).strftime("%Y%m%d%H")
        for window, keys in view.window_keys.items():
            if window < old_window:
                for key in keys:
                    self._store.delete(key)
        for hour, key in view.hour_keys.items():
            if hour < old_hour:
                self._store.delete(key)


def window_doc(day: str, window: str, agg: Aggregate, now: datetime) -> dict[str, Any]:
    latency = {
        key: {
            "count": hist.count,
            "sum_ms": round(hist.sum_ms, 1),
            "p50_ms": hist.quantile(0.5),
            "p95_ms": hist.quantile(0.95),
            "max_ms": round(hist.max_ms, 1),
        }
        for key, hist in agg.hists.items()
    }
    return {
        "day": day,
        "window": window,
        "window_start": window_start(window),
        "counters": dict(agg.counters),
        "latency": latency,
        "exceptions": agg.counter(EXCEPTIONS),
        "created_at": now,
    }


# ---- recording helpers for the request path ----------------------------------------------------------------


def status_class(status: int) -> str:
    return f"{status // 100}xx" if 100 <= status < 600 else "other"


def record_request(metrics: Metrics, route: str, status: int, latency_ms: float) -> None:
    """One finished HTTP request. `route` is the route template (never a raw path with ids)."""
    metrics.incr(HTTP_REQUESTS, route=route, status_class=status_class(status))
    metrics.observe_ms(HTTP_LATENCY_MS, latency_ms, route=route)


def record_exception(metrics: Metrics, exc: BaseException) -> None:
    """Counted by class name only (m3); the message may carry user text and is never recorded."""
    metrics.incr(EXCEPTIONS, type=type(exc).__name__)


def record_session_event(metrics: Metrics, event: Mapping[str, Any]) -> None:
    """The phone's per-session counters from `/v1/event` (metadata only)."""
    for name in SESSION_EVENT_FIELDS:
        value = event.get(name)
        if isinstance(value, int) and not isinstance(value, bool) and 0 < value <= 10_000:
            metrics.incr(EVENT_PREFIX + name, value)


def firestore_event_hook(metrics: Metrics):
    """`FirestoreRest(on_event=...)`: counts shed/buffered/exhausted events."""
    return lambda kind: metrics.incr(FIRESTORE_EVENTS, kind=kind)
