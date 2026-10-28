"""Blocking hot-store and Firestore calls never run on the event loop (offload.py): a slow Modal Dict call in one
request must not stall the others a container serves, /healthz included."""

from __future__ import annotations

import asyncio
import hashlib
import json
import threading
import time
from typing import Any

import httpx
import pytest
from fastapi.testclient import TestClient

from ridemate_api.app import create_app
from ridemate_api.auth import FirebaseAuth
from ridemate_api.clock import FakeClock
from ridemate_api.crypto import HmacKeyring
from ridemate_api.demo_cache import DemoCache, DemoImage
from ridemate_api.deps import Deps
from ridemate_api.interfaces import Purpose, Reservation
from ridemate_api.offload import abandoned_count, off_loop, off_loop_shielded
from ridemate_api.ondc.bap import build_buyer
from ridemate_api.router import ProviderRouter
from ridemate_api.store import MemoryStore
from ridemate_api.testing import (
    PNG_BYTES,
    FakeFirestore,
    FirebaseTokenMinter,
    bearer,
    make_settings,
    make_test_deps,
)
from tests.test_ai_fakes import FakeProvider

DEMO_PNG = b"\x89PNG\r\n\x1a\n" + b"demo-kestrel-fare-options"
DEMO_SHA = hashlib.sha256(DEMO_PNG).hexdigest()
SESSION = "6f1d2c3b-4a5e-4f60-8172-93a4b5c6d7e8"
READ = {"app": "uber", "screen_type": "fare_options", "options": [], "fee_text": None,
        "driver_eta_min": None, "driver_distance_text": None}  # fmt: skip
GLOBAL_IP = "8.8.8.8"  # a public address, so the per-IP bucket is used


def _on_event_loop() -> bool:
    try:
        asyncio.get_running_loop()
    except RuntimeError:
        return False
    return True


class LoopGuardStore(MemoryStore):
    """Records every call made from a thread that is running an event loop."""

    def __init__(self) -> None:
        super().__init__()
        self.on_loop: list[str] = []
        self.calls = 0

    def _check(self) -> None:
        super()._check()
        self.calls += 1
        if _on_event_loop():
            self.on_loop.append(threading.current_thread().name)


class LoopGuardFirestore(FakeFirestore):
    def __init__(self) -> None:
        super().__init__()
        self.on_loop: list[str] = []
        self.calls = 0

    def _check(self) -> None:
        super()._check()
        self.calls += 1
        if _on_event_loop():
            self.on_loop.append(threading.current_thread().name)


def _deps(store: MemoryStore, firestore: Any = None, **settings: Any) -> Deps:
    deps = make_test_deps(
        store=store, firestore=firestore or FakeFirestore(), settings=make_settings(**settings)
    )
    provider = FakeProvider()
    provider.default.update(
        {
            "read": READ,
            "reread": {"rows": [{"row_id": "r1", "vehicle_class": "auto", "label_text": "Auto",
                                 "price_min": 110, "price_max": 110, "eta_min": 3}]},
            "point": {"box_0_1000": [10, 20, 30, 40]},
            "parse": {"label": "searching", "evidence_span": "looking for a captain"},
            "place": {"pick": "c1"},
        }
    )  # fmt: skip
    deps.router = ProviderRouter(
        providers={"modal_spark_open": provider},
        spend=deps.spend,
        remote_config=deps.remote_config,
        metrics=deps.metrics,
        clock=deps.clock,
    )
    deps.demo_cache = DemoCache(
        store=store,
        metrics=deps.metrics,
        images={DEMO_SHA: DemoImage("demo_kestrel", "fare_options")},
        recorded_dir=None,
    )
    deps.remote_config.set("point_supported", True)
    return deps


def _read_form(image: bytes = PNG_BYTES, app: str = "uber") -> dict[str, Any]:
    return {
        "data": {"meta": json.dumps({"app": app, "frame_w": 1080, "frame_h": 2400})},
        "files": {"image": ("s.png", image, "image/png")},
    }


def _headers(token: str, n: int) -> dict[str, str]:
    return {
        **bearer(token),
        "X-Request-Id": f"00000000-0000-4000-8000-{n:012d}",
        "X-Session-Id": SESSION,
        "X-App-Version": "1234",
        "X-Forwarded-For": GLOBAL_IP,
    }


# ---- no blocking call on the loop, on any request path ------------------------------------------------------


