"""Interfaces shared between backend modules. Implementations live in their own modules (see docs/contracts.md §3);
endpoint code depends only on these shapes, and tests swap in fakes.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime
from enum import StrEnum
from typing import Any, Literal, Protocol

from fastapi import Request


class ApiError(Exception):
    """Raised anywhere on the request path; app.py renders it as the §1.2 error envelope."""

    def __init__(
        self,
        status: int,
        code: str,
        *,
        resume_at: datetime | None = None,
        retry_after_s: int | None = None,
        reason: str | None = None,
    ) -> None:
        super().__init__(code)
        self.status = status
        self.code = code
        self.resume_at = resume_at
        self.retry_after_s = retry_after_s
        self.reason = reason


@dataclass(frozen=True)
class Principal:
    uid: str
    provider: str  # firebase.sign_in_provider: "google.com", "anonymous", ...
    sub_hmac: str  # HMAC(Google sub) with the current key; HMAC(uid) for non-Google tokens
    email: str | None = None
    email_verified: bool = False
    auth_time: datetime | None = None
    is_owner: bool = False
    is_judge: bool = False
    raw_token: str = field(default="", repr=False)
    sub_hmac_previous: str | None = None  # same sub under the previous HMAC key, for the 30-day dual read

    @property
    def is_google(self) -> bool:
        return self.provider == "google.com"

    @property
    def uid_hash(self) -> str:
        return self.sub_hmac[:16]


class Purpose(StrEnum):
    """What a model call is for. Each maps to a sub-budget in the spend meter."""

    READ = "read"
    REREAD = "reread"
    POINT = "point"
    PARSE = "parse"
    PLACE = "place"
    DEMO = "demo"  # demo-image cache miss
    EVAL = "eval"  # eval harness runs
    DOCTOR = "doctor"  # Template Doctor
    PROBE = "probe"  # cron probes and day-1 spikes


@dataclass
class Reservation:
    id: str
    purpose: Purpose
    reserved_usd: float
    shard: str
    request_id: str
    settled: bool = False


@dataclass(frozen=True)
class AiStatus:
    enabled: bool
    reason: str | None = None  # see contracts §1.3 config-check
    resume_at: datetime | None = None


class Auth(Protocol):
    async def verify(self, request: Request, *, require_google: bool) -> Principal: ...

    async def check_not_revoked(self, principal: Principal) -> None: ...


class IpLimiter(Protocol):
    def check(self, request: Request) -> None:
        """Raise ApiError(429, 'rate_limited', retry_after_s=…) when the client IP bucket is empty."""


QuotaKind = Literal["read", "parse"]


class QuotaManager(Protocol):
    def consume(self, principal: Principal, kind: QuotaKind, request_id: str) -> None:
        """Count one unit, idempotent on request_id. Raise ApiError(429, 'quota_exhausted', resume_at=…)."""

    def refund(self, principal: Principal, kind: QuotaKind, request_id: str) -> None:
        """Undo a consume when the server failed before producing a result (timeout, breaker)."""

    def remaining(self, principal: Principal) -> dict[str, int]: ...

    def is_young(self, principal: Principal) -> bool: ...


class SpendMeter(Protocol):
    def reserve(self, *, purpose: Purpose, max_usd: float, principal: Principal | None, request_id: str) -> Reservation:
        """Reserve the worst-case cost before a model call. Raise ApiError(429,'ai_paused',…) when a budget,
        the day's pacing cap or a tier pause blocks it, ApiError(503,'meter_unavailable') when unreadable."""

    def settle(self, reservation: Reservation, *, input_tokens: int, cached_input_tokens: int, output_tokens: int) -> float:
        """Replace the reservation with the actual cost; returns USD charged."""

    def keep(self, reservation: Reservation) -> float:
        """Timeouts/unknown usage: charge the full reservation."""

    def release(self, reservation: Reservation) -> None:
        """The call was never sent: drop the reservation."""

    def ai_status(self, principal: Principal | None) -> AiStatus: ...

    def record_compute(self, *, cpu_cores: float, memory_mib: float, seconds: float, source: str) -> float: ...

    def summary(self) -> dict[str, Any]: ...


class FirestoreUnavailable(Exception):
    pass


class FirestoreQuotaExhausted(FirestoreUnavailable):
    pass


class Firestore(Protocol):
    """Document paths look like 'ondc_txn/abc'. Values are plain JSON types plus datetime."""

    def get(self, path: str) -> dict[str, Any] | None: ...

    def set(self, path: str, data: dict[str, Any], *, merge: bool = False) -> None: ...

    def delete(self, path: str) -> None: ...

    def increment(self, path: str, field_name: str, amount: float) -> None: ...

    def query(self, collection: str, field_name: str, op: str, value: Any, *, limit: int = 100) -> list[tuple[str, dict[str, Any]]]: ...


class RemoteConfig(Protocol):
    def get_bool(self, key: str, default: bool) -> bool: ...

    def get_int(self, key: str, default: int) -> int: ...

    def get_str(self, key: str, default: str) -> str: ...

    def get_json(self, key: str, default: Any) -> Any: ...


class Metrics(Protocol):
    def incr(self, name: str, n: int = 1, **labels: str) -> None: ...

    def observe_ms(self, name: str, value_ms: float, **labels: str) -> None: ...
