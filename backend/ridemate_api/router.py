"""Provider router (architecture §4.2 "Provider router", §4.18 E4/L4).

Every model call goes through `ProviderRouter.call`, which in order: resolves the provider and structured mode
from Remote Config `model_route`, confirms the pinned model id is served (lazy, cached), asks the circuit
breaker, reserves the worst-case cost in the spend meter, sends the request under its deadline, and settles
the reservation with the real usage (kept in full on a timeout, released when nothing was sent). A read is
retried once only when the first attempt failed without timing out and at least 6 s of the phone's 20 s tap
budget remain.
"""

from __future__ import annotations

import asyncio
import logging
import math
import os
from collections import deque
from collections.abc import Mapping
from dataclasses import dataclass, field
from enum import StrEnum
from typing import Any, Protocol

import httpx

from .clock import Clock
from .interfaces import ApiError, Metrics, Principal, Purpose, RemoteConfig, Reservation, SpendMeter
from .model_gateway import (
    ModelBadOutput,
    ModelError,
    ModelGateway,
    ModelRequest,
    ModelResult,
    ModelTimeout,
    ModelUnavailable,
    ModelUnsupported,
    StructuredMode,
    parse_mode,
)
from .settings import (
    DEADLINE_PARSE_S,
    DEADLINE_PLACE_S,
    DEADLINE_POINT_S,
    DEADLINE_READ_S,
    PINNED_MODEL_ID,
    PRICE_INPUT_PER_M,
    PRICE_OUTPUT_PER_M,
    RETRY_MIN_REMAINING_S,
    TAP_BUDGET_S,
    Settings,
)

log = logging.getLogger(__name__)

DEFAULT_PROVIDER = "modal_spark_open"
STRUCTURED_MODE_ENV = "MODEL_STRUCTURED_MODE"
IMAGE_TOKENS_ESTIMATE = 2500
CHARS_PER_TOKEN = 3
MODEL_CHECK_TIMEOUT_S = 5.0
MODEL_MISSING_RECHECK_S = 300.0
MODEL_CHECK_FAILED_BACKOFF_S = 30.0
# Rejected before inference: no tokens were processed, so the reservation settles at zero.
_ZERO_COST_STATUSES = frozenset({400, 401, 403, 404, 413, 422, 429})


class ModelProvider(Protocol):
    name: str
    model_id: str

    async def complete(self, request: ModelRequest, *, mode: StructuredMode | None = None) -> ModelResult: ...

    async def list_models(self) -> list[str]: ...


class TaskKind(StrEnum):
    READ = "read"
    REREAD = "reread"
    POINT = "point"
    PARSE = "parse"
    PLACE = "place"


@dataclass(frozen=True)
class TaskPolicy:
    deadline_s: float
    retry: bool  # one retry inside the tap budget (read-screen only)
    tap_budget: bool  # bounded by the phone's X-Tap-Deadline-Ms
    route_key: str  # model_route key consulted when the task has no key of its own


DEFAULT_POLICIES: Mapping[TaskKind, TaskPolicy] = {
    TaskKind.READ: TaskPolicy(DEADLINE_READ_S, retry=True, tap_budget=True, route_key="read"),
    TaskKind.REREAD: TaskPolicy(DEADLINE_READ_S, retry=False, tap_budget=True, route_key="read"),
    TaskKind.POINT: TaskPolicy(DEADLINE_POINT_S, retry=False, tap_budget=False, route_key="read"),
    TaskKind.PARSE: TaskPolicy(DEADLINE_PARSE_S, retry=False, tap_budget=False, route_key="parse"),
    TaskKind.PLACE: TaskPolicy(DEADLINE_PLACE_S, retry=False, tap_budget=False, route_key="parse"),
}


def estimate_input_tokens(request: ModelRequest) -> int:
    return request.image_count * IMAGE_TOKENS_ESTIMATE + math.ceil(request.text_chars / CHARS_PER_TOKEN)


def max_cost_usd(request: ModelRequest) -> float:
    """Worst case for one attempt: estimated input at the uncached price plus every allowed output token."""
    return (
        estimate_input_tokens(request) * PRICE_INPUT_PER_M + request.max_tokens * PRICE_OUTPUT_PER_M
    ) / 1_000_000


@dataclass(frozen=True)
class RouterResult:
    data: dict[str, Any]
    model: str
    provider: str
    mode: StructuredMode
    latency_ms: int
    cost_usd: float
    attempts: int


