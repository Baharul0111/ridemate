"""Non-AI endpoints (contracts §1.3): /v1/event, /v1/erase, /v1/config-check, /v1/owner/fcm-token, /healthz,
/readyz.

Server writes happen only for Google-signed-in users; anonymous calls to /v1/event are accepted and dropped.
Events hold enums and counters only. Erase deletes the caller's server data; the HMAC(sub) usage counters stay
48 h (abuse prevention, deleted by the cleanup job) and the Auth account itself is deleted by the phone. During
an HMAC key rotation a user's data may sit under either key, so erase covers both (§4.18 H5).

Event writes and erases cost no model money but spend the free Firestore quota that ONDC and every other user
share, so each has a per-user daily cap (limits.allow_daily) on top of the per-IP limit. An event also closes the
session's metadata audit record (audit.py): its one `audit` document is written now instead of by the dispatcher.
Audit records are kept a year (DPDP Rules), so erase leaves them.
"""

from __future__ import annotations

import hashlib
import logging
from collections.abc import Iterator, Sequence
from datetime import datetime
from typing import Annotated, Any, Literal

from fastapi import APIRouter, Depends, Request, Response
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field
from starlette.concurrency import run_in_threadpool

from ..auth import any_principal, google_principal, optional_principal
from ..clock import ist_day, ist_hhmm, next_ist_midnight
from ..deps import Deps, get_deps
from ..interfaces import ApiError, Firestore, FirestoreUnavailable, Principal
from ..limits import allow_daily
from ..store import StoreUnavailable

log = logging.getLogger(__name__)

router = APIRouter()
DepsDep = Annotated[Deps, Depends(get_deps)]

OWNER_TOKENS_KEY = "owner:fcm_tokens"  # {token: {"uid_hash", "updated_at"}}; alerts.py pushes to every key
EVENT_SEEN_PREFIX = "event:seen:"
ERASE_PENDING_PREFIX = "erase:pending:"
ERASED_PREFIX = "erased:"
QUERY_PAGE = 100
MAX_QUERY_PAGES = 20
MAX_OWNER_TOKENS = 10
# One event per session; even a heavy user has a handful of sessions a day (the AI quota is 6 reads).
EVENT_WRITES_PER_DAY = 30
# The phone sends one erase (retried from its outbox until it succeeds).
ERASES_PER_DAY = 10

App = Literal["uber", "ola", "rapido", "ondc", "demo_kestrel", "demo_pepper", "demo_tamarind", "mixed"]
Counter = Annotated[int, Field(ge=0, le=10_000)]


class EventIn(BaseModel):
    model_config = ConfigDict(extra="ignore")

    session_id: str = Field(pattern=r"^[A-Za-z0-9-]{8,64}$")
    app: App
    version_code: int = Field(ge=0, le=2_000_000_000)
    outcome: Literal["confirmed_all", "dismissed", "abandoned", "demo"]
    time_to_confirm_bucket: Literal["lt_1m", "1_3m", "3_10m", "gt_10m", "none"]
    android_version: int = Field(ge=0, le=100)
    reads: Counter = 0
    verified_rows: Counter = 0
    unreadable_rows: Counter = 0
    rule_hits: Counter = 0
    rule_misses: Counter = 0
    misses_unclassified: Counter = 0
    demo: bool = False


class FcmTokenIn(BaseModel):
    model_config = ConfigDict(extra="ignore")

    token: str = Field(min_length=1, max_length=4096, pattern=r"^[A-Za-z0-9_:\-.]+$")


# ---- /v1/event ---------------------------------------------------------------------------------------------


@router.post("/v1/event", status_code=202)
async def post_event(
    body: EventIn, principal: Annotated[Principal, Depends(any_principal)], deps: DepsDep
) -> Response:
    if principal.is_google:
        await run_in_threadpool(_record_event, deps, principal, body)
    return Response(status_code=202)


def _record_event(deps: Deps, principal: Principal, body: EventIn) -> None:
    _store_event(deps, principal, body)
    if deps.audit is not None:
        deps.audit.flush_session(principal, body.session_id)  # never raises


def _store_event(deps: Deps, principal: Principal, body: EventIn) -> None:
    doc_id = hashlib.sha256(f"{principal.sub_hmac}:{body.session_id}".encode()).hexdigest()[:32]
    now = deps.clock.now_utc()
    try:
        allowed = allow_daily(deps.store, deps.clock, principal, "event", EVENT_WRITES_PER_DAY)
    except StoreUnavailable:
        allowed = True  # metadata only; a Dict outage already pauses AI and nothing here costs money
    if not allowed:
        deps.metrics.incr("session_events_dropped", reason="daily_cap")
        return
    try:
        first_time = deps.store.put_if_absent(EVENT_SEEN_PREFIX + doc_id, now.isoformat())
    except StoreUnavailable:
        first_time = True
    if first_time:  # a retried event overwrites its document but is counted in the alert metrics only once
        try:
            from ..metrics import record_session_event

            record_session_event(deps.metrics, body.model_dump())
        except ImportError:
            pass
        deps.metrics.incr("session_events", outcome=body.outcome)
    if deps.firestore is None:
        return
    doc = {
        "uid_hash": principal.uid_hash,
        **body.model_dump(exclude={"session_id"}),
        "day": ist_day(now),
        "created_at": now,
    }
    try:
        deps.firestore.set(f"events/{doc_id}", doc)
    except FirestoreUnavailable as e:
        log.warning("event not stored: %s", type(e).__name__)


