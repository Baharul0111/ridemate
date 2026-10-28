"""audit.py: metadata audit records aggregated per session in the hot store, one Firestore `audit` document per
session (on /v1/event or by the dispatcher), never text or images, parked in the Dict when the quota is gone."""

from __future__ import annotations

import json
from concurrent.futures import ThreadPoolExecutor
from datetime import UTC, datetime, timedelta
from typing import Any

import httpx
import pytest
import respx
from fastapi.testclient import TestClient

from ridemate_api.audit import (
    HINT_PREFIX,
    OPEN_PREFIX,
    PREFIX,
    REC_PREFIX,
    AuditEntry,
    AuditTrail,
    latency_bucket,
)
from ridemate_api.clock import FakeClock
from ridemate_api.firestore import BUFFER_PREFIX, FirestoreRest, quota_day, quota_reset_utc
from ridemate_api.interfaces import Principal
from ridemate_api.router import ProviderRouter
from ridemate_api.store import MemoryStore, StoreUnavailable
from ridemate_api.testing import (
    PNG_BYTES,
    FakeFirestore,
    FakeMetrics,
    bearer,
    make_test_app,
    make_test_deps,
)
from tests.test_ai_fakes import FakeProvider
from tests.test_ops_fakes import PROJECT, FakeTokens

NOW = datetime(2026, 10, 28, 6, 0, tzinfo=UTC)  # 11:30 IST
ALICE = Principal(uid="alice", provider="google.com", sub_hmac="a" * 64)
BOB = Principal(uid="bob", provider="google.com", sub_hmac="b" * 64)
ANON = Principal(uid="anon", provider="anonymous", sub_hmac="c" * 64)
SESSION = "6f1d2c3b-4a5e-4f60-8172-93a4b5c6d7e8"
OTHER_SESSION = "0b7f5c1e-3c1d-4b8a-9a43-2f1e8f0c9d11"
READ = {
    "app": "uber",
    "screen_type": "fare_options",
    "options": [],
    "fee_text": None,
    "driver_eta_min": None,
    "driver_distance_text": None,
}


class Rig:
    def __init__(self, **kwargs: Any) -> None:
        self.store = MemoryStore()
        self.clock = FakeClock(NOW)
        self.fs = FakeFirestore()
        self.metrics = FakeMetrics()
        self.trail = AuditTrail(
            store=self.store, clock=self.clock, firestore=self.fs, metrics=self.metrics, **kwargs
        )

    def record(self, principal: Principal = ALICE, session: str | None = SESSION, **entry: Any) -> bool:
        values: dict[str, Any] = {"endpoint": "read_screen", "outcome": "ok", "latency_ms": 2300}
        values.update(entry)
        return self.trail.record(principal, session, AuditEntry(**values))

    def docs(self) -> list[dict[str, Any]]:
        return list(self.fs.collection("audit").values())

    def hot_keys(self) -> list[str]:
        return sorted(self.store.keys_with_prefix(PREFIX))


# ---- the record ----------------------------------------------------------------------------------------------


@pytest.mark.parametrize(
    ("ms", "bucket"),
    [(0, "lt_1s"), (999, "lt_1s"), (1000, "1_2s"), (3999, "2_4s"), (11_999, "8_12s"), (19_999, "12_20s"),
     (20_000, "gt_20s"), (90_000, "gt_20s")],
)  # fmt: skip
def test_latency_is_kept_as_a_bucket(ms, bucket):
    assert latency_bucket(ms) == bucket


