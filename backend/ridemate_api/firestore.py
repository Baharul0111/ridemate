"""Firestore over its REST API (architecture §4.2 "Store", §4.18 E4/m16).

REST rather than gRPC keeps the image and cold start small. Every call has a 3 s deadline. A 429 /
RESOURCE_EXHAUSTED becomes `FirestoreQuotaExhausted`; any other failure becomes `FirestoreUnavailable`, so
callers on the request path can degrade instead of crash.

The Spark plan allows 20k writes and 20k deletes per day, reset at midnight US Pacific (≈ 13:30 IST). Writes and
deletes are counted per quota day in the hot store, sharded per process so no key has two writers:
- above 80% of the write quota, low-value writes (metrics detail) are shed;
- above 100% (or after Firestore itself says the quota is gone), audit and events records are parked in the hot
  store under `buffer:audit:*` and replayed by the dispatcher after the reset; other writes raise
  `FirestoreQuotaExhausted` (ONDC shows as unavailable, AI keeps working on Dict quotas).
"""

from __future__ import annotations

import base64
import json
import logging
import re
import threading
import uuid
from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass
from datetime import UTC, date, datetime, timedelta
from enum import StrEnum
from typing import Any, Protocol
from urllib.parse import quote

import httpx

from .clock import Clock
from .interfaces import FirestoreQuotaExhausted, FirestoreUnavailable
from .settings import DEADLINE_FIRESTORE_S, Settings
from .store import HotStore, StoreUnavailable

log = logging.getLogger(__name__)

DATASTORE_SCOPE = "https://www.googleapis.com/auth/datastore"
API_ROOT = "https://firestore.googleapis.com/v1"
DEFAULT_DATABASE = "(default)"

DAILY_WRITE_QUOTA = 20_000
DAILY_DELETE_QUOTA = 20_000
SHED_FRACTION = 0.80
TOTALS_REFRESH_S = 30.0
MAX_BUFFERED_PER_WRITER = 10_000  # per process and quota day
BUFFER_PREFIX = "buffer:audit:"


# ---- access tokens -----------------------------------------------------------------------------------------


class TokenError(Exception):
    """An access token could not be obtained (bad key, token endpoint unreachable or refusing)."""


class TokenSource(Protocol):
    def token(self) -> str: ...


class _HttpxResponse:
    """google.auth.transport.Response over an httpx.Response."""

    def __init__(self, response: httpx.Response) -> None:
        self._r = response

    @property
    def status(self) -> int:
        return self._r.status_code

    @property
    def headers(self) -> Mapping[str, str]:
        return self._r.headers

    @property
    def data(self) -> bytes:
        return self._r.content


class _HttpxRequest:
    """google.auth.transport.Request over httpx, so token refreshes share the client, deadline and test mocks."""

    def __init__(self, client: httpx.Client, timeout_s: float) -> None:
        self._client = client
        self._timeout_s = timeout_s

    def __call__(
        self,
        url: str,
        method: str = "GET",
        body: bytes | None = None,
        headers: Mapping[str, str] | None = None,
        timeout: float | None = None,
        **_: Any,
    ) -> _HttpxResponse:
        from google.auth import exceptions

        try:
            response = self._client.request(
                method, url, content=body, headers=dict(headers or {}), timeout=timeout or self._timeout_s
            )
        except httpx.HTTPError as e:
            raise exceptions.TransportError(type(e).__name__) from e
        return _HttpxResponse(response)


class ServiceAccountTokens:
    """OAuth access tokens for one service-account key (a JSON string from a Modal Secret) and fixed scopes."""

    def __init__(
        self,
        sa_json: str,
        scopes: Sequence[str],
        *,
        http_client: httpx.Client | None = None,
        timeout_s: float = DEADLINE_FIRESTORE_S,
    ) -> None:
        from google.oauth2 import service_account

        try:
            info = json.loads(sa_json)
            self._credentials = service_account.Credentials.from_service_account_info(
                info, scopes=list(scopes)
            )
        except (ValueError, KeyError, TypeError) as e:
            # The message could quote key material; keep only the type.
            raise ValueError(f"invalid service-account JSON ({type(e).__name__})") from None
        self.client_email: str = str(info.get("client_email", ""))
        self.project_id: str = str(info.get("project_id", ""))
        self._client = http_client or httpx.Client()
        self._timeout_s = timeout_s
        self._lock = threading.Lock()

    def token(self) -> str:
        with self._lock:
            if not self._credentials.valid:
                from google.auth import exceptions

                try:
                    self._credentials.refresh(_HttpxRequest(self._client, self._timeout_s))
                except exceptions.GoogleAuthError as e:
                    raise TokenError(type(e).__name__) from None
            return str(self._credentials.token)


