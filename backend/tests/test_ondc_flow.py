"""ONDC buyer against the in-process demo seller: the whole ride, idempotent signed callbacks, ownership, failure modes."""

from __future__ import annotations

import asyncio
import json
import threading
from pathlib import Path
from typing import Any

import pytest

from ridemate_api.clock import FakeClock
from ridemate_api.interfaces import ApiError, FirestoreQuotaExhausted, FirestoreUnavailable, Principal
from ridemate_api.ondc import bap as bap_module
from ridemate_api.ondc.bap import OndcBuyer, build_buyer, next_state
from ridemate_api.ondc.mock_bpp import (
    MOCK_BPP_ID,
    MOCK_PROVIDER_NAME,
    MockBpp,
    cancellation_fee_inr,
    merge_example,
)
from ridemate_api.ondc.payloads import (
    CORE_VERSION,
    DOMAIN,
    GeoPoint,
    cancel_message,
    context,
    encode,
    envelope,
    parse_on_search,
    parse_order,
)
from ridemate_api.ondc.signing import SubscriberKey, authorization_header
from ridemate_api.store import MemoryStore

from .test_ai_fakes import FakeAuth, FakeFirestore, FakeMetrics, make_settings

PICKUP = GeoPoint(26.1445, 91.7362)  # Guwahati
DROP = GeoPoint(26.1061, 91.5859)  # LGBI airport
ALICE = Principal(uid="alice", provider="google.com", sub_hmac="h" * 32)
MALLORY = Principal(uid="mallory", provider="google.com", sub_hmac="m" * 32)


class Rig:
    def __init__(self, examples_dir: Path | None = None) -> None:
        self.fs = FakeFirestore()
        self.auth = FakeAuth()
        self.clock = FakeClock()
        self.metrics = FakeMetrics()
        self.store = MemoryStore()
        buyer = build_buyer(
            make_settings(ondc_mode="mock"),
            firestore=self.fs,
            auth=self.auth,
            clock=self.clock,
            metrics=self.metrics,
            store=self.store,
            examples_dir=examples_dir,
        )
        assert buyer is not None and buyer.mock is not None
        self.buyer: OndcBuyer = buyer
        self.seller: MockBpp = buyer.mock
        self.delivered: list[tuple[str, bytes, str]] = []
        original = self.buyer.handle_callback

        async def recording(action: str, body: bytes, header: str | None) -> tuple[int, dict[str, Any]]:
            self.delivered.append((action, body, header or ""))
            return await original(action, body, header)

        self.seller.attach(recording)

    def txn(self, txn_id: str) -> dict[str, Any]:
        doc = self.fs.docs[f"ondc_txn/{txn_id}"]
        return doc

    async def searched(self) -> str:
        txn_id = await self.buyer.search(ALICE, session_id="s1", pickup=PICKUP, drop=DROP)
        await self.seller.drain()
        return txn_id

    async def confirmed(self) -> str:
        txn_id = await self.searched()
        await self.buyer.select(ALICE, txn_id, "demo-auto")
        await self.seller.drain()
        await self.buyer.init(ALICE, txn_id)
        await self.seller.drain()
        await self.buyer.confirm(ALICE, txn_id, "tap-confirm-1")
        await self.seller.drain()
        return txn_id