def test_a_session_becomes_one_metadata_document_when_it_ends():
    rig = Rig()
    assert rig.record(cost_usd=0.0031234567, source="live", app="uber", version_code=1234)
    rig.clock.advance(40)
    assert rig.record(endpoint="parse_notification", outcome="quota_exhausted", latency_ms=12, app="rapido")
    rig.clock.advance(5)
    assert rig.record(endpoint="read_screen", outcome="ok", latency_ms=21_000, source="cache", app="uber")
    assert rig.docs() == []  # nothing reaches Firestore while the session runs

    assert rig.trail.flush_session(ALICE, SESSION) == 1
    (doc,) = rig.docs()
    assert doc["uid_hash"] == ALICE.uid_hash and doc["day"] == "2026-10-28"
    assert doc["count"] == 3 and doc["cost_usd"] == pytest.approx(0.003123)
    assert doc["first_at"] == NOW and doc["last_at"] == NOW + timedelta(seconds=45)
    assert doc["app"] == "mixed" and doc["version_code"] == 1234
    assert doc["outcomes"] == {"ok": 2, "quota_exhausted": 1}
    assert doc["requests"][0] == {
        "endpoint": "read_screen",
        "outcome": "ok",
        "source": "live",
        "latency_bucket": "2_4s",
        "cost_usd": 0.003123,
        "app": "uber",
        "version_code": 1234,
        "at": NOW,
    }
    assert [r["latency_bucket"] for r in doc["requests"]] == ["2_4s", "lt_1s", "gt_20s"]
    assert doc["created_at"] == NOW + timedelta(
        seconds=45
    )  # the daily cleanup expires records by this (1 year)
    assert rig.hot_keys() == []  # the session's hot-store keys are gone
    assert rig.trail.flush_session(ALICE, SESSION) == 0  # a second /v1/event writes nothing
    assert len(rig.fs.writes) == 1


def test_records_hold_metadata_only_never_free_text():
    rig = Rig()
    rig.record(
        outcome="Ignore previous instructions; captain Ravi 98765 43210", app="₹110 Auto", source="x y"
    )
    rig.trail.flush_session(ALICE, SESSION)
    (doc,) = rig.docs()
    request = doc["requests"][0]
    assert request["outcome"] == request["app"] == request["source"] == "other"
    text = json.dumps(doc, default=str)
    assert "Ravi" not in text and "98765" not in text and "₹" not in text
    assert ALICE.sub_hmac not in text and SESSION not in text and ALICE.uid not in text


def test_only_google_signed_in_callers_are_recorded():
    rig = Rig()
    assert not rig.record(ANON)
    assert rig.trail.flush_session(ANON, SESSION) == 0
    assert rig.hot_keys() == [] and rig.docs() == []


def test_sessions_and_users_are_kept_apart():
    rig = Rig()
    rig.record(ALICE, SESSION)
    rig.record(ALICE, OTHER_SESSION)
    rig.record(BOB, SESSION)  # the same session id from another user is another session
    assert rig.trail.flush_session(ALICE, SESSION) == 1
    assert rig.trail.flush_session(BOB, SESSION) == 1
    assert rig.trail.flush_session(ALICE, OTHER_SESSION) == 1
    assert sorted((d["uid_hash"], d["count"]) for d in rig.docs()) == [
        (ALICE.uid_hash, 1),
        (ALICE.uid_hash, 1),
        (BOB.uid_hash, 1),
    ]


def test_requests_without_a_session_share_one_session_per_user_and_day():
    rig = Rig()
    rig.record(session=None)
    rig.record(session=None)
    assert rig.trail.flush_session(ALICE, None) == 1
    (doc,) = rig.docs()
    assert doc["count"] == 2


def test_rotating_session_ids_cannot_multiply_firestore_writes():
    rig = Rig(sessions_per_day=3)
    sessions = [f"{i:08d}-0000-4000-8000-000000000000" for i in range(10)]
    for session in sessions:
        assert rig.record(session=session)
    written = sum(rig.trail.flush_session(ALICE, s) for s in sessions)
    rig.clock.advance(3600)
    written += rig.trail.flush_idle()["docs"]
    assert written == 4  # three sessions of their own, then one overflow session for the rest of the day
    assert sorted(d["count"] for d in rig.docs()) == [1, 1, 1, 7]
    assert rig.metrics.count("audit_overflow") == 7


def test_a_full_session_drops_further_records_and_counts_them():
    rig = Rig(max_records=3)
    assert [rig.record() for _ in range(5)] == [True, True, True, False, False]
    assert rig.metrics.count("audit_dropped", reason="session_full") == 2
    rig.trail.flush_session(ALICE, SESSION)
    assert rig.docs()[0]["count"] == 3


def test_concurrent_requests_of_one_session_each_get_their_own_slot():
    rig = Rig()
    with ThreadPoolExecutor(max_workers=16) as pool:
        results = list(pool.map(lambda i: rig.record(cost_usd=0.001, latency_ms=i), range(64)))
    assert all(results)
    assert rig.trail.flush_session(ALICE, SESSION) == 1
    (doc,) = rig.docs()
    assert doc["count"] == 64 and doc["cost_usd"] == pytest.approx(0.064)


