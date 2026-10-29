"""The ONDC buyer app (architecture §4.2 "ONDC BAP", sequence "Data flow: ONDC", §4.4 ONDC states, §4.18 C1).

Every user action is a signed TRV10 request sent under a 5 s deadline; the seller's `on_*` callbacks are verified,
stored in `ondc_event` before they are acknowledged (idempotent per transaction, action, seller and message id),
and folded into `ondc_txn/{txnId}`, which the phone listens to. Each `ondc_event` carries the transaction owner's
`uid` (None when the transaction is unknown), the field firestore.rules matches for owner-only reads. Each
transaction belongs to the uid that started it; confirm and cancel also need an unrevoked token and record the
user's tap id (I1). Pickup and drop are kept only while the ride can still need them and are cleared once the
transaction ends: at the ride's end or cancel, when the phone abandons a search it no longer shows, and — for a
search nobody booked or cancelled — by the dispatcher within the hour (`expire_abandoned`).

Cancelling is two-step (§4.4): a soft cancel asks the seller for the fee (any earlier quote is cleared at once, so
the phone never shows an old fee as the answer to a new request), and only a confirmed cancel cancels. "Keep ride"
after a quote is recorded here too: the transaction returns to the ride state it would have reached meanwhile
(progress reported during the soft cancel is remembered in `resume_state`), and a quote that arrives late for a
soft cancel the user already answered is ignored.
"""

from __future__ import annotations

import asyncio
import hashlib
import json
import logging
import uuid
from collections.abc import Callable
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any, Protocol
from urllib.parse import urlparse

from ..clock import Clock
from ..interfaces import ApiError, Auth, Firestore, FirestoreUnavailable, Metrics, Principal
from ..offload import off_loop
from ..settings import DEADLINE_ONDC_OUTBOUND_S, Settings
from ..store import HotStore
from .mock_bpp import DEFAULT_EXAMPLES_DIR, MOCK_BPP_ID, MOCK_UNIQUE_KEY_ID, MockBpp, forget_order
from .payloads import (
    FULFILLMENT_STATE,
    MAX_QUOTES,
    GeoPoint,
    ack,
    ack_status,
    cancel_message,
    confirm_message,
    context,
    encode,
    envelope,
    init_message,
    nack,
    parse_on_search,
    parse_order,
    search_message,
    select_message,
    status_message,
)
from .signing import KeyLookup, SignatureError, StaticKeyRegistry, SubscriberKey, authorization_header, verify

log = logging.getLogger(__name__)

CALLBACK_ACTIONS = frozenset(
    {"on_search", "on_select", "on_init", "on_confirm", "on_status", "on_cancel", "on_update"}
)
# Replies that must answer a request we sent (same message id); status/update/cancel may come unsolicited.
SOLICITED = frozenset({"on_search", "on_select", "on_init", "on_confirm"})
TERMINAL = frozenset({"ENDED", "CANCEL_CONFIRMED", "FAILED"})
PROGRESS = ("SEARCHING_NET", "ON_SELECT", "INIT", "CONFIRMED", "ARRIVING", "ON_TRIP")
RIDE_ACTIVE = frozenset({"CONFIRMED", "ARRIVING", "ON_TRIP"})
BEFORE_ORDER = frozenset({"SEARCHING_NET", "ON_SELECT", "INIT"})
MAX_TAP_ID_CHARS = 64
# A search nobody booked or cancelled is expired (pickup/drop cleared) once idle this long; the dispatcher runs
# every 15 minutes, so its stops are gone within the hour.
ABANDONED_AFTER = timedelta(minutes=45)
EXPIRE_PAGE = 200


def next_state(current: str, proposed: str) -> str:
    """Terminal states stick, a pending soft cancel isn't undone by ride progress, and progress never regresses."""
    if current in TERMINAL:
        return current
    if proposed in TERMINAL:
        return proposed
    if current == "SOFT_CANCEL":
        return current if proposed in PROGRESS else proposed
    if current in PROGRESS and proposed in PROGRESS and PROGRESS.index(proposed) < PROGRESS.index(current):
        return current
    return proposed


