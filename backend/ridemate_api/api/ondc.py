"""ONDC routes (contracts §1.4): the buyer endpoints the phone calls, the seller callbacks, and — only when
ONDC_MODE=mock — the demo seller's own endpoints (§4.18 C1: never mounted in production).

`mount(app, settings)` is the one way to attach them.

Every buyer call writes to Firestore (and the seller's callbacks write more), whose free daily quota every user and
the audit trail share, so each user has daily caps on top of the per-IP bucket (limits.allow_daily): searches
(SEARCHES_PER_DAY) and every other buyer action (ACTIONS_PER_DAY).

/v1/ondc/cancel has three more modes besides the soft cancel and the confirmed cancel: `keep` ("Keep ride" after
the fee was shown) and `abandon` (the phone stopped showing a transaction that has no order yet; no tap needed).
"""

from __future__ import annotations

from typing import Annotated, Any

from fastapi import APIRouter, Depends, FastAPI, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field, ValidationError

from ..clock import next_ist_midnight
from ..deps import Deps, get_deps
from ..interfaces import ApiError, Principal
from ..limits import allow_daily
from ..offload import off_loop
from ..ondc.bap import CALLBACK_ACTIONS, OndcBuyer
from ..ondc.payloads import GeoPoint, nack
from ..settings import MAX_JSON_BYTES, Settings
from ..store import StoreUnavailable

router = APIRouter()
mock_router = APIRouter()
DepsDep = Annotated[Deps, Depends(get_deps)]

UUID_PATTERN = r"^[0-9a-fA-F-]{36}$"
TAP_ID_PATTERN = r"^[A-Za-z0-9_-]{1,64}$"
# Per user and IST day. A search costs ~4 Firestore writes with its callbacks; Spark allows 20,000 a day for all.
SEARCHES_PER_DAY = 20
ACTIONS_PER_DAY = 100  # select, init, confirm, status, cancel (all modes) together


class _Body(BaseModel):
    model_config = ConfigDict(extra="ignore")


class LatLng(_Body):
    lat: float = Field(ge=-90, le=90)
    lng: float = Field(ge=-180, le=180)


class SearchIn(_Body):
    session_id: str = Field(min_length=1, max_length=64)
    pickup: LatLng
    drop: LatLng


class TxnIn(_Body):
    txn_id: str = Field(pattern=UUID_PATTERN)


class SelectIn(TxnIn):
    item_id: str = Field(min_length=1, max_length=128)


class ConfirmIn(TxnIn):
    tap_id: str = Field(pattern=TAP_ID_PATTERN)


class CancelIn(TxnIn):
    tap_id: str | None = Field(default=None, pattern=TAP_ID_PATTERN)  # required except for `abandon`
    confirm: bool = False
    keep: bool = False
    abandon: bool = False


def mount(app: FastAPI, settings: Settings) -> None:
    app.include_router(router)
    if settings.ondc_mode == "mock":
        app.include_router(mock_router)


async def _body[M: BaseModel](request: Request, model: type[M]) -> M:
    raw = await request.body()
    if len(raw) > MAX_JSON_BYTES:
        raise ApiError(413, "too_large")
    try:
        return model.model_validate_json(raw)
    except ValidationError as e:
        raise ApiError(422, "invalid") from e


def _buyer(deps: Deps) -> OndcBuyer:
    if (
        deps.settings.ondc_mode == "off"
        or deps.ondc is None
        or not deps.remote_config.get_bool("ondc_enabled", True)
    ):
        raise ApiError(503, "ondc_unavailable")
    return deps.ondc


async def _user(request: Request, deps: Deps, *, cap: str = "ondc_action") -> tuple[OndcBuyer, Any]:
    # The bucket lives in the Modal Dict: never on the event loop.
    await off_loop(deps.ip_limiter.check, request)
    buyer = _buyer(deps)
    principal = await deps.auth.verify(request, require_google=True)
    await off_loop(_daily_cap, deps, principal, cap)
    return buyer, principal


def _daily_cap(deps: Deps, principal: Principal, cap: str) -> None:
    """One use of today's per-user allowance, before anything is written to Firestore."""
    limit = SEARCHES_PER_DAY if cap == "ondc_search" else ACTIONS_PER_DAY
    try:
        allowed = allow_daily(deps.store, deps.clock, principal, cap, limit)
    except StoreUnavailable:
        raise ApiError(503, "storage_unavailable") from None
    if not allowed:
        deps.metrics.incr("ondc_daily_cap", cap=cap)
        raise ApiError(429, "rate_limited", resume_at=next_ist_midnight(deps.clock.now_utc()))


