"""ONDC routes over HTTP (httpx ASGITransport, same event loop as the in-process seller)."""

from __future__ import annotations

from typing import Any

import httpx
import pytest

from ridemate_api.deps import Deps
from ridemate_api.ondc.bap import OndcBuyer, build_buyer
from ridemate_api.ondc.payloads import GeoPoint, context, encode, envelope, search_message
from ridemate_api.ondc.signing import authorization_header

from .test_ai_fakes import FakeFirestore, anon_headers, google_headers, make_app, make_deps, make_settings

SEARCH = {
    "session_id": "s-1",
    "pickup": {"lat": 26.1445, "lng": 91.7362},
    "drop": {"lat": 26.1061, "lng": 91.5859},
}


def build(mode: str = "mock", rc: dict[str, Any] | None = None) -> tuple[Deps, httpx.AsyncClient]:
    deps = make_deps(settings=make_settings(ondc_mode=mode), rc=rc, firestore=FakeFirestore())
    deps.ondc = build_buyer(
        deps.settings,
        firestore=deps.firestore,
        auth=deps.auth,
        clock=deps.clock,
        metrics=deps.metrics,
        store=deps.store,
        examples_dir=None,
    )
    client = httpx.AsyncClient(transport=httpx.ASGITransport(app=make_app(deps)), base_url="http://test")
    return deps, client


def buyer(deps: Deps) -> OndcBuyer:
    assert isinstance(deps.ondc, OndcBuyer)
    return deps.ondc


async def drain(deps: Deps) -> None:
    seller = buyer(deps).mock
    assert seller is not None
    await seller.drain()


async def test_whole_flow_over_http() -> None:
    deps, client = build()
    async with client:
        response = await client.post("/v1/ondc/search", json=SEARCH, headers=google_headers("alice"))
        assert response.status_code == 200, response.text
        txn_id = response.json()["txn_id"]
        await drain(deps)
        assert deps.firestore is not None
        txn = deps.firestore.get(f"ondc_txn/{txn_id}")
        assert txn is not None and len(txn["quotes"]) == 3

        steps: list[tuple[str, dict[str, Any]]] = [
            ("select", {"txn_id": txn_id, "item_id": "demo-cab"}),
            ("init", {"txn_id": txn_id}),
            ("confirm", {"txn_id": txn_id, "tap_id": "tap-1"}),
            ("status", {"txn_id": txn_id}),
            ("cancel", {"txn_id": txn_id, "tap_id": "tap-2", "confirm": False}),
            ("cancel", {"txn_id": txn_id, "tap_id": "tap-3", "confirm": True}),
        ]
        for step, body in steps:
            reply = await client.post(f"/v1/ondc/{step}", json=body, headers=google_headers("alice"))
            assert reply.status_code == 200, (step, reply.text)
            assert reply.json() == {"ok": True}
            await drain(deps)
        txn = deps.firestore.get(f"ondc_txn/{txn_id}")
        assert txn is not None
        assert txn["state"] == "CANCEL_CONFIRMED"
        assert txn["fee"]["amount"] > 0


async def test_other_users_get_403_and_anonymous_users_need_google() -> None:
    deps, client = build()
    async with client:
        txn_id = (await client.post("/v1/ondc/search", json=SEARCH, headers=google_headers("alice"))).json()[
            "txn_id"
        ]
        await drain(deps)
        stolen = await client.post(
            "/v1/ondc/select",
            json={"txn_id": txn_id, "item_id": "demo-auto"},
            headers=google_headers("mallory"),
        )
        assert stolen.status_code == 403 and stolen.json()["error"]["code"] == "forbidden"
        anon = await client.post("/v1/ondc/search", json=SEARCH, headers=anon_headers())
        assert anon.status_code == 403 and anon.json()["error"]["code"] == "google_required"