class OndcTransport(Protocol):
    async def send(
        self, uri: str, action: str, body: bytes, authorization: str
    ) -> tuple[int, dict[str, Any]]: ...


class InProcessTransport:
    """Mock mode: requests go straight to the in-process seller (no HTTP hop, no public URL needed)."""

    def __init__(self, seller: MockBpp) -> None:
        self._seller = seller

    async def send(
        self, uri: str, action: str, body: bytes, authorization: str
    ) -> tuple[int, dict[str, Any]]:
        return await self._seller.receive(action, body, authorization)


def _valid_tap_id(tap_id: str) -> str:
    if not tap_id or len(tap_id) > MAX_TAP_ID_CHARS or not all(c.isalnum() or c in "-_" for c in tap_id):
        raise ApiError(422, "invalid")
    return tap_id


def _txn_path(txn_id: str) -> str:
    try:
        return f"ondc_txn/{uuid.UUID(txn_id)}"
    except ValueError:
        raise ApiError(404, "not_found") from None


class OndcBuyer:
    def __init__(
        self,
        *,
        mode: str,
        firestore: Firestore | None,
        auth: Auth,
        clock: Clock,
        metrics: Metrics,
        key: SubscriberKey,
        lookup: KeyLookup,
        transport: OndcTransport,
        bap_uri: str,
        search_uri: str,
        mock: MockBpp | None = None,
    ) -> None:
        self.mode = mode
        self.mock = mock
        self._fs = firestore
        self._auth = auth
        self._clock = clock
        self._metrics = metrics
        self._key = key
        self._lookup = lookup
        self._transport = transport
        self._bap_uri = bap_uri
        self._search_uri = search_uri

    # ---- user actions (/v1/ondc/*) ------------------------------------------------------------------------

    async def search(self, principal: Principal, *, session_id: str, pickup: GeoPoint, drop: GeoPoint) -> str:
        self._require_firestore()
        txn_id = str(uuid.uuid4())
        message_id = str(uuid.uuid4())
        now = self._clock.now_utc()
        await self._set(
            f"ondc_txn/{txn_id}",
            {
                "uid": principal.uid,
                "session_id": session_id,
                "bpp_id": None,
                "bpp_uri": None,
                "bpps": {},
                "demo": self.mode == "mock",
                "state": "SEARCHING_NET",
                "quotes": [],
                "selected_item_id": None,
                "order_id": None,
                "fee": {"amount": 0, "currency": "INR"},
                "driver": None,
                "stops": {"pickup": [pickup.lat, pickup.lng], "drop": [drop.lat, drop.lng]},
                "msg_search": message_id,
                "created_at": now,
                "updated_at": now,
            },
        )
        await self._send(
            "search",
            txn_id,
            message_id,
            uri=self._search_uri,
            bpp_id=None,
            message=search_message(pickup, drop),
        )
        return txn_id

    async def select(self, principal: Principal, txn_id: str, item_id: str) -> None:
        txn = await self._owned(principal, txn_id)
        if txn.get("state") not in ("SEARCHING_NET", "ON_SELECT"):
            raise ApiError(422, "invalid")
        quote = next((q for q in txn.get("quotes") or [] if q.get("item_id") == item_id), None)
        if quote is None:
            raise ApiError(422, "invalid")
        bpp_id = quote["bpp_id"]
        bpp_uri = (txn.get("bpps") or {}).get(bpp_id)
        message_id = str(uuid.uuid4())
        await self._update(
            txn_id,
            {"selected_item_id": item_id, "bpp_id": bpp_id, "bpp_uri": bpp_uri, "msg_select": message_id},
        )
        await self._send(
            "select",
            txn_id,
            message_id,
            uri=bpp_uri,
            bpp_id=bpp_id,
            message=select_message(quote["provider_id"], item_id),
        )

    async def init(self, principal: Principal, txn_id: str) -> None:
        txn = await self._owned(principal, txn_id)
        if txn.get("state") != "ON_SELECT":
            raise ApiError(422, "invalid")
        quote = self._selected_quote(txn)
        pickup, drop = self._stops(txn)
        message_id = str(uuid.uuid4())
        await self._update(txn_id, {"msg_init": message_id})
        message = init_message(
            quote["provider_id"], quote["item_id"], quote.get("fulfillment_id"), pickup, drop
        )
        await self._send(
            "init", txn_id, message_id, uri=txn["bpp_uri"], bpp_id=txn["bpp_id"], message=message
        )

    async def confirm(self, principal: Principal, txn_id: str, tap_id: str) -> None:
        tap_id = _valid_tap_id(tap_id)
        txn = await self._owned(principal, txn_id)
        await self._auth.check_not_revoked(principal)
        if txn.get("state") != "INIT":
            raise ApiError(422, "invalid")
        quote = self._selected_quote(txn)
        pickup, drop = self._stops(txn)
        message_id = str(uuid.uuid4())
        await self._update(txn_id, {"msg_confirm": message_id, "confirm_tap_id": tap_id})
        message = confirm_message(
            quote["provider_id"], quote["item_id"], quote.get("fulfillment_id"), pickup, drop
        )
        try:
            await self._send(
                "confirm", txn_id, message_id, uri=txn["bpp_uri"], bpp_id=txn["bpp_id"], message=message
            )
        except ApiError as e:
            if e.reason == "nack":
                # The seller refused the confirm, so no order exists: the user may confirm again or cancel locally.
                await self._update(txn_id, {"msg_confirm": None})
            raise

    async def status(self, principal: Principal, txn_id: str) -> None:
        txn = await self._owned(principal, txn_id)
        if txn.get("state") not in RIDE_ACTIVE | {"SOFT_CANCEL"} or not txn.get("order_id"):
            raise ApiError(422, "invalid")
        message_id = str(uuid.uuid4())
        await self._update(txn_id, {"msg_status": message_id})
        await self._send(
            "status",
            txn_id,
            message_id,
            uri=txn["bpp_uri"],
            bpp_id=txn["bpp_id"],
            message=status_message(txn["order_id"]),
        )

    async def cancel(self, principal: Principal, txn_id: str, tap_id: str, *, confirm: bool) -> None:
        """confirm=False asks the seller for the fee (soft cancel); confirm=True, after the user saw it, cancels."""
        tap_id = _valid_tap_id(tap_id)
        txn = await self._owned(principal, txn_id)
        await self._auth.check_not_revoked(principal)
        state = txn.get("state")
        if state == "INIT" and txn.get("msg_confirm"):
            # A confirm is with the seller and its order may already exist. Cancelling locally now would leave a
            # booked ride behind, so the cancel waits for on_confirm (then goes through the seller) or its failure.
            raise ApiError(422, "invalid")
        if state in BEFORE_ORDER:
            # No order exists at the seller yet: nothing to cancel there, and no fee.
            await self._update(
                txn_id, {"state": "CANCEL_CONFIRMED", "cancel_tap_id": tap_id, "stops": None, "quotes": []}
            )
            return
        if confirm:
            if state != "SOFT_CANCEL":
                raise ApiError(422, "invalid")
            fields: dict[str, Any] = {"confirm_cancel_tap_id": tap_id}
        else:
            if state not in RIDE_ACTIVE and state != "SOFT_CANCEL":
                raise ApiError(422, "invalid")
            # Any earlier quote is cleared now: until the seller prices THIS request there is no fee to show, so
            # the phone can't present (or accept a Confirm against) the previous round's fee.
            fields = {"cancel_tap_id": tap_id, "fee": None}
            if state in RIDE_ACTIVE:
                fields["resume_state"] = state
        message_id = str(uuid.uuid4())
        await self._update(txn_id, {**fields, "msg_cancel": message_id})
        await self._send(
            "cancel",
            txn_id,
            message_id,
            uri=txn["bpp_uri"],
            bpp_id=txn["bpp_id"],
            message=cancel_message(txn["order_id"], soft=not confirm),
        )

    async def keep(self, principal: Principal, txn_id: str, tap_id: str) -> None:
        """ "Keep ride" after a soft cancel: the ride goes on, so progress is shown again. Nothing is sent to the
        seller (a soft cancel only asked for the fee); a quote that still arrives for it is ignored."""
        tap_id = _valid_tap_id(tap_id)
        txn = await self._owned(principal, txn_id)
        state = txn.get("state")
        if state not in RIDE_ACTIVE and state != "SOFT_CANCEL":
            raise ApiError(422, "invalid")
        if state == "SOFT_CANCEL" and txn.get("confirm_cancel_tap_id"):
            raise ApiError(422, "invalid")  # the cancel was already confirmed: it is with the seller now
        fields: dict[str, Any] = {
            "keep_tap_id": tap_id,
            "msg_cancel": None,
            "fee": None,
            "resume_state": None,
        }
        if state == "SOFT_CANCEL":
            resume = txn.get("resume_state")
            fields["state"] = resume if resume in RIDE_ACTIVE else "CONFIRMED"
        await self._update(txn_id, fields)

    async def abandon(self, principal: Principal, txn_id: str) -> None:
        """The phone stopped showing a transaction before anything was ordered (session closed, new search):
        pickup and drop are cleared now rather than by the dispatcher. No order exists, so the seller isn't
        contacted; a booked ride is never touched (that needs the user's cancel)."""
        txn = await self._owned(principal, txn_id)
        state = txn.get("state")
        if state not in BEFORE_ORDER:
            return  # already ended, or booked: nothing to clear here
        if state == "INIT" and txn.get("msg_confirm"):
            raise ApiError(422, "invalid")  # a confirm is with the seller; its order may already exist
        await self._update(txn_id, _expired_fields("abandoned"))
        if self.mock is not None:
            await off_loop(self.mock.forget, txn_id)  # the demo seller's copy of the points goes too

    # ---- seller callbacks (/ondc/on_*) --------------------------------------------------------------------

    async def handle_callback(
        self, action: str, body: bytes, authorization: str | None
    ) -> tuple[int, dict[str, Any]]:
        """Verify → store the event → apply → ACK. Returns (HTTP status, ACK/NACK body)."""
        if action not in CALLBACK_ACTIONS:
            return 404, nack("unknown_action")
        if self._fs is None:
            return 503, nack("unavailable")
        try:
            signer = verify(authorization, body, self._lookup, now=self._epoch())
        except SignatureError as e:
            self._metrics.incr("ondc_callback", action=action, outcome="bad_signature")
            return 401, nack("invalid_signature", str(e))

        payload_hash = hashlib.sha256(body).hexdigest()
        try:
            return await self._store_and_apply(action, body, payload_hash, signer.subscriber_id)
        except ApiError:
            self._metrics.incr("ondc_callback", action=action, outcome="unavailable")
            return 503, nack("unavailable")

    async def _store_and_apply(
        self, action: str, body: bytes, payload_hash: str, signer: str
    ) -> tuple[int, dict[str, Any]]:
        now = self._clock.now_utc()
        try:
            payload = json.loads(body)
            ctx = payload["context"]
            message = payload.get("message") or {}
            txn_id, message_id = str(ctx["transaction_id"]), str(ctx["message_id"])
            if ctx.get("action") != action or not isinstance(message, dict):
                raise ValueError("action/message")
        except (ValueError, KeyError, TypeError):
            await self._set(
                f"ondc_event/{payload_hash[:40]}",
                {
                    "uid": None,  # no transaction, so no owner: unreadable by any phone
                    "txnId": None,
                    "action": action,
                    "message_id": None,
                    "payload_hash": payload_hash,
                    "received_at": now,
                    "parse_error": "invalid_payload",
                    "applied": True,
                },
            )
            self._metrics.incr("ondc_callback", action=action, outcome="invalid_payload")
            return 400, nack("invalid_payload")
        if ctx.get("bpp_id") != signer:
            self._metrics.incr("ondc_callback", action=action, outcome="signer_mismatch")
            return 401, nack("signer_mismatch")

        event_id = hashlib.sha256(f"{txn_id}|{action}|{signer}|{message_id}".encode()).hexdigest()[:40]
        event_path = f"ondc_event/{event_id}"
        existing = await self._get(event_path)
        if existing is not None and existing.get("applied"):
            self._metrics.incr("ondc_callback", action=action, outcome="duplicate")
            error = existing.get("apply_error")
            return (400, nack(error)) if error else (200, ack())
        txn = await self._txn_or_none(txn_id)
        # The owner's uid on the event is what firestore.rules checks before a phone may read it.
        uid = txn.get("uid") if txn is not None else None
        if existing is None:
            await self._set(
                event_path,
                {
                    "uid": uid,
                    "txnId": txn_id,
                    "action": action,
                    "message_id": message_id,
                    "payload_hash": payload_hash,
                    "received_at": now,
                    "parse_error": None,
                    "applied": False,
                },
            )
        error = await self._apply(action, txn_id, txn, ctx, message, payload.get("error"))
        await self._set(event_path, {"uid": uid, "applied": True, "apply_error": error}, merge=True)
        self._metrics.incr("ondc_callback", action=action, outcome=error or "ok")
        return (400, nack(error)) if error else (200, ack())

    async def _txn_or_none(self, txn_id: str) -> dict[str, Any] | None:
        """The transaction a callback names, or None when the id is malformed or unknown."""
        try:
            txn_path = _txn_path(txn_id)
        except ApiError:
            return None
        return await self._get(txn_path)

    async def _apply(
        self,
        action: str,
        txn_id: str,
        txn: dict[str, Any] | None,
        ctx: dict[str, Any],
        message: dict[str, Any],
        error: Any,
    ) -> str | None:
        """Folds one verified callback into `txn` (read just before the event was stored). Returns a NACK code, or
        None when accepted."""
        if txn is None:
            return "unknown_transaction"
        bpp_id = str(ctx.get("bpp_id"))
        state = str(txn.get("state"))
        if action in SOLICITED and str(ctx.get("message_id")) != txn.get(f"msg_{action[3:]}"):
            return "unsolicited"

        if action == "on_search":
            if state != "SEARCHING_NET":
                return None  # a late catalogue after the user moved on: acknowledged, ignored
            quotes = [q for q in txn.get("quotes") or [] if q.get("bpp_id") != bpp_id]
            quotes += [q.as_doc(bpp_id) for q in parse_on_search(message)]
            bpps = {**(txn.get("bpps") or {}), bpp_id: ctx.get("bpp_uri")}
            await self._update(txn_id, {"quotes": quotes[:MAX_QUOTES], "bpps": bpps})
            return None

        if txn.get("bpp_id") != bpp_id:
            return "wrong_bpp"
        if isinstance(error, dict) and action in SOLICITED:
            await self._update(
                txn_id, self._with_state(state, "FAILED", {"failure_code": str(error.get("code"))[:32]})
            )
            return None

        view = parse_order(message)
        fields: dict[str, Any] = {}
        proposed: str | None = None
        if action == "on_select" and state in ("SEARCHING_NET", "ON_SELECT"):
            proposed = "ON_SELECT"
        elif action == "on_init" and state == "ON_SELECT":
            proposed = "INIT"
        elif action == "on_confirm" and state == "INIT":
            if not view.order_id:
                return "missing_order_id"
            proposed = "CONFIRMED"
            fields["order_id"] = view.order_id
        elif action == "on_cancel":
            if view.order_status == "SOFT_CANCEL":
                if str(ctx.get("message_id")) != txn.get("msg_cancel"):
                    return None  # a quote for a soft cancel the user already answered ("Keep ride"): ignored
                proposed = "SOFT_CANCEL"
            elif view.order_status == "CANCELLED":
                proposed = "CANCEL_CONFIRMED"
            if view.fee_inr is not None:
                fields["fee"] = {"amount": view.fee_inr, "currency": "INR"}
        elif action in ("on_status", "on_update"):
            proposed = FULFILLMENT_STATE.get(view.fulfillment_state or "")
        if action in ("on_confirm", "on_status", "on_update", "on_cancel") and (
            view.driver_eta_min is not None or view.driver_distance_text is not None
        ):
            fields["driver"] = {"eta_min": view.driver_eta_min, "distance_text": view.driver_distance_text}
        if proposed is not None:
            if state == "SOFT_CANCEL" and proposed in PROGRESS:
                # Held back while the fee is on screen, but remembered: "Keep ride" resumes from here.
                resume = txn.get("resume_state")
                if resume not in PROGRESS or PROGRESS.index(proposed) > PROGRESS.index(str(resume)):
                    fields["resume_state"] = proposed
            fields = self._with_state(state, proposed, fields)
        if fields:
            await self._update(txn_id, fields)
        return None

    # ---- helpers -------------------------------------------------------------------------------------------

    @staticmethod
    def _with_state(current: str, proposed: str, fields: dict[str, Any]) -> dict[str, Any]:
        new_state = next_state(current, proposed)
        out = {**fields, "state": new_state}
        if new_state in TERMINAL:
            out["stops"] = None
        return out

    async def _send(
        self,
        action: str,
        txn_id: str,
        message_id: str,
        *,
        uri: str | None,
        bpp_id: str | None,
        message: dict[str, Any],
    ) -> None:
        if not uri:
            raise ApiError(503, "ondc_unavailable")
        ctx = context(
            action=action,
            transaction_id=txn_id,
            message_id=message_id,
            bap_id=self._key.subscriber_id,
            bap_uri=self._bap_uri,
            timestamp=self._clock.now_utc(),
            bpp_id=bpp_id,
            bpp_uri=uri if bpp_id else None,
        )
        body = encode(envelope(ctx, message))
        header = authorization_header(body, self._key, created=self._epoch())
        try:
            async with asyncio.timeout(DEADLINE_ONDC_OUTBOUND_S):
                status, reply = await self._transport.send(uri, action, body, header)
        except TimeoutError:
            # Unknown outcome: the seller may have received and acted on it.
            self._metrics.incr("ondc_outbound", action=action, outcome="timeout")
            raise ApiError(503, "ondc_unavailable", reason="timeout") from None
        if status != 200 or ack_status(reply) != "ACK":
            self._metrics.incr("ondc_outbound", action=action, outcome="nack")
            log.warning("ONDC %s not acknowledged: HTTP %d", action, status)
            raise ApiError(503, "ondc_unavailable", reason="nack")
        self._metrics.incr("ondc_outbound", action=action, outcome="ack")

    async def _owned(self, principal: Principal, txn_id: str) -> dict[str, Any]:
        txn = await self._get(_txn_path(txn_id))
        if txn is None:
            raise ApiError(404, "not_found")
        if txn.get("uid") != principal.uid:
            self._metrics.incr("ondc_forbidden")
            raise ApiError(403, "forbidden")
        return txn

    @staticmethod
    def _selected_quote(txn: dict[str, Any]) -> dict[str, Any]:
        selected = txn.get("selected_item_id")
        for quote in txn.get("quotes") or []:
            if quote.get("item_id") == selected and quote.get("bpp_id") == txn.get("bpp_id"):
                return quote
        raise ApiError(422, "invalid")

    @staticmethod
    def _stops(txn: dict[str, Any]) -> tuple[GeoPoint, GeoPoint]:
        stops = txn.get("stops") or {}
        try:
            return GeoPoint(*stops["pickup"]), GeoPoint(*stops["drop"])
        except (KeyError, TypeError) as e:
            raise ApiError(422, "invalid") from e

    def _require_firestore(self) -> Firestore:
        if self._fs is None:
            raise ApiError(503, "ondc_unavailable")
        return self._fs

    async def _get(self, path: str) -> dict[str, Any] | None:
        fs = self._require_firestore()
        try:
            return await asyncio.to_thread(fs.get, path)
        except FirestoreUnavailable:
            raise ApiError(503, "ondc_unavailable") from None

    async def _set(self, path: str, data: dict[str, Any], *, merge: bool = False) -> None:
        fs = self._require_firestore()
        try:
            await asyncio.to_thread(fs.set, path, data, merge=merge)
        except FirestoreUnavailable:
            raise ApiError(503, "ondc_unavailable") from None

    async def _update(self, txn_id: str, fields: dict[str, Any]) -> None:
        await self._set(_txn_path(txn_id), {**fields, "updated_at": self._now()}, merge=True)

    def _now(self) -> datetime:
        return self._clock.now_utc()

    def _epoch(self) -> int:
        return int(self._clock.now_utc().timestamp())