def test_no_hot_store_or_firestore_call_runs_on_the_event_loop():
    store, fs = LoopGuardStore(), LoopGuardFirestore()
    deps = _deps(store, fs, ondc_mode="mock", public_base_url="https://ridemate-api.example.test")
    deps.ondc = build_buyer(
        deps.settings, firestore=fs, auth=deps.auth, clock=deps.clock, metrics=deps.metrics, store=store
    )
    token, anon = deps.auth.google("u1"), deps.auth.anonymous("a1")
    n = iter(range(1, 1000))
    with TestClient(create_app(deps)) as client:
        assert (
            client.post("/v1/read-screen", **_read_form(), headers=_headers(token, next(n))).status_code
            == 200
        )
        demo = _read_form(DEMO_PNG, "demo_kestrel")
        for _ in range(2):  # a cache miss (live, then cached), then a cache hit
            assert client.post("/v1/read-screen", **demo, headers=_headers(anon, next(n))).status_code == 200
        reread = {
            "data": {"meta": json.dumps({"app": "uber", "rows": [{"row_id": "r1"}]})},
            "files": {"crop_r1": ("r1.png", PNG_BYTES, "image/png")},
        }
        assert client.post("/v1/reread", **reread, headers=_headers(token, next(n))).status_code == 200
        point = {
            "data": {"meta": json.dumps({"app": "uber", "target": "cancel_button"})},
            "files": {"image": ("s.png", PNG_BYTES, "image/png")},
        }
        assert client.post("/v1/point", **point, headers=_headers(token, next(n))).status_code == 200
        parse = {"app": "rapido", "title": "Rapido", "text": "Looking for a captain"}
        response = client.post("/v1/parse-notification", json=parse, headers=_headers(token, next(n)))
        assert response.status_code == 200
        pick = {"transcript": "airport", "candidates": [{"id": "c1", "label": "LGBI Airport"}]}
        assert client.post("/v1/place-pick", json=pick, headers=_headers(token, next(n))).status_code == 200
        # A model timeout: the quota unit is refunded and the reservation kept, both off the loop.
        deps.router._providers["modal_spark_open"].default["parse"] = TimeoutError()
        response = client.post("/v1/parse-notification", json=parse, headers=_headers(token, next(n)))
        assert response.status_code == 504
        for _ in range(3):  # the young account's three reads a day are used up: quota_exhausted
            client.post("/v1/read-screen", **_read_form(), headers=_headers(token, next(n)))
        search = {
            "session_id": SESSION,
            "pickup": {"lat": 26.14, "lng": 91.73},
            "drop": {"lat": 26.1, "lng": 91.58},
        }
        assert (
            client.post("/v1/ondc/search", json=search, headers=_headers(token, next(n))).status_code == 200
        )
        client.portal.call(deps.ondc.mock.drain)  # the seller's on_search callback, stored and applied
        event = {"session_id": SESSION, "app": "uber", "version_code": 1234, "outcome": "confirmed_all",
                 "time_to_confirm_bucket": "lt_1m", "android_version": 34}  # fmt: skip
        assert client.post("/v1/event", json=event, headers=_headers(token, next(n))).status_code == 202
        assert client.get("/v1/config-check", headers=_headers(token, next(n))).status_code == 200
        assert client.get("/readyz").status_code == 200
    assert store.calls > 100 and fs.calls > 5  # the paths above really used both
    assert fs.collection("audit") and fs.collection("ondc_event")
    assert store.on_loop == []
    assert fs.on_loop == []


async def test_token_verification_reads_the_key_rotation_record_off_the_loop():
    store, clock = LoopGuardStore(), FakeClock()
    minter = FirebaseTokenMinter(clock=clock)
    keyring = HmacKeyring(["current-key", "previous-key"], store=store, clock=clock)
    deps = make_test_deps(store=store, clock=clock)
    auth = FirebaseAuth(
        settings=deps.settings,
        keyring=keyring,
        clock=clock,
        remote_config=deps.remote_config,
        firestore=None,
        http_client=minter.client(),
    )
    principal = await auth.verify_token(minter.mint())
    assert principal.sub_hmac_previous is not None  # the previous key is inside its grace window
    assert store.calls >= 1 and store.on_loop == []


# ---- a blocked store call leaves the loop free ----------------------------------------------------------------


class GatedStore(MemoryStore):
    """The first call on a key under `prefix` blocks until the test releases it."""

    def __init__(self, prefix: str) -> None:
        super().__init__()
        self.prefix = prefix
        self.entered = threading.Event()
        self.release = threading.Event()
        self.timed_out = False
        self._armed = True
        self._lock = threading.Lock()

    def _gate(self, key: str) -> None:
        with self._lock:
            fire = self._armed and key.startswith(self.prefix)
            if fire:
                self._armed = False
        if fire:
            self.entered.set()
            if not self.release.wait(5):  # only reached when this call blocked the event loop
                self.timed_out = True

    def get(self, key: str, default: Any = None) -> Any:
        self._gate(key)
        return super().get(key, default)

    def put(self, key: str, value: Any) -> None:
        self._gate(key)
        super().put(key, value)

    def put_if_absent(self, key: str, value: Any) -> bool:
        self._gate(key)
        return super().put_if_absent(key, value)

    def delete(self, key: str) -> None:
        self._gate(key)
        super().delete(key)

    def keys_with_prefix(self, prefix: str) -> list[str]:
        self._gate(prefix)
        return super().keys_with_prefix(prefix)