# ---- /v1/erase ---------------------------------------------------------------------------------------------


@router.post("/v1/erase")
async def erase(principal: Annotated[Principal, Depends(google_principal)], deps: DepsDep) -> dict[str, Any]:
    await run_in_threadpool(_erase, deps, principal)
    return {"erased": True}


def _erase(deps: Deps, principal: Principal) -> None:
    now = deps.clock.now_utc()
    sub_hmacs = [h for h in (principal.sub_hmac, principal.sub_hmac_previous) if h]
    record = {"uid": principal.uid, "sub_hmacs": sub_hmacs}
    try:
        if not allow_daily(deps.store, deps.clock, principal, "erase", ERASES_PER_DAY):
            raise ApiError(429, "rate_limited", resume_at=next_ist_midnight(now))
        _forget_owner_tokens(deps, {_uid_hash(h) for h in sub_hmacs})
        deps.store.put(ERASED_PREFIX + principal.sub_hmac, now.isoformat())
    except StoreUnavailable:
        raise ApiError(503, "storage_unavailable") from None
    if deps.firestore is None:
        return
    try:
        erase_server_data(deps.firestore, uid=principal.uid, sub_hmacs=sub_hmacs, now=now)
    except FirestoreUnavailable as e:
        log.warning("erase deferred: %s", type(e).__name__)
        try:  # finished by complete_pending_erasures even if the phone deletes the account meanwhile
            deps.store.put(
                ERASE_PENDING_PREFIX + principal.sub_hmac, {**record, "requested_at": now.isoformat()}
            )
        except StoreUnavailable:
            pass
        raise ApiError(503, "storage_unavailable") from None


def _uid_hash(sub_hmac: str) -> str:
    return sub_hmac[:16]  # the same truncation as Principal.uid_hash


def erase_server_data(firestore: Firestore, *, uid: str, sub_hmacs: Sequence[str], now: datetime) -> None:
    """Delete a user's ONDC transactions (and their callback records) and session events, and mark the usage
    snapshot erased (the cleanup job deletes it 48 h later). `sub_hmacs` holds the user's HMAC(sub) under the
    current key and, during a rotation, the previous one."""
    for txn_path, _ in _drain(firestore, "ondc_txn", "uid", uid):
        txn_id = txn_path.rsplit("/", 1)[-1]
        for event_path, _ in _drain(firestore, "ondc_event", "txnId", txn_id):
            firestore.delete(event_path)
        firestore.delete(txn_path)
    for sub_hmac in sub_hmacs:
        for path, _ in _drain(firestore, "events", "uid_hash", _uid_hash(sub_hmac)):
            firestore.delete(path)
    for sub_hmac in sub_hmacs:
        firestore.set(f"usage_snapshot/{sub_hmac}", {"erased_at": now}, merge=True)


def _drain(
    firestore: Firestore, collection: str, field: str, value: Any
) -> Iterator[tuple[str, dict[str, Any]]]:
    """Every matching document, page by page; callers delete what they get, so each page is a fresh query."""
    for _ in range(MAX_QUERY_PAGES):
        page = firestore.query(collection, field, "==", value, limit=QUERY_PAGE)
        for doc_id, data in page:
            yield (doc_id if "/" in doc_id else f"{collection}/{doc_id}"), data
        if len(page) < QUERY_PAGE:
            return
    raise FirestoreUnavailable(f"{collection}: more than {QUERY_PAGE * MAX_QUERY_PAGES} documents")


def complete_pending_erasures(deps: Deps, *, limit: int = 50) -> int:
    """Finish erasures that Firestore refused at request time (called by the dispatcher cron)."""
    if deps.firestore is None:
        return 0
    done = 0
    for key in deps.store.keys_with_prefix(ERASE_PENDING_PREFIX)[:limit]:
        record = deps.store.get(key)
        if not isinstance(record, dict):
            deps.store.delete(key)
            continue
        try:
            erase_server_data(
                deps.firestore,
                uid=str(record["uid"]),
                sub_hmacs=[str(h) for h in record.get("sub_hmacs", ())],
                now=deps.clock.now_utc(),
            )
        except FirestoreUnavailable:
            break
        deps.store.delete(key)
        done += 1
    return done


def _forget_owner_tokens(deps: Deps, uid_hashes: set[str]) -> None:
    tokens = deps.store.get(OWNER_TOKENS_KEY)
    if not isinstance(tokens, dict):
        return
    kept = {
        t: meta
        for t, meta in tokens.items()
        if not (isinstance(meta, dict) and meta.get("uid_hash") in uid_hashes)
    }
    if len(kept) != len(tokens):
        deps.store.put(OWNER_TOKENS_KEY, kept)