async def test_full_mock_ride_search_to_confirmed_cancel() -> None:
    rig = Rig()
    txn_id = await rig.buyer.search(ALICE, session_id="s1", pickup=PICKUP, drop=DROP)
    assert rig.txn(txn_id)["state"] == "SEARCHING_NET"
    assert rig.txn(txn_id)["uid"] == "alice" and rig.txn(txn_id)["demo"] is True
    await rig.seller.drain()

    quotes = rig.txn(txn_id)["quotes"]
    assert {q["vehicle_class"] for q in quotes} == {"auto", "cab_economy", "bike"}
    assert all(q["provider_name"] == MOCK_PROVIDER_NAME and q["bpp_id"] == MOCK_BPP_ID for q in quotes)
    assert all(isinstance(q["price"], int) and q["price"] > 0 and q["eta_min"] for q in quotes)
    auto = next(q for q in quotes if q["item_id"] == "demo-auto")

    await rig.buyer.select(ALICE, txn_id, "demo-auto")
    await rig.seller.drain()
    assert rig.txn(txn_id)["state"] == "ON_SELECT"
    assert rig.txn(txn_id)["bpp_id"] == MOCK_BPP_ID

    await rig.buyer.init(ALICE, txn_id)
    await rig.seller.drain()
    assert rig.txn(txn_id)["state"] == "INIT"

    await rig.buyer.confirm(ALICE, txn_id, "tap-confirm-1")
    await rig.seller.drain()
    txn = rig.txn(txn_id)
    assert txn["state"] == "CONFIRMED" and txn["order_id"]
    assert txn["confirm_tap_id"] == "tap-confirm-1"
    assert txn["driver"] is None

    await rig.buyer.status(ALICE, txn_id)
    await rig.seller.drain()
    txn = rig.txn(txn_id)
    assert txn["state"] == "CONFIRMED"
    assert txn["driver"] == {"eta_min": 4, "distance_text": "1.1 km"}

    await rig.buyer.status(ALICE, txn_id)
    await rig.seller.drain()
    assert rig.txn(txn_id)["state"] == "ARRIVING"

    await rig.buyer.cancel(ALICE, txn_id, "tap-cancel-1", confirm=False)
    await rig.seller.drain()
    txn = rig.txn(txn_id)
    assert txn["state"] == "SOFT_CANCEL"
    assert txn["fee"] == {"amount": cancellation_fee_inr(auto["price"]), "currency": "INR"}
    assert txn["fee"]["amount"] > 0
    assert txn["cancel_tap_id"] == "tap-cancel-1"

    await rig.buyer.cancel(ALICE, txn_id, "tap-cancel-2", confirm=True)
    await rig.seller.drain()
    txn = rig.txn(txn_id)
    assert txn["state"] == "CANCEL_CONFIRMED"
    assert txn["confirm_cancel_tap_id"] == "tap-cancel-2"
    assert txn["stops"] is None  # location dropped once the ride is over
    assert rig.auth.revocation_checks == ["alice", "alice", "alice"]

    actions = [action for action, _, _ in rig.delivered]
    assert actions == [
        "on_search",
        "on_select",
        "on_init",
        "on_confirm",
        "on_status",
        "on_status",
        "on_cancel",
        "on_cancel",
    ]
    events = rig.fs.collection("ondc_event")
    assert len(events) == len(actions)
    assert all(e["applied"] and e["payload_hash"] and e["txnId"] == txn_id for e in events.values())
    # firestore.rules lets a phone read an ondc_event only when its `uid` is the caller's: the owner's, here.
    assert {e["uid"] for e in events.values()} == {"alice"}


async def test_soft_cancel_before_a_driver_is_assigned_is_free() -> None:
    rig = Rig()
    txn_id = await rig.confirmed()
    await rig.buyer.cancel(ALICE, txn_id, "tap-c", confirm=False)
    await rig.seller.drain()
    assert rig.txn(txn_id)["state"] == "SOFT_CANCEL"
    assert rig.txn(txn_id)["fee"]["amount"] == 0


async def test_cancel_before_an_order_exists_is_local_and_free() -> None:
    rig = Rig()
    txn_id = await rig.searched()
    await rig.buyer.cancel(ALICE, txn_id, "tap-early", confirm=False)
    assert rig.txn(txn_id)["state"] == "CANCEL_CONFIRMED"
    assert rig.txn(txn_id)["fee"]["amount"] == 0
    assert [a for a, _, _ in rig.delivered] == ["on_search"]


async def test_duplicate_callbacks_are_acknowledged_once_and_change_nothing() -> None:
    rig = Rig()
    txn_id = await rig.confirmed()
    before = rig.txn(txn_id)
    events_before = len(rig.fs.collection("ondc_event"))
    for action, body, header in list(rig.delivered):
        status, reply = await rig.buyer.handle_callback(action, body, header)
        assert status == 200 and reply["message"]["ack"]["status"] == "ACK"
    assert len(rig.fs.collection("ondc_event")) == events_before
    after = rig.txn(txn_id)
    assert after["state"] == before["state"] == "CONFIRMED"
    assert after["quotes"] == before["quotes"]
    assert rig.metrics.total("ondc_callback", outcome="duplicate") == len(rig.delivered)