# ---- quota day ---------------------------------------------------------------------------------------------


def _nth_sunday(year: int, month: int, n: int) -> date:
    first = date(year, month, 1)
    first_sunday = first + timedelta(days=(6 - first.weekday()) % 7)
    return first_sunday + timedelta(weeks=n - 1)


def pacific_offset(dt: datetime) -> timedelta:
    """US Pacific UTC offset at `dt` (DST from 2 am on the 2nd Sunday of March to 2 am on the 1st Sunday of
    November). Computed here so the image needs no tz database."""
    utc = dt.astimezone(UTC)
    dst_start = datetime.combine(_nth_sunday(utc.year, 3, 2), datetime.min.time(), UTC) + timedelta(hours=10)
    dst_end = datetime.combine(_nth_sunday(utc.year, 11, 1), datetime.min.time(), UTC) + timedelta(hours=9)
    return timedelta(hours=-7) if dst_start <= utc < dst_end else timedelta(hours=-8)


def quota_day(dt: datetime) -> str:
    """The Firestore free-quota day: the US Pacific calendar date, which rolls over ≈ 13:30 IST (12:30 in summer)."""
    return (dt.astimezone(UTC) + pacific_offset(dt)).strftime("%Y-%m-%d")


def quota_reset_utc(dt: datetime) -> datetime:
    """The instant the current quota day ends."""
    offset = pacific_offset(dt)
    local = dt.astimezone(UTC) + offset
    next_midnight_local = datetime.combine(local.date() + timedelta(days=1), datetime.min.time(), UTC)
    return next_midnight_local - offset


# ---- write budget ------------------------------------------------------------------------------------------


class WriteClass(StrEnum):
    ESSENTIAL = "essential"  # ONDC, spend shards, blocked uids, usage snapshots: never shed, never deferred
    BUFFERED = "buffered"  # audit + events: parked in the hot store when the quota is gone, replayed later
    LOW = "low"  # metrics detail: shed above 80% of the write quota


COLLECTION_CLASS: Mapping[str, WriteClass] = {
    "metrics": WriteClass.LOW,
    "audit": WriteClass.BUFFERED,
    "events": WriteClass.BUFFERED,
}


def write_class(path: str) -> WriteClass:
    parts = path.strip("/").split("/")
    if parts[0] == "metrics" and len(parts) == 2:
        # The once-a-day parent doc is how the cleanup job finds a day's shards; shedding it would orphan them.
        return WriteClass.ESSENTIAL
    return COLLECTION_CLASS.get(parts[0], WriteClass.ESSENTIAL)


class OpKind(StrEnum):
    WRITES = "writes"
    DELETES = "deletes"


QUOTAS: Mapping[OpKind, int] = {OpKind.WRITES: DAILY_WRITE_QUOTA, OpKind.DELETES: DAILY_DELETE_QUOTA}


@dataclass
class _Totals:
    day: str = ""
    own: int = 0  # this process's count for `day`
    others: int = 0  # every other process's count, as of `refreshed_at`
    refreshed_at: float = float("-inf")


