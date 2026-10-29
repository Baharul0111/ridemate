"""api/misc.py: /v1/event, /v1/erase, /v1/config-check, /v1/owner/fcm-token, /healthz, /readyz."""

from __future__ import annotations

import re
from datetime import UTC, datetime
from typing import Any

import pytest
from fastapi.testclient import TestClient

from ridemate_api import app as app_module
from ridemate_api.api.misc import (
    ERASES_PER_DAY,
    EVENT_WRITES_PER_DAY,
    OWNER_TOKENS_KEY,
    complete_pending_erasures,
)
from ridemate_api.app import create_app
from ridemate_api.clock import FakeClock
from ridemate_api.deps import Deps
from ridemate_api.interfaces import ApiError, Purpose
from ridemate_api.store import MemoryStore
from ridemate_api.testing import FakeAuth, FakeFirestore, FakeRouter, bearer, make_settings, make_test_deps

SESSION = "9b2c4c4e-8d7e-4f2a-9d55-0a7a1c3e5f10"
EVENT = {
    "session_id": SESSION,
    "app": "uber",
    "version_code": 1234,
    "outcome": "confirmed_all",
    "time_to_confirm_bucket": "1_3m",
    "android_version": 34,
    "reads": 4,
    "verified_rows": 9,
    "unreadable_rows": 1,
    "rule_hits": 6,
    "rule_misses": 1,
    "misses_unclassified": 0,
    "demo": False,
}


def setup(**kw: Any) -> tuple[Deps, TestClient, FakeAuth]:
    kw.setdefault("router", FakeRouter())
    deps = make_test_deps(**kw)
    assert isinstance(deps.auth, FakeAuth)
    return deps, TestClient(create_app(deps)), deps.auth


def fs_of(deps: Deps) -> FakeFirestore:
    assert isinstance(deps.firestore, FakeFirestore)
    return deps.firestore


# ---- /v1/event -------------------------------------------------------------------------------------------


def test_event_from_google_user_stores_enums_and_counters_only() -> None:
    deps, client, auth = setup()
    token = auth.google("rider")
    r = client.post("/v1/event", json={**EVENT, "free_text": "my OTP is 4821"}, headers=bearer(token))
    assert r.status_code == 202
    docs = fs_of(deps).collection("events")
    assert len(docs) == 1
    doc = next(iter(docs.values()))
    principal = auth.principal(token)
    assert doc["uid_hash"] == principal.uid_hash
    assert doc["outcome"] == "confirmed_all" and doc["verified_rows"] == 9 and doc["day"] == "2026-10-28"
    assert "session_id" not in doc and "free_text" not in doc
    assert SESSION not in str(doc) and principal.uid not in str(doc)
    assert deps.metrics.count("event_verified_rows") == 9  # type: ignore[attr-defined]
    assert deps.metrics.count("event_reads") == 4  # type: ignore[attr-defined]


def test_retried_event_overwrites_its_document_and_is_counted_once() -> None:
    deps, client, auth = setup()
    token = auth.google()
    for _ in range(3):
        assert client.post("/v1/event", json=EVENT, headers=bearer(token)).status_code == 202
    assert len(fs_of(deps).collection("events")) == 1
    assert deps.metrics.count("event_reads") == 4  # type: ignore[attr-defined]


def test_anonymous_event_is_accepted_and_dropped() -> None:
    deps, client, auth = setup()
    r = client.post("/v1/event", json=EVENT, headers=bearer(auth.anonymous()))
    assert r.status_code == 202
    assert fs_of(deps).writes == []
    assert deps.metrics.count("event_reads") == 0  # type: ignore[attr-defined]


def test_event_auth_comes_before_body_validation() -> None:
    _, client, auth = setup()
    assert client.post("/v1/event", json={"junk": 1}).status_code == 401
    r = client.post("/v1/event", json={**EVENT, "outcome": "free text"}, headers=bearer(auth.google()))
    assert r.status_code == 422 and r.json()["error"]["code"] == "invalid"
    r = client.post("/v1/event", json={**EVENT, "reads": 10_001}, headers=bearer(auth.google()))
    assert r.status_code == 422


