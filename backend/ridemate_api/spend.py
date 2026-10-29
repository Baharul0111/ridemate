"""The all-spend credit meter (architecture §4.2 "Credit budget", §4.18 L1).

Every dollar RideMate spends on Modal goes through `SpendMeter`: each model call (reserve before the call, then
settle with the real usage, keep the whole reservation on a timeout, or release it when nothing was sent) and the
containers' CPU/memory time (`record_compute`). Amounts are integer nano-USD, so sums are exact.

Budgets (settings.py): AI $80 = app traffic (the rest) + judging $15 + evals/Template Doctor $5 (≤ $0.50 per run)
+ probes $2 + demo cache misses $3 + dev deployments $1; compute $12; margin $8. Compute that overruns its budget
eats the margin first and only then the AI headroom, so the total never passes the $100 credit. App traffic is also
held to the daily pacing cap, and young or heavy accounts pause at 70% of it. Demo misses have a small daily cap of
their own (DEMO_DAY_CAP_USD), so anonymous demo traffic can never use up the public's day.

One meter per credit: every deployment writes to the hot store of the 'main' Modal environment (deps_prod.py), and
a deployment in any other environment (dev) charges everything it spends to the $1 'dev' sub-budget as well and
is never paced, so testing in dev can neither double the AI budget nor eat the public's day cap.

Storage. Modal Dict has no atomic increment, so each writer process owns a shard (MODAL_TASK_ID, else
local-<hostname>-<pid>) and only read-modify-writes its own keys, under a per-shard lock:
    spend:{shard}:{budget}:{period}   charged nano-USD; period is 'lifetime' or an IST day 'YYYY-MM-DD'
    spend:{shard}:reserved            {"items": {id: {n, budgets, day, at}}, "updated_at": epoch s}
Totals are sums over the shards found with keys_with_prefix('spend:') (the list is cached ~30 s).
Containers come and go, so `compact()` (run by the dispatcher cron) folds idle shards into
    spend:rollup                      {"frozen": [shard, ...], "totals": {label: nano-USD}}
after writing the marker spend:frozen:{shard}. Writers check their marker and the rollup's frozen list after every
write and, if their shard was frozen, move to a fresh shard and re-apply that write there: a race can over-count,
never lose spend.
Outstanding reservations of a frozen shard are counted as spent (a crashed call may still have been billed).
    spend:drift_factor                ≥ 1.0, set by billing reconciliation; multiplies every reservation and charge
"""

from __future__ import annotations

import hashlib
import json
import logging
import math
import os
import re
import socket
import threading
import uuid
from collections import defaultdict
from collections.abc import Iterable
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta
from typing import Any, Protocol

from .clock import IST, Clock, ist_day, next_ist_midnight
from .interfaces import (
    AiStatus,
    ApiError,
    Firestore,
    Principal,
    Purpose,
    RemoteConfig,
    Reservation,
)
from .settings import (
    BUDGET_AI_USD,
    BUDGET_COMPUTE_USD,
    BUDGET_MARGIN_USD,
    DEMO_DAY_CAP_USD,
    JUDGING_DAY_CAP_USD,
    PACING_MULTIPLIER,
    PRICE_CACHED_INPUT_PER_M,
    PRICE_CPU_PER_CORE_S,
    PRICE_INPUT_PER_M,
    PRICE_MEM_PER_GIB_S,
    PRICE_OUTPUT_PER_M,
    SUB_BUDGET_DEMO_USD,
    SUB_BUDGET_DEV_USD,
    SUB_BUDGET_EVAL_RUN_USD,
    SUB_BUDGET_EVALS_USD,
    SUB_BUDGET_JUDGING_USD,
    SUB_BUDGET_PROBES_USD,
    TIER_PAUSE_FRACTION,
    Settings,
)
from .store import HotStore, StoreUnavailable

log = logging.getLogger(__name__)

NANO = 1_000_000_000
LIFETIME = "lifetime"

AI = "ai"
APP = "app"
JUDGING = "judging"
EVALS = "evals"
PROBES = "probes"
DEMO = "demo"
DEV = "dev"
COMPUTE = "compute"
EVAL_RUN = "evalrun-"  # + run id; one per eval / Template Doctor run
EVAL_RUN_REQUEST_PREFIX = "eval-run:"
UNLABELLED_RUN = "unlabelled"  # eval calls without a run id share one per-run cap

PREFIX = "spend:"
ROLLUP_KEY = "spend:rollup"
DRIFT_KEY = "spend:drift_factor"
FROZEN_PREFIX = "spend:frozen:"
SNAPSHOT_MEMO_KEY = "snap:spend"
FS_COLLECTION = "spend_shards"
FS_ROLLUP_DOC = "spend_shards/rollup"
_RESERVED_NAMES = frozenset({"frozen", "rollup", "drift_factor"})