def _expired_fields(code: str) -> dict[str, Any]:
    return {"state": "FAILED", "failure_code": code, "stops": None, "quotes": []}


def _as_datetime(value: Any) -> datetime | None:
    if isinstance(value, datetime):
        return value
    try:
        return datetime.fromisoformat(str(value))
    except ValueError:
        return None


def expire_abandoned(
    fs: Firestore,
    now: datetime,
    *,
    store: HotStore | None = None,
    stop: Callable[[], bool] | None = None,
    page: int = EXPIRE_PAGE,
) -> int:
    """Dispatcher job: every transaction still before an order (searching, selected, initialised) and idle for
    ABANDONED_AFTER becomes FAILED ('expired') with its pickup/drop and quotes cleared, and the demo seller's copy of
    the order (in `store`, the hot store) is dropped. Pickup and drop must not outlive the ride that needed them
    (privacy notice); a search the user simply walked away from has no ride. One equality query per state
    (single-field indexes only). Returns the number expired."""
    cutoff = now - ABANDONED_AFTER
    expired = 0
    for state in sorted(BEFORE_ORDER):
        for path, doc in fs.query("ondc_txn", "state", "==", state, limit=page):
            if stop is not None and stop():
                return expired
            updated = _as_datetime(doc.get("updated_at") or doc.get("created_at"))
            if updated is not None and updated.tzinfo is None:
                updated = updated.replace(tzinfo=now.tzinfo)
            if updated is not None and updated > cutoff:
                continue
            fs.set(path, {**_expired_fields("expired"), "updated_at": now}, merge=True)
            if store is not None:
                forget_order(store, path.rsplit("/", 1)[-1])
            expired += 1
    return expired


