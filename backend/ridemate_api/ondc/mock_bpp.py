"""The hackathon's in-process ONDC seller, shown to users as "ONDC demo seller" (architecture §4.2 "ONDC BAP").

It behaves like a TRV10 seller app: every request must carry a valid BAP signature, is ACKed at once, and is
answered by an asynchronous, properly signed `on_*` callback delivered in-process to the buyer's callback handler.
Replies replay the official TRV10 example payloads when scripts/fetch_trv10_examples.py has placed them in the
image, with the seller's own catalogue laid over them; without the examples the built-in minimal payloads are used,
so tests never need the network. Order state lives in the hot store so any container can continue a ride.
"""

from __future__ import annotations

import asyncio
import copy
import json
import logging
import math
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from ..clock import Clock
from ..offload import off_loop
from ..store import HotStore, StoreUnavailable
from .payloads import (
    CONFIRM_CANCEL,
    DISTANCE_CODE,
    ETA_CODE,
    FEE_BREAKUP_TITLE,
    INFO_TAG,
    SOFT_CANCEL,
    GeoPoint,
    ack,
    encode,
    envelope,
    iso_timestamp,
    nack,
    stops,
)
from .signing import KeyLookup, SignatureError, SubscriberKey, authorization_header, verify

log = logging.getLogger(__name__)

MOCK_BPP_ID = "mock-bpp.ridemate"
MOCK_UNIQUE_KEY_ID = "mock-bpp-k1"
MOCK_PROVIDER_ID = "ondc-demo-seller"
MOCK_PROVIDER_NAME = "ONDC demo seller"
DEFAULT_EXAMPLES_DIR = Path("/opt/ridemate/trv10_examples")
ROAD_FACTOR = 1.35
STORE_PREFIX = "mock_bpp:order:"
REPLIES = {
    "search": "on_search",
    "select": "on_select",
    "init": "on_init",
    "confirm": "on_confirm",
    "status": "on_status",
    "cancel": "on_cancel",
}
# Example lists whose first element is the template for each of our own elements.
_TEMPLATED_LISTS = frozenset({"providers", "items", "fulfillments", "payments"})

# (on_action, body, Authorization header) -> (HTTP status, ACK/NACK body)
Deliver = Callable[[str, bytes, str], Awaitable[tuple[int, dict[str, Any]]]]


@dataclass(frozen=True)
class MockItem:
    item_id: str
    fulfillment_id: str
    category: str  # TRV10 vehicle category
    name: str
    base_inr: int
    per_km_inr: float
    min_inr: int
    eta_min: int
    driver_m: int


MOCK_ITEMS: tuple[MockItem, ...] = (
    MockItem("demo-auto", "ful-auto", "AUTO_RICKSHAW", "Demo Auto", 30, 15.0, 40, 4, 1100),
    MockItem("demo-cab", "ful-cab", "CAB", "Demo Cab", 60, 20.0, 80, 6, 1800),
    MockItem("demo-bike", "ful-bike", "TWO_WHEELER", "Demo Bike", 20, 8.0, 25, 3, 700),
)
_ITEMS = {item.item_id: item for item in MOCK_ITEMS}