APP_BUDGET_USD = BUDGET_AI_USD - (
    SUB_BUDGET_EVALS_USD
    + SUB_BUDGET_PROBES_USD
    + SUB_BUDGET_JUDGING_USD
    + SUB_BUDGET_DEMO_USD
    + SUB_BUDGET_DEV_USD
)
TOTAL_CREDIT_USD = BUDGET_AI_USD + BUDGET_COMPUTE_USD + BUDGET_MARGIN_USD
SUB_BUDGETS_USD: dict[str, float] = {
    APP: APP_BUDGET_USD,
    JUDGING: SUB_BUDGET_JUDGING_USD,
    EVALS: SUB_BUDGET_EVALS_USD,
    PROBES: SUB_BUDGET_PROBES_USD,
    DEMO: SUB_BUDGET_DEMO_USD,
    DEV: SUB_BUDGET_DEV_USD,
}
APP_PURPOSES = frozenset({Purpose.READ, Purpose.REREAD, Purpose.POINT, Purpose.PARSE, Purpose.PLACE})
# Only app traffic draws on the public's daily share; demo misses have their own daily cap (DEMO_DAY_CAP_USD).
PACED_PURPOSES = APP_PURPOSES
PACED_BUDGETS = (APP,)

# Token prices as integer nano-USD per token ($1.25 / 1M = 1250 n$).
_NANO_INPUT = round(PRICE_INPUT_PER_M * 1000)
_NANO_CACHED = round(PRICE_CACHED_INPUT_PER_M * 1000)
_NANO_OUTPUT = round(PRICE_OUTPUT_PER_M * 1000)
_MAX_DRIFT = 10.0


def usd_to_nano(usd: float) -> int:
    return math.ceil(round(usd * NANO, 3))


def token_cost_nano(input_tokens: int, cached_input_tokens: int, output_tokens: int) -> int:
    """Cached tokens are a subset of the input tokens and are billed at the cached rate instead."""
    if min(input_tokens, cached_input_tokens, output_tokens) < 0:
        raise ValueError("token counts must be non-negative")
    cached = min(cached_input_tokens, input_tokens)
    return (input_tokens - cached) * _NANO_INPUT + cached * _NANO_CACHED + output_tokens * _NANO_OUTPUT


def token_cost_usd(input_tokens: int, cached_input_tokens: int, output_tokens: int) -> float:
    return token_cost_nano(input_tokens, cached_input_tokens, output_tokens) / NANO


def compute_cost_usd(cpu_cores: float, memory_mib: float, seconds: float) -> float:
    return seconds * (cpu_cores * PRICE_CPU_PER_CORE_S + memory_mib / 1024 * PRICE_MEM_PER_GIB_S)


def _safe_id(raw: str, limit: int = 96) -> str:
    cleaned = re.sub(r"[^A-Za-z0-9._-]", "_", raw)[:limit] or "x"
    return f"x-{cleaned}" if cleaned in _RESERVED_NAMES else cleaned


def default_shard_id() -> str:
    """This writer process's shard: the Modal task id, or local-<hostname>-<pid> off Modal."""
    task = os.environ.get("MODAL_TASK_ID", "")
    return _safe_id(task or f"local-{socket.gethostname()}-{os.getpid()}")


def eval_run_id(request_id: str) -> str:
    if request_id.startswith(EVAL_RUN_REQUEST_PREFIX):
        run = request_id[len(EVAL_RUN_REQUEST_PREFIX) :].split(":", 1)[0]
        if re.fullmatch(r"[A-Za-z0-9_-]{1,64}", run):
            return run
        if run:
            return hashlib.sha256(run.encode()).hexdigest()[:16]
    return UNLABELLED_RUN


def _shard_of(key: str) -> str | None:
    parts = key.split(":")
    if len(parts) < 3 or parts[0] != "spend" or parts[1] == "frozen":
        return None
    return parts[1]


def _int(value: Any) -> int:
    try:
        return int(value or 0)
    except (TypeError, ValueError):
        return 0


def _clamp_drift(value: Any) -> float:
    try:
        f = float(value)
    except (TypeError, ValueError):
        return 1.0
    if not math.isfinite(f):
        return 1.0
    return min(max(f, 1.0), _MAX_DRIFT)


def _paused(reason: str, resume_at: datetime | None) -> ApiError:
    return ApiError(429, "ai_paused", reason=reason, resume_at=resume_at)


def _meter_down() -> ApiError:
    return ApiError(503, "meter_unavailable", reason="meter")


class TierOracle(Protocol):
    """Account signals for the 70% tier (limits.QuotaManager implements it)."""

    def is_young(self, principal: Principal) -> bool: ...

    def is_heavy(self, principal: Principal) -> bool: ...


# One lock per shard id for the whole process, so every SpendMeter writing a shard serialises on it.
_LOCKS: dict[str, threading.RLock] = {}
_LOCKS_GUARD = threading.Lock()


