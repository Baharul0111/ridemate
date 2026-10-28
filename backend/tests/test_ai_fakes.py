"""Fakes for the AI and ONDC tests, written against the Protocols in ridemate_api.interfaces.

No test functions here; the test_ai_* and test_ondc_* modules import from it.
"""

from __future__ import annotations

import asyncio
import copy
import threading
from collections import Counter
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from ridemate_api.api import ai as ai_api
from ridemate_api.api import ondc as ondc_api
from ridemate_api.clock import FakeClock, ist_hhmm
from ridemate_api.deps import Deps
from ridemate_api.interfaces import (
    AiStatus,
    ApiError,
    FirestoreQuotaExhausted,
    FirestoreUnavailable,
    Principal,
    Purpose,
    QuotaKind,
    Reservation,
)
from ridemate_api.model_gateway import ModelRequest, ModelResult, ModelTimeout, StructuredMode, Usage
from ridemate_api.settings import PINNED_MODEL_ID, Settings
from ridemate_api.store import MemoryStore

GOOGLE = "Bearer google:"
ANON = "Bearer anon:"


def google_headers(uid: str = "u1", **extra: str) -> dict[str, str]:
    return {"Authorization": f"{GOOGLE}{uid}", **extra}


def anon_headers(uid: str = "a1") -> dict[str, str]:
    return {"Authorization": f"{ANON}{uid}"}


class FakeAuth:
    """Tokens look like 'google:<uid>' or 'anon:<uid>'."""

    def __init__(self) -> None:
        self.revoked: set[str] = set()
        self.calls: list[bool] = []
        self.revocation_checks: list[str] = []

    async def verify(self, request: Request, *, require_google: bool) -> Principal:
        self.calls.append(require_google)
        header = request.headers.get("authorization", "")
        if not header.startswith("Bearer "):
            raise ApiError(401, "auth_required")
        kind, _, uid = header[len("Bearer ") :].partition(":")
        if kind not in ("google", "anon") or not uid:
            raise ApiError(401, "auth_invalid")
        if kind == "anon" and require_google:
            raise ApiError(403, "google_required")
        provider = "google.com" if kind == "google" else "anonymous"
        return Principal(uid=uid, provider=provider, sub_hmac=f"hmac-{uid}".ljust(32, "0"))

    async def check_not_revoked(self, principal: Principal) -> None:
        self.revocation_checks.append(principal.uid)
        if principal.uid in self.revoked:
            raise ApiError(401, "auth_invalid")


class FakeIpLimiter:
    def __init__(self) -> None:
        self.blocked = False
        self.checks = 0

    def check(self, request: Request) -> None:
        self.checks += 1
        if self.blocked:
            raise ApiError(429, "rate_limited", retry_after_s=30)


class FakeQuotas:
    def __init__(self) -> None:
        self.consumed: list[tuple[str, QuotaKind, str]] = []
        self.refunded: list[tuple[str, QuotaKind, str]] = []
        self.exhausted = False

    def consume(self, principal: Principal, kind: QuotaKind, request_id: str) -> bool:
        if self.exhausted:
            raise ApiError(429, "quota_exhausted")
        self.consumed.append((principal.uid, kind, request_id))
        return True

    def refund(self, principal: Principal, kind: QuotaKind, request_id: str) -> None:
        self.refunded.append((principal.uid, kind, request_id))

    def remaining(self, principal: Principal) -> dict[str, int]:
        return {"reads_left": 6, "parses_left": 10}

    def is_young(self, principal: Principal) -> bool:
        return False