# ---- /v1/config-check --------------------------------------------------------------------------------------


@router.get("/v1/config-check")
async def config_check(
    principal: Annotated[Principal | None, Depends(optional_principal)], deps: DepsDep
) -> dict[str, Any]:
    return await run_in_threadpool(_config_check, deps, principal)


def _config_check(deps: Deps, principal: Principal | None) -> dict[str, Any]:
    rc = deps.remote_config
    now = deps.clock.now_utc()
    reason: str | None = None
    resume_at: datetime | None = None
    quota = {"reads_left": 0, "parses_left": 0}
    meter_ok = True
    if principal is not None and principal.is_google:
        try:
            quota = deps.quotas.remaining(principal)
        except (ApiError, StoreUnavailable):
            meter_ok = False

    if not rc.get_bool("ai_reader_enabled", True):
        reason = "kill_switch"
    elif principal is None:
        reason = "signed_out"
    elif not principal.is_google:
        reason = "anonymous"
    elif not meter_ok:
        reason = "meter"
    else:
        status = deps.spend.ai_status(principal)
        if not status.enabled:
            reason, resume_at = status.reason, status.resume_at
        elif (router_reason := _router_reason(deps)) is not None:
            reason = router_reason
        elif quota["reads_left"] <= 0:
            reason, resume_at = "quota", next_ist_midnight(now)

    budget = None
    if principal is not None and principal.is_owner:
        try:
            budget = deps.spend.summary()
        except StoreUnavailable:
            budget = None
    return {
        "ai_enabled": reason is None,
        "reason": reason,
        "resume_at_ist": ist_hhmm(resume_at) if resume_at is not None else None,
        "point_supported": rc.get_bool("point_supported", False),
        "quota": {"reads_left": int(quota["reads_left"]), "parses_left": int(quota["parses_left"])},
        "cap_profile": "judging" if rc.get_str("cap_profile", "normal") == "judging" else "normal",
        "min_supported_version": rc.get_int("min_supported_version", 1),
        "server_time_utc": now.strftime("%Y-%m-%dT%H:%M:%SZ"),
        "budget": budget,
    }


def _router_reason(deps: Deps) -> str | None:
    """'breaker' / 'model_missing' from the provider router; no router at all means no model to call."""
    if deps.router is None:
        return "model_missing"
    check = getattr(deps.router, "unavailable_reason", None)
    return check() if callable(check) else None


# ---- /v1/owner/fcm-token -----------------------------------------------------------------------------------


@router.post("/v1/owner/fcm-token", status_code=204)
async def owner_fcm_token(
    body: FcmTokenIn, principal: Annotated[Principal, Depends(google_principal)], deps: DepsDep
) -> Response:
    if not principal.is_owner:
        raise ApiError(403, "forbidden")
    await run_in_threadpool(_store_owner_token, deps, principal, body.token)
    return Response(status_code=204)


def _store_owner_token(deps: Deps, principal: Principal, token: str) -> None:
    try:
        tokens = deps.store.get(OWNER_TOKENS_KEY)
        tokens = dict(tokens) if isinstance(tokens, dict) else {}
        tokens = {
            t: m
            for t, m in tokens.items()
            if not (isinstance(m, dict) and m.get("uid_hash") == principal.uid_hash)
        }
        tokens[token] = {"uid_hash": principal.uid_hash, "updated_at": deps.clock.now_utc().isoformat()}
        while len(tokens) > MAX_OWNER_TOKENS:  # oldest registration first (dicts keep insertion order)
            tokens.pop(next(iter(tokens)))
        deps.store.put(OWNER_TOKENS_KEY, tokens)
    except StoreUnavailable:
        raise ApiError(503, "storage_unavailable") from None


# ---- health --------------------------------------------------------------------------------------------------


@router.get("/healthz")
async def healthz() -> dict[str, bool]:
    return {"ok": True}


@router.get("/readyz")
async def readyz(request: Request, deps: DepsDep) -> JSONResponse:
    checks = await run_in_threadpool(_readiness, deps)
    # Endpoint groups app.py mounts only when their module imports: a broken one shows up here as not ready.
    routes = getattr(request.app.state, "optional_routes", {})
    checks.update({f"routes_{name}": bool(mounted) for name, mounted in routes.items()})
    ok = all(v for v in checks.values() if isinstance(v, bool))
    return JSONResponse({"ok": ok, "checks": checks}, status_code=200 if ok else 503)


def _readiness(deps: Deps) -> dict[str, bool]:
    settings = deps.settings
    checks = {"settings": bool(settings.firebase_project_id and settings.hmac_keys)}
    try:
        deps.store.get("readyz:probe")
        checks["store"] = True
    except StoreUnavailable:
        checks["store"] = False
    if deps.firestore is not None:
        try:
            deps.firestore.get("blocked_uids/list")
            checks["firestore"] = True
        except FirestoreUnavailable:
            checks["firestore"] = False
    return checks