def build_buyer(
    settings: Settings,
    *,
    firestore: Firestore | None,
    auth: Auth,
    clock: Clock,
    metrics: Metrics,
    store: HotStore,
    examples_dir: Path | None = DEFAULT_EXAMPLES_DIR,
) -> OndcBuyer | None:
    """The buyer for ONDC_MODE. `off` → None (routes answer 503 ondc_unavailable). `mock` → the buyer wired to the
    in-process demo seller with per-container keys. `preprod`/`prod` need registry keys and an HTTP transport
    (delivery plan P2) and stay unavailable until those exist."""
    if settings.ondc_mode != "mock":
        if settings.ondc_mode != "off":
            log.warning("ONDC_MODE=%s needs registered keys (P2); ONDC stays unavailable", settings.ondc_mode)
        return None
    base = settings.public_base_url or "https://ridemate-api.invalid"
    bap_key = SubscriberKey.generate(urlparse(base).hostname or "ridemate-bap", "ridemate-bap-k1")
    seller_key = SubscriberKey.generate(MOCK_BPP_ID, MOCK_UNIQUE_KEY_ID)
    registry = StaticKeyRegistry()
    registry.register(bap_key)
    registry.register(seller_key)
    seller = MockBpp(
        key=seller_key,
        lookup=registry.lookup,
        clock=clock,
        store=store,
        bpp_uri=f"{base}/mock-bpp",
        examples_dir=examples_dir,
    )
    buyer = OndcBuyer(
        mode="mock",
        firestore=firestore,
        auth=auth,
        clock=clock,
        metrics=metrics,
        key=bap_key,
        lookup=registry.lookup,
        transport=InProcessTransport(seller),
        bap_uri=f"{base}/ondc",
        search_uri=seller.bpp_uri,
        mock=seller,
    )
    seller.attach(buyer.handle_callback)
    return buyer
