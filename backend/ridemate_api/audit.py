"""Metadata audit records (architecture §4.18 I8, §5 `audit`): Modal keeps logs for one day only, and the DPDP
Rules want request metadata for a year, so each AI request leaves a record in Firestore `audit`.

What a record holds: endpoint, outcome, source, latency bucket, cost, app, versionCode and the time, under the
caller's uid hash. Never image bytes, OCR or notification text, model output or the raw session id. Only
Google-signed-in callers are recorded (server writes happen only for them, §4.2 "Store").

Writes. Firestore's free tier allows 20k writes a day, so requests are aggregated per session (X-Session-Id) in
the hot store and written as ONE `audit/{id}` document when the session ends: on `/v1/event`, or by the dispatcher
once the session has been idle for 30 minutes (or open for 6 hours). The document goes through
`Firestore.set` like every other write, so it is counted in the write budget, and when the quota is exhausted it
is parked under `buffer:audit:*` and replayed by the dispatcher after the reset (firestore.py). A user opens at
most `SESSIONS_PER_DAY` audit sessions a day; past that, records fold into one overflow session for the day, so
rotating session ids can't turn into Firestore writes. Requests without a session id share a per-user daily one.

Hot-store layout (Modal Dict has no atomic increment, so every request claims its own slot with put_if_absent):
    audit:open:{sk}             {"gen", "uid_hash", "opened_at"}   the session's open generation
    audit:rec:{sk}:{gen}:{n}    one request's record, n = 0, 1, 2, … (contiguous: claims take the lowest free slot)
    audit:hint:{sk}:{gen}       {"n": next free slot, "last_at": epoch s}  a scan hint, not the truth
where sk = sha256(HMAC(sub) + session id). A flush first deletes `audit:open:{sk}` (sealing the generation, so later
requests open a new one), then reads the slots until the first gap, writes the document and deletes the slots.
A request that read the open generation just before the seal can still land one slot after the flush read it; the
dispatcher writes such leftovers (and anything a failed flush kept) as their own document after a short grace, so
records are never lost. A hot-store failure in the middle of a flush can duplicate records, never drop them.
"""

from __future__ import annotations

import hashlib
import json
import logging
import re
import uuid
from collections import Counter
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Any

from .clock import Clock, ist_day
from .interfaces import Firestore, FirestoreUnavailable, Metrics, Principal
from .limits import allow_daily
from .store import HotStore, StoreUnavailable

log = logging.getLogger(__name__)

PREFIX = "audit:"
OPEN_PREFIX = "audit:open:"
REC_PREFIX = "audit:rec:"
HINT_PREFIX = "audit:hint:"
COLLECTION = "audit"

MAX_RECORDS_PER_SESSION = (
    100  # ≈ 16 model calls a day per user plus refusals; a document stays well under 1 MiB
)
SESSIONS_PER_DAY = 30  # the same per-user ceiling as /v1/event
IDLE_FLUSH_S = 30 * 60
MAX_OPEN_S = 6 * 3600
LATE_GRACE_S = 5 * 60  # a sealed generation's leftovers wait this long, so a flush in progress can finish
MAX_DOCS_PER_RUN = 500
CAP_ACTION = "audit_session"

# (upper bound in ms, bucket) — the record keeps the bucket, the exact latency stays in the 1-day logs.
LATENCY_BUCKETS: tuple[tuple[int, str], ...] = (
    (1_000, "lt_1s"),
    (2_000, "1_2s"),
    (4_000, "2_4s"),
    (8_000, "4_8s"),
    (12_000, "8_12s"),
    (20_000, "12_20s"),
)
LATENCY_OVER = "gt_20s"
_ENUM = re.compile(r"^[a-z0-9_]{1,40}$")
OTHER = "other"


def latency_bucket(latency_ms: float) -> str:
    for bound, name in LATENCY_BUCKETS:
        if latency_ms < bound:
            return name
    return LATENCY_OVER


def _enum(value: Any) -> str | None:
    """An enum-like value as recorded: lower-case, digits and underscores, ≤ 40 chars; anything else is 'other'."""
    if value is None:
        return None
    text = str(value).lower()
    return text if _ENUM.match(text) else OTHER


def _version(value: Any) -> int | None:
    if isinstance(value, bool) or not isinstance(value, int) or not 0 <= value <= 2_000_000_000:
        return None
    return value