@dataclass
class FakeSpend:
    paused: bool = False
    reservations: list[Reservation] = field(default_factory=list)
    settled: list[tuple[str, int, int, int]] = field(default_factory=list)
    kept: list[str] = field(default_factory=list)
    released: list[str] = field(default_factory=list)

    def reserve(
        self, *, purpose: Purpose, max_usd: float, principal: Principal | None, request_id: str
    ) -> Reservation:
        if self.paused:
            raise ApiError(429, "ai_paused")
        reservation = Reservation(f"r{len(self.reservations) + 1}", purpose, max_usd, "s0", request_id)
        self.reservations.append(reservation)
        return reservation

    def settle(
        self, reservation: Reservation, *, input_tokens: int, cached_input_tokens: int, output_tokens: int
    ) -> float:
        reservation.settled = True
        self.settled.append((reservation.id, input_tokens, cached_input_tokens, output_tokens))
        return (input_tokens * 1.25 + output_tokens * 4.25) / 1_000_000

    def keep(self, reservation: Reservation) -> float:
        reservation.settled = True
        self.kept.append(reservation.id)
        return reservation.reserved_usd

    def release(self, reservation: Reservation) -> None:
        reservation.settled = True
        self.released.append(reservation.id)

    def ai_status(self, principal: Principal | None) -> AiStatus:
        return AiStatus(enabled=not self.paused)

    def record_compute(self, *, cpu_cores: float, memory_mib: float, seconds: float, source: str) -> float:
        return 0.0

    def summary(self) -> dict[str, Any]:
        return {}

    @property
    def purposes(self) -> list[Purpose]:
        return [r.purpose for r in self.reservations]


class FakeRemoteConfig:
    def __init__(self, values: dict[str, Any] | None = None) -> None:
        self.values: dict[str, Any] = dict(values or {})

    def get_bool(self, key: str, default: bool) -> bool:
        return bool(self.values.get(key, default))

    def get_int(self, key: str, default: int) -> int:
        return int(self.values.get(key, default))

    def get_str(self, key: str, default: str) -> str:
        return str(self.values.get(key, default))

    def get_json(self, key: str, default: Any) -> Any:
        return self.values.get(key, default)


class FakeMetrics:
    def __init__(self) -> None:
        self.counts: Counter[tuple[str, tuple[tuple[str, str], ...]]] = Counter()
        self.observations: list[tuple[str, float]] = []

    def incr(self, name: str, n: int = 1, **labels: str) -> None:
        self.counts[(name, tuple(sorted(labels.items())))] += n

    def observe_ms(self, name: str, value_ms: float, **labels: str) -> None:
        self.observations.append((name, value_ms))

    def total(self, name: str, **labels: str) -> int:
        return sum(
            n
            for (metric, lbls), n in self.counts.items()
            if metric == name and set(labels.items()) <= set(lbls)
        )


class FakeFirestore:
    """Top-level-field merge semantics, like a Firestore update with a field mask."""

    def __init__(self) -> None:
        self.docs: dict[str, dict[str, Any]] = {}
        self.fail: type[FirestoreUnavailable] | None = None
        self._lock = threading.Lock()

    def _check(self) -> None:
        if self.fail is not None:
            raise self.fail("forced")

    def get(self, path: str) -> dict[str, Any] | None:
        self._check()
        with self._lock:
            doc = self.docs.get(path)
            return copy.deepcopy(doc) if doc is not None else None

    def set(self, path: str, data: dict[str, Any], *, merge: bool = False) -> None:
        self._check()
        with self._lock:
            if merge and path in self.docs:
                self.docs[path].update(copy.deepcopy(data))
            else:
                self.docs[path] = copy.deepcopy(data)

    def delete(self, path: str) -> None:
        self._check()
        with self._lock:
            self.docs.pop(path, None)

    def increment(self, path: str, field_name: str, amount: float) -> None:
        self._check()
        with self._lock:
            doc = self.docs.setdefault(path, {})
            doc[field_name] = doc.get(field_name, 0) + amount

    def query(
        self, collection: str, field_name: str, op: str, value: Any, *, limit: int = 100
    ) -> list[tuple[str, dict[str, Any]]]:
        self._check()
        with self._lock:
            return [
                (path, copy.deepcopy(doc))
                for path, doc in self.docs.items()
                if path.startswith(f"{collection}/") and op == "==" and doc.get(field_name) == value
            ][:limit]

    def collection(self, name: str) -> dict[str, dict[str, Any]]:
        return {path: doc for path, doc in self.docs.items() if path.startswith(f"{name}/")}


