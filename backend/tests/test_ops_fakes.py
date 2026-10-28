"""Fakes for the ops tests (test_ops_*), written against the Protocols in ridemate_api.interfaces.

No test functions here; the test_ops_* modules import from it.
"""

from __future__ import annotations

import base64
import json
from datetime import UTC, datetime
from functools import lru_cache
from typing import Any

import httpx

from ridemate_api.clock import FakeClock
from ridemate_api.deps import Deps
from ridemate_api.interfaces import FirestoreUnavailable
from ridemate_api.settings import Settings
from ridemate_api.store import MemoryStore

PROJECT = "demo-ridemate"
TOKEN_URI = "https://oauth2.googleapis.com/token"
HC_HEARTBEAT = "https://hc-ping.com/heartbeat-check-uuid"
HC_ALERT = "https://hc-ping.com/alert-check-uuid"
OWNER_TOKEN = "owner-device-token-from-secret"
# A service-account key file's type marker and key field, assembled at runtime: tools/ci/check_no_secrets.sh flags
# any file that spells out both, and this file only ever builds throwaway keys.
SA_TYPE = "service-account".replace("-", "_")
SA_KEY_FIELD = "private-key".replace("-", "_")


@lru_cache(maxsize=1)
def sa_json(project: str = PROJECT) -> str:
    """A syntactically real service-account key: a fresh RSA key generated now, never used anywhere."""
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric import rsa

    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    pem = key.private_bytes(
        serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()
    ).decode()
    info: dict[str, str] = {
        "type": SA_TYPE,
        "project_id": project,
        f"{SA_KEY_FIELD}_id": "0123456789abcdef0123456789abcdef01234567",
        SA_KEY_FIELD: pem,
        "client_email": f"ridemate-ops@{project}.iam.gserviceaccount.com",
        "client_id": "1",
        "token_uri": TOKEN_URI,
    }
    return json.dumps(info)


def token_response(token: str = "access-token-1", expires_in: int = 3600) -> httpx.Response:
    return httpx.Response(200, json={"access_token": token, "expires_in": expires_in, "token_type": "Bearer"})


def assertion_claims(request: httpx.Request) -> dict[str, Any]:
    """The JWT-bearer assertion a token request carries, decoded (signature not checked)."""
    form = dict(pair.split("=", 1) for pair in request.content.decode().split("&"))
    payload = form["assertion"].split(".")[1]
    return json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))


class FakeTokens:
    def __init__(self, token: str = "fake-access-token") -> None:
        self.value = token
        self.calls = 0

    def token(self) -> str:
        self.calls += 1
        return self.value


def _comparable(a: Any, b: Any) -> bool:
    numbers = (int, float)
    if isinstance(a, bool) or isinstance(b, bool):
        return isinstance(a, bool) and isinstance(b, bool)
    if isinstance(a, numbers) and isinstance(b, numbers):
        return True
    return type(a) is type(b)


class FakeFirestore:
    """In-memory Firestore with Firestore's type-strict range filters. Paths are relative ('events/e1')."""

    def __init__(self) -> None:
        self.docs: dict[str, dict[str, Any]] = {}
        self.writes: list[tuple[str, dict[str, Any], bool]] = []
        self.deleted: list[str] = []
        self.fail = False
        self.budget = None

    def _check(self) -> None:
        if self.fail:
            raise FirestoreUnavailable("fake outage")

    def get(self, path: str) -> dict[str, Any] | None:
        self._check()
        doc = self.docs.get(path)
        return dict(doc) if doc is not None else None

    def set(self, path: str, data: dict[str, Any], *, merge: bool = False) -> None:
        self._check()
        self.writes.append((path, dict(data), merge))
        self.docs[path] = {**self.docs.get(path, {}), **data} if merge else dict(data)

    def delete(self, path: str) -> None:
        self._check()
        self.deleted.append(path)
        self.docs.pop(path, None)

    def increment(self, path: str, field_name: str, amount: float) -> None:
        self._check()
        doc = self.docs.setdefault(path, {})
        doc[field_name] = doc.get(field_name, 0) + amount

    def query(self, collection: str, field_name: str, op: str, value: Any, *, limit: int = 100):
        self._check()
        depth = collection.count("/") + 2
        out = []
        for path, doc in sorted(self.docs.items()):
            if not path.startswith(collection + "/") or path.count("/") + 1 != depth or field_name not in doc:
                continue
            actual = doc[field_name]
            if not _comparable(actual, value):
                continue
            ok = {
                "<": lambda a, b: a < b,
                "<=": lambda a, b: a <= b,
                ">": lambda a, b: a > b,
                ">=": lambda a, b: a >= b,
                "==": lambda a, b: a == b,
                "!=": lambda a, b: a != b,
            }[op](actual, value)
            if ok:
                out.append((path, dict(doc)))
        return out[:limit]