def test_event_writes_are_capped_per_user_per_day() -> None:
    """One account must not burn the free Firestore write quota (20k/day) that ONDC and everyone else share."""
    deps, client, auth = setup()
    token, other = auth.google("spammer"), auth.google("rider")
    for i in range(EVENT_WRITES_PER_DAY + 5):
        body = {**EVENT, "session_id": f"{i:08d}-0000-4000-8000-000000000000"}
        assert client.post("/v1/event", json=body, headers=bearer(token)).status_code == 202
    assert len(fs_of(deps).collection("events")) == EVENT_WRITES_PER_DAY
    assert deps.metrics.count("event_reads") == 4 * EVENT_WRITES_PER_DAY  # type: ignore[attr-defined]
    assert deps.metrics.count("session_events_dropped", reason="daily_cap") == 5  # type: ignore[attr-defined]
    for _ in range(3):  # resending one session id is a write each time, so it counts too
        client.post("/v1/event", json=EVENT, headers=bearer(token))
    assert len(fs_of(deps).collection("events")) == EVENT_WRITES_PER_DAY
    assert client.post("/v1/event", json=EVENT, headers=bearer(other)).status_code == 202
    assert len(fs_of(deps).collection("events")) == EVENT_WRITES_PER_DAY + 1
    deps.clock.advance(86400)  # type: ignore[attr-defined]
    client.post(
        "/v1/event",
        json={**EVENT, "session_id": "ffffffff-0000-4000-8000-000000000000"},
        headers=bearer(token),
    )
    assert len(fs_of(deps).collection("events")) == EVENT_WRITES_PER_DAY + 2


def test_event_survives_firestore_outage_and_absence() -> None:
    deps, client, auth = setup()
    fs_of(deps).fail = True
    assert client.post("/v1/event", json=EVENT, headers=bearer(auth.google())).status_code == 202
    _, client2, auth2 = setup(firestore=None)
    assert client2.post("/v1/event", json=EVENT, headers=bearer(auth2.google())).status_code == 202


# ---- /v1/erase -------------------------------------------------------------------------------------------


def seed_user_data(deps: Deps, uid: str, uid_hash: str) -> None:
    fs = fs_of(deps)
    fs.set("ondc_txn/11111111-1111-4111-8111-111111111111", {"uid": uid, "state": "CONFIRMED"})
    fs.set("ondc_txn/22222222-2222-4222-8222-222222222222", {"uid": "someone-else"})
    fs.set("ondc_event/e1", {"txnId": "11111111-1111-4111-8111-111111111111", "action": "on_confirm"})
    fs.set("ondc_event/e2", {"txnId": "22222222-2222-4222-8222-222222222222", "action": "on_confirm"})
    fs.set("events/mine", {"uid_hash": uid_hash, "outcome": "dismissed"})
    fs.set("events/theirs", {"uid_hash": "0" * 16, "outcome": "dismissed"})


def test_erase_deletes_only_the_callers_server_data() -> None:
    deps, client, auth = setup()
    token = auth.google("eraser", owner=True)
    p = auth.principal(token)
    seed_user_data(deps, p.uid, p.uid_hash)
    assert (
        client.post("/v1/owner/fcm-token", json={"token": "fcm-abc"}, headers=bearer(token)).status_code
        == 204
    )
    deps.quotas.consume(p, "read", "before-erase-1")

    r = client.post("/v1/erase", headers=bearer(token))
    assert r.status_code == 200 and r.json() == {"erased": True}
    fs = fs_of(deps)
    assert set(fs.collection("ondc_txn")) == {"ondc_txn/22222222-2222-4222-8222-222222222222"}
    assert set(fs.collection("ondc_event")) == {"ondc_event/e2"}
    assert set(fs.collection("events")) == {"events/theirs"}
    assert fs.get(f"usage_snapshot/{p.sub_hmac}")["erased_at"] == deps.clock.now_utc()
    assert deps.store.get(OWNER_TOKENS_KEY) == {}
    # Usage counters stay (48 h, abuse prevention): erasing doesn't hand out a fresh quota.
    assert deps.quotas.remaining(p)["reads_left"] == 2