def test_an_unreadable_hot_store_never_fails_the_request():
    rig = Rig()
    rig.store.fail = True
    assert rig.record() is False
    assert rig.metrics.count("audit_dropped", reason="store") == 1
    assert rig.trail.flush_session(ALICE, SESSION) == 0


# ---- the dispatcher ------------------------------------------------------------------------------------------


def test_the_dispatcher_writes_sessions_that_went_idle_and_leaves_active_ones():
    rig = Rig()
    rig.record(ALICE, SESSION)
    rig.clock.advance(20 * 60)
    rig.record(BOB, SESSION)
    rig.clock.advance(15 * 60)  # alice idle 35 min, bob 15 min
    assert rig.trail.flush_idle() == {"sessions": 1, "late": 0, "docs": 1, "open": 1}
    assert [d["uid_hash"] for d in rig.docs()] == [ALICE.uid_hash]
    rig.clock.advance(20 * 60)
    assert rig.trail.flush_idle()["docs"] == 1
    assert rig.hot_keys() == []


def test_a_session_that_never_goes_idle_is_still_written_after_six_hours():
    rig = Rig()
    for _ in range(25):
        rig.record()
        rig.clock.advance(15 * 60)
    assert rig.trail.flush_idle()["docs"] == 1
    assert rig.docs()[0]["count"] == 25


def test_a_record_that_races_the_seal_is_written_later_never_lost():
    rig = Rig()
    rig.record()
    sk = AuditTrail.session_key(ALICE, SESSION, NOW)
    gen = rig.store.get(OPEN_PREFIX + sk)["gen"]
    rig.trail.flush_session(ALICE, SESSION)
    # A request that read the open generation just before the seal lands its record after the flush read.
    rig.store.put(f"{REC_PREFIX}{sk}:{gen}:1", {**_record(rig, latency_ms=50), "at": NOW.timestamp()})
    assert rig.trail.flush_idle()["docs"] == 0  # within the grace: the flush might still be finishing
    rig.clock.advance(6 * 60)
    assert rig.trail.flush_idle() == {"sessions": 0, "late": 1, "docs": 1, "open": 0}
    assert sorted(d["count"] for d in rig.docs()) == [1, 1]
    assert rig.hot_keys() == []


def test_a_late_record_that_reuses_a_cleared_slot_never_overwrites_the_flushed_document():
    # The racing request read the open generation before the seal and its hint only after the flush cleared it,
    # so it claimed slot 0 again: its record must become a document of its own, not replace the flushed one.
    rig = Rig()
    rig.record()
    rig.record()
    sk = AuditTrail.session_key(ALICE, SESSION, NOW)
    gen = rig.store.get(OPEN_PREFIX + sk)["gen"]
    assert rig.trail.flush_session(ALICE, SESSION) == 1
    assert rig.store.get(f"{HINT_PREFIX}{sk}:{gen}") is None
    rig.clock.advance(1)
    rig.store.put(f"{REC_PREFIX}{sk}:{gen}:0", _record(rig, latency_ms=50))
    rig.clock.advance(6 * 60)
    assert rig.trail.flush_idle()["late"] == 1
    assert sorted(d["count"] for d in rig.docs()) == [1, 2]  # nothing lost
    assert rig.hot_keys() == []


def test_an_unexpected_firestore_error_never_fails_v1_event_or_blocks_other_sessions():
    rig = Rig()
    rig.record(session=SESSION, endpoint="point")
    rig.record(session=OTHER_SESSION, endpoint="read_screen")
    real_set = rig.fs.set

    def flaky_set(path: str, data: dict[str, Any], *, merge: bool = False) -> None:
        if any(r.get("endpoint") == "point" for r in data.get("requests", ())):
            raise ValueError("unstorable")  # not a FirestoreUnavailable: a bug or a 4xx
        real_set(path, data, merge=merge)

    rig.fs.set = flaky_set  # type: ignore[method-assign]
    assert rig.trail.flush_session(ALICE, SESSION) == 0  # no exception reaches /v1/event
    assert rig.metrics.count("audit_flush_failed") == 1
    rig.clock.advance(31 * 60)
    assert rig.trail.flush_idle()["docs"] == 1  # the other session is written all the same
    rig.fs.set = real_set  # type: ignore[method-assign]
    assert rig.trail.flush_idle()["late"] == 1  # the failed one's slots were kept and are written now
    assert sorted(d["count"] for d in rig.docs()) == [1, 1]
    assert rig.hot_keys() == []