def _shard_lock(shard: str) -> threading.RLock:
    with _LOCKS_GUARD:
        lock = _LOCKS.get(shard)
        if lock is None:
            lock = _LOCKS[shard] = threading.RLock()
        return lock


@dataclass(frozen=True)
class _Index:
    shards: frozenset[str]
    frozen: frozenset[str]
    rollup: dict[str, int]
    drift: float
    loaded_at: float


@dataclass
class _View:
    charged: dict[str, int]
    reserved: dict[str, int]

    def used(self, label: str) -> int:
        return self.charged.get(label, 0) + self.reserved.get(label, 0)


@dataclass
class _Pending:
    charges: dict[str, int]
    day: str
    remove: tuple[str, ...] = ()


@dataclass
class _ShardData:
    charged: dict[str, int] = field(default_factory=dict)
    items: dict[str, dict[str, Any]] = field(default_factory=dict)
    updated_at: float = 0.0


class SpendMeter:
    def __init__(
        self,
        *,
        store: HotStore,
        clock: Clock,
        settings: Settings,
        remote_config: RemoteConfig,
        tiers: TierOracle | None = None,
        firestore: Firestore | None = None,
        shard: str | None = None,
        index_ttl_s: float = 30.0,
        peer_ttl_s: float = 1.0,
        require_history: bool = False,
    ) -> None:
        """`require_history` (production): a missing rollup is accepted as a fresh meter only after Firestore
        confirmed there is no saved one; without Firestore the meter fails closed instead of restarting at $0."""
        self._store = store
        self._require_history = require_history
        self._clock = clock
        self._settings = settings
        self._rc = remote_config
        self._tiers = tiers
        self._firestore = firestore
        self._base_shard = _safe_id(shard) if shard else default_shard_id()
        self._shard = self._base_shard
        self._generation = 0
        self._index_ttl = index_ttl_s
        self._peer_ttl = peer_ttl_s
        self._index_cache: _Index | None = None
        self._peer_cache: dict[tuple[str, str], tuple[float, Any]] = {}
        self._hwm: dict[str, int] = {}
        self._drift = 1.0
        self._pending: list[_Pending] = []
        self._state_lock = threading.Lock()

    @property
    def shard(self) -> str:
        return self._shard

    # ---- the SpendMeter protocol ---------------------------------------------------------------------------

    def reserve(
        self, *, purpose: Purpose, max_usd: float, principal: Principal | None, request_id: str
    ) -> Reservation:
        if not math.isfinite(max_usd) or max_usd < 0:
            raise ValueError("max_usd must be a finite, non-negative amount")
        budgets = self.budgets_for(purpose, principal, request_id)
        paced = self._is_paced(purpose, principal)
        shard = self._shard
        try:
            with _shard_lock(shard):
                self._flush_pending()
                now = self._clock.now_utc()
                day = ist_day(now)
                idx = self._index()
                n = usd_to_nano(max_usd * idx.drift)
                view = self._view(self._labels(budgets, day))
                for b in budgets:
                    if view.used(f"{b}:{LIFETIME}") + n > self._limit(b, view):
                        raise _paused("budget", None)
                if paced:
                    cap = self._day_cap(view, now)
                    day_used = self._paced_day_used(view, day)
                    if day_used + n > cap:
                        raise _paused("day_cap", next_ist_midnight(now))
                    if self._tier_paused(purpose, principal, day_used, cap):
                        raise _paused("tier_pause", next_ist_midnight(now))
                if DEMO in budgets and view.used(f"{DEMO}:{day}") + n > usd_to_nano(DEMO_DAY_CAP_USD):
                    raise _paused("day_cap", next_ist_midnight(now))
                rid = uuid.uuid4().hex
                item = {"n": n, "budgets": list(budgets), "day": day, "at": now.timestamp()}
                shard = self._write({}, day, add={rid: item}, shard=shard)
        except StoreUnavailable:
            raise _meter_down() from None
        return Reservation(
            id=rid,
            purpose=purpose,
            reserved_usd=n / NANO,
            shard=shard,
            request_id=request_id,
            reserved_nusd=n,
            budgets=tuple(budgets),
            day=day,
        )

    def settle(
        self, reservation: Reservation, *, input_tokens: int, cached_input_tokens: int, output_tokens: int
    ) -> float:
        cost = token_cost_nano(input_tokens, cached_input_tokens, output_tokens)
        return self._finish(reservation, math.ceil(cost * self._current_drift()))

    def keep(self, reservation: Reservation) -> float:
        return self._finish(reservation, self._reserved_nano(reservation))

    def release(self, reservation: Reservation) -> None:
        self._finish(reservation, 0)

    def record_compute(self, *, cpu_cores: float, memory_mib: float, seconds: float, source: str) -> float:
        if min(cpu_cores, memory_mib, seconds) < 0:
            raise ValueError("compute usage must be non-negative")
        n = usd_to_nano(compute_cost_usd(cpu_cores, memory_mib, seconds) * self._current_drift())
        if n:
            self._charge({COMPUTE: n}, ist_day(self._clock.now_utc()), ())
            log.debug("compute recorded: source=%s nusd=%d", source, n)
        return n / NANO

    def ai_status(self, principal: Principal | None) -> AiStatus:
        if not self._rc.get_bool("ai_reader_enabled", True):
            return AiStatus(False, "kill_switch")
        purpose = Purpose.READ
        budgets = self.budgets_for(purpose, principal, "")
        now = self._clock.now_utc()
        day = ist_day(now)
        try:
            view = self._view(self._labels(budgets, day))
            over = self._compute_over_budget(view)
            for b in budgets:
                if view.used(f"{b}:{LIFETIME}") >= self._limit(b, view):
                    return AiStatus(False, "budget", compute_over_budget=over)
            if self._is_paced(purpose, principal):
                cap = self._day_cap(view, now)
                day_used = self._paced_day_used(view, day)
                if day_used >= cap:
                    return AiStatus(False, "day_cap", next_ist_midnight(now), compute_over_budget=over)
                if self._tier_paused(purpose, principal, day_used, cap):
                    return AiStatus(False, "tier_pause", next_ist_midnight(now), compute_over_budget=over)
        except StoreUnavailable:
            return AiStatus(False, "meter")
        return AiStatus(True, compute_over_budget=over)

    def summary(self) -> dict[str, Any]:
        """Developer-mode budget view (contracts §1.3 'budget'). Raises StoreUnavailable when unreadable."""
        now = self._clock.now_utc()
        day = ist_day(now)
        names = [AI, COMPUTE, *SUB_BUDGETS_USD]
        labels = {f"{b}:{LIFETIME}" for b in names} | self._labels((), day) | {f"{COMPUTE}:{day}"}
        view = self._view(labels)

        def usd(n: int) -> float:
            return round(n / NANO, 9)

        return {
            "ai_spent_usd": usd(view.charged.get(f"{AI}:{LIFETIME}", 0)),
            "ai_reserved_usd": usd(view.reserved.get(f"{AI}:{LIFETIME}", 0)),
            "ai_budget_usd": BUDGET_AI_USD,
            "ai_limit_usd": usd(self._limit(AI, view)),
            "compute_spent_usd": usd(view.charged.get(f"{COMPUTE}:{LIFETIME}", 0)),
            "compute_budget_usd": BUDGET_COMPUTE_USD,
            "compute_over_budget": self._compute_over_budget(view),
            "margin_usd": BUDGET_MARGIN_USD,
            "credit_usd": TOTAL_CREDIT_USD,
            "day_cap_usd": usd(self._day_cap(view, now)),
            "day_spent_usd": usd(self._paced_day_used(view, day)),
            "demo_day_cap_usd": DEMO_DAY_CAP_USD,
            "demo_day_spent_usd": usd(view.used(f"{DEMO}:{day}")),
            "credit_used_usd": usd(view.used(f"{AI}:{LIFETIME}") + view.used(f"{COMPUTE}:{LIFETIME}")),
            "ai_day_spent_usd": usd(view.used(f"{AI}:{day}")),
            "compute_day_spent_usd": usd(view.charged.get(f"{COMPUTE}:{day}", 0)),
            "horizon_end": self.horizon_end().isoformat(),
            "cap_profile": self._cap_profile(),
            "drift_factor": self._index().drift,
            "sub_budgets": {
                b: {"spent_usd": usd(view.used(f"{b}:{LIFETIME}")), "budget_usd": limit}
                for b, limit in SUB_BUDGETS_USD.items()
            },
        }

    # ---- budget rules --------------------------------------------------------------------------------------

    def budgets_for(self, purpose: Purpose, principal: Principal | None, request_id: str) -> list[str]:
        if purpose in (Purpose.EVAL, Purpose.DOCTOR):
            budgets = [AI, EVALS, EVAL_RUN + eval_run_id(request_id)]
        elif purpose == Purpose.PROBE:
            budgets = [AI, PROBES]
        elif not self._settings.is_meter_environment:
            return [AI, DEV]  # a dev deployment's app and demo traffic: its own $1, never the public's share
        elif purpose == Purpose.DEMO:
            budgets = [AI, DEMO]
        else:
            budgets = [AI, JUDGING if principal is not None and principal.is_judge else APP]
        if not self._settings.is_meter_environment:
            budgets.append(DEV)
        return budgets

    def _is_paced(self, purpose: Purpose, principal: Principal | None) -> bool:
        """App traffic is paced; judges, evals, probes, demo misses and dev deployments draw on their own
        (sub-)budgets instead."""
        if not self._settings.is_meter_environment:
            return False
        return purpose in PACED_PURPOSES and not (principal is not None and principal.is_judge)

    def _tier_paused(self, purpose: Purpose, principal: Principal | None, day_used: int, cap: int) -> bool:
        if principal is None or self._tiers is None or purpose not in APP_PURPOSES or not principal.is_google:
            return False
        if day_used < TIER_PAUSE_FRACTION * cap:
            return False
        return self._tiers.is_young(principal) or self._tiers.is_heavy(principal)

    def _limit(self, budget: str, view: _View) -> int:
        if budget == AI:
            # Compute overruns eat the margin first; past it they shrink the AI headroom so the credit holds.
            compute = max(usd_to_nano(BUDGET_COMPUTE_USD), view.used(f"{COMPUTE}:{LIFETIME}"))
            return max(0, min(usd_to_nano(BUDGET_AI_USD), usd_to_nano(TOTAL_CREDIT_USD) - compute))
        if budget.startswith(EVAL_RUN):
            return usd_to_nano(SUB_BUDGET_EVAL_RUN_USD)
        return usd_to_nano(SUB_BUDGETS_USD[budget])

    @staticmethod
    def _paced_day_used(view: _View, day: str) -> int:
        """Today's spend that the pacing cap governs: app traffic. Judges, evals, probes, demo misses and dev
        deployments are bounded by their own (sub-)budgets instead, so they never eat the public's daily share."""
        return sum(view.used(f"{b}:{day}") for b in PACED_BUDGETS)

    @staticmethod
    def _compute_over_budget(view: _View) -> bool:
        return view.used(f"{COMPUTE}:{LIFETIME}") > usd_to_nano(BUDGET_COMPUTE_USD)

    def _day_cap(self, view: _View, now: datetime) -> int:
        """(AI budget remaining at the start of today ÷ days until the horizon end) × 1.5, at least one day.
        "Remaining" counts every AI dollar (all purposes); the cap then governs paced spend only."""
        today = now.astimezone(IST).date()
        days = max(1, (self.horizon_end() - today).days)
        spent_before_today = view.charged.get(f"{AI}:{LIFETIME}", 0) - view.charged.get(f"{AI}:{today}", 0)
        remaining = max(0, self._limit(AI, view) - spent_before_today)
        cap = math.floor(remaining * PACING_MULTIPLIER / days)
        if self._cap_profile() == "judging":
            cap = min(cap, usd_to_nano(JUDGING_DAY_CAP_USD))
        return cap

    def horizon_end(self) -> date:
        """The credit expiry date when known, else budget_horizon_start + budget_horizon_days."""
        for raw in (self._rc.get_str("credit_expiry_date", ""), self._settings.credit_expiry_date):
            parsed = _date(raw)
            if parsed is not None:
                return parsed
        start = _date(self._rc.get_str("budget_horizon_start", "")) or _date(
            self._settings.budget_horizon_start
        )
        days = self._rc.get_int("budget_horizon_days", self._settings.budget_horizon_days)
        return (start or date(2026, 10, 28)) + timedelta(days=max(1, int(days)))

    def _cap_profile(self) -> str:
        return "judging" if self._rc.get_str("cap_profile", "normal") == "judging" else "normal"

    # ---- reading totals ------------------------------------------------------------------------------------

    @staticmethod
    def _labels(budgets: Iterable[str], day: str) -> set[str]:
        labels = {f"{b}:{LIFETIME}" for b in budgets}
        labels |= {f"{AI}:{LIFETIME}", f"{AI}:{day}", f"{COMPUTE}:{LIFETIME}"}
        labels |= {f"{b}:{day}" for b in (*PACED_BUDGETS, DEMO)}
        return labels

    def _index(self) -> _Index:
        now = self._clock.monotonic()
        idx = self._index_cache
        if idx is None or now - idx.loaded_at >= self._index_ttl:
            idx = self._load_index(now)
        return idx

    def _load_index(self, now: float) -> _Index:
        rollup = self._store.get(ROLLUP_KEY)
        if not isinstance(rollup, dict):
            rollup = self._bootstrap_rollup()
        frozen = frozenset(str(s) for s in rollup.get("frozen", []))
        shards = {s for k in self._store.keys_with_prefix(PREFIX) if (s := _shard_of(k)) is not None}
        drift = _clamp_drift(self._store.get(DRIFT_KEY, 1.0))
        idx = _Index(
            shards=frozenset(shards - frozen),
            frozen=frozen,
            rollup={str(k): _int(v) for k, v in dict(rollup.get("totals", {})).items()},
            drift=drift,
            loaded_at=now,
        )
        self._index_cache = idx
        self._drift = drift
        return idx

    def _bootstrap_rollup(self) -> dict[str, Any]:
        """No rollup in the Dict: either a fresh deployment or a lost Dict. Firestore tells them apart; if it
        can't be asked (unreachable, or not configured where history is required), fail closed rather than
        restart the meter from zero."""
        if self._firestore is None and self._require_history:
            raise StoreUnavailable("meter history unverifiable: Firestore not configured")
        if self._firestore is not None:
            try:
                saved = self._firestore.get(FS_ROLLUP_DOC)
            except Exception as e:  # any failure here means the meter's history is unknown
                raise StoreUnavailable(f"meter history unverifiable: {type(e).__name__}") from e
            if saved:
                log.warning("spend rollup missing from the hot store; restoring from Firestore")
                try:
                    self.restore_from_firestore()
                except StoreUnavailable:
                    raise
                except Exception as e:
                    raise StoreUnavailable(f"meter restore failed: {type(e).__name__}") from e
        self._store.put_if_absent(ROLLUP_KEY, {"frozen": [], "totals": {}, "updated_at": 0.0})
        rollup = self._store.get(ROLLUP_KEY)
        return rollup if isinstance(rollup, dict) else {"frozen": [], "totals": {}}

    def _view(self, labels: set[str]) -> _View:
        idx = self._index()
        charged = {label: idx.rollup.get(label, 0) for label in labels}
        reserved: dict[str, int] = defaultdict(int)
        own = {self._shard} - idx.frozen  # once frozen, its values live in the rollup until we move on
        for shard in idx.shards | own:
            fresh = shard == self._shard
            for label in labels:
                charged[label] += _int(self._read(shard, label, fresh))
            for item in self._items(self._read(shard, "reserved", fresh)).values():
                n = _int(item.get("n"))
                for b in item.get("budgets", ()):
                    reserved[f"{b}:{LIFETIME}"] += n
                    reserved[f"{b}:{item.get('day', '')}"] += n
        for label, value in charged.items():  # charges only grow; never trust a smaller sum than seen before
            charged[label] = max(value, self._hwm.get(label, 0))
            self._hwm[label] = charged[label]
        return _View(charged, dict(reserved))

    def _read(self, shard: str, label: str, fresh: bool) -> Any:
        key = (shard, label)
        now = self._clock.monotonic()
        if not fresh:
            hit = self._peer_cache.get(key)
            if hit is not None and now - hit[0] < self._peer_ttl:
                return hit[1]
        value = self._store.get(f"{PREFIX}{shard}:{label}")
        if not fresh:
            self._peer_cache[key] = (now, value)
        return value

    @staticmethod
    def _items(record: Any) -> dict[str, dict[str, Any]]:
        if isinstance(record, dict) and isinstance(record.get("items"), dict):
            return {str(k): v for k, v in record["items"].items() if isinstance(v, dict)}
        return {}

    def _current_drift(self) -> float:
        try:
            return self._index().drift
        except StoreUnavailable:
            return self._drift

    # ---- writing this shard --------------------------------------------------------------------------------

    def _reserved_nano(self, reservation: Reservation) -> int:
        return reservation.reserved_nusd or usd_to_nano(reservation.reserved_usd)

    def _finish(self, reservation: Reservation, charge: int) -> float:
        if reservation.settled:
            return 0.0
        reservation.settled = True
        budgets = reservation.budgets or tuple(
            self.budgets_for(reservation.purpose, None, reservation.request_id)
        )
        day = reservation.day or ist_day(self._clock.now_utc())
        # The item is removed from this process's current shard. If that shard has since moved on, the item sits
        # in a frozen shard that the rollup already counted as spent, and the charge below is an over-count.
        self._charge({b: charge for b in budgets} if charge else {}, day, (reservation.id,))
        return charge / NANO

    def _charge(self, charges: dict[str, int], day: str, remove: tuple[str, ...]) -> None:
        """Charges never fail the request that incurred them: if the store is down they queue in memory and
        flush with the next write, while the reservation keeps counting in the store."""
        op = _Pending(charges, day, remove)
        try:
            with _shard_lock(self._shard):
                self._flush_pending()
                self._write(op.charges, op.day, remove=op.remove)
        except StoreUnavailable:
            log.warning("spend meter write deferred: store unavailable")
            with self._state_lock:
                self._pending.append(op)

    def _flush_pending(self) -> None:
        with self._state_lock:
            pending, self._pending = self._pending, []
        for i, op in enumerate(pending):
            try:
                self._write(op.charges, op.day, remove=op.remove)
            except StoreUnavailable:
                with self._state_lock:
                    self._pending[:0] = pending[i:]
                raise

    def _write(
        self,
        charges: dict[str, int],
        day: str,
        *,
        add: dict[str, dict[str, Any]] | None = None,
        remove: tuple[str, ...] = (),
        shard: str | None = None,
    ) -> str:
        """Read-modify-write this process's shard. Charges are written before the reservation is removed, so a
        failure in between over-counts. Returns the shard that holds the write."""
        shard = shard or self._shard
        with _shard_lock(shard):
            for budget, n in charges.items():
                for period in (LIFETIME, day):
                    key = f"{PREFIX}{shard}:{budget}:{period}"
                    self._store.put(key, _int(self._store.get(key)) + n)
            key = f"{PREFIX}{shard}:reserved"
            items = self._items(self._store.get(key))
            for rid in remove:
                items.pop(rid, None)
            items.update(add or {})
            self._store.put(key, {"items": items, "updated_at": self._clock.now_utc().timestamp()})
            frozen = self._is_frozen(shard)
        if not frozen:
            return shard
        self._rotate(shard)
        return self._write(charges, day, add=add)

    def _is_frozen(self, shard: str) -> bool:
        """Frozen once compaction wrote its marker, or once the rollup lists it. Readers trust only the rollup's
        list, and the marker is an ordinary Dict entry that expires when idle, so a reused shard id (a recycled
        local pid, a long-lived container) must check both or its spend would be invisible to every reader."""
        if self._store.get(FROZEN_PREFIX + shard) is not None:
            return True
        idx = self._index_cache
        return idx is not None and shard in idx.frozen

    def _rotate(self, frozen_shard: str) -> None:
        with self._state_lock:
            if self._shard != frozen_shard:
                return
            while True:
                self._generation += 1
                candidate = f"{self._base_shard}.g{self._generation}"
                if not self._is_frozen(candidate):
                    break
            log.info("spend shard %s was compacted; continuing as %s", frozen_shard, candidate)
            self._shard = candidate
            self._index_cache = None

    # ---- maintenance (dispatcher cron) ---------------------------------------------------------------------

    def set_drift_factor(self, factor: float) -> float:
        """Billing reconciliation: tighten every reservation and charge by `factor` (never below 1.0)."""
        value = _clamp_drift(factor)
        self._store.put(DRIFT_KEY, value)
        self._index_cache = None
        return value

    def drift_factor(self) -> float:
        """The factor billing reconciliation last set (1.0 until then), read fresh from the store."""
        self._index_cache = None
        return self._index().drift

    def totals_usd(self) -> dict[str, float]:
        """Lifetime AI and compute spend for billing reconciliation."""
        view = self._view({f"{AI}:{LIFETIME}", f"{COMPUTE}:{LIFETIME}"})
        return {
            "ai": view.used(f"{AI}:{LIFETIME}") / NANO,
            "compute": view.used(f"{COMPUTE}:{LIFETIME}") / NANO,
        }

    def day_total_usd(self, day: str) -> float:
        """Everything the meter counted for one IST day (`YYYY-MM-DD`), AI and compute together, for billing
        reconciliation: live shards, the rollup of compacted shards, and reservations still outstanding for that
        day (counted as spent, as compaction would). Raises StoreUnavailable when the meter is unreadable."""
        if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", day) or _date(day) is None:
            raise ValueError("day must be an IST date YYYY-MM-DD")
        self._index_cache = None  # the dispatcher runs this once a day: read the current shard list
        view = self._view({f"{AI}:{day}", f"{COMPUTE}:{day}"})
        return (view.used(f"{AI}:{day}") + view.used(f"{COMPUTE}:{day}")) / NANO

    def compact(self, idle_s: float = 3600.0) -> list[str]:
        """Fold shards idle for `idle_s` into the rollup. Safe to run while writers are live (see module doc)."""
        rollup = self._store.get(ROLLUP_KEY)
        if not isinstance(rollup, dict):
            rollup = self._bootstrap_rollup()
        frozen = {str(s) for s in rollup.get("frozen", [])}
        totals: dict[str, int] = defaultdict(
            int, {str(k): _int(v) for k, v in rollup.get("totals", {}).items()}
        )
        now = self._clock.now_utc().timestamp()
        newly: list[str] = []
        for shard in sorted(self._keys_by_shard()):
            if shard in frozen:
                continue
            record = self._store.get(f"{PREFIX}{shard}:reserved")
            updated = float(record.get("updated_at", 0.0)) if isinstance(record, dict) else 0.0
            if now - updated >= idle_s:
                self._store.put(FROZEN_PREFIX + shard, now)
                newly.append(shard)
        if newly:
            # Keys are listed and read only after the markers exist: anything a writer adds later is re-applied by
            # that writer on a fresh shard, so nothing written before or after the marker is missed.
            by_shard = self._keys_by_shard()
            for shard in newly:
                data = self._shard_data(shard, by_shard.get(shard, ()))
                for label, n in data.charged.items():
                    totals[label] += n
                for item in data.items.values():
                    n = _int(item.get("n"))
                    for b in item.get("budgets", ()):
                        totals[f"{b}:{LIFETIME}"] += n
                        totals[f"{b}:{item.get('day', '')}"] += n
                frozen.add(shard)
        self._store.put(ROLLUP_KEY, {"frozen": sorted(frozen), "totals": dict(totals), "updated_at": now})
        self._index_cache = None
        if newly:
            log.info("spend meter compacted %d idle shards", len(newly))
        return newly

    def _keys_by_shard(self) -> dict[str, list[str]]:
        out: dict[str, list[str]] = defaultdict(list)
        for key in self._store.keys_with_prefix(PREFIX):
            shard = _shard_of(key)
            if shard is not None:
                out[shard].append(key)
        return out

    def _shard_data(self, shard: str, keys: Iterable[str]) -> _ShardData:
        data = _ShardData()
        prefix = f"{PREFIX}{shard}:"
        for key in keys:
            label = key[len(prefix) :]
            if label == "reserved":
                record = self._store.get(key)
                data.items = self._items(record)
                if isinstance(record, dict):
                    data.updated_at = float(record.get("updated_at", 0.0))
            elif label.count(":") == 1:
                data.charged[label] = _int(self._store.get(key))
        return data

    def snapshot_to_firestore(self, firestore: Firestore | None = None) -> int:
        """Write absolute values (set, not increment, so a rerun is harmless) of the rollup and each live shard
        to spend_shards/{period}_{shard}. Unchanged documents are skipped. Returns the number written."""
        fs = firestore or self._firestore
        if fs is None:
            return 0
        rollup = self._store.get(ROLLUP_KEY)
        if not isinstance(rollup, dict):
            rollup = self._bootstrap_rollup()
        frozen = {str(s) for s in rollup.get("frozen", [])}
        now = self._clock.now_utc()
        today = ist_day(now)
        yesterday = ist_day(now - timedelta(days=1))
        docs: dict[str, dict[str, Any]] = {
            FS_ROLLUP_DOC: {
                "period": "rollup",
                "frozen": sorted(frozen),
                "totals_nusd": {str(k): _int(v) for k, v in rollup.get("totals", {}).items()},
            }
        }
        for shard, keys in self._keys_by_shard().items():
            if shard in frozen:
                continue
            data = self._shard_data(shard, keys)
            reserved: dict[str, int] = defaultdict(int)
            for item in data.items.values():
                for b in item.get("budgets", ()):
                    reserved[b] += _int(item.get("n"))
            for period in (LIFETIME, today, yesterday):
                charged = {
                    lbl.split(":")[0]: n for lbl, n in data.charged.items() if lbl.endswith(f":{period}")
                }
                if period != LIFETIME and not charged:
                    continue
                res = dict(reserved) if period == LIFETIME else {}
                docs[f"{FS_COLLECTION}/{period}_{shard}"] = {
                    "period": period,
                    "shard": shard,
                    "charged_nusd": charged,
                    "reserved_nusd": res,
                    "usd": {b: (charged.get(b, 0) + res.get(b, 0)) / NANO for b in set(charged) | set(res)},
                }
        memo = self._store.get(SNAPSHOT_MEMO_KEY)
        memo = dict(memo) if isinstance(memo, dict) else {}
        new_memo: dict[str, str] = {}
        written = 0
        try:
            for path, doc in docs.items():
                digest = hashlib.sha256(json.dumps(doc, sort_keys=True).encode()).hexdigest()[:16]
                if memo.get(path) != digest:
                    fs.set(path, {**doc, "updated_at": now})
                    written += 1
                new_memo[path] = digest
        finally:
            self._store.put(SNAPSHOT_MEMO_KEY, new_memo)
        return written

    def restore_from_firestore(self, firestore: Firestore | None = None) -> int:
        """Refill what the Dict lost (entries expire after 7 idle days): the rollup if missing, then every
        missing key of a non-frozen shard, counting its outstanding reservations as spent. Never overwrites."""
        fs = firestore or self._firestore
        if fs is None:
            return 0
        restored = 0
        saved = fs.get(FS_ROLLUP_DOC)
        if saved and not isinstance(self._store.get(ROLLUP_KEY), dict):
            value = {
                "frozen": [str(s) for s in saved.get("frozen", [])],
                "totals": {str(k): _int(v) for k, v in dict(saved.get("totals_nusd", {})).items()},
                "updated_at": self._clock.now_utc().timestamp(),
            }
            restored += int(self._store.put_if_absent(ROLLUP_KEY, value))
        rollup = self._store.get(ROLLUP_KEY)
        frozen = {str(s) for s in rollup.get("frozen", [])} if isinstance(rollup, dict) else set()
        now = self._clock.now_utc()
        for period in (LIFETIME, ist_day(now), ist_day(now - timedelta(days=1))):
            for _doc_id, doc in fs.query(FS_COLLECTION, "period", "==", period, limit=1000):
                shard = _safe_id(str(doc.get("shard", "")))
                if not doc.get("shard") or shard in frozen:
                    continue
                reserved = dict(doc.get("reserved_nusd", {})) if period == LIFETIME else {}
                for budget, n in dict(doc.get("charged_nusd", {})).items():
                    value = _int(n) + _int(reserved.get(budget))
                    restored += int(self._store.put_if_absent(f"{PREFIX}{shard}:{budget}:{period}", value))
        self._index_cache = None
        return restored


def _date(raw: Any) -> date | None:
    try:
        return date.fromisoformat(str(raw))
    except ValueError:
        return None