async def test_bad_bodies_are_invalid() -> None:
    _, client = build()
    async with client:
        for path, body in (
            ("/v1/ondc/search", {**SEARCH, "pickup": {"lat": 123, "lng": 0}}),
            ("/v1/ondc/select", {"txn_id": "nope", "item_id": "x"}),
            ("/v1/ondc/confirm", {"txn_id": "00000000-0000-4000-8000-000000000000", "tap_id": "has space"}),
        ):
            response = await client.post(path, json=body, headers=google_headers())
            assert response.status_code == 422, path
            assert response.json()["error"]["code"] == "invalid"


async def test_mode_off_answers_503_and_does_not_mount_the_mock_seller() -> None:
    deps, client = build(mode="off")
    assert deps.ondc is None
    async with client:
        response = await client.post("/v1/ondc/search", json=SEARCH, headers=google_headers())
        assert response.status_code == 503 and response.json()["error"]["code"] == "ondc_unavailable"
        callback = await client.post("/ondc/on_search", content=b"{}")
        assert callback.status_code == 503
        assert (await client.post("/mock-bpp/search", content=b"{}")).status_code == 404


async def test_remote_config_switch_turns_ondc_off() -> None:
    _, client = build(rc={"ondc_enabled": False})
    async with client:
        response = await client.post("/v1/ondc/search", json=SEARCH, headers=google_headers())
        assert response.status_code == 503


async def test_callback_route_verifies_signatures() -> None:
    deps, client = build()
    async with client:
        txn_id = (await client.post("/v1/ondc/search", json=SEARCH, headers=google_headers("alice"))).json()[
            "txn_id"
        ]
        await drain(deps)
        unsigned = await client.post("/ondc/on_status", content=b'{"context": {}}')
        assert unsigned.status_code == 401
        assert unsigned.json()["message"]["ack"]["status"] == "NACK"
        unknown = await client.post("/ondc/on_teleport", content=b"{}")
        assert unknown.status_code == 404
        too_big = await client.post("/ondc/on_status", content=b"x" * 70_000)
        assert too_big.status_code == 413
        assert txn_id


async def test_mock_seller_route_accepts_signed_requests_only() -> None:
    deps, client = build()
    async with client:
        b = buyer(deps)
        ctx = context(action="search", transaction_id="t-http", message_id="m-1", bap_id=b._key.subscriber_id,
                      bap_uri="https://ridemate-api.example.test/ondc", timestamp=deps.clock.now_utc())  # fmt: skip
        body = encode(envelope(ctx, search_message(GeoPoint(26.14, 91.73), GeoPoint(26.10, 91.58))))
        header = authorization_header(body, b._key, created=int(deps.clock.now_utc().timestamp()))
        ok = await client.post("/mock-bpp/search", content=body, headers={"Authorization": header})
        assert ok.status_code == 200 and ok.json()["message"]["ack"]["status"] == "ACK"
        await drain(deps)  # the reply's transaction is unknown to the buyer: NACKed, nothing stored as a txn
        forged = await client.post(
            "/mock-bpp/search", content=body.replace(b"t-http", b"t-evil"), headers={"Authorization": header}
        )
        assert forged.status_code == 401


@pytest.mark.parametrize("path", ["/v1/ondc/init", "/v1/ondc/status"])
async def test_unknown_transaction_is_not_found(path: str) -> None:
    _, client = build()
    async with client:
        response = await client.post(
            path, json={"txn_id": "00000000-0000-4000-8000-000000000000"}, headers=google_headers()
        )
        assert response.status_code == 404 and response.json()["error"]["code"] == "not_found"


# ---- per-user daily caps, keep and abandon over HTTP -----------------------------------------------------------


async def test_the_21st_search_of_the_day_is_refused_before_anything_is_written() -> None:
    from ridemate_api.api.ondc import SEARCHES_PER_DAY

    deps, client = build()
    fs = deps.firestore
    assert isinstance(fs, FakeFirestore)
    async with client:
        for _ in range(SEARCHES_PER_DAY):
            ok = await client.post("/v1/ondc/search", json=SEARCH, headers=google_headers("alice"))
            assert ok.status_code == 200
        await drain(deps)
        before = dict(fs.docs)
        refused = await client.post("/v1/ondc/search", json=SEARCH, headers=google_headers("alice"))
        assert refused.status_code == 429 and refused.json()["error"]["code"] == "rate_limited"
        assert refused.json()["error"]["resume_at_ist"] == "00:00"
        await drain(deps)
        assert fs.docs == before  # no ondc_txn, no seller callback, no event
        # the cap is per user
        other = await client.post("/v1/ondc/search", json=SEARCH, headers=google_headers("bob"))
        assert other.status_code == 200
        # a new IST day, a new allowance
        deps.clock.advance(24 * 3600)  # type: ignore[attr-defined]
        again = await client.post("/v1/ondc/search", json=SEARCH, headers=google_headers("alice"))
        assert again.status_code == 200