def test_a_flush_that_firestore_refuses_is_retried_by_the_dispatcher():
    rig = Rig()
    rig.record()
    rig.record()
    rig.fs.fail = True
    assert rig.trail.flush_session(ALICE, SESSION) == 0
    assert rig.metrics.count("audit_flush_failed") == 1
    rig.fs.fail = False
    rig.record()  # the session goes on in a new generation
    rig.clock.advance(31 * 60)
    assert rig.trail.flush_idle()["docs"] == 2
    assert sorted(d["count"] for d in rig.docs()) == [1, 2]
    assert rig.hot_keys() == []


def test_a_retried_flush_rewrites_the_same_document():
    rig = Rig()
    rig.record()
    real_delete = rig.store.delete

    def failing_delete(key: str) -> None:
        if key.startswith(REC_PREFIX):
            raise StoreUnavailable("down")
        real_delete(key)

    rig.store.delete = failing_delete  # type: ignore[method-assign]
    assert rig.trail.flush_session(ALICE, SESSION) == 0  # written, but its slots couldn't be cleared
    rig.store.delete = real_delete  # type: ignore[method-assign]
    rig.clock.advance(10 * 60)
    assert rig.trail.flush_idle()["docs"] == 1
    assert len(rig.docs()) == 1 and len(rig.fs.writes) == 2  # the same document, rewritten


def test_stale_hints_are_dropped_and_the_scan_stops_when_told():
    rig = Rig()
    rig.store.put(f"{HINT_PREFIX}{'f' * 32}:deadbeef0000", {"n": 3, "last_at": 0})
    rig.trail.flush_idle()
    assert rig.hot_keys() == []
    for session in (SESSION, OTHER_SESSION):
        rig.record(session=session)
    rig.clock.advance(3600)
    assert rig.trail.flush_idle(stop=lambda: True)["docs"] == 0
    assert rig.trail.flush_idle(max_docs=1)["docs"] == 1
    assert rig.trail.flush_idle()["docs"] == 1


# ---- the Firestore write budget --------------------------------------------------------------------------------

DOCS_URL = f"https://firestore.googleapis.com/v1/projects/{PROJECT}/databases/(default)/documents"


@respx.mock
def test_with_the_quota_gone_the_document_is_parked_in_the_dict_and_replayed_after_the_reset():
    route = respx.patch(url__startswith=DOCS_URL).mock(return_value=httpx.Response(200, json={"name": "x"}))
    store, clock, metrics = MemoryStore(), FakeClock(NOW), FakeMetrics()
    store.put(f"fs:writes:{quota_day(NOW)}:other", 20_000)  # today's free writes are used up
    fs = FirestoreRest(project_id=PROJECT, tokens=FakeTokens(), clock=clock, store=store, writer_id="w1")
    trail = AuditTrail(store=store, clock=clock, firestore=fs, metrics=metrics)
    trail.record(ALICE, SESSION, AuditEntry("read_screen", "ok", 2300, cost_usd=0.003))

    assert trail.flush_session(ALICE, SESSION) == 1
    assert route.call_count == 0
    (parked,) = store.keys_with_prefix(BUFFER_PREFIX)
    assert store.get(parked)["path"].startswith("audit/")
    assert store.keys_with_prefix(PREFIX) == []  # handed over to the buffer: nothing is written twice

    clock.advance((quota_reset_utc(NOW) - NOW).total_seconds() + 60)
    assert fs.replay_buffer() == 1
    fields = json.loads(route.calls[0].request.content)["fields"]
    assert fields["uid_hash"] == {"stringValue": ALICE.uid_hash}
    assert fields["count"] == {"integerValue": "1"}