async def _raw_body(request: Request) -> bytes | None:
    raw = await request.body()
    return None if len(raw) > MAX_JSON_BYTES else raw


# ---- buyer endpoints (Google token) ----------------------------------------------------------------------------


@router.post("/v1/ondc/search")
async def search(request: Request, deps: DepsDep) -> dict[str, Any]:
    buyer, principal = await _user(request, deps, cap="ondc_search")
    body = await _body(request, SearchIn)
    txn_id = await buyer.search(
        principal,
        session_id=body.session_id,
        pickup=GeoPoint(body.pickup.lat, body.pickup.lng),
        drop=GeoPoint(body.drop.lat, body.drop.lng),
    )
    return {"txn_id": txn_id}


@router.post("/v1/ondc/select")
async def select(request: Request, deps: DepsDep) -> dict[str, Any]:
    buyer, principal = await _user(request, deps)
    body = await _body(request, SelectIn)
    await buyer.select(principal, body.txn_id, body.item_id)
    return {"ok": True}


@router.post("/v1/ondc/init")
async def init(request: Request, deps: DepsDep) -> dict[str, Any]:
    buyer, principal = await _user(request, deps)
    body = await _body(request, TxnIn)
    await buyer.init(principal, body.txn_id)
    return {"ok": True}


@router.post("/v1/ondc/confirm")
async def confirm(request: Request, deps: DepsDep) -> dict[str, Any]:
    buyer, principal = await _user(request, deps)
    body = await _body(request, ConfirmIn)
    await buyer.confirm(principal, body.txn_id, body.tap_id)
    return {"ok": True}


@router.post("/v1/ondc/status")
async def status(request: Request, deps: DepsDep) -> dict[str, Any]:
    buyer, principal = await _user(request, deps)
    body = await _body(request, TxnIn)
    await buyer.status(principal, body.txn_id)
    return {"ok": True}


@router.post("/v1/ondc/cancel")
async def cancel(request: Request, deps: DepsDep) -> dict[str, Any]:
    buyer, principal = await _user(request, deps)
    body = await _body(request, CancelIn)
    if body.confirm + body.keep + body.abandon > 1:
        raise ApiError(422, "invalid")
    if body.abandon:
        await buyer.abandon(principal, body.txn_id)
        return {"ok": True}
    if body.tap_id is None:
        raise ApiError(422, "invalid")  # soft cancel, confirmed cancel and keep each follow a user tap (I1)
    if body.keep:
        await buyer.keep(principal, body.txn_id, body.tap_id)
    else:
        await buyer.cancel(principal, body.txn_id, body.tap_id, confirm=body.confirm)
    return {"ok": True}


# ---- seller callbacks (signed by the seller, answered with ACK/NACK) -------------------------------------------


@router.post("/ondc/on_{action}")
async def on_callback(action: str, request: Request, deps: DepsDep) -> JSONResponse:
    if f"on_{action}" not in CALLBACK_ACTIONS:
        return JSONResponse(nack("unknown_action"), status_code=404)
    if deps.ondc is None or deps.settings.ondc_mode == "off":
        return JSONResponse(nack("unavailable"), status_code=503)
    raw = await _raw_body(request)
    if raw is None:
        return JSONResponse(nack("too_large"), status_code=413)
    code, reply = await deps.ondc.handle_callback(f"on_{action}", raw, request.headers.get("authorization"))
    return JSONResponse(reply, status_code=code)


# ---- the demo seller (ONDC_MODE=mock only) ---------------------------------------------------------------------


@mock_router.post("/mock-bpp/{action}")
async def mock_seller(action: str, request: Request, deps: DepsDep) -> JSONResponse:
    if deps.settings.ondc_mode != "mock" or deps.ondc is None or deps.ondc.mock is None:
        return JSONResponse(nack("unavailable"), status_code=503)
    raw = await _raw_body(request)
    if raw is None:
        return JSONResponse(nack("too_large"), status_code=413)
    code, reply = await deps.ondc.mock.receive(action, raw, request.headers.get("authorization"))
    return JSONResponse(reply, status_code=code)