async def test_event_stored_but_not_applied_is_applied_on_redelivery() -> None:
    rig = Rig()
    txn_id = await rig.buyer.search(ALICE, session_id="s1", pickup=PICKUP, drop=DROP)
    rig.seller.attach(lambda *a: asyncio.sleep(0, result=(200, {})))  # swallow the first delivery
    await rig.seller.drain()
    # Simulate a crash between storing the event and applying it.
    body, header = await _signed_on_search(rig, txn_id)
    real_apply = rig.buyer._apply

    async def failing_apply(*args: Any) -> str | None:
        raise ApiError(503, "ondc_unavailable")

    rig.buyer._apply = failing_apply  # type: ignore[method-assign]
    status, _ = await rig.buyer.handle_callback("on_search", body, header)
    assert status == 503
    assert not rig.txn(txn_id)["quotes"]
    rig.buyer._apply = real_apply  # type: ignore[method-assign]
    status, _ = await rig.buyer.handle_callback("on_search", body, header)
    assert status == 200
    assert len(rig.txn(txn_id)["quotes"]) == 3
    (event,) = [e for e in rig.fs.collection("ondc_event").values() if e["txnId"] == txn_id]
    assert event["applied"] and event["uid"] == "alice"


def test_drain_returns_when_a_finished_callback_is_still_waiting_for_its_done_callback() -> None:
    # The task finished in the same loop turn drain started in, so it is still in the set: drain used to gather it
    # again and again, and a gather of finished tasks never yields, so the loop spun at 100% CPU forever.
    seller = Rig().seller
    finished = threading.Event()

    async def scenario() -> None:
        done: asyncio.Future[None] = asyncio.get_running_loop().create_future()
        done.set_result(None)
        seller._tasks.add(done)  # type: ignore[arg-type]  # its discard callback has not run yet
        await seller.drain()
        finished.set()

    # In its own thread: a regression spins without yielding, so no timeout on this loop could fire.
    threading.Thread(target=asyncio.run, args=(scenario(),), daemon=True).start()
    assert finished.wait(5), "MockBpp.drain() spun on a finished task"


async def test_an_event_stored_by_an_older_server_gets_its_owner_uid_when_applied() -> None:
    rig = Rig()
    txn_id = await rig.buyer.search(ALICE, session_id="s1", pickup=PICKUP, drop=DROP)
    rig.seller.attach(lambda *a: asyncio.sleep(0, result=(200, {})))
    await rig.seller.drain()
    body, header = await _signed_on_search(rig, txn_id)
    status, _ = await rig.buyer.handle_callback("on_search", body, header)
    assert status == 200
    (path,) = rig.fs.collection("ondc_event")
    stale = {k: v for k, v in rig.fs.docs[path].items() if k != "uid"}
    rig.fs.docs[path] = {**stale, "applied": False}  # stored before events carried a uid, never applied
    status, _ = await rig.buyer.handle_callback("on_search", body, header)
    assert status == 200 and rig.fs.docs[path]["uid"] == "alice" and rig.fs.docs[path]["applied"]


async def _signed_on_search(rig: Rig, txn_id: str, key: SubscriberKey | None = None) -> tuple[bytes, str]:
    seller_key: SubscriberKey = key or rig.seller._key
    message = rig.seller._on_search(txn_id, {"intent": {"fulfillment": {"stops": [
        {"type": "START", "location": {"gps": PICKUP.gps}}, {"type": "END", "location": {"gps": DROP.gps}}]}}})  # fmt: skip
    ctx = context(
        action="on_search",
        transaction_id=txn_id,
        message_id=rig.txn(txn_id)["msg_search"],
        bap_id="ridemate-api.example.test",
        bap_uri="https://ridemate-api.example.test/ondc",
        timestamp=rig.clock.now_utc(),
        bpp_id=seller_key.subscriber_id,
        bpp_uri=rig.seller.bpp_uri,
    )
    body = encode(envelope(ctx, message))
    return body, authorization_header(body, seller_key, created=int(rig.clock.now_utc().timestamp()))