class WriteBudget:
    """Per-quota-day write/delete counts across all containers, kept in the hot store.

    Each process owns `fs:{kind}:{day}:{writer}` and stores its absolute count there, so there is never a
    read-modify-write race. Totals over all writers are refreshed at most every 30 s. Accounting is best effort:
    a hot-store failure never fails a Firestore call."""

    def __init__(self, store: HotStore, clock: Clock, writer_id: str) -> None:
        self._store = store
        self._clock = clock
        self._writer = writer_id
        self._lock = threading.Lock()
        self._totals = {kind: _Totals() for kind in OpKind}

    def _roll(self, kind: OpKind, day: str) -> _Totals:
        t = self._totals[kind]
        if t.day != day:
            self._totals[kind] = t = _Totals(day=day)
        return t

    def record(self, kind: OpKind, n: int = 1) -> None:
        day = quota_day(self._clock.now_utc())
        with self._lock:
            t = self._roll(kind, day)
            t.own += n
            own = t.own
        try:
            self._store.put(f"fs:{kind.value}:{day}:{self._writer}", own)
        except StoreUnavailable:
            log.warning("firestore budget: hot store unavailable; count kept in memory")

    def used(self, kind: OpKind) -> int:
        now = self._clock.now_utc()
        day = quota_day(now)
        with self._lock:
            t = self._roll(kind, day)
            stale = self._clock.monotonic() - t.refreshed_at >= TOTALS_REFRESH_S
        if stale:
            self._refresh(kind, day)
        with self._lock:
            t = self._roll(kind, day)
            return t.own + t.others

    def fraction(self, kind: OpKind) -> float:
        return self.used(kind) / QUOTAS[kind]

    def _refresh(self, kind: OpKind, day: str) -> None:
        prefix = f"fs:{kind.value}:{day}:"
        own_key = prefix + self._writer
        others = 0
        try:
            for key in self._store.keys_with_prefix(prefix):
                if key != own_key:
                    others += int(self._store.get(key, 0) or 0)
        except (StoreUnavailable, TypeError, ValueError):
            log.warning("firestore budget: totals unavailable; using the last known totals")
            return
        with self._lock:
            t = self._roll(kind, day)
            t.others = others
            t.refreshed_at = self._clock.monotonic()

    def exhausted(self, kind: OpKind = OpKind.WRITES) -> bool:
        day = quota_day(self._clock.now_utc())
        try:
            if self._store.get(f"fs:exhausted:{kind.value}:{day}"):
                return True
        except StoreUnavailable:
            pass
        return self.used(kind) >= QUOTAS[kind]

    def mark_exhausted(self, kind: OpKind = OpKind.WRITES) -> None:
        day = quota_day(self._clock.now_utc())
        try:
            self._store.put(f"fs:exhausted:{kind.value}:{day}", True)
        except StoreUnavailable:
            log.warning("firestore budget: could not record quota exhaustion")


# ---- value encoding ----------------------------------------------------------------------------------------

_SIMPLE_FIELD = re.compile(r"^[_a-zA-Z][_a-zA-Z0-9]*$")


def quote_field_path(name: str) -> str:
    if _SIMPLE_FIELD.match(name):
        return name
    return "`" + name.replace("\\", "\\\\").replace("`", "\\`") + "`"


def _timestamp(dt: datetime) -> str:
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=UTC)
    return dt.astimezone(UTC).isoformat(timespec="microseconds").replace("+00:00", "Z")


def encode_value(value: Any) -> dict[str, Any]:
    if value is None:
        return {"nullValue": None}
    if isinstance(value, bool):
        return {"booleanValue": value}
    if isinstance(value, int):
        return {"integerValue": str(value)}
    if isinstance(value, float):
        return {"doubleValue": value}
    if isinstance(value, str):
        return {"stringValue": value}
    if isinstance(value, datetime):
        return {"timestampValue": _timestamp(value)}
    if isinstance(value, bytes | bytearray):
        return {"bytesValue": base64.b64encode(bytes(value)).decode("ascii")}
    if isinstance(value, Mapping):
        return {"mapValue": {"fields": encode_fields(value)}}
    if isinstance(value, list | tuple):
        return {"arrayValue": {"values": [encode_value(v) for v in value]}}
    raise TypeError(f"cannot store {type(value).__name__} in Firestore")


def encode_fields(data: Mapping[str, Any]) -> dict[str, Any]:
    return {str(k): encode_value(v) for k, v in data.items()}


def decode_value(value: Mapping[str, Any]) -> Any:
    if "nullValue" in value:
        return None
    if "booleanValue" in value:
        return bool(value["booleanValue"])
    if "integerValue" in value:
        return int(value["integerValue"])
    if "doubleValue" in value:
        return float(value["doubleValue"])
    if "stringValue" in value:
        return value["stringValue"]
    if "timestampValue" in value:
        return datetime.fromisoformat(value["timestampValue"])
    if "bytesValue" in value:
        return base64.b64decode(value["bytesValue"])
    if "referenceValue" in value:
        return value["referenceValue"]
    if "geoPointValue" in value:
        point = value["geoPointValue"]
        return {"latitude": point.get("latitude", 0.0), "longitude": point.get("longitude", 0.0)}
    if "mapValue" in value:
        return decode_fields(value["mapValue"].get("fields", {}))
    if "arrayValue" in value:
        return [decode_value(v) for v in value["arrayValue"].get("values", [])]
    raise ValueError(f"unknown Firestore value type: {sorted(value)}")