@respx.mock
def test_audit_documents_are_never_shed_near_the_quota():
    route = respx.patch(url__startswith=DOCS_URL).mock(return_value=httpx.Response(200, json={"name": "x"}))
    store, clock = MemoryStore(), FakeClock(NOW)
    store.put(f"fs:writes:{quota_day(NOW)}:other", 17_000)  # above the 80% shedding line
    fs = FirestoreRest(project_id=PROJECT, tokens=FakeTokens(), clock=clock, store=store, writer_id="w1")
    trail = AuditTrail(store=store, clock=clock, firestore=fs, metrics=FakeMetrics())
    trail.record(ALICE, SESSION, AuditEntry("read_screen", "ok", 2300))
    assert trail.flush_session(ALICE, SESSION) == 1
    assert route.call_count == 1
    assert int(store.get(f"fs:writes:{quota_day(NOW)}:w1")) == 1  # counted in the write budget


# ---- through the endpoints -------------------------------------------------------------------------------------


def _record(rig: Rig, **entry: Any) -> dict[str, Any]:
    values: dict[str, Any] = {"endpoint": "read_screen", "outcome": "ok", "latency_ms": 2300}
    values.update(entry)
    return AuditTrail._record_doc(ALICE, AuditEntry(**values), rig.clock.now_utc())


def _app_rig() -> tuple[Any, TestClient, FakeProvider]:
    deps = make_test_deps()
    provider = FakeProvider()
    provider.default["read"] = READ
    deps.router = ProviderRouter(
        providers={"modal_spark_open": provider},
        spend=deps.spend,
        remote_config=deps.remote_config,
        metrics=deps.metrics,
        clock=deps.clock,
    )
    return deps, TestClient(make_test_app(deps)), provider


def _read(client: TestClient, token: str, request_id: str, **headers: str) -> httpx.Response:
    return client.post(
        "/v1/read-screen",
        data={"meta": json.dumps({"app": "uber", "frame_w": 1080, "frame_h": 2400})},
        files={"image": ("s.png", PNG_BYTES, "image/png")},
        headers={**bearer(token), "X-Request-Id": request_id, **headers},
    )


def test_ai_requests_of_a_session_land_in_one_audit_document_on_v1_event():
    deps, client, _ = _app_rig()
    token = deps.auth.google("u1")
    session = {"X-Session-Id": SESSION, "X-App-Version": "1234"}
    assert _read(client, token, "11111111-aaaa-4bbb-8ccc-000000000001", **session).status_code == 200
    assert _read(client, token, "11111111-aaaa-4bbb-8ccc-000000000002", **session).status_code == 200
    deps.quotas.consume = lambda *a: (_ for _ in ()).throw(StoreUnavailable("down"))  # type: ignore[method-assign]
    assert _read(client, token, "11111111-aaaa-4bbb-8ccc-000000000003", **session).status_code == 503
    assert deps.firestore.collection("audit") == {}

    event = {
        "session_id": SESSION,
        "app": "uber",
        "version_code": 1234,
        "outcome": "confirmed_all",
        "time_to_confirm_bucket": "lt_1m",
        "android_version": 34,
    }
    assert client.post("/v1/event", json=event, headers=bearer(token)).status_code == 202
    (doc,) = deps.firestore.collection("audit").values()
    principal = deps.auth.principal(token)
    assert doc["uid_hash"] == principal.uid_hash and doc["count"] == 3
    assert doc["app"] == "uber" and doc["version_code"] == 1234
    assert [(r["endpoint"], r["outcome"], r["source"]) for r in doc["requests"]] == [
        ("read_screen", "ok", "live"),
        ("read_screen", "ok", "live"),
        ("read_screen", "meter_unavailable", None),
    ]
    assert doc["cost_usd"] > 0 and doc["requests"][2]["cost_usd"] == 0.0
    assert set(deps.firestore.collection("events")) and deps.store.keys_with_prefix(PREFIX) == []


def test_anonymous_demo_reads_leave_no_audit_record():
    deps, client, _ = _app_rig()
    token = deps.auth.anonymous("a1")
    response = _read(client, token, "22222222-aaaa-4bbb-8ccc-000000000001", **{"X-Session-Id": SESSION})
    assert response.status_code == 403  # a non-demo image needs Google sign-in
    assert deps.store.keys_with_prefix(PREFIX) == []