async def test_tampered_or_unsigned_callbacks_are_rejected_and_never_stored() -> None:
    rig = Rig()
    txn_id = await rig.buyer.search(ALICE, session_id="s1", pickup=PICKUP, drop=DROP)
    rig.seller.attach(lambda *a: asyncio.sleep(0, result=(200, {})))
    await rig.seller.drain()
    body, header = await _signed_on_search(rig, txn_id)
    events_before = len(rig.fs.collection("ondc_event"))

    tampered = body.replace(b'"value":"', b'"value":"1', 1)
    status, reply = await rig.buyer.handle_callback("on_search", tampered, header)
    assert status == 401 and reply["message"]["ack"]["status"] == "NACK"
    status, _ = await rig.buyer.handle_callback("on_search", body, None)
    assert status == 401
    stranger = SubscriberKey.generate(MOCK_BPP_ID, "rogue-key")
    rogue_body, rogue_header = await _signed_on_search(rig, txn_id, stranger)
    status, _ = await rig.buyer.handle_callback("on_search", rogue_body, rogue_header)
    assert status == 401
    assert len(rig.fs.collection("ondc_event")) == events_before
    assert rig.txn(txn_id)["quotes"] == []


async def test_signer_must_be_the_seller_named_in_the_context() -> None:
    rig = Rig()
    txn_id = await rig.buyer.search(ALICE, session_id="s1", pickup=PICKUP, drop=DROP)
    await rig.seller.drain()
    # Our own BAP key is registered too, but it may not speak for the seller.
    body, _ = await _signed_on_search(rig, txn_id)
    header = authorization_header(body, rig.buyer._key, created=int(rig.clock.now_utc().timestamp()))
    status, reply = await rig.buyer.handle_callback("on_search", body, header)
    assert status == 401 and reply["error"]["code"] == "signer_mismatch"


async def test_unsolicited_replies_and_unknown_transactions_are_nacked() -> None:
    rig = Rig()
    txn_id = await rig.searched()
    order = {"order": {"provider": {"id": "x"}, "items": [{"id": "demo-auto"}]}}
    ctx = context(action="on_select", transaction_id=txn_id, message_id="not-ours", bap_id="b", bap_uri="u",
                  timestamp=rig.clock.now_utc(), bpp_id=MOCK_BPP_ID, bpp_uri=rig.seller.bpp_uri)  # fmt: skip
    await rig.buyer.select(ALICE, txn_id, "demo-auto")
    await rig.seller.drain()
    body = encode(envelope(ctx, order))
    status, reply = await rig.buyer.handle_callback(
        "on_select",
        body,
        authorization_header(body, rig.seller._key, created=int(rig.clock.now_utc().timestamp())),
    )
    assert status == 400 and reply["error"]["code"] == "unsolicited"

    ghost = encode(
        envelope(
            {**ctx, "transaction_id": "00000000-0000-4000-8000-000000000000", "action": "on_status"}, order
        )
    )
    status, reply = await rig.buyer.handle_callback(
        "on_status",
        ghost,
        authorization_header(ghost, rig.seller._key, created=int(rig.clock.now_utc().timestamp())),
    )
    assert status == 400 and reply["error"]["code"] == "unknown_transaction"
    # Events of an unknown transaction have no owner, so no phone can read them.
    (orphan,) = [
        e
        for e in rig.fs.collection("ondc_event").values()
        if e["txnId"] == "00000000-0000-4000-8000-000000000000"
    ]
    assert orphan["uid"] is None and orphan["apply_error"] == "unknown_transaction"
    unsolicited = [
        e for e in rig.fs.collection("ondc_event").values() if e.get("apply_error") == "unsolicited"
    ]
    assert unsolicited and all(e["uid"] == "alice" for e in unsolicited)