def test_erase_during_key_rotation_covers_data_under_both_keys() -> None:
    """Events and the owner device written before an HMAC rotation sit under the previous key (§4.18 H5)."""
    store, clock = MemoryStore(), FakeClock()
    old = make_test_deps(store=store, clock=clock, settings=make_settings(hmac_keys=("old-key",)))
    old_auth = old.auth
    assert isinstance(old_auth, FakeAuth)
    old_token = old_auth.google("rotated", google_sub="g-rot", owner=True)
    before = old_auth.principal(old_token)
    old_client = TestClient(create_app(old))
    assert old_client.post("/v1/event", json=EVENT, headers=bearer(old_token)).status_code == 202
    assert (
        old_client.post(
            "/v1/owner/fcm-token", json={"token": "fcm-old"}, headers=bearer(old_token)
        ).status_code
        == 204
    )

    deps = make_test_deps(
        store=store,
        clock=clock,
        firestore=old.firestore,
        settings=make_settings(hmac_keys=("new-key", "old-key")),
    )
    auth = deps.auth
    assert isinstance(auth, FakeAuth)
    token = auth.google("rotated", google_sub="g-rot", owner=True)
    after = auth.principal(token)
    assert after.sub_hmac_previous == before.sub_hmac != after.sub_hmac
    client = TestClient(create_app(deps))
    assert (
        client.post(
            "/v1/event",
            json={**EVENT, "session_id": "aaaaaaaa-0000-4000-8000-000000000000"},
            headers=bearer(token),
        ).status_code
        == 202
    )
    assert len(fs_of(deps).collection("events")) == 2

    assert client.post("/v1/erase", headers=bearer(token)).json() == {"erased": True}
    assert fs_of(deps).collection("events") == {}
    assert deps.store.get(OWNER_TOKENS_KEY) == {}
    for h in (before.sub_hmac, after.sub_hmac):
        assert fs_of(deps).get(f"usage_snapshot/{h}")["erased_at"] == clock.now_utc()


def test_queued_erase_during_rotation_also_covers_both_keys() -> None:
    store, clock = MemoryStore(), FakeClock()
    fs = FakeFirestore()
    deps = make_test_deps(
        store=store, clock=clock, firestore=fs, settings=make_settings(hmac_keys=("new-key", "old-key"))
    )
    auth = deps.auth
    assert isinstance(auth, FakeAuth)
    token = auth.google("q", google_sub="g-q")
    p = auth.principal(token)
    assert p.sub_hmac_previous is not None
    fs.set("events/old", {"uid_hash": p.sub_hmac_previous[:16]})
    fs.set("events/new", {"uid_hash": p.uid_hash})
    fs.fail = True
    assert TestClient(create_app(deps)).post("/v1/erase", headers=bearer(token)).status_code == 503
    fs.fail = False
    assert complete_pending_erasures(deps) == 1
    assert fs.collection("events") == {}


def test_erases_are_capped_per_user_per_day() -> None:
    deps, client, auth = setup()
    token = auth.google("eraser")
    for _ in range(ERASES_PER_DAY):
        assert client.post("/v1/erase", headers=bearer(token)).status_code == 200
    r = client.post("/v1/erase", headers=bearer(token))
    assert (r.status_code, r.json()["error"]["code"]) == (429, "rate_limited")
    assert r.json()["error"]["resume_at_ist"] == "00:00"
    assert client.post("/v1/erase", headers=bearer(auth.google("someone-else"))).status_code == 200
    deps.clock.advance(86400)  # type: ignore[attr-defined]
    assert client.post("/v1/erase", headers=bearer(token)).status_code == 200


def test_erase_requires_google() -> None:
    _, client, auth = setup()
    r = client.post("/v1/erase", headers=bearer(auth.anonymous()))
    assert (r.status_code, r.json()["error"]["code"]) == (403, "google_required")


def test_erase_without_firestore_cleans_the_dict_and_succeeds() -> None:
    deps, client, auth = setup(firestore=None)
    token = auth.google(owner=True)
    client.post("/v1/owner/fcm-token", json={"token": "fcm-x"}, headers=bearer(token))
    assert client.post("/v1/erase", headers=bearer(token)).json() == {"erased": True}
    assert deps.store.get(OWNER_TOKENS_KEY) == {}
    assert deps.store.get(f"erased:{auth.principal(token).sub_hmac}") is not None


def test_erase_during_firestore_outage_is_queued_and_completed_later() -> None:
    deps, client, auth = setup()
    token = auth.google("later")
    p = auth.principal(token)
    seed_user_data(deps, p.uid, p.uid_hash)
    fs_of(deps).fail = True
    r = client.post("/v1/erase", headers=bearer(token))
    assert (r.status_code, r.json()["error"]["code"]) == (503, "storage_unavailable")
    assert complete_pending_erasures(deps) == 0  # still down
    fs_of(deps).fail = False
    assert complete_pending_erasures(deps) == 1
    assert "events/mine" not in fs_of(deps).docs
    assert deps.store.keys_with_prefix("erase:pending:") == []


# ---- /v1/config-check ------------------------------------------------------------------------------------