@pytest.mark.parametrize(
    "prefix",
    [
        "ip:",  # per-IP bucket (middleware)
        "quota:",  # run claim and quota consume
        "spend:",  # spend reservation inside the router
        "audit:",  # the request's audit record
        "demo:",  # demo-image response cache
    ],
)
async def test_a_hot_store_call_that_hangs_never_stalls_other_requests(prefix):
    store = GatedStore(prefix)
    deps = _deps(store)
    demo = prefix == "demo:"
    token = deps.auth.anonymous("a1") if demo else deps.auth.google("u1")
    form = _read_form(DEMO_PNG, "demo_kestrel") if demo else _read_form()
    transport = httpx.ASGITransport(app=create_app(deps))
    async with httpx.AsyncClient(transport=transport, base_url="http://ridemate.test") as client:
        slow = asyncio.create_task(client.post("/v1/read-screen", **form, headers=_headers(token, 1)))
        assert await asyncio.to_thread(store.entered.wait, 5), "the read never reached the gated call"
        started = time.monotonic()
        health = await asyncio.wait_for(client.get("/healthz"), timeout=2)
        assert health.status_code == 200 and time.monotonic() - started < 1.0
        assert not slow.done()  # the read is still waiting on the hot store, in its worker thread
        store.release.set()
        response = await asyncio.wait_for(slow, timeout=10)
    assert response.status_code == 200, response.text
    assert not store.timed_out, f"a {prefix} call blocked the event loop"


async def test_a_read_cancelled_while_its_quota_unit_is_counted_gets_the_unit_back():
    deps = _deps(MemoryStore())
    token = deps.auth.google("u1")
    principal = deps.auth.principal(token)
    before = deps.quotas.remaining(principal)["reads_left"]
    started, release, refunded = threading.Event(), threading.Event(), threading.Event()
    consume, refund = deps.quotas.consume, deps.quotas.refund

    def gated_consume(*args: Any) -> bool:
        started.set()
        release.wait(5)
        return consume(*args)

    def spy_refund(*args: Any) -> None:
        refund(*args)
        refunded.set()

    deps.quotas.consume, deps.quotas.refund = gated_consume, spy_refund
    transport = httpx.ASGITransport(app=create_app(deps))
    async with httpx.AsyncClient(transport=transport, base_url="http://ridemate.test") as client:
        task = asyncio.create_task(client.post("/v1/read-screen", **_read_form(), headers=_headers(token, 1)))
        assert await asyncio.to_thread(started.wait, 5)
        task.cancel()  # the phone gave up while the unit was being counted
        with pytest.raises(asyncio.CancelledError):
            await task
        release.set()
        assert await asyncio.to_thread(
            refunded.wait, 5
        )  # counted after the request was gone, then given back
    assert deps.quotas.remaining(principal)["reads_left"] == before
    assert deps.router._providers["modal_spark_open"].calls == 0


# ---- offload helpers -----------------------------------------------------------------------------------------


async def test_off_loop_runs_in_a_worker_thread_and_returns_the_result():
    loop_thread = threading.current_thread()
    seen: list[threading.Thread] = []

    def work(a: int, *, b: int) -> int:
        seen.append(threading.current_thread())
        return a + b

    assert await off_loop(work, 2, b=3) == 5
    assert await off_loop_shielded(work, 4, b=5) == 9
    assert seen and all(t is not loop_thread for t in seen)


async def test_a_call_abandoned_by_a_cancelled_request_finishes_and_is_undone():
    started, release = threading.Event(), threading.Event()
    undone: list[Reservation] = []
    reservation = Reservation("r1", Purpose.READ, 0.01, "s", "rid")

    def reserve() -> Reservation:
        started.set()
        release.wait(5)
        return reservation

    task = asyncio.create_task(off_loop_shielded(reserve, undo=undone.append))
    await asyncio.to_thread(started.wait, 5)
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    assert undone == [] and abandoned_count() >= 1  # still running: nothing to undo yet
    release.set()
    for _ in range(200):
        if undone:
            break
        await asyncio.sleep(0.01)
    assert undone == [reservation]  # the reservation nobody would settle is released


async def test_a_cleanup_finishes_even_when_its_request_is_cancelled():
    started, release, finished = threading.Event(), threading.Event(), threading.Event()

    def refund() -> None:
        started.set()
        release.wait(5)
        finished.set()

    task = asyncio.create_task(off_loop_shielded(refund))
    await asyncio.to_thread(started.wait, 5)
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    release.set()
    assert await asyncio.to_thread(finished.wait, 5)