async def test_garbage_payload_is_stored_with_a_parse_error_and_nacked() -> None:
    rig = Rig()
    body = b'{"context": "nope"}'
    header = authorization_header(body, rig.seller._key, created=int(rig.clock.now_utc().timestamp()))
    status, reply = await rig.buyer.handle_callback("on_status", body, header)
    assert status == 400 and reply["error"]["code"] == "invalid_payload"
    (event,) = rig.fs.collection("ondc_event").values()
    assert event["parse_error"] == "invalid_payload"
    assert "uid" in event and event["uid"] is None  # unreadable by any phone (owner-only rule)
    status, _ = await rig.buyer.handle_callback("on_teleport", body, header)
    assert status == 404


async def test_only_the_owner_can_act_on_a_transaction() -> None:
    rig = Rig()
    txn_id = await rig.searched()
    for call in (
        lambda: rig.buyer.select(MALLORY, txn_id, "demo-auto"),
        lambda: rig.buyer.init(MALLORY, txn_id),
        lambda: rig.buyer.confirm(MALLORY, txn_id, "tap"),
        lambda: rig.buyer.status(MALLORY, txn_id),
        lambda: rig.buyer.cancel(MALLORY, txn_id, "tap", confirm=False),
    ):
        with pytest.raises(ApiError) as err:
            await call()
        assert (err.value.status, err.value.code) == (403, "forbidden")
    with pytest.raises(ApiError) as missing:
        await rig.buyer.init(ALICE, "00000000-0000-4000-8000-000000000000")
    assert missing.value.code == "not_found"
    with pytest.raises(ApiError) as bad_id:
        await rig.buyer.init(ALICE, "../ondc_txn/x")
    assert bad_id.value.code == "not_found"


async def test_revoked_token_cannot_confirm_or_cancel() -> None:
    rig = Rig()
    txn_id = await rig.searched()
    await rig.buyer.select(ALICE, txn_id, "demo-auto")
    await rig.seller.drain()
    await rig.buyer.init(ALICE, txn_id)
    await rig.seller.drain()
    rig.auth.revoked.add("alice")
    with pytest.raises(ApiError) as err:
        await rig.buyer.confirm(ALICE, txn_id, "tap")
    assert err.value.code == "auth_invalid"
    with pytest.raises(ApiError):
        await rig.buyer.cancel(ALICE, txn_id, "tap", confirm=False)
    assert rig.txn(txn_id)["state"] == "INIT"
    assert "confirm_tap_id" not in rig.txn(txn_id)


async def test_out_of_order_actions_are_invalid() -> None:
    rig = Rig()
    txn_id = await rig.searched()
    for call in (
        lambda: rig.buyer.init(ALICE, txn_id),
        lambda: rig.buyer.confirm(ALICE, txn_id, "tap"),
        lambda: rig.buyer.status(ALICE, txn_id),
        lambda: rig.buyer.select(ALICE, txn_id, "no-such-item"),
        lambda: rig.buyer.confirm(ALICE, txn_id, "bad tap id!"),
    ):
        with pytest.raises(ApiError) as err:
            await call()
        assert err.value.code == "invalid"
    confirmed = await rig.confirmed()
    with pytest.raises(ApiError) as early_confirm_cancel:
        await rig.buyer.cancel(ALICE, confirmed, "tap", confirm=True)  # needs a soft cancel first
    assert early_confirm_cancel.value.code == "invalid"


async def test_firestore_missing_or_exhausted_makes_ondc_unavailable() -> None:
    rig = Rig()
    rig.fs.fail = FirestoreQuotaExhausted
    with pytest.raises(ApiError) as err:
        await rig.buyer.search(ALICE, session_id="s", pickup=PICKUP, drop=DROP)
    assert (err.value.status, err.value.code) == (503, "ondc_unavailable")
    rig.fs.fail = FirestoreUnavailable
    status, _ = await rig.buyer.handle_callback("on_status", b"{}", "x")
    assert status == 401  # signature is checked before Firestore is touched

    no_fs = build_buyer(make_settings(ondc_mode="mock"), firestore=None, auth=FakeAuth(), clock=FakeClock(),
                        metrics=FakeMetrics(), store=MemoryStore(), examples_dir=None)  # fmt: skip
    assert no_fs is not None
    with pytest.raises(ApiError) as missing:
        await no_fs.search(ALICE, session_id="s", pickup=PICKUP, drop=DROP)
    assert missing.value.code == "ondc_unavailable"
    status, _ = await no_fs.handle_callback("on_search", b"{}", None)
    assert status == 503