def decode_fields(fields: Mapping[str, Any]) -> dict[str, Any]:
    return {k: decode_value(v) for k, v in fields.items()}


_OPS: Mapping[str, str] = {
    "<": "LESS_THAN",
    "<=": "LESS_THAN_OR_EQUAL",
    ">": "GREATER_THAN",
    ">=": "GREATER_THAN_OR_EQUAL",
    "==": "EQUAL",
    "!=": "NOT_EQUAL",
    "array-contains": "ARRAY_CONTAINS",
    "in": "IN",
    "array-contains-any": "ARRAY_CONTAINS_ANY",
    "not-in": "NOT_IN",
}


# ---- the client --------------------------------------------------------------------------------------------


def _segments(path: str) -> list[str]:
    parts = [p for p in path.strip("/").split("/") if p]
    if not parts:
        raise ValueError("empty Firestore path")
    return parts


def _doc_segments(path: str) -> list[str]:
    parts = _segments(path)
    if len(parts) % 2:
        raise ValueError("a document path has an even number of segments (collection/id[/collection/id...])")
    return parts


class FirestoreRest:
    """`interfaces.Firestore` over https://firestore.googleapis.com/v1. Paths are relative to the database's
    documents root ('ondc_txn/abc', 'metrics/2026-10-28/shards/202610281005'); `query` returns such paths."""

    def __init__(
        self,
        *,
        project_id: str,
        tokens: TokenSource,
        clock: Clock,
        store: HotStore | None = None,
        http_client: httpx.Client | None = None,
        database: str = DEFAULT_DATABASE,
        deadline_s: float = DEADLINE_FIRESTORE_S,
        writer_id: str | None = None,
        on_event: Callable[[str], None] | None = None,
    ) -> None:
        self._project = project_id
        self._tokens = tokens
        self._clock = clock
        self._store = store
        self._client = http_client or httpx.Client()
        self._deadline_s = deadline_s
        self._writer = writer_id or uuid.uuid4().hex[:12]
        self._on_event = on_event or (lambda _name: None)
        self._db_name = f"projects/{project_id}/databases/{database}"
        self._docs_name = f"{self._db_name}/documents"
        self._buffer_seq = 0
        self._parked_day = ""
        self._parked_count = 0
        self._buffer_lock = threading.Lock()
        self.budget: WriteBudget | None = (
            WriteBudget(store, clock, self._writer) if store is not None else None
        )

    # ---- interfaces.Firestore ------------------------------------------------------------------------------

    def get(self, path: str) -> dict[str, Any] | None:
        response = self._request("GET", self._doc_url(path), allow_404=True)
        if response is None:
            return None
        return decode_fields(response.json().get("fields", {}))

    def set(self, path: str, data: dict[str, Any], *, merge: bool = False) -> None:
        cls = write_class(path)
        if cls is WriteClass.BUFFERED and not merge and "created_at" not in data:
            # Spark has no TTL: the daily cleanup finds expired audit/events records by this field.
            data = {**data, "created_at": self._clock.now_utc()}
        # Bad paths and unstorable values fail here, whatever the quota state, so nothing unwritable is parked.
        _doc_segments(path)
        encode_fields(data)
        if self._should_shed(cls):
            self._on_event("firestore_shed")
            return
        if self._writes_exhausted():
            self._defer_or_raise(cls, path, data, merge)
            return
        try:
            self._patch(path, data, merge=merge)
        except FirestoreQuotaExhausted:
            self._mark_exhausted(OpKind.WRITES)
            self._defer_or_raise(cls, path, data, merge)
            return
        self._record(OpKind.WRITES)

    def delete(self, path: str) -> None:
        if self.budget is not None and self.budget.exhausted(OpKind.DELETES):
            raise FirestoreQuotaExhausted("delete quota exhausted")
        try:
            self._request("DELETE", self._doc_url(path), allow_404=True)
        except FirestoreQuotaExhausted:
            self._mark_exhausted(OpKind.DELETES)
            raise
        self._record(OpKind.DELETES)

    def increment(self, path: str, field_name: str, amount: float) -> None:
        if self._writes_exhausted():
            raise FirestoreQuotaExhausted("write quota exhausted")
        number = {"integerValue": str(amount)} if isinstance(amount, int) else {"doubleValue": float(amount)}
        write = {
            # An empty update mask with no fields creates the document if needed and leaves other fields alone.
            "update": {"name": self._doc_name(path), "fields": {}},
            "updateMask": {"fieldPaths": []},
            "updateTransforms": [{"fieldPath": quote_field_path(field_name), "increment": number}],
        }
        try:
            self._request("POST", f"{API_ROOT}/{self._docs_name}:commit", json_body={"writes": [write]})
        except FirestoreQuotaExhausted:
            self._mark_exhausted(OpKind.WRITES)
            raise
        self._record(OpKind.WRITES)

    def query(
        self, collection: str, field_name: str, op: str, value: Any, *, limit: int = 100
    ) -> list[tuple[str, dict[str, Any]]]:
        if op not in _OPS:
            raise ValueError(f"unsupported query operator {op!r}")
        parts = _segments(collection)
        if len(parts) % 2 == 0:
            raise ValueError("a collection path has an odd number of segments")
        parent = "/".join(quote(p, safe="") for p in parts[:-1])
        url = f"{API_ROOT}/{self._docs_name}" + (f"/{parent}" if parent else "") + ":runQuery"
        body = {
            "structuredQuery": {
                "from": [{"collectionId": parts[-1]}],
                "where": {
                    "fieldFilter": {
                        "field": {"fieldPath": quote_field_path(field_name)},
                        "op": _OPS[op],
                        "value": encode_value(value),
                    }
                },
                "limit": int(limit),
            }
        }
        response = self._request("POST", url, json_body=body)
        if response is None:
            return []
        prefix = self._docs_name + "/"
        results: list[tuple[str, dict[str, Any]]] = []
        for item in response.json():
            doc = item.get("document") if isinstance(item, dict) else None
            if not doc:
                continue
            name = str(doc.get("name", ""))
            rel = name.removeprefix(prefix)
            results.append((rel, decode_fields(doc.get("fields", {}))))
        return results

    # ---- buffered audit/events -----------------------------------------------------------------------------

    def buffered_count(self) -> int:
        if self._store is None:
            return 0
        try:
            return len(self._store.keys_with_prefix(BUFFER_PREFIX))
        except StoreUnavailable:
            return 0

    def replay_buffer(self, *, max_items: int = 500, stop: Callable[[], bool] | None = None) -> int:
        """Write parked audit/events records once the quota is back; returns how many were written. Each
        record has its own key, so a replay never races a container that is still parking new ones. `stop` is
        checked before each record (the dispatcher's time limit)."""
        if self._store is None or self._writes_exhausted():
            return 0
        written = 0
        for key in sorted(self._store.keys_with_prefix(BUFFER_PREFIX))[:max_items]:
            if stop is not None and stop():
                break
            item = self._store.get(key)
            if not isinstance(item, dict) or "path" not in item:
                self._store.delete(key)
                continue
            try:
                self._patch(item["path"], item.get("data") or {}, merge=bool(item.get("merge")))
            except FirestoreQuotaExhausted:
                self._mark_exhausted(OpKind.WRITES)
                break
            except (ValueError, TypeError):
                # Can never be written; dropping it keeps one bad record from blocking every later one.
                self._on_event("firestore_buffer_dropped")
                self._store.delete(key)
                continue
            self._record(OpKind.WRITES)
            self._store.delete(key)
            written += 1
        return written

    # ---- internals -----------------------------------------------------------------------------------------

    def _should_shed(self, cls: WriteClass) -> bool:
        return (
            cls is WriteClass.LOW
            and self.budget is not None
            and self.budget.fraction(OpKind.WRITES) >= SHED_FRACTION
        )

    def _writes_exhausted(self) -> bool:
        return self.budget is not None and self.budget.exhausted(OpKind.WRITES)

    def _record(self, kind: OpKind) -> None:
        if self.budget is not None:
            self.budget.record(kind)

    def _mark_exhausted(self, kind: OpKind) -> None:
        self._on_event(f"firestore_{kind.value}_exhausted")
        if self.budget is not None:
            self.budget.mark_exhausted(kind)

    def _defer_or_raise(self, cls: WriteClass, path: str, data: dict[str, Any], merge: bool) -> None:
        if cls is WriteClass.LOW:
            self._on_event("firestore_shed")
            return
        if cls is WriteClass.BUFFERED and self._park(path, data, merge):
            return
        raise FirestoreQuotaExhausted("write quota exhausted")

    def _park(self, path: str, data: dict[str, Any], merge: bool) -> bool:
        if self._store is None:
            return False
        day = quota_day(self._clock.now_utc())
        with self._buffer_lock:
            # The cap is per quota day: a long-lived container must still be able to park after earlier
            # exhaustions were replayed.
            if self._parked_day != day:
                self._parked_day, self._parked_count = day, 0
            if self._parked_count >= MAX_BUFFERED_PER_WRITER:
                self._on_event("firestore_buffer_full")
                return False
            self._parked_count += 1
            self._buffer_seq += 1
            key = f"{BUFFER_PREFIX}{self._writer}:{self._buffer_seq:08d}"
        try:
            self._store.put(key, {"path": path, "data": data, "merge": merge, "at": self._clock.now_utc()})
        except StoreUnavailable:
            return False
        self._on_event("firestore_buffered")
        return True

    def _patch(self, path: str, data: Mapping[str, Any], *, merge: bool) -> None:
        params: list[tuple[str, str]] = []
        if merge:
            params = [("updateMask.fieldPaths", quote_field_path(str(k))) for k in data]
        self._request("PATCH", self._doc_url(path), params=params, json_body={"fields": encode_fields(data)})

    def _doc_name(self, path: str) -> str:
        return f"{self._docs_name}/" + "/".join(_doc_segments(path))

    def _doc_url(self, path: str) -> str:
        return f"{API_ROOT}/{self._docs_name}/" + "/".join(quote(p, safe="") for p in _doc_segments(path))

    def _request(
        self,
        method: str,
        url: str,
        *,
        params: Sequence[tuple[str, str]] = (),
        json_body: Any = None,
        allow_404: bool = False,
    ) -> httpx.Response | None:
        try:
            token = self._tokens.token()
        except TokenError as e:
            raise FirestoreUnavailable(f"token: {e}") from None
        try:
            response = self._client.request(
                method,
                url,
                params=list(params) or None,
                json=json_body,
                headers={"Authorization": f"Bearer {token}"},
                timeout=self._deadline_s,
            )
        except httpx.TimeoutException:
            raise FirestoreUnavailable("deadline exceeded") from None
        except httpx.HTTPError as e:
            raise FirestoreUnavailable(type(e).__name__) from None
        if response.status_code == 404 and allow_404:
            return None
        if response.status_code == 429 or _status_name(response) == "RESOURCE_EXHAUSTED":
            raise FirestoreQuotaExhausted(f"HTTP {response.status_code}")
        if response.status_code >= 400:
            raise FirestoreUnavailable(f"HTTP {response.status_code} {_status_name(response) or ''}".strip())
        return response


