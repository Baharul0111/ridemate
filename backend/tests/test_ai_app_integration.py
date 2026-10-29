"""The AI and ONDC routes inside the real app (create_app: request ids, body limits, IP limit, §1.2 envelope) over the
real QuotaManager and SpendMeter, with a scripted model provider: the server side of every call the phone makes."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import httpx
import pytest

from ridemate_api.app import create_app
from ridemate_api.demo_cache import DemoCache
from ridemate_api.deps import Deps
from ridemate_api.interfaces import Purpose
from ridemate_api.model_gateway import ModelTimeout
from ridemate_api.ondc.bap import OndcBuyer, build_buyer
from ridemate_api.router import ProviderRouter
from ridemate_api.settings import MAX_IMAGE_BYTES, PINNED_MODEL_ID
from ridemate_api.testing import PNG_BYTES, FakeAuth, bearer, make_settings, make_test_deps

from .test_ai_fakes import FakeProvider

REPO = Path(__file__).resolve().parents[2]
DEMO_FARE = REPO / "android/app/src/main/assets/demo/kestrel/fare_options.png"
RID = "6c1f0a52-3d4e-4f7a-8b9c-0d1e2f3a4b5c"
READ = {"app": "uber", "screen_type": "fare_options", "options": [
    {"vehicle_class": "auto", "label_text": "Auto", "price_min": 110, "price_max": 110, "price_struck_text": None,
     "eta_min": 3, "bbox_0_1000": [40, 300, 960, 360]}],
    "fee_text": None, "driver_eta_min": None, "driver_distance_text": None}  # fmt: skip
SEARCH = {
    "session_id": "s-1",
    "pickup": {"lat": 26.1445, "lng": 91.7362},
    "drop": {"lat": 26.1061, "lng": 91.5859},
}


class Rig:
    def __init__(self, tmp_path: Path) -> None:
        self.deps: Deps = make_test_deps(settings=make_settings(ondc_mode="mock"))
        self.provider = FakeProvider()
        self.deps.router = ProviderRouter(
            providers={"modal_spark_open": self.provider},
            spend=self.deps.spend,
            remote_config=self.deps.remote_config,
            metrics=self.deps.metrics,
            clock=self.deps.clock,
        )
        self.deps.demo_cache = DemoCache.from_manifest(  # the packaged allowlist
            store=self.deps.store, metrics=self.deps.metrics, recorded_dir=tmp_path
        )
        self.deps.ondc = build_buyer(
            self.deps.settings,
            firestore=self.deps.firestore,
            auth=self.deps.auth,
            clock=self.deps.clock,
            metrics=self.deps.metrics,
            store=self.deps.store,
            examples_dir=None,
        )
        self.client = httpx.AsyncClient(
            transport=httpx.ASGITransport(app=create_app(self.deps)), base_url="http://test"
        )

    @property
    def auth(self) -> FakeAuth:
        assert isinstance(self.deps.auth, FakeAuth)
        return self.deps.auth

    async def read(
        self, token: str, image: bytes = PNG_BYTES, app: str = "uber", **headers: str
    ) -> httpx.Response:
        return await self.client.post(
            "/v1/read-screen",
            data={"meta": json.dumps({"app": app, "frame_w": 1080, "frame_h": 2400, "demo": False})},
            files={"image": ("s.png", image, "image/png")},
            headers={**bearer(token), **headers},
        )

    def reads_left(self, token: str) -> int:
        return self.deps.quotas.remaining(self.auth.principal(token))["reads_left"]


@pytest.fixture
async def rig(tmp_path: Path):
    rig = Rig(tmp_path)
    async with rig.client:
        yield rig


async def test_read_screen_through_the_real_app(rig: Rig) -> None:
    token = rig.auth.google("alice")
    before = rig.reads_left(token)
    rig.provider.script("read", READ)
    response = await rig.read(token, **{"X-Request-Id": RID})
    assert response.status_code == 200, response.text
    body = response.json()
    assert (body["source"], body["model"], body["request_id"]) == ("live", PINNED_MODEL_ID, RID)
    assert body["read"]["options"][0]["price_min"] == 110
    assert response.headers["x-request-id"] == RID
    assert rig.reads_left(token) == before - 1


async def test_a_replayed_request_id_is_counted_once_and_runs_at_most_twice(rig: Rig) -> None:
    token = rig.auth.google("alice")
    before = rig.reads_left(token)
    rig.provider.default["read"] = READ
    statuses = [(await rig.read(token, **{"X-Request-Id": RID})).status_code for _ in range(4)]
    assert statuses == [200, 200, 422, 422]
    assert rig.provider.calls == 2
    assert rig.reads_left(token) == before - 1  # the real QuotaManager counted the id once


async def test_a_failed_replay_never_gives_back_the_unit_its_answered_first_run_used(rig: Rig) -> None:
    # Otherwise every answer could be had for free: get it, then replay its id into a failure (a timeout, a
    # dropped connection) and the refund returns the unit the answer was counted on.
    token = rig.auth.google("alice")
    before = rig.reads_left(token)
    rig.provider.script("read", READ, ModelTimeout("slow"), ModelTimeout("slow again"))
    first = await rig.read(token, **{"X-Request-Id": RID})
    replay = await rig.read(token, **{"X-Request-Id": RID})
    assert (first.status_code, replay.status_code) == (200, 504)
    assert rig.provider.calls == 3  # the replay's first try and its one more try both timed out
    assert rig.reads_left(token) == before - 1


async def test_a_failed_first_run_is_given_back_and_its_retry_is_counted_again(rig: Rig) -> None:
    token = rig.auth.google("alice")
    before = rig.reads_left(token)
    rig.provider.script("read", ModelTimeout("slow"), ModelTimeout("slow again"), READ)
    assert (await rig.read(token, **{"X-Request-Id": RID})).status_code == 504
    assert rig.reads_left(token) == before  # the server failed to answer: the unit is given back
    assert (await rig.read(token, **{"X-Request-Id": RID})).status_code == 200
    assert rig.reads_left(token) == before - 1


async def test_one_request_id_reused_across_the_read_endpoints_still_runs_at_most_twice(rig: Rig) -> None:
    # read-screen, reread and point share the 'read' unit, so the run allowance is per quota kind, not endpoint.
    token = rig.auth.google("alice")
    before = rig.reads_left(token)
    rig.deps.remote_config.set("point_supported", True)  # type: ignore[attr-defined]
    rig.provider.default.update({"read": READ, "point": {"box_0_1000": [10, 20, 30, 40]}})
    headers = {**bearer(token), "X-Request-Id": RID}
    assert (await rig.read(token, **{"X-Request-Id": RID})).status_code == 200
    point = await rig.client.post(
        "/v1/point",
        data={"meta": json.dumps({"app": "uber", "target": "cancel_button"})},
        files={"image": ("s.png", PNG_BYTES, "image/png")},
        headers=headers,
    )
    assert point.status_code == 200, point.text
    reread = await rig.client.post(
        "/v1/reread",
        data={"meta": json.dumps({"app": "uber", "rows": [{"row_id": "r1"}]})},
        files={"crop_r1": ("r1.png", PNG_BYTES, "image/png")},
        headers=headers,
    )
    assert reread.status_code == 422 and reread.json()["error"]["code"] == "invalid"
    assert rig.provider.calls == 2
    assert rig.reads_left(token) == before - 1


async def test_errors_render_the_envelope_and_never_reach_the_model(rig: Rig) -> None:
    anonymous = rig.auth.anonymous("judge-phone")
    refused = await rig.read(anonymous, **{"X-Request-Id": RID})
    assert refused.status_code == 403
    assert refused.json()["error"]["code"] == "google_required"
    assert refused.json()["error"]["request_id"] == RID

    too_big = await rig.read(rig.auth.google("alice"), image=b"\x89PNG\r\n\x1a\n" + b"0" * MAX_IMAGE_BYTES)
    assert too_big.status_code == 413 and too_big.json()["error"]["code"] == "too_large"
    assert rig.provider.calls == 0


async def test_try_the_demo_works_anonymously_with_the_packaged_allowlist(
    rig: Rig, monkeypatch: pytest.MonkeyPatch
) -> None:
    if not DEMO_FARE.is_file():
        pytest.skip("android demo assets are not in this checkout")
    token = rig.auth.anonymous("judge-phone")
    purposes: list[Purpose] = []
    reserve = rig.deps.spend.reserve

    def recording_reserve(**kwargs: Any) -> Any:
        purposes.append(kwargs["purpose"])
        return reserve(**kwargs)

    monkeypatch.setattr(rig.deps.spend, "reserve", recording_reserve)
    rig.provider.script("read", {**READ, "app": "demo_kestrel"})
    first = await rig.read(token, image=DEMO_FARE.read_bytes(), app="demo_kestrel")
    assert first.status_code == 200, first.text
    assert first.json()["source"] == "live" and first.json()["read"]["app"] == "demo_kestrel"
    second = await rig.read(token, image=DEMO_FARE.read_bytes(), app="demo_kestrel")
    assert second.json()["source"] == "cache"
    assert rig.provider.calls == 1
    assert purposes == [Purpose.DEMO]  # metered on the demo sub-budget; anonymous users have no quota to use


async def test_ondc_ride_through_the_real_app(rig: Rig) -> None:
    token = rig.auth.google("alice")
    headers = bearer(token)
    buyer = rig.deps.ondc
    assert isinstance(buyer, OndcBuyer) and buyer.mock is not None

    response = await rig.client.post("/v1/ondc/search", json=SEARCH, headers=headers)
    assert response.status_code == 200, response.text
    txn_id = response.json()["txn_id"]
    await buyer.mock.drain()
    assert rig.deps.firestore is not None
    quotes = rig.deps.firestore.get(f"ondc_txn/{txn_id}")["quotes"]
    assert {q["provider_name"] for q in quotes} == {"ONDC demo seller"}

    steps: list[tuple[str, dict[str, Any]]] = [
        ("select", {"txn_id": txn_id, "item_id": "demo-auto"}),
        ("init", {"txn_id": txn_id}),
        ("confirm", {"txn_id": txn_id, "tap_id": "tap-1"}),
        ("status", {"txn_id": txn_id}),
        ("cancel", {"txn_id": txn_id, "tap_id": "tap-2", "confirm": False}),
        ("cancel", {"txn_id": txn_id, "tap_id": "tap-3", "confirm": True}),
    ]
    for step, body in steps:
        reply = await rig.client.post(f"/v1/ondc/{step}", json=body, headers=headers)
        assert reply.status_code == 200, (step, reply.text)
        await buyer.mock.drain()
    txn = rig.deps.firestore.get(f"ondc_txn/{txn_id}")
    assert txn["state"] == "CANCEL_CONFIRMED" and txn["fee"]["amount"] > 0
    assert txn["uid"] == "alice" and txn["demo"] is True

    other = await rig.client.post(
        "/v1/ondc/status", json={"txn_id": txn_id}, headers=bearer(rig.auth.google("mallory"))
    )
    assert other.status_code == 403 and other.json()["error"]["code"] == "forbidden"