async def test_outbound_calls_have_a_deadline(monkeypatch: pytest.MonkeyPatch) -> None:
    rig = Rig()
    monkeypatch.setattr(bap_module, "DEADLINE_ONDC_OUTBOUND_S", 0.05)

    async def hang(*args: Any) -> tuple[int, dict[str, Any]]:
        await asyncio.sleep(5)
        return 200, {}

    monkeypatch.setattr(rig.seller, "receive", hang)
    with pytest.raises(ApiError) as err:
        await rig.buyer.search(ALICE, session_id="s", pickup=PICKUP, drop=DROP)
    assert err.value.code == "ondc_unavailable"
    assert rig.metrics.total("ondc_outbound", outcome="timeout") == 1


async def test_seller_rejects_unsigned_requests_and_bad_sequences() -> None:
    rig = Rig()
    status, reply = await rig.seller.receive("search", b"{}", None)
    assert status == 401 and reply["message"]["ack"]["status"] == "NACK"
    txn_id = await rig.searched()
    body = encode(envelope(
        context(action="cancel", transaction_id=txn_id, message_id="m", bap_id=rig.buyer._key.subscriber_id,
                bap_uri="u", timestamp=rig.clock.now_utc(), bpp_id=MOCK_BPP_ID, bpp_uri="u"),
        cancel_message("demo-order-x", soft=False),
    ))  # fmt: skip
    status, reply = await rig.seller.receive(
        "cancel",
        body,
        authorization_header(body, rig.buyer._key, created=int(rig.clock.now_utc().timestamp())),
    )
    assert status == 400 and reply["error"]["code"] == "unknown_order"


def test_mode_off_and_unregistered_modes_build_no_buyer() -> None:
    for mode in ("off", "preprod", "prod"):
        assert build_buyer(make_settings(ondc_mode=mode), firestore=FakeFirestore(), auth=FakeAuth(),
                           clock=FakeClock(), metrics=FakeMetrics(), store=MemoryStore()) is None  # fmt: skip


@pytest.mark.parametrize(
    ("current", "proposed", "expected"),
    [
        ("CONFIRMED", "ARRIVING", "ARRIVING"),
        ("ARRIVING", "CONFIRMED", "ARRIVING"),  # late on_status never regresses
        ("SOFT_CANCEL", "ARRIVING", "SOFT_CANCEL"),
        ("SOFT_CANCEL", "CANCEL_CONFIRMED", "CANCEL_CONFIRMED"),
        ("CANCEL_CONFIRMED", "ARRIVING", "CANCEL_CONFIRMED"),
        ("ENDED", "FAILED", "ENDED"),
        ("ON_TRIP", "ENDED", "ENDED"),
        ("INIT", "FAILED", "FAILED"),
    ],
)
def test_state_folding_rules(current: str, proposed: str, expected: str) -> None:
    assert next_state(current, proposed) == expected


# ---- payloads and examples -------------------------------------------------------------------------------------


def test_context_and_cancel_payloads_follow_trv10() -> None:
    from datetime import UTC, datetime

    ctx = context(action="search", transaction_id="t", message_id="m", bap_id="b", bap_uri="u",
                  timestamp=datetime(2026, 10, 28, 6, 0, 0, 123000, tzinfo=UTC))  # fmt: skip
    assert ctx["domain"] == DOMAIN == "ONDC:TRV10"
    assert ctx["version"] == CORE_VERSION == "2.0.1"
    assert ctx["timestamp"] == "2026-10-28T06:00:00.123Z"
    assert ctx["location"]["country"]["code"] == "IND"
    assert "bpp_id" not in ctx
    soft = cancel_message("O1", soft=True)
    assert soft["descriptor"]["code"] == "SOFT_CANCEL" and soft["cancellation_reason_id"]
    assert cancel_message("O1", soft=False)["descriptor"]["code"] == "CONFIRM_CANCEL"