class BreakerState(StrEnum):
    CLOSED = "closed"
    OPEN = "open"
    HALF_OPEN = "half_open"


@dataclass
class CircuitBreaker:
    """5 failures within 60 s open it for 60 s; then exactly one half-open probe decides."""

    clock: Clock
    threshold: int = 5
    window_s: float = 60.0
    open_s: float = 60.0
    state: BreakerState = BreakerState.CLOSED
    _failures: deque[float] = field(default_factory=deque)
    _opened_at: float = 0.0
    _probe_in_flight: bool = False

    def allow(self) -> bool:
        if self.state is BreakerState.CLOSED:
            return True
        now = self.clock.monotonic()
        if self.state is BreakerState.OPEN:
            if now - self._opened_at < self.open_s:
                return False
            self.state = BreakerState.HALF_OPEN
            self._probe_in_flight = False
        if self._probe_in_flight:
            return False
        self._probe_in_flight = True
        return True

    def retry_after_s(self) -> int:
        if self.state is not BreakerState.OPEN:
            return 1
        return max(1, math.ceil(self.open_s - (self.clock.monotonic() - self._opened_at)))

    def record_success(self) -> None:
        if self.state is BreakerState.HALF_OPEN:
            self.state = BreakerState.CLOSED
            self._failures.clear()
        self._probe_in_flight = False

    def record_failure(self) -> None:
        now = self.clock.monotonic()
        if self.state is BreakerState.HALF_OPEN:
            self._open(now)
            return
        self._failures.append(now)
        while self._failures and now - self._failures[0] > self.window_s:
            self._failures.popleft()
        if len(self._failures) >= self.threshold:
            self._open(now)

    def abandon(self) -> None:
        """The allowed call never reached the provider; let the next caller be the probe."""
        self._probe_in_flight = False

    @property
    def is_open(self) -> bool:
        return self.state is BreakerState.OPEN and self.clock.monotonic() - self._opened_at < self.open_s

    def _open(self, now: float) -> None:
        self.state = BreakerState.OPEN
        self._opened_at = now
        self._failures.clear()
        self._probe_in_flight = False


@dataclass
class _ModelCheck:
    present: bool | None = None  # None = not confirmed yet
    checked_at: float = -math.inf
    failed_at: float = -math.inf