def check(client: TestClient, token: str | None = None) -> dict[str, Any]:
    r = client.get("/v1/config-check", headers=bearer(token) if token else {})
    assert r.status_code == 200, r.text
    return r.json()


def test_config_check_signed_out_and_anonymous() -> None:
    _, client, auth = setup()
    body = check(client)
    assert body["ai_enabled"] is False and body["reason"] == "signed_out"
    assert body["quota"] == {"reads_left": 0, "parses_left": 0} and body["budget"] is None
    assert check(client, auth.anonymous())["reason"] == "anonymous"


def test_config_check_for_a_google_user() -> None:
    _, client, auth = setup()
    body = check(client, auth.google())
    assert body["ai_enabled"] is True and body["reason"] is None and body["resume_at_ist"] is None
    assert body["quota"] == {"reads_left": 3, "parses_left": 5}  # young account on day one
    assert body["cap_profile"] == "normal" and body["min_supported_version"] == 1
    assert body["point_supported"] is False and body["budget"] is None
    assert re.fullmatch(r"\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ", body["server_time_utc"])


def test_config_check_reflects_remote_config() -> None:
    _, client, auth = setup(
        rc={"point_supported": True, "cap_profile": "judging", "min_supported_version": 3}
    )
    body = check(client, auth.google())
    assert body["point_supported"] is True and body["cap_profile"] == "judging"
    assert body["min_supported_version"] == 3


def test_config_check_kill_switch_wins() -> None:
    _, client, auth = setup(rc={"ai_reader_enabled": False})
    assert check(client)["reason"] == "kill_switch"
    assert check(client, auth.google())["reason"] == "kill_switch"


@pytest.mark.parametrize(("router", "reason"), [(FakeRouter("breaker"), "breaker"), (None, "model_missing")])
def test_config_check_router_states(router: FakeRouter | None, reason: str) -> None:
    _, client, auth = setup(router=router)
    assert check(client, auth.google())["reason"] == reason


def test_config_check_quota_exhausted_resumes_at_ist_midnight() -> None:
    deps, client, auth = setup()
    token = auth.google()
    for i in range(3):
        deps.quotas.consume(auth.principal(token), "read", f"r-{i:06d}")
    body = check(client, token)
    assert (body["ai_enabled"], body["reason"], body["resume_at_ist"]) == (False, "quota", "00:00")


def test_config_check_tier_pause_day_cap_and_meter_states() -> None:
    clock = FakeClock(datetime(2026, 10, 28, 8, 0, tzinfo=UTC))
    deps, client, auth = setup(clock=clock)
    token = auth.google()  # a young account (first seen today)

    def spend(usd: float) -> None:
        deps.spend.keep(deps.spend.reserve(purpose=Purpose.READ, max_usd=usd, principal=None, request_id="x"))

    cap = deps.spend.summary()["day_cap_usd"]
    spend(cap * 0.8)
    body = check(client, token)
    assert (body["reason"], body["resume_at_ist"]) == ("tier_pause", "00:00")
    spend(deps.spend.summary()["day_cap_usd"] - deps.spend.summary()["day_spent_usd"])
    with pytest.raises(ApiError):
        spend(0.001)
    body = check(client, token)
    assert (body["ai_enabled"], body["reason"], body["resume_at_ist"]) == (False, "day_cap", "00:00")
    deps.store.fail = True  # type: ignore[attr-defined]
    assert check(client, token)["reason"] == "meter"


def test_config_check_budget_only_for_owners() -> None:
    _, client, auth = setup()
    owner = check(client, auth.google("boss", owner=True))
    budget = owner["budget"]
    assert budget is not None
    for key in ("ai_spent_usd", "ai_budget_usd", "compute_spent_usd", "compute_budget_usd", "day_cap_usd"):
        assert key in budget
    assert budget["horizon_end"] == "2027-01-26"
    assert check(client, auth.google("pleb"))["budget"] is None


def test_config_check_tells_only_the_owner_that_they_are_the_owner() -> None:
    """The phone ships no owner list (contracts §2.7): developer mode and owner alerts follow `is_owner`."""
    _, client, auth = setup()
    assert check(client, auth.google("boss", owner=True))["is_owner"] is True
    assert check(client, auth.google("pleb"))["is_owner"] is False
    assert check(client, auth.anonymous())["is_owner"] is False
    assert check(client)["is_owner"] is False


def test_config_check_rejects_a_bad_token() -> None:
    _, client, _ = setup()
    r = client.get("/v1/config-check", headers=bearer("forged"))
    assert (r.status_code, r.json()["error"]["code"]) == (401, "auth_invalid")