def test_parsers_ignore_malformed_seller_data() -> None:
    assert parse_on_search(None) == []
    catalog = {"catalog": {"providers": [{"id": "p", "items": [
        {"id": "i1", "price": {"value": "abc"}},
        {"id": "i2", "price": {"value": "-5"}},
        {"id": "i3", "price": {"value": "99.6"}, "fulfillment_ids": ["f"]},
    ], "fulfillments": [{"id": "f", "vehicle": {"category": "SUV"}}]}]}}  # fmt: skip
    (quote,) = parse_on_search(catalog)
    assert (quote.item_id, quote.price, quote.vehicle_class, quote.eta_min) == ("i3", 100, "cab_xl", None)
    view = parse_order({"order": {"fulfillments": [{"tags": [{"descriptor": {"code": "INFO"}, "list": [
        {"descriptor": {"code": "ETA_TO_NEAREST_DRIVER_MIN"}, "value": "inf"}]}]}]}})  # fmt: skip
    assert view.driver_eta_min is None and view.order_id is None


async def test_seller_replays_trv10_examples_with_its_own_catalogue(tmp_path: Path) -> None:
    example = {
        "context": {"action": "on_search"},
        "message": {
            "catalog": {
                "descriptor": {
                    "name": "Example Mobility",
                    "images": [{"url": "https://example.test/logo.png"}],
                },
                "providers": [
                    {
                        "id": "P1",
                        "descriptor": {"name": "Example Cabs"},
                        "payments": [
                            {"collected_by": "BPP", "tags": [{"descriptor": {"code": "SETTLEMENT_TERMS"}}]}
                        ],
                        "items": [{"id": "I1", "payment_ids": ["PA1"], "descriptor": {"code": "RIDE"}}],
                        "fulfillments": [
                            {
                                "id": "F1",
                                "type": "DELIVERY",
                                "vehicle": {"category": "CAB", "variant": "SEDAN"},
                            }
                        ],
                    }
                ],
            }
        },
    }
    (tmp_path / "on_search.json").write_text(json.dumps(example))
    rig = Rig(examples_dir=tmp_path)
    txn_id = await rig.searched()
    (_, body, _) = rig.delivered[0]
    message = json.loads(body)["message"]
    assert message["catalog"]["descriptor"]["images"]  # example-only fields kept
    assert message["catalog"]["descriptor"]["name"] == MOCK_PROVIDER_NAME
    provider = message["catalog"]["providers"][0]
    assert provider["id"] != "P1" and provider["payments"][0]["collected_by"] == "BPP"
    assert [item["id"] for item in provider["items"]] == ["demo-auto", "demo-cab", "demo-bike"]
    assert all(item["payment_ids"] == ["PA1"] for item in provider["items"])
    assert len(rig.txn(txn_id)["quotes"]) == 3


def test_merge_example_semantics() -> None:
    base = {"a": {"x": 1, "y": 2}, "items": [{"t": 1, "id": "e"}], "keep": True, "other": [1, 2]}
    ours = {"a": {"x": 9}, "items": [{"id": "o1"}, {"id": "o2"}], "other": [3]}
    assert merge_example(base, ours) == {
        "a": {"x": 9, "y": 2},
        "items": [{"t": 1, "id": "o1"}, {"t": 1, "id": "o2"}],
        "keep": True,
        "other": [3],
    }


def test_fee_follows_the_aggregator_cap() -> None:
    assert cancellation_fee_inr(50) == 10
    assert cancellation_fee_inr(240) == 24
    assert cancellation_fee_inr(5000) == 100


def test_fetch_script_picks_examples_without_network() -> None:
    import sys

    sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
    import fetch_trv10_examples as fetch

    root = fetch.EXAMPLES_ROOT
    chosen = fetch.pick_paths([
        f"{root}/on_cancel/Return_current_order_status_after_confirm-cancel___X9.yaml",
        f"{root}/on_cancel/Return_current_order_status_after_soft-cancel___X8.yaml",
        f"{root}/on_status/a.yaml",
        f"{root}/init/x.yaml",
        "README.md",
    ])  # fmt: skip
    assert chosen["on_cancel"].endswith("soft-cancel___X8.yaml")
    assert chosen["on_status"].endswith("a.yaml")
    assert "on_search" not in chosen
    assert fetch.extract_example(
        {"summary": "s", "value": {"context": {"a": 1}, "message": {"order": {}}}}
    ) == {
        "context": {"a": 1},
        "message": {"order": {}},
    }
    assert fetch.extract_example({"value": "x"}) is None
    assert len(fetch.PINNED_REF) == 40