class ProviderRouter:
    def __init__(
        self,
        *,
        providers: Mapping[str, ModelProvider],
        spend: SpendMeter,
        remote_config: RemoteConfig,
        metrics: Metrics,
        clock: Clock,
        default_provider: str = DEFAULT_PROVIDER,
        policies: Mapping[TaskKind, TaskPolicy] | None = None,
        breaker_threshold: int = 5,
        breaker_window_s: float = 60.0,
        breaker_open_s: float = 60.0,
    ) -> None:
        if default_provider not in providers:
            raise ValueError(f"default provider {default_provider!r} is not registered")
        self._providers = dict(providers)
        self._default = default_provider
        self._spend = spend
        self._rc = remote_config
        self._metrics = metrics
        self._clock = clock
        self._policies = dict(policies or DEFAULT_POLICIES)
        self._breakers = {
            name: CircuitBreaker(clock, breaker_threshold, breaker_window_s, breaker_open_s)
            for name in providers
        }
        self._checks = {name: _ModelCheck() for name in providers}
        self._check_lock = asyncio.Lock()

    # ---- status for /v1/config-check, /readyz and alerts -------------------------------------------------

    def unavailable_reason(self, task: TaskKind = TaskKind.READ) -> str | None:
        """'model_missing' or 'breaker' when calls for `task` would be refused right now, else None (no I/O)."""
        provider = self._provider_for(task)
        if self._checks[provider.name].present is False:
            return "model_missing"
        if self._breakers[provider.name].is_open:
            return "breaker"
        return None

    async def model_available(self, task: TaskKind = TaskKind.READ) -> bool | None:
        """Confirms the pinned id with GET /models if not yet known. None when the endpoint couldn't be asked."""
        provider = self._provider_for(task)
        await self._check_model(provider)
        return self._checks[provider.name].present

    def breaker(self, provider: str = DEFAULT_PROVIDER) -> CircuitBreaker:
        return self._breakers[provider]

    # ---- the call ------------------------------------------------------------------------------------------

    async def call(
        self,
        request: ModelRequest,
        *,
        task: TaskKind,
        purpose: Purpose,
        principal: Principal | None,
        request_id: str,
        deadline_at: float | None = None,
    ) -> RouterResult:
        """`deadline_at` is the monotonic instant the phone's tap budget ends (read and reread only)."""
        policy = self._policies[task]
        provider = self._provider_for(task)
        mode = self._mode_for(task, provider)
        breaker = self._breakers[provider.name]
        started = self._clock.monotonic()
        if policy.tap_budget and deadline_at is None:
            deadline_at = started + TAP_BUDGET_S

        # The lazy model check runs inside the first attempt's deadline, so the endpoint's E4 deadline holds
        # even on a container's first call.
        await self._check_model(provider, timeout_s=min(MODEL_CHECK_TIMEOUT_S, policy.deadline_s / 2))
        if self._checks[provider.name].present is False:
            self._metrics.incr("ai_call", task=task.value, outcome="model_missing")
            raise ApiError(503, "ai_unavailable", reason="model_missing")

        attempt = 0
        while True:
            attempt += 1
            now = self._clock.monotonic()
            timeout_s = policy.deadline_s - (now - started) if attempt == 1 else policy.deadline_s
            if policy.tap_budget and deadline_at is not None:
                timeout_s = min(timeout_s, deadline_at - now)
            if timeout_s <= 0:
                self._metrics.incr("ai_call", task=task.value, outcome="no_budget")
                raise ApiError(504, "model_timeout")
            if not breaker.allow():
                self._metrics.incr("ai_call", task=task.value, outcome="breaker_open")
                raise ApiError(503, "ai_unavailable", reason="breaker", retry_after_s=breaker.retry_after_s())

            try:
                reservation = self._spend.reserve(
                    purpose=purpose,
                    max_usd=max_cost_usd(request),
                    principal=principal,
                    request_id=f"{request_id}:{task.value}:{attempt}",
                )
            except BaseException:
                breaker.abandon()
                raise

            sent_at = self._clock.monotonic()
            try:
                async with asyncio.timeout(timeout_s):
                    result = await provider.complete(request, mode=mode)
            except (TimeoutError, ModelTimeout):
                self._spend.keep(reservation)
                breaker.record_failure()
                self._metrics.incr("ai_call", task=task.value, outcome="timeout")
                raise ApiError(504, "model_timeout") from None
            except ModelError as e:
                self._settle_failure(reservation, e)
                breaker.record_failure()
                if e.status == 404:
                    # The pinned id may have been withdrawn since it was confirmed: look again on the next call,
                    # so AI switches off with reason model_missing (and the alert fires) instead of failing on.
                    self._checks[provider.name] = _ModelCheck()
                outcome = _failure_outcome(e)
                self._metrics.incr("ai_call", task=task.value, outcome=outcome)
                log.warning("model call failed: task=%s outcome=%s status=%s", task.value, outcome, e.status)
                if (
                    policy.retry
                    and attempt == 1
                    and _retryable(e)
                    and deadline_at is not None
                    and deadline_at - self._clock.monotonic() >= RETRY_MIN_REMAINING_S
                ):
                    self._metrics.incr("ai_retry", task=task.value)
                    continue
                raise ApiError(503, "ai_unavailable") from None
            except BaseException:
                # Cancelled mid-flight (client went away): the request may have been processed.
                self._spend.keep(reservation)
                breaker.abandon()
                raise

            cost = self._settle_success(reservation, result)
            breaker.record_success()
            latency_ms = int((self._clock.monotonic() - sent_at) * 1000)
            self._metrics.incr("ai_call", task=task.value, outcome="ok", mode=result.mode.value)
            self._metrics.observe_ms("ai_model_latency_ms", latency_ms, task=task.value)
            return RouterResult(
                data=result.data,
                model=result.model,
                provider=provider.name,
                mode=result.mode,
                latency_ms=latency_ms,
                cost_usd=cost,
                attempts=attempt,
            )

    # ---- internals -----------------------------------------------------------------------------------------

    def _route(self) -> dict[str, Any]:
        route = self._rc.get_json("model_route", {})
        return route if isinstance(route, dict) else {}

    def _provider_for(self, task: TaskKind) -> ModelProvider:
        route = self._route()
        name = route.get(task.value) or route.get(self._policies[task].route_key) or self._default
        provider = self._providers.get(str(name))
        if provider is None:
            self._metrics.incr("ai_route_unknown", task=task.value)
            provider = self._providers[self._default]
        return provider

    def _mode_for(self, task: TaskKind, provider: ModelProvider) -> StructuredMode | None:
        """Remote Config may pin a structured mode per task or globally; otherwise the provider's own pin."""
        route = self._route()
        raw = route.get(f"structured_mode_{task.value}") or route.get("structured_mode")
        if raw is None:
            return None
        return parse_mode(raw, getattr(provider, "pinned_mode", StructuredMode.JSON_SCHEMA))

    async def _check_model(
        self, provider: ModelProvider, *, timeout_s: float = MODEL_CHECK_TIMEOUT_S
    ) -> None:
        check = self._checks[provider.name]
        now = self._clock.monotonic()
        if check.present is True:
            return
        if check.present is False and now - check.checked_at < MODEL_MISSING_RECHECK_S:
            return
        if check.present is None and now - check.failed_at < MODEL_CHECK_FAILED_BACKOFF_S:
            return
        async with self._check_lock:
            check = self._checks[provider.name]
            now = self._clock.monotonic()
            if check.present is True:
                return
            try:
                async with asyncio.timeout(timeout_s):
                    ids = await provider.list_models()
            except (TimeoutError, ModelError):
                # Unknown, not missing: the call itself decides, and the breaker handles an endpoint outage.
                check.failed_at = now
                self._metrics.incr("ai_model_check_failed", provider=provider.name)
                return
            check.present = provider.model_id in ids
            check.checked_at = now
            if not check.present:
                self._metrics.incr("ai_model_missing", provider=provider.name)
                log.error("pinned model %s is not served by %s; AI is off", provider.model_id, provider.name)

    def _settle_failure(self, reservation: Reservation, error: ModelError) -> None:
        if not error.sent:
            self._spend.release(reservation)
        elif error.usage is not None:
            self._spend.settle(
                reservation,
                input_tokens=error.usage.input_tokens,
                cached_input_tokens=error.usage.cached_input_tokens,
                output_tokens=error.usage.output_tokens,
            )
        elif error.status in _ZERO_COST_STATUSES:
            self._spend.settle(reservation, input_tokens=0, cached_input_tokens=0, output_tokens=0)
        else:
            self._spend.keep(reservation)

    def _settle_success(self, reservation: Reservation, result: ModelResult) -> float:
        if result.usage is None:
            return self._spend.keep(reservation)
        return self._spend.settle(
            reservation,
            input_tokens=result.usage.input_tokens,
            cached_input_tokens=result.usage.cached_input_tokens,
            output_tokens=result.usage.output_tokens,
        )


