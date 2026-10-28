"""ONDC mobility (TRV10, core 2.0.1) context and message builders for the buyer app, and the parsers that reduce
seller replies to the few facts RideMate stores: quotes, order id, ride state, driver ETA/distance and the
cancellation fee. Parsers are tolerant: unknown or missing fields are ignored, never guessed.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Any

DOMAIN = "ONDC:TRV10"
CORE_VERSION = "2.0.1"
COUNTRY_CODE = "IND"
DEFAULT_CITY_CODE = "std:0361"  # Guwahati, where RideMate is tested
DEFAULT_TTL = "PT30S"
CANCELLATION_REASON_ID = "7"  # rider: "booked another ride / plans changed"
SOFT_CANCEL = "SOFT_CANCEL"
CONFIRM_CANCEL = "CONFIRM_CANCEL"
FEE_BREAKUP_TITLE = "CANCELLATION_CHARGES"
INFO_TAG = "INFO"
ETA_CODE = "ETA_TO_NEAREST_DRIVER_MIN"
DISTANCE_CODE = "DISTANCE_TO_NEAREST_DRIVER_METER"
MAX_QUOTES = 20
MAX_PRICE_INR = 100_000

VEHICLE_CATEGORY_CLASS = {
    "AUTO_RICKSHAW": "auto",
    "TWO_WHEELER": "bike",
    "BIKE": "bike",
    "CAB": "cab_economy",
    "SEDAN": "cab_economy",
    "HATCHBACK": "cab_economy",
    "SUV": "cab_xl",
}

# Fulfillment state code → RideMate ONDC state (contracts §1.4). Codes not listed leave the state unchanged.
FULFILLMENT_STATE = {
    "RIDE_ASSIGNED": "CONFIRMED",
    "RIDE_ENROUTE_PICKUP": "ARRIVING",
    "RIDE_ARRIVED_PICKUP": "ARRIVING",
    "RIDE_STARTED": "ON_TRIP",
    "RIDE_ENDED": "ENDED",
    "RIDE_CANCELLED": "CANCEL_CONFIRMED",
}


@dataclass(frozen=True)
class GeoPoint:
    lat: float
    lng: float

    @property
    def gps(self) -> str:
        return f"{self.lat:.6f}, {self.lng:.6f}"


def iso_timestamp(at: datetime) -> str:
    return at.astimezone(UTC).strftime("%Y-%m-%dT%H:%M:%S.") + f"{at.microsecond // 1000:03d}Z"


def context(
    *,
    action: str,
    transaction_id: str,
    message_id: str,
    bap_id: str,
    bap_uri: str,
    timestamp: datetime,
    bpp_id: str | None = None,
    bpp_uri: str | None = None,
    city_code: str = DEFAULT_CITY_CODE,
    ttl: str = DEFAULT_TTL,
) -> dict[str, Any]:
    ctx: dict[str, Any] = {
        "location": {"country": {"code": COUNTRY_CODE}, "city": {"code": city_code}},
        "domain": DOMAIN,
        "timestamp": iso_timestamp(timestamp),
        "bap_id": bap_id,
        "bap_uri": bap_uri,
        "transaction_id": transaction_id,
        "message_id": message_id,
        "version": CORE_VERSION,
        "action": action,
        "ttl": ttl,
    }
    if bpp_id is not None:
        ctx["bpp_id"] = bpp_id
    if bpp_uri is not None:
        ctx["bpp_uri"] = bpp_uri
    return ctx


def envelope(ctx: dict[str, Any], message: dict[str, Any]) -> dict[str, Any]:
    return {"context": ctx, "message": message}


def encode(payload: dict[str, Any]) -> bytes:
    """The exact bytes that are signed and sent."""
    return json.dumps(payload, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


def stops(pickup: GeoPoint, drop: GeoPoint) -> list[dict[str, Any]]:
    return [
        {"type": "START", "location": {"gps": pickup.gps}},
        {"type": "END", "location": {"gps": drop.gps}},
    ]


def _payment_terms() -> dict[str, Any]:
    return {
        "collected_by": "BPP",
        "type": "ON-FULFILLMENT",
        "status": "NOT-PAID",
        "tags": [
            {
                "descriptor": {"code": "SETTLEMENT_TERMS"},
                "list": [{"descriptor": {"code": "DELAY_INTEREST"}, "value": "0"}],
            }
        ],
    }


def search_message(pickup: GeoPoint, drop: GeoPoint) -> dict[str, Any]:
    return {
        "intent": {
            "fulfillment": {"stops": stops(pickup, drop)},
            "payment": {"collected_by": "BPP"},
        }
    }


def select_message(provider_id: str, item_id: str) -> dict[str, Any]:
    return {"order": {"provider": {"id": provider_id}, "items": [{"id": item_id}]}}


def _order(
    provider_id: str, item_id: str, fulfillment_id: str | None, pickup: GeoPoint, drop: GeoPoint
) -> dict[str, Any]:
    fulfillment: dict[str, Any] = {"stops": stops(pickup, drop)}
    if fulfillment_id:
        fulfillment["id"] = fulfillment_id
    return {
        "provider": {"id": provider_id},
        "items": [{"id": item_id}],
        "fulfillments": [fulfillment],
        "payments": [_payment_terms()],
    }


def init_message(
    provider_id: str, item_id: str, fulfillment_id: str | None, pickup: GeoPoint, drop: GeoPoint
) -> dict[str, Any]:
    return {"order": _order(provider_id, item_id, fulfillment_id, pickup, drop)}


def confirm_message(
    provider_id: str, item_id: str, fulfillment_id: str | None, pickup: GeoPoint, drop: GeoPoint
) -> dict[str, Any]:
    return {"order": _order(provider_id, item_id, fulfillment_id, pickup, drop)}


def status_message(order_id: str) -> dict[str, Any]:
    return {"order_id": order_id}


def cancel_message(order_id: str, *, soft: bool) -> dict[str, Any]:
    """Soft cancel asks the seller for the fee; CONFIRM_CANCEL (after the user's tap) cancels for real."""
    code = SOFT_CANCEL if soft else CONFIRM_CANCEL
    return {
        "order_id": order_id,
        "cancellation_reason_id": CANCELLATION_REASON_ID,
        "descriptor": {"code": code, "name": "Ride Cancellation"},
    }


def ack() -> dict[str, Any]:
    return {"message": {"ack": {"status": "ACK"}}}


def nack(code: str, message: str = "") -> dict[str, Any]:
    return {
        "message": {"ack": {"status": "NACK"}},
        "error": {"type": "CONTEXT-ERROR", "code": code, "message": message},
    }


def ack_status(reply: Any) -> str | None:
    try:
        return str(reply["message"]["ack"]["status"])
    except (KeyError, TypeError):
        return None


# ---- parsing seller replies ------------------------------------------------------------------------------


@dataclass(frozen=True)
class Quote:
    item_id: str
    provider_id: str
    provider_name: str
    vehicle_class: str
    price: int
    eta_min: int | None
    fulfillment_id: str | None

    def as_doc(self, bpp_id: str) -> dict[str, Any]:
        return {
            "item_id": self.item_id,
            "provider_id": self.provider_id,
            "provider_name": self.provider_name,
            "vehicle_class": self.vehicle_class,
            "price": self.price,
            "eta_min": self.eta_min,
            "fulfillment_id": self.fulfillment_id,
            "bpp_id": bpp_id,
        }


@dataclass(frozen=True)
class OrderView:
    order_id: str | None
    order_status: str | None
    fulfillment_state: str | None
    fee_inr: int | None
    driver_eta_min: int | None
    driver_distance_text: str | None


def _dict(value: Any) -> dict[str, Any]:
    return value if isinstance(value, dict) else {}


def _list(value: Any) -> list[Any]:
    return value if isinstance(value, list) else []


def _str(value: Any, max_len: int = 128) -> str | None:
    return value[:max_len] if isinstance(value, str) and value else None


def _rupees(price: Any) -> int | None:
    value = _dict(price).get("value")
    try:
        number = float(str(value))
    except ValueError:
        return None
    if not (0 <= number <= MAX_PRICE_INR):
        return None
    return round(number)


def _tag_value(tags: Any, group: str, code: str) -> str | None:
    for tag in _list(tags):
        tag = _dict(tag)
        if _dict(tag.get("descriptor")).get("code") != group:
            continue
        for entry in _list(tag.get("list")):
            entry = _dict(entry)
            if _dict(entry.get("descriptor")).get("code") == code:
                return _str(entry.get("value"), 32)
    return None


def _small_int(text: str | None, high: int) -> int | None:
    if text is None:
        return None
    try:
        number = int(float(text))
    except (ValueError, OverflowError):
        return None
    return number if 0 <= number <= high else None


def distance_text(meters: int) -> str:
    return f"{meters / 1000:.1f} km" if meters >= 1000 else f"{meters} m"


def _driver_facts(tags: Any) -> tuple[int | None, str | None]:
    eta = _small_int(_tag_value(tags, INFO_TAG, ETA_CODE), 240)
    meters = _small_int(_tag_value(tags, INFO_TAG, DISTANCE_CODE), 500_000)
    return eta, (distance_text(meters) if meters is not None else None)


def parse_on_search(message: Any) -> list[Quote]:
    catalog = _dict(_dict(message).get("catalog"))
    quotes: list[Quote] = []
    for provider in _list(catalog.get("providers")):
        provider = _dict(provider)
        provider_id = _str(provider.get("id"))
        if provider_id is None:
            continue
        provider_name = _str(_dict(provider.get("descriptor")).get("name")) or provider_id
        categories = {
            _str(f.get("id")): VEHICLE_CATEGORY_CLASS.get(
                str(_dict(f.get("vehicle")).get("category", "")).upper()
            )
            for f in map(_dict, _list(provider.get("fulfillments")))
        }
        for item in map(_dict, _list(provider.get("items"))):
            item_id = _str(item.get("id"))
            price = _rupees(item.get("price"))
            fulfillment_ids = [_str(f) for f in _list(item.get("fulfillment_ids"))]
            fulfillment_id = next((f for f in fulfillment_ids if f), None)
            vehicle_class = categories.get(fulfillment_id) or "other"
            if item_id is None or price is None:
                continue
            eta, _ = _driver_facts(item.get("tags"))
            quotes.append(
                Quote(item_id, provider_id, provider_name, vehicle_class, price, eta, fulfillment_id)
            )
            if len(quotes) >= MAX_QUOTES:
                return quotes
    return quotes


def parse_order(message: Any) -> OrderView:
    order = _dict(_dict(message).get("order"))
    fulfillment = _dict(next(iter(_list(order.get("fulfillments"))), {}))
    state = _str(_dict(_dict(fulfillment.get("state")).get("descriptor")).get("code"), 64)
    fee = None
    for line in map(_dict, _list(_dict(order.get("quote")).get("breakup"))):
        if line.get("title") == FEE_BREAKUP_TITLE:
            fee = _rupees(line.get("price"))
    eta, distance = _driver_facts(fulfillment.get("tags"))
    return OrderView(
        order_id=_str(order.get("id")),
        order_status=_str(order.get("status"), 64),
        fulfillment_state=state,
        fee_inr=fee,
        driver_eta_min=eta,
        driver_distance_text=distance,
    )