async def test_cancel_waits_while_a_confirm_is_with_the_seller() -> None:
    # Cancelling locally before on_confirm lands would leave a booked ride behind at the seller.
    rig = Rig()
    txn_id = await rig.searched()
    await rig.buyer.select(ALICE, txn_id, "demo-auto")
    await rig.seller.drain()
    await rig.buyer.init(ALICE, txn_id)
    await rig.seller.drain()
    gate = asyncio.Event()
    deliver = rig.buyer.handle_callback

    async def hold_on_confirm(action: str, body: bytes, header: str) -> tuple[int, dict[str, Any]]:
        if action == "on_confirm":
            await gate.wait()
        return await deliver(action, body, header)

    rig.seller.attach(hold_on_confirm)
    await rig.buyer.confirm(ALICE, txn_id, "tap-confirm")  # ACKed; on_confirm held back
    assert rig.txn(txn_id)["state"] == "INIT"
    with pytest.raises(ApiError) as err:
        await rig.buyer.cancel(ALICE, txn_id, "tap-cancel", confirm=False)
    assert err.value.code == "invalid"
    assert "cancel_tap_id" not in rig.txn(txn_id)

    gate.set()
    await rig.seller.drain()
    assert rig.txn(txn_id)["state"] == "CONFIRMED"
    await rig.buyer.cancel(ALICE, txn_id, "tap-cancel", confirm=False)  # now through the seller
    await rig.seller.drain()
    assert rig.txn(txn_id)["state"] == "SOFT_CANCEL"
    assert rig.txn(txn_id)["fee"]["amount"] == 0  # no driver yet


async def test_a_refused_confirm_leaves_the_local_cancel_open(monkeypatch: pytest.MonkeyPatch) -> None:
    rig = Rig()
    txn_id = await rig.searched()
    await rig.buyer.select(ALICE, txn_id, "demo-auto")
    await rig.seller.drain()
    await rig.buyer.init(ALICE, txn_id)
    await rig.seller.drain()

    async def refuse(*args: Any) -> tuple[int, dict[str, Any]]:
        return 400, {"message": {"ack": {"status": "NACK"}}}

    monkeypatch.setattr(rig.seller, "receive", refuse)
    with pytest.raises(ApiError) as err:
        await rig.buyer.confirm(ALICE, txn_id, "tap-confirm")
    assert (err.value.code, err.value.reason) == ("ondc_unavailable", "nack")
    assert rig.txn(txn_id)["msg_confirm"] is None
    await rig.buyer.cancel(ALICE, txn_id, "tap-cancel", confirm=False)
    assert rig.txn(txn_id)["state"] == "CANCEL_CONFIRMED"


async def test_a_confirm_that_timed_out_keeps_the_cancel_waiting(monkeypatch: pytest.MonkeyPatch) -> None:
    rig = Rig()
    txn_id = await rig.searched()
    await rig.buyer.select(ALICE, txn_id, "demo-auto")
    await rig.seller.drain()
    await rig.buyer.init(ALICE, txn_id)
    await rig.seller.drain()
    monkeypatch.setattr(bap_module, "DEADLINE_ONDC_OUTBOUND_S", 0.05)

    async def hang(*args: Any) -> tuple[int, dict[str, Any]]:
        await asyncio.sleep(5)
        return 200, {}

    monkeypatch.setattr(rig.seller, "receive", hang)
    with pytest.raises(ApiError) as err:
        await rig.buyer.confirm(ALICE, txn_id, "tap-confirm")
    assert err.value.reason == "timeout"
    assert rig.txn(txn_id)["msg_confirm"]  # the seller may have acted on it
    with pytest.raises(ApiError) as cancel:
        await rig.buyer.cancel(ALICE, txn_id, "tap-cancel", confirm=False)
    assert cancel.value.code == "invalid"