def _status_name(response: httpx.Response) -> str | None:
    if response.status_code < 400:
        return None
    try:
        body = response.json()
    except ValueError:
        return None
    if isinstance(body, list) and body:
        body = body[0]
    error = body.get("error") if isinstance(body, dict) else None
    return str(error.get("status")) if isinstance(error, dict) and error.get("status") else None


def build_firestore(
    settings: Settings,
    *,
    store: HotStore | None,
    clock: Clock,
    role: str = "web",
    writer_id: str | None = None,
    on_event: Callable[[str], None] | None = None,
    http_client: httpx.Client | None = None,
) -> FirestoreRest | None:
    """The production client for the web app (role 'web', Datastore-user key) or the dispatcher (role 'ops').
    None when the key or project isn't configured: AI paths keep working without Firestore."""
    sa_json = settings.web_sa_json if role == "web" else settings.ops_sa_json
    if not sa_json or not settings.firebase_project_id:
        return None
    client = http_client or httpx.Client()
    try:
        tokens = ServiceAccountTokens(sa_json, [DATASTORE_SCOPE], http_client=client)
    except ValueError:
        log.error("firestore: %s service-account key is not valid JSON; Firestore disabled", role)
        return None
    return FirestoreRest(
        project_id=settings.firebase_project_id,
        tokens=tokens,
        clock=clock,
        store=store,
        http_client=client,
        writer_id=writer_id,
        on_event=on_event,
    )