# ---- /v1/owner/fcm-token ---------------------------------------------------------------------------------


def test_owner_token_registration() -> None:
    deps, client, auth = setup()
    owner = auth.google("boss", owner=True)
    assert (
        client.post("/v1/owner/fcm-token", json={"token": "fcm-1"}, headers=bearer(owner)).status_code == 204
    )
    assert (
        client.post("/v1/owner/fcm-token", json={"token": "fcm-2"}, headers=bearer(owner)).status_code == 204
    )
    tokens = deps.store.get(OWNER_TOKENS_KEY)
    assert list(tokens) == ["fcm-2"]  # a re-registration replaces the device's previous token
    assert tokens["fcm-2"]["uid_hash"] == auth.principal(owner).uid_hash


def test_owner_token_is_owner_only_and_validated() -> None:
    _, client, auth = setup()
    r = client.post("/v1/owner/fcm-token", json={"token": "x"}, headers=bearer(auth.google("pleb")))
    assert (r.status_code, r.json()["error"]["code"]) == (403, "forbidden")
    r = client.post("/v1/owner/fcm-token", json={"token": "x"}, headers=bearer(auth.anonymous()))
    assert r.json()["error"]["code"] == "google_required"
    owner = auth.google("boss", owner=True)
    assert client.post("/v1/owner/fcm-token", json={"token": ""}, headers=bearer(owner)).status_code == 422
    assert client.post("/v1/owner/fcm-token", json={"token": "a b"}, headers=bearer(owner)).status_code == 422


# ---- health ----------------------------------------------------------------------------------------------


def test_healthz_and_readyz() -> None:
    deps, client, _ = setup()
    assert client.get("/healthz").json() == {"ok": True}
    r = client.get("/readyz")
    assert r.status_code == 200
    assert r.json() == {
        "ok": True,
        "checks": {
            "settings": True,
            "store": True,
            "firestore": True,
            "routes_ai": True,
            "routes_ondc": True,
        },
        "client_bucketed": False,  # the test client sends no X-Forwarded-For
    }
    fs_of(deps).fail = True
    r = client.get("/readyz")
    assert r.status_code == 503 and r.json()["checks"]["firestore"] is False
    fs_of(deps).fail = False
    deps.store.fail = True  # type: ignore[attr-defined]
    r = client.get("/readyz")
    assert r.status_code == 503 and r.json()["checks"]["store"] is False


def test_readyz_without_firestore_and_without_settings() -> None:
    # main needs Firestore (blocklist, audit, the spend meter's lost-Dict check): without it, never ready.
    _, client, _ = setup(firestore=None)
    r = client.get("/readyz")
    assert r.status_code == 503 and r.json()["checks"]["firestore"] is False
    # a dev deployment may run without it
    _, client, _ = setup(firestore=None, settings=make_settings(environment="dev"))
    assert client.get("/readyz").json() == {
        "ok": True,
        "checks": {"settings": True, "store": True, "routes_ai": True, "routes_ondc": True},
        "client_bucketed": False,
    }
    _, client, _ = setup(settings=make_settings(firebase_project_id=""))
    r = client.get("/readyz")
    assert r.status_code == 503 and r.json()["checks"]["settings"] is False


def test_readyz_tells_whether_the_request_got_a_per_network_bucket() -> None:
    _, client, _ = setup()
    behind_proxy = client.get("/readyz", headers={"X-Forwarded-For": "10.0.0.1, 49.36.10.10"})
    assert behind_proxy.json()["client_bucketed"] is True
    assert "49.36.10.10" not in behind_proxy.text  # a bool only, never the address
    private_hop = client.get("/readyz", headers={"X-Forwarded-For": "49.36.10.10, 10.0.0.1"})
    assert private_hop.json()["client_bucketed"] is False


def test_readyz_reports_an_endpoint_module_that_failed_to_load(monkeypatch: pytest.MonkeyPatch) -> None:
    real_import = app_module.importlib.import_module

    def fake_import(name: str) -> Any:
        if name == "ridemate_api.api.ondc":
            raise SyntaxError("broken module")
        return real_import(name)

    monkeypatch.setattr(app_module.importlib, "import_module", fake_import)
    client = TestClient(create_app(make_test_deps()))
    r = client.get("/readyz")
    assert r.status_code == 503
    assert r.json()["checks"]["routes_ondc"] is False and r.json()["checks"]["routes_ai"] is True