@dataclass(frozen=True)
class AuditEntry:
    """One request, as the endpoint saw it."""

    endpoint: str
    outcome: str
    latency_ms: int
    cost_usd: float = 0.0
    source: str | None = None
    app: str | None = None
    version_code: int | None = None


class AuditTrail:
    def __init__(
        self,
        *,
        store: HotStore,
        clock: Clock,
        firestore: Firestore,
        metrics: Metrics,
        max_records: int = MAX_RECORDS_PER_SESSION,
        sessions_per_day: int = SESSIONS_PER_DAY,
        idle_flush_s: float = IDLE_FLUSH_S,
        max_open_s: float = MAX_OPEN_S,
        late_grace_s: float = LATE_GRACE_S,
    ) -> None:
        self._store = store
        self._clock = clock
        self._fs = firestore
        self._metrics = metrics
        self._max_records = max_records
        self._sessions_per_day = sessions_per_day
        self._idle_flush_s = idle_flush_s
        self._max_open_s = max_open_s
        self._late_grace_s = late_grace_s

    # ---- the request path (blocking: endpoints call these off the event loop) -------------------------------

    def record(self, principal: Principal, session_id: str | None, entry: AuditEntry) -> bool:
        """Add one request to its session. Never raises: an audit failure must not fail the request."""
        if not principal.is_google:
            return False
        now = self._clock.now_utc()
        try:
            sk, gen = self._open(principal, self.session_key(principal, session_id, now), now)
            record = self._record_doc(principal, entry, now)
            if self._claim(sk, gen, record, now):
                return True
        except StoreUnavailable:
            self._metrics.incr("audit_dropped", reason="store")
            return False
        except Exception as e:  # noqa: BLE001 - metadata only; the request has already been answered
            log.warning("audit record failed: %s", type(e).__name__)
            self._metrics.incr("audit_dropped", reason="error")
            return False
        self._metrics.incr("audit_dropped", reason="session_full")
        return False

    def flush_session(self, principal: Principal, session_id: str | None) -> int:
        """Close the caller's session now (/v1/event). Returns the documents written; never raises."""
        if not principal.is_google:
            return 0
        sk = self.session_key(principal, session_id, self._clock.now_utc())
        try:
            return self._flush_open(sk)
        except StoreUnavailable:
            self._metrics.incr("audit_flush_failed", reason="store")
            return 0
        except Exception as e:  # noqa: BLE001 - /v1/event already stored the event; the dispatcher retries this
            log.warning("audit flush failed: %s", type(e).__name__)
            self._metrics.incr("audit_flush_failed", reason="error")
            return 0

    # ---- the dispatcher -----------------------------------------------------------------------------------

    def flush_idle(
        self, *, stop: Callable[[], bool] | None = None, max_docs: int = MAX_DOCS_PER_RUN
    ) -> dict[str, int]:
        """Flush sessions idle for 30 min (or open for 6 h), then sealed leftovers, and drop stale hints.
        `stop()` is checked between sessions (the dispatcher's time limit). Raises StoreUnavailable."""
        result = {"sessions": 0, "late": 0, "docs": 0, "open": 0}
        now = self._clock.now_utc().timestamp()
        opens: set[str] = set()
        slots: dict[tuple[str, str], list[int]] = {}
        hints: set[tuple[str, str]] = set()
        for key in self._store.keys_with_prefix(PREFIX):
            parts = key.split(":")
            if key.startswith(OPEN_PREFIX) and len(parts) == 3:
                opens.add(parts[2])
            elif key.startswith(REC_PREFIX) and len(parts) == 5 and parts[4].isdigit():
                slots.setdefault((parts[2], parts[3]), []).append(int(parts[4]))
            elif key.startswith(HINT_PREFIX) and len(parts) == 4:
                hints.add((parts[2], parts[3]))

        def out_of_time() -> bool:
            return result["docs"] >= max_docs or (stop is not None and stop())

        still_open: set[tuple[str, str]] = set()
        flushed: set[tuple[str, str]] = (
            set()
        )  # a failed flush is retried on the next run, not twice in this one
        for sk in sorted(opens):
            if out_of_time():
                return result
            meta = self._store.get(OPEN_PREFIX + sk)
            if not _valid_meta(meta):
                continue
            gen = str(meta["gen"])
            hint = self._store.get(f"{HINT_PREFIX}{sk}:{gen}")
            last = _float(hint.get("last_at")) if isinstance(hint, Mapping) else None
            opened = _float(meta.get("opened_at")) or now
            if now - (last or opened) < self._idle_flush_s and now - opened < self._max_open_s:
                still_open.add((sk, gen))
                result["open"] += 1
                continue
            written = self._flush_open(sk)
            flushed.add((sk, gen))
            result["sessions"] += 1
            result["docs"] += written

        for (sk, gen), numbers in sorted(slots.items()):
            if (sk, gen) in still_open or (sk, gen) in flushed:
                continue
            if out_of_time():
                return result
            records = self._read_slots(sk, gen, sorted(numbers))
            if not records:
                continue
            if now - max(_float(r.get("at")) or 0.0 for _, r in records) < self._late_grace_s:
                continue  # a flush of this generation may still be running
            written = self._write(sk, gen, records)
            if written:
                self._delete_slots(sk, gen, records)
                result["late"] += len(records)
                result["docs"] += written

        for sk, gen in sorted(hints):  # hints left by a flush that failed half-way, once nobody can use them
            if (sk, gen) in still_open or (sk, gen) in slots:
                continue
            hint = self._store.get(f"{HINT_PREFIX}{sk}:{gen}")
            last = _float(hint.get("last_at")) if isinstance(hint, Mapping) else None
            if last is None or now - last >= self._idle_flush_s:
                self._store.delete(f"{HINT_PREFIX}{sk}:{gen}")
        return result

    # ---- internals ----------------------------------------------------------------------------------------

    @staticmethod
    def session_key(principal: Principal, session_id: str | None, now: datetime) -> str:
        session = session_id or f"day:{ist_day(now)}"
        return hashlib.sha256(f"{principal.sub_hmac}:{session}".encode()).hexdigest()[:32]

    def _open(self, principal: Principal, sk: str, now: datetime) -> tuple[str, str]:
        """(session key, generation) to record into, opening a generation when none is open."""
        meta = self._store.get(OPEN_PREFIX + sk)
        if _valid_meta(meta):
            return sk, str(meta["gen"])
        overflow = self.session_key(principal, f"overflow:{ist_day(now)}", now)
        if sk != overflow and not allow_daily(
            self._store, self._clock, principal, CAP_ACTION, self._sessions_per_day
        ):
            self._metrics.incr("audit_overflow")
            return self._open(principal, overflow, now)
        fresh = {"gen": uuid.uuid4().hex[:12], "uid_hash": principal.uid_hash, "opened_at": now.timestamp()}
        self._store.put_if_absent(OPEN_PREFIX + sk, fresh)
        meta = self._store.get(OPEN_PREFIX + sk)  # a concurrent request may have opened it first
        return sk, (str(meta["gen"]) if _valid_meta(meta) else fresh["gen"])

    def _claim(self, sk: str, gen: str, record: dict[str, Any], now: datetime) -> bool:
        hint_key = f"{HINT_PREFIX}{sk}:{gen}"
        hint = self._store.get(hint_key)
        start = int(_float(hint.get("n")) or 0) if isinstance(hint, Mapping) else 0
        for n in range(min(max(start, 0), self._max_records), self._max_records):
            if self._store.put_if_absent(f"{REC_PREFIX}{sk}:{gen}:{n}", record):
                self._store.put(hint_key, {"n": n + 1, "last_at": now.timestamp()})
                return True
        return False

    @staticmethod
    def _record_doc(principal: Principal, entry: AuditEntry, now: datetime) -> dict[str, Any]:
        return {
            "uid_hash": principal.uid_hash,
            "endpoint": _enum(entry.endpoint),
            "outcome": _enum(entry.outcome),
            "source": _enum(entry.source),
            "latency_bucket": latency_bucket(entry.latency_ms),
            "cost_usd": round(max(0.0, float(entry.cost_usd)), 6),
            "app": _enum(entry.app),
            "version_code": _version(entry.version_code),
            "at": now.timestamp(),
        }

    def _flush_open(self, sk: str) -> int:
        meta = self._store.get(OPEN_PREFIX + sk)
        if not _valid_meta(meta):
            return 0
        gen = str(meta["gen"])
        self._store.delete(OPEN_PREFIX + sk)  # sealed: requests from now on open a new generation
        records: list[tuple[int, dict[str, Any]]] = []
        for n in range(self._max_records):
            record = self._store.get(f"{REC_PREFIX}{sk}:{gen}:{n}")
            if not isinstance(record, dict):
                break
            records.append((n, record))
        if not records:
            self._store.delete(f"{HINT_PREFIX}{sk}:{gen}")
            return 0
        written = self._write(sk, gen, records)
        if written:  # otherwise the slots stay and the dispatcher retries them as a sealed generation
            self._delete_slots(sk, gen, records)
        return written

    def _read_slots(self, sk: str, gen: str, numbers: list[int]) -> list[tuple[int, dict[str, Any]]]:
        out = []
        for n in numbers:
            record = self._store.get(f"{REC_PREFIX}{sk}:{gen}:{n}")
            if isinstance(record, dict):
                out.append((n, record))
        return out

    def _delete_slots(self, sk: str, gen: str, records: list[tuple[int, dict[str, Any]]]) -> None:
        for n, _ in records:
            self._store.delete(f"{REC_PREFIX}{sk}:{gen}:{n}")
        self._store.delete(f"{HINT_PREFIX}{sk}:{gen}")

    def _write(self, sk: str, gen: str, records: list[tuple[int, dict[str, Any]]]) -> int:
        """One `audit/{id}` document for these records. The id is derived from the generation and the records
        themselves (slot numbers and contents), so a retried flush of the same slots rewrites the same document,
        while a late record that reused a slot number the flush had already cleared (its hint was gone too) gets a
        document of its own instead of overwriting the flushed one."""
        digest = hashlib.sha256(
            json.dumps(records, sort_keys=True, separators=(",", ":"), default=str).encode()
        ).hexdigest()
        doc_id = hashlib.sha256(f"{sk}:{gen}:{digest}".encode()).hexdigest()[:32]
        try:
            self._fs.set(f"{COLLECTION}/{doc_id}", audit_document(records, self._clock.now_utc()))
        except FirestoreUnavailable as e:  # quota exhausted with a full buffer, or Firestore down
            log.warning("audit flush deferred: %s", type(e).__name__)
            self._metrics.incr("audit_flush_failed", reason=type(e).__name__)
            return 0
        except Exception as e:  # noqa: BLE001 - the slots stay and are retried; one bad session never stops the rest
            log.warning("audit flush failed: %s", type(e).__name__)
            self._metrics.incr("audit_flush_failed", reason="error")
            return 0
        self._metrics.incr("audit_flushed")
        return 1