def trip_km(pickup: GeoPoint, drop: GeoPoint) -> float:
    lat1, lat2 = math.radians(pickup.lat), math.radians(drop.lat)
    dlat, dlng = lat2 - lat1, math.radians(drop.lng - pickup.lng)
    a = math.sin(dlat / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin(dlng / 2) ** 2
    return 2 * 6371.0 * math.asin(math.sqrt(a)) * ROAD_FACTOR


def fare_inr(item: MockItem, km: float) -> int:
    return max(item.min_inr, round(item.base_inr + item.per_km_inr * km))


def cancellation_fee_inr(price: int) -> int:
    """After a driver is assigned: 10% of the fare, at least ₹10, capped at ₹100 (MV Aggregator Guidelines 2025)."""
    return min(100, max(10, round(price * 0.10)))


def merge_example(base: Any, ours: Any) -> Any:
    """Our values win; example-only keys stay. In templated lists each of our elements is laid over a copy of the
    example's first element, so the reply keeps the example's shape with our catalogue."""
    if isinstance(base, dict) and isinstance(ours, dict):
        merged = copy.deepcopy(base)
        for key, value in ours.items():
            if key in _TEMPLATED_LISTS and isinstance(value, list) and isinstance(base.get(key), list):
                template = base[key][0] if base[key] and isinstance(base[key][0], dict) else {}
                merged[key] = [merge_example(template, element) for element in value]
            elif key in merged:
                merged[key] = merge_example(merged[key], value)
            else:
                merged[key] = copy.deepcopy(value)
        return merged
    return copy.deepcopy(ours)


def load_examples(directory: Path | None) -> dict[str, dict[str, Any]]:
    """{on_action: example message} from <directory>/<on_action>.json files written at image-build time."""
    examples: dict[str, dict[str, Any]] = {}
    if directory is None or not directory.is_dir():
        return examples
    for action in REPLIES.values():
        path = directory / f"{action}.json"
        try:
            doc = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            continue
        message = doc.get("message") if isinstance(doc, dict) else None
        if isinstance(message, dict):
            examples[action] = message
    if examples:
        log.info("mock seller replays %d TRV10 examples", len(examples))
    return examples


class _Reject(Exception):
    def __init__(self, code: str) -> None:
        super().__init__(code)
        self.code = code


def _parse_gps(value: Any) -> GeoPoint:
    try:
        lat_text, lng_text = str(value).split(",")
        point = GeoPoint(float(lat_text), float(lng_text))
    except ValueError as e:
        raise _Reject("invalid_location") from e
    if not (-90 <= point.lat <= 90 and -180 <= point.lng <= 180):
        raise _Reject("invalid_location")
    return point


def _info_tags(eta_min: int, driver_m: int) -> list[dict[str, Any]]:
    return [
        {
            "descriptor": {"code": INFO_TAG},
            "list": [
                {"descriptor": {"code": ETA_CODE}, "value": str(eta_min)},
                {"descriptor": {"code": DISTANCE_CODE}, "value": str(driver_m)},
            ],
        }
    ]


def forget_order(store: HotStore, txn_id: str) -> bool:
    """Drop the demo seller's copy of an order (it holds the pickup/drop points) once nobody will book it. False when
    the store couldn't be written (the entry then expires with the Dict's 7-day idle limit)."""
    try:
        store.delete(f"{STORE_PREFIX}{txn_id}")
    except StoreUnavailable:
        log.warning("mock seller order not forgotten: store unavailable")
        return False
    return True


def _d(value: Any) -> dict[str, Any]:
    return value if isinstance(value, dict) else {}


def _price(value: int) -> dict[str, str]:
    return {"currency": "INR", "value": str(value)}


class MockBpp:
    def __init__(
        self,
        *,
        key: SubscriberKey,
        lookup: KeyLookup,
        clock: Clock,
        store: HotStore,
        bpp_uri: str,
        examples_dir: Path | None = DEFAULT_EXAMPLES_DIR,
    ) -> None:
        self.bpp_id = key.subscriber_id
        self.bpp_uri = bpp_uri
        self._key = key
        self._lookup = lookup
        self._clock = clock
        self._store = store
        self._examples = load_examples(examples_dir)
        self._deliver: Deliver | None = None
        self._tasks: set[asyncio.Task[None]] = set()

    def attach(self, deliver: Deliver) -> None:
        self._deliver = deliver

    def forget(self, txn_id: str) -> bool:
        return forget_order(self._store, txn_id)

    async def drain(self) -> None:
        """Wait until every scheduled callback has been delivered (tests, graceful shutdown). Only unfinished tasks
        are awaited: a finished one stays in the set until its done-callback runs on the loop, and awaiting a gather
        of finished tasks returns without ever yielding, so looping on the set itself could spin forever."""
        while pending := [task for task in self._tasks if not task.done()]:
            await asyncio.gather(*pending, return_exceptions=True)

    async def receive(
        self, action: str, body: bytes, authorization: str | None
    ) -> tuple[int, dict[str, Any]]:
        if action not in REPLIES:
            return 404, nack("unknown_action")
        try:
            signer = verify(authorization, body, self._lookup, now=self._epoch())
        except SignatureError as e:
            return 401, nack("invalid_signature", str(e))
        try:
            payload = json.loads(body)
            ctx = payload["context"]
            message = payload.get("message") or {}
            if not isinstance(ctx, dict) or not isinstance(message, dict):
                raise TypeError("context/message")
        except (ValueError, KeyError, TypeError):
            return 400, nack("invalid_payload")
        if ctx.get("action") != action or ctx.get("bap_id") != signer.subscriber_id:
            return 401, nack("signer_mismatch")
        if action != "search" and ctx.get("bpp_id") != self.bpp_id:
            return 400, nack("wrong_bpp")
        try:
            # Order state lives in the Modal Dict: the blocking reads/writes run off the event loop.
            reply = await off_loop(self._handle, action, str(ctx.get("transaction_id", "")), message)
        except _Reject as e:
            return 400, nack(e.code)
        except StoreUnavailable:
            return 503, nack("unavailable")
        task = asyncio.create_task(self._reply(REPLIES[action], ctx, reply))
        self._tasks.add(task)
        task.add_done_callback(self._tasks.discard)
        return 200, ack()

    # ---- order handling ------------------------------------------------------------------------------------

    def _handle(self, action: str, txn_id: str, message: dict[str, Any]) -> dict[str, Any]:
        if not txn_id:
            raise _Reject("missing_transaction")
        if action == "search":
            return self._on_search(txn_id, message)
        order = self._load(txn_id)
        if action == "select":
            return self._on_select(txn_id, order, message)
        if action == "init":
            return self._on_init(txn_id, order)
        if action == "confirm":
            return self._on_confirm(txn_id, order)
        if action == "status":
            return self._on_status(txn_id, order, message)
        return self._on_cancel(txn_id, order, message)

    def _on_search(self, txn_id: str, message: dict[str, Any]) -> dict[str, Any]:
        stops_in = _d(_d(message.get("intent")).get("fulfillment")).get("stops")
        points = {
            _d(stop).get("type"): _d(_d(stop).get("location")).get("gps")
            for stop in (stops_in if isinstance(stops_in, list) else [])
        }
        pickup, drop = _parse_gps(points.get("START")), _parse_gps(points.get("END"))
        km = trip_km(pickup, drop)
        self._save(
            txn_id,
            {"pickup": [pickup.lat, pickup.lng], "drop": [drop.lat, drop.lng], "km": km, "status": "QUOTED"},
        )
        return {
            "catalog": {
                "descriptor": {"name": MOCK_PROVIDER_NAME},
                "providers": [
                    {
                        "id": MOCK_PROVIDER_ID,
                        "descriptor": {"name": MOCK_PROVIDER_NAME},
                        "fulfillments": [
                            {
                                "id": item.fulfillment_id,
                                "type": "DELIVERY",
                                "vehicle": {"category": item.category},
                                "stops": stops(pickup, drop),
                            }
                            for item in MOCK_ITEMS
                        ],
                        "items": [
                            {
                                "id": item.item_id,
                                "descriptor": {"code": "RIDE", "name": item.name},
                                "fulfillment_ids": [item.fulfillment_id],
                                "price": _price(fare_inr(item, km)),
                                "tags": _info_tags(item.eta_min, item.driver_m),
                            }
                            for item in MOCK_ITEMS
                        ],
                    }
                ],
            }
        }

    def _on_select(self, txn_id: str, order: dict[str, Any], message: dict[str, Any]) -> dict[str, Any]:
        if order["status"] not in ("QUOTED", "SELECTED"):
            raise _Reject("invalid_order_state")
        items = _d(message.get("order")).get("items")
        item_id = _d(items[0]).get("id") if isinstance(items, list) and items else None
        if item_id not in _ITEMS:
            raise _Reject("unknown_item")
        order.update(selected=item_id, status="SELECTED")
        self._save(txn_id, order)
        return {"order": self._order_body(order)}

    def _on_init(self, txn_id: str, order: dict[str, Any]) -> dict[str, Any]:
        if order["status"] != "SELECTED":
            raise _Reject("invalid_order_state")
        order["status"] = "INITIALIZED"
        self._save(txn_id, order)
        return {"order": self._order_body(order)}

    def _on_confirm(self, txn_id: str, order: dict[str, Any]) -> dict[str, Any]:
        if order["status"] != "INITIALIZED":
            raise _Reject("invalid_order_state")
        order.update(status="ACTIVE", order_id=f"demo-order-{txn_id[:8]}", driver_assigned=False)
        self._save(txn_id, order)
        return {"order": self._order_body(order, fulfillment_state="RIDE_CONFIRMED")}

    def _on_status(self, txn_id: str, order: dict[str, Any], message: dict[str, Any]) -> dict[str, Any]:
        self._check_order_id(order, message)
        if order["status"] == "CANCELLED":
            return {"order": self._order_body(order, fulfillment_state="RIDE_CANCELLED")}
        state = "RIDE_ENROUTE_PICKUP" if order.get("driver_assigned") else "RIDE_ASSIGNED"
        order["driver_assigned"] = True
        self._save(txn_id, order)
        return {"order": self._order_body(order, fulfillment_state=state, with_driver=True)}

    def _on_cancel(self, txn_id: str, order: dict[str, Any], message: dict[str, Any]) -> dict[str, Any]:
        self._check_order_id(order, message)
        code = _d(message.get("descriptor")).get("code")
        price = self._fare(order)
        fee = cancellation_fee_inr(price) if order.get("driver_assigned") else 0
        if code == SOFT_CANCEL and order["status"] in ("ACTIVE", "SOFT_CANCEL"):
            order["status"] = "SOFT_CANCEL"
            state = "RIDE_ASSIGNED" if order.get("driver_assigned") else "RIDE_CONFIRMED"
        elif code == CONFIRM_CANCEL and order["status"] == "SOFT_CANCEL":
            order["status"] = "CANCELLED"
            state = "RIDE_CANCELLED"
        else:
            raise _Reject("invalid_cancel")
        self._save(txn_id, order)
        body = self._order_body(order, fulfillment_state=state, fee=fee)
        body["cancellation"] = {"cancelled_by": "CONSUMER", "reason": {"descriptor": {"code": "7"}}}
        return {"order": body}

    def _order_body(
        self,
        order: dict[str, Any],
        *,
        fulfillment_state: str | None = None,
        with_driver: bool = False,
        fee: int | None = None,
    ) -> dict[str, Any]:
        item = _ITEMS[order["selected"]]
        price = self._fare(order)
        pickup, drop = GeoPoint(*order["pickup"]), GeoPoint(*order["drop"])
        fulfillment: dict[str, Any] = {
            "id": item.fulfillment_id,
            "type": "DELIVERY",
            "vehicle": {"category": item.category},
            "stops": stops(pickup, drop),
        }
        if fulfillment_state:
            fulfillment["state"] = {"descriptor": {"code": fulfillment_state}}
        if with_driver:
            fulfillment["agent"] = {"person": {"name": "Demo Driver"}, "contact": {"phone": "0000000000"}}
            fulfillment["vehicle"]["registration"] = "DEMO 0000"
            fulfillment["tags"] = _info_tags(item.eta_min, item.driver_m)
        breakup = [{"title": "BASE_FARE", "price": _price(price)}]
        total = price
        if fee is not None:
            breakup = [{"title": FEE_BREAKUP_TITLE, "price": _price(fee)}]
            total = fee
        body: dict[str, Any] = {
            "provider": {"id": MOCK_PROVIDER_ID, "descriptor": {"name": MOCK_PROVIDER_NAME}},
            "items": [
                {
                    "id": item.item_id,
                    "descriptor": {"code": "RIDE", "name": item.name},
                    "price": _price(price),
                }
            ],
            "fulfillments": [fulfillment],
            "quote": {"price": _price(total), "breakup": breakup},
            "payments": [{"collected_by": "BPP", "type": "ON-FULFILLMENT", "status": "NOT-PAID"}],
        }
        if order.get("order_id"):
            body["id"] = order["order_id"]
            body["status"] = order["status"] if order["status"] in ("SOFT_CANCEL", "CANCELLED") else "ACTIVE"
        return body

    @staticmethod
    def _fare(order: dict[str, Any]) -> int:
        return fare_inr(_ITEMS[order["selected"]], float(order["km"]))

    @staticmethod
    def _check_order_id(order: dict[str, Any], message: dict[str, Any]) -> None:
        if not order.get("order_id") or message.get("order_id") != order["order_id"]:
            raise _Reject("unknown_order")

    # ---- plumbing ------------------------------------------------------------------------------------------

    def _load(self, txn_id: str) -> dict[str, Any]:
        order = self._store.get(f"{STORE_PREFIX}{txn_id}")
        if not isinstance(order, dict):
            raise _Reject("unknown_transaction")
        return dict(order)

    def _save(self, txn_id: str, order: dict[str, Any]) -> None:
        self._store.put(f"{STORE_PREFIX}{txn_id}", order)

    def _epoch(self) -> int:
        return int(self._clock.now_utc().timestamp())

    async def _reply(self, action: str, request_ctx: dict[str, Any], ours: dict[str, Any]) -> None:
        if self._deliver is None:
            log.error("mock seller has no buyer attached; dropping %s", action)
            return
        message = merge_example(self._examples.get(action, {}), ours)
        ctx = {
            **request_ctx,
            "action": action,
            "bpp_id": self.bpp_id,
            "bpp_uri": self.bpp_uri,
            "timestamp": iso_timestamp(self._clock.now_utc()),
        }
        body = encode(envelope(ctx, message))
        header = authorization_header(body, self._key, created=self._epoch())
        try:
            status, reply = await self._deliver(action, body, header)
        except Exception:  # a failed delivery must not kill the event loop's task
            log.exception("mock seller delivery of %s failed", action)
            return
        if status != 200:
            log.warning("buyer answered %s with HTTP %d: %s", action, status, reply.get("error"))