QUOTA_EXHAUSTED = FirestoreQuotaExhausted

Reply = dict[str, Any] | Exception | Callable[[ModelRequest], Any]


class FakeProvider:
    """A ModelProvider whose replies are scripted per task (or one queue for every task)."""

    def __init__(self, name: str = "modal_spark_open", model_id: str = PINNED_MODEL_ID) -> None:
        self.name = name
        self.model_id = model_id
        self.pinned_mode = StructuredMode.JSON_SCHEMA
        self.replies: dict[str, list[Reply]] = {}
        self.default: dict[str, Reply] = {}
        self.requests: list[tuple[ModelRequest, StructuredMode | None]] = []
        self.models: list[str] | Exception = [model_id]
        self.list_calls = 0
        self.usage = Usage(input_tokens=1500, cached_input_tokens=0, output_tokens=300)
        self.on_call: Callable[[], Any] | None = None

    def script(self, task: str, *replies: Reply) -> None:
        self.replies.setdefault(task, []).extend(replies)

    async def complete(self, request: ModelRequest, *, mode: StructuredMode | None = None) -> ModelResult:
        self.requests.append((request, mode))
        if self.on_call is not None:
            result = self.on_call()
            if asyncio.iscoroutine(result):
                await result
        queue = self.replies.get(request.task)
        reply = queue.pop(0) if queue else self.default.get(request.task, ModelTimeout("unscripted"))
        if callable(reply) and not isinstance(reply, Exception):
            reply = reply(request)
            if asyncio.iscoroutine(reply):
                reply = await reply
        if isinstance(reply, Exception):
            raise reply
        return ModelResult(data=reply, usage=self.usage, mode=mode or self.pinned_mode, model=self.model_id)

    async def list_models(self) -> list[str]:
        self.list_calls += 1
        if isinstance(self.models, Exception):
            raise self.models
        return list(self.models)

    @property
    def calls(self) -> int:
        return len(self.requests)


def make_settings(**overrides: Any) -> Settings:
    base: dict[str, Any] = {
        "model_base_url": "https://spark.example.test",
        "model_token_id": "wk-test",
        "model_token_secret": "ws-test",
        "ondc_mode": "mock",
        "public_base_url": "https://ridemate-api.example.test",
    }
    base.update(overrides)
    return Settings(**base)


def make_deps(
    *,
    settings: Settings | None = None,
    rc: dict[str, Any] | None = None,
    firestore: FakeFirestore | None = None,
    clock: FakeClock | None = None,
) -> Deps:
    return Deps(
        settings=settings or make_settings(),
        clock=clock or FakeClock(),
        store=MemoryStore(),
        auth=FakeAuth(),
        ip_limiter=FakeIpLimiter(),
        quotas=FakeQuotas(),
        spend=FakeSpend(),
        firestore=firestore,
        remote_config=FakeRemoteConfig(rc),
        metrics=FakeMetrics(),
    )


def make_app(deps: Deps) -> FastAPI:
    """A minimal app with the §1.2 error envelope, the AI routes and the ONDC routes."""
    app = FastAPI()
    app.state.deps = deps

    @app.exception_handler(ApiError)
    async def _api_error(request: Request, exc: ApiError) -> JSONResponse:
        error: dict[str, Any] = {"code": exc.code, "request_id": request.headers.get("x-request-id")}
        if exc.retry_after_s is not None:
            error["retry_after_s"] = exc.retry_after_s
        if isinstance(exc.resume_at, datetime):
            error["resume_at_ist"] = ist_hhmm(exc.resume_at)
        return JSONResponse({"error": error}, status_code=exc.status)

    app.include_router(ai_api.router)
    ondc_api.mount(app, deps.settings)
    return app