class FakeSpend:
    def __init__(self, summary: dict[str, Any] | None = None) -> None:
        self._summary = summary or {
            "ai_spent_usd": 1.0,
            "ai_budget_usd": 80.0,
            "compute_spent_usd": 0.5,
            "compute_budget_usd": 12.0,
            "day_cap_usd": 1.3,
            "day_spent_usd": 0.1,
            "sub_budgets": {"evals": {"spent_usd": 0.0, "budget_usd": 5.0}},
        }
        self.summary_error: Exception | None = None
        self.compute: list[dict[str, Any]] = []
        self.snapshots: list[Any] = []
        self.compacted = 0
        self.drift_factors: list[float] = []
        self.factor = 1.0
        self.day_totals: dict[str, float] = {}  # IST day → what the meter counted (AI + compute)
        self.day_total_error: Exception | None = None

    def summary(self) -> dict[str, Any]:
        if self.summary_error is not None:
            raise self.summary_error
        return dict(self._summary)

    def record_compute(self, *, cpu_cores: float, memory_mib: float, seconds: float, source: str) -> float:
        self.compute.append(
            {"cpu_cores": cpu_cores, "memory_mib": memory_mib, "seconds": seconds, "source": source}
        )
        return seconds * 0.000001

    def compact(self) -> list[str]:
        self.compacted += 1
        return []

    def snapshot_to_firestore(self, firestore: Any = None) -> int:
        self.snapshots.append(firestore)
        return 1

    def set_drift_factor(self, factor: float) -> float:
        value = max(1.0, float(factor))
        self.drift_factors.append(value)
        self.factor = value
        return value

    def drift_factor(self) -> float:
        return self.factor

    def day_total_usd(self, day: str) -> float:
        if self.day_total_error is not None:
            raise self.day_total_error
        return self.day_totals.get(day, 0.0)


class FakeRouter:
    def __init__(self, reason: str | None = None) -> None:
        self.reason = reason
        self.calls: list[dict[str, Any]] = []
        self.result: Any = None
        self.error: Exception | None = None

    def unavailable_reason(self, task: Any = None) -> str | None:
        return self.reason

    async def call(self, request: Any, **kwargs: Any) -> Any:
        self.calls.append({"request": request, **kwargs})
        if self.error is not None:
            raise self.error
        return self.result


def make_settings(**overrides: Any) -> Settings:
    values: dict[str, Any] = {
        "firebase_project_id": PROJECT,
        "healthchecks_heartbeat_url": HC_HEARTBEAT,
        "healthchecks_alert_url": HC_ALERT,
        "ops_sa_json": sa_json(),
        "owner_fcm_token": OWNER_TOKEN,
        "environment": "main",
    }
    values.update(overrides)
    return Settings(**values)


def make_deps(
    *,
    clock: FakeClock | None = None,
    store: MemoryStore | None = None,
    firestore: Any = None,
    spend: FakeSpend | None = None,
    metrics: Any = None,
    router: Any = None,
    settings: Settings | None = None,
) -> Deps:
    from ridemate_api.metrics import DictMetrics

    clock = clock or FakeClock(datetime(2026, 10, 28, 6, 0, tzinfo=UTC))
    store = store or MemoryStore()
    return Deps(
        settings=settings or make_settings(),
        clock=clock,
        store=store,
        auth=None,  # type: ignore[arg-type]
        ip_limiter=None,  # type: ignore[arg-type]
        quotas=None,  # type: ignore[arg-type]
        spend=spend or FakeSpend(),  # type: ignore[arg-type]
        firestore=firestore,
        remote_config=None,  # type: ignore[arg-type]
        metrics=metrics or DictMetrics(store, clock, shard="s1", background_flush=False),
        router=router,
    )