def _retryable(error: ModelError) -> bool:
    if isinstance(error, ModelUnsupported):
        return False
    # Auth, rate limit or a malformed request won't change on an immediate retry.
    return not (isinstance(error, ModelUnavailable) and error.status is not None and error.status < 500)


def _failure_outcome(error: ModelError) -> str:
    if isinstance(error, ModelBadOutput):
        return "bad_output"
    if isinstance(error, ModelUnsupported):
        return "unsupported"
    if error.status is not None:
        return f"http_{error.status}"
    return "connection"


def build_providers(
    settings: Settings,
    *,
    http_client: httpx.AsyncClient | None = None,
    structured_mode: str | None = None,
) -> dict[str, ModelProvider]:
    """The registered providers: today Muse Spark Open on Modal's shared endpoint under the pinned id. A funded
    provider is one more entry here; which provider serves which task is then switched in `model_route`.

    The structured-output mode found on day 1 is pinned by the `MODEL_STRUCTURED_MODE` environment variable
    (json_schema | tool | prompt; json_schema when unset or unknown). Remote Config `model_route`
    (`structured_mode`, `structured_mode_<task>`) overrides it per call without a redeploy."""
    if not settings.ai_configured:
        return {}
    raw_mode = structured_mode if structured_mode is not None else os.environ.get(STRUCTURED_MODE_ENV, "")
    mode = parse_mode(raw_mode.strip().lower(), StructuredMode.JSON_SCHEMA)
    return {
        DEFAULT_PROVIDER: ModelGateway(
            name=DEFAULT_PROVIDER,
            base_url=settings.model_base_url,
            api_key=settings.model_api_key,
            model_id=PINNED_MODEL_ID,
            mode=mode,
            http_client=http_client,
        )
    }