def audit_document(records: list[tuple[int, dict[str, Any]]], now: datetime) -> dict[str, Any]:
    entries = [record for _, record in records]
    times = [_float(e.get("at")) or now.timestamp() for e in entries]
    apps = {e.get("app") for e in entries if e.get("app")}
    versions = [v for e in entries if (v := _version(e.get("version_code"))) is not None]
    first_at = datetime.fromtimestamp(min(times), UTC)
    return {
        "uid_hash": next((str(e["uid_hash"]) for e in entries if e.get("uid_hash")), None),
        "day": ist_day(first_at),
        "first_at": first_at,
        "last_at": datetime.fromtimestamp(max(times), UTC),
        "count": len(entries),
        "cost_usd": round(sum(_float(e.get("cost_usd")) or 0.0 for e in entries), 6),
        "app": apps.pop() if len(apps) == 1 else ("mixed" if apps else None),
        "version_code": versions[-1] if versions else None,
        "outcomes": dict(Counter(str(e.get("outcome") or OTHER) for e in entries)),
        "requests": [
            {
                "endpoint": e.get("endpoint"),
                "outcome": e.get("outcome"),
                "source": e.get("source"),
                "latency_bucket": e.get("latency_bucket"),
                "cost_usd": _float(e.get("cost_usd")) or 0.0,
                "app": e.get("app"),
                "version_code": _version(e.get("version_code")),
                "at": datetime.fromtimestamp(t, UTC),
            }
            for e, t in zip(entries, times, strict=True)
        ],
        "created_at": now,
    }


def _valid_meta(meta: Any) -> bool:
    return isinstance(meta, Mapping) and isinstance(meta.get("gen"), str) and bool(meta["gen"])


def _float(value: Any) -> float | None:
    if isinstance(value, bool):
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None