async def test_buyer_actions_have_their_own_daily_cap(monkeypatch: pytest.MonkeyPatch) -> None:
    from ridemate_api.api import ondc as ondc_api

    monkeypatch.setattr(ondc_api, "ACTIONS_PER_DAY", 3)
    deps, client = build()
    async with client:
        txn_id = (await client.post("/v1/ondc/search", json=SEARCH, headers=google_headers("alice"))).json()[
            "txn_id"
        ]
        await drain(deps)
        statuses = []
        for _ in range(4):
            r = await client.post("/v1/ondc/status", json={"txn_id": txn_id}, headers=google_headers("alice"))
            statuses.append(r.status_code)
        assert statuses == [422, 422, 422, 429]  # no order yet, but each call still counted


async def test_a_store_outage_refuses_ondc_calls_rather_than_skipping_the_cap() -> None:
    deps, client = build()
    async with client:
        deps.store.fail = True  # type: ignore[attr-defined]
        r = await client.post("/v1/ondc/search", json=SEARCH, headers=google_headers("alice"))
        assert r.status_code == 503
        assert deps.firestore is not None and not deps.firestore.docs  # type: ignore[attr-defined]


async def test_keep_ride_and_abandon_over_http() -> None:
    deps, client = build()
    alice = google_headers("alice")
    async with client:
        txn_id = (await client.post("/v1/ondc/search", json=SEARCH, headers=alice)).json()["txn_id"]
        await drain(deps)
        for step, body in (
            ("select", {"txn_id": txn_id, "item_id": "demo-cab"}),
            ("init", {"txn_id": txn_id}),
            ("confirm", {"txn_id": txn_id, "tap_id": "tap-1"}),
            ("cancel", {"txn_id": txn_id, "tap_id": "tap-2"}),
        ):
            assert (await client.post(f"/v1/ondc/{step}", json=body, headers=alice)).status_code == 200
            await drain(deps)
        assert deps.firestore is not None
        assert deps.firestore.get(f"ondc_txn/{txn_id}")["state"] == "SOFT_CANCEL"
        for bad in (
            {"txn_id": txn_id, "keep": True},  # keep follows a tap too
            {"txn_id": txn_id, "tap_id": "tap-3", "keep": True, "confirm": True},
            {"txn_id": txn_id, "tap_id": "tap-3", "keep": True, "abandon": True},
            {"txn_id": txn_id, "confirm": False},
        ):
            r = await client.post("/v1/ondc/cancel", json=bad, headers=alice)
            assert r.status_code == 422, bad
        kept = await client.post(
            "/v1/ondc/cancel", json={"txn_id": txn_id, "tap_id": "tap-3", "keep": True}, headers=alice
        )
        assert kept.status_code == 200
        txn = deps.firestore.get(f"ondc_txn/{txn_id}")
        assert txn["state"] == "CONFIRMED" and txn["fee"] is None

        searched = (await client.post("/v1/ondc/search", json=SEARCH, headers=alice)).json()["txn_id"]
        await drain(deps)
        gone = await client.post("/v1/ondc/cancel", json={"txn_id": searched, "abandon": True}, headers=alice)
        assert gone.status_code == 200
        txn = deps.firestore.get(f"ondc_txn/{searched}")
        assert txn["state"] == "FAILED" and txn["stops"] is None
        theft = await client.post(
            "/v1/ondc/cancel", json={"txn_id": txn_id, "abandon": True}, headers=google_headers("mallory")
        )
        assert theft.status_code == 403
