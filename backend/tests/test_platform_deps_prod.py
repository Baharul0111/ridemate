"""deps_prod.build_production_deps: real wiring, lazy optional modules, graceful degradation, no network."""

from __future__ import annotations

import builtins
import io
import json
from typing import Any

import httpx
import pytest
from fastapi.testclient import TestClient

from ridemate_api import deps_prod
from ridemate_api.app import create_app
from ridemate_api.audit import AuditTrail
from ridemate_api.auth import FirebaseAuth
from ridemate_api.clock import FakeClock
from ridemate_api.deps_prod import DefaultRemoteConfig, NullMetrics, build_production_deps
from ridemate_api.limits import IpLimiter, QuotaManager
from ridemate_api.logging_setup import configure_logging
from ridemate_api.spend import SpendMeter
from ridemate_api.store import MemoryStore
from ridemate_api.testing import FakeFirestore, make_settings


@pytest.fixture(autouse=True)
def no_network(monkeypatch: pytest.MonkeyPatch) -> None:
    def refuse(*args: Any, **kwargs: Any) -> Any:
        raise AssertionError("network access in a unit test")

    monkeypatch.setattr(httpx.Client, "send", refuse)
    monkeypatch.setattr(httpx.AsyncClient, "send", refuse)


def test_builds_real_platform_components_without_cloud_credentials() -> None:
    deps = build_production_deps(make_settings(), store=MemoryStore(), clock=FakeClock())
    assert isinstance(deps.auth, FirebaseAuth)
    assert isinstance(deps.ip_limiter, IpLimiter)
    assert isinstance(deps.quotas, QuotaManager)
    assert isinstance(deps.spend, SpendMeter)
    assert deps.firestore is None  # no service-account key: AI paths keep working on Dict quotas
    assert deps.router is None  # no model endpoint configured
    assert deps.ondc is None  # ONDC_MODE=off
    assert deps.audit is None  # nowhere to write audit records, so none are kept in the hot store
    client = TestClient(create_app(deps))
    assert client.get("/healthz").json() == {"ok": True}
    # main without Firestore is never ready: no blocklist, no audit, and the meter can't verify its history
    ready = client.get("/readyz")
    assert ready.status_code == 503 and ready.json()["checks"]["firestore"] is False
    body = client.get("/v1/config-check").json()
    assert body["reason"] == "signed_out" and body["min_supported_version"] == 1


def test_without_firestore_a_missing_rollup_fails_closed_instead_of_restarting_the_meter() -> None:
    from ridemate_api.interfaces import ApiError, Purpose

    store = MemoryStore()
    deps = build_production_deps(make_settings(), store=store, clock=FakeClock())
    assert deps.firestore is None
    with pytest.raises(ApiError) as err:
        deps.spend.reserve(purpose=Purpose.READ, max_usd=0.01, principal=None, request_id="r")
    assert (err.value.status, err.value.code) == (503, "meter_unavailable")
    assert deps.spend.ai_status(None).reason == "meter"
    assert store.get("spend:rollup") is None  # nothing written that would hide a lost history later


class _FakeModalDicts:
    """Stands in for ModalDictStore: one MemoryStore per (name, environment)."""

    def __init__(self) -> None:
        self.stores: dict[tuple[str, str | None], MemoryStore] = {}

    def __call__(self, name: str, environment_name: str | None = None) -> MemoryStore:
        return self.stores.setdefault((name, environment_name), MemoryStore())


def test_a_dev_deployment_spends_under_mains_meter_and_its_dev_sub_budget(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    from ridemate_api.interfaces import Principal, Purpose
    from ridemate_api.settings import SUB_BUDGET_DEV_USD

    dicts = _FakeModalDicts()
    monkeypatch.setattr(deps_prod, "ModalDictStore", dicts)
    main = dicts("ridemate-hot", "main")
    main.put("spend:rollup", {"frozen": [], "totals": {}})  # main's meter already exists
    dev = build_production_deps(make_settings(environment="dev"), clock=FakeClock())
    assert dev.store is dicts.stores[("ridemate-hot", "dev")]
    user = Principal(uid="u", provider="google.com", sub_hmac="h-u")
    r = dev.spend.reserve(purpose=Purpose.READ, max_usd=0.25, principal=user, request_id="r1")
    dev.spend.keep(r)
    assert r.budgets == ("ai", "dev")  # never the public's paced app budget
    spend_keys = [k for k in main.keys_with_prefix("spend:") if k != "spend:rollup"]
    assert spend_keys and not dicts.stores[("ridemate-hot", "dev")].keys_with_prefix("spend:")
    # main's own meter (the one the dispatcher watches) counts it against the one AI budget
    main_deps = build_production_deps(make_settings(), clock=FakeClock())
    summary = main_deps.spend.summary()
    assert summary["ai_spent_usd"] == pytest.approx(0.25)
    assert summary["sub_budgets"]["dev"] == {
        "spent_usd": pytest.approx(0.25),
        "budget_usd": SUB_BUDGET_DEV_USD,
    }
    assert summary["day_spent_usd"] == 0  # the public's day cap is untouched
    # quotas and per-network buckets are shared with main too
    assert dev.quotas._store is main and dev.ip_limiter._store is main  # type: ignore[attr-defined]


def test_missing_hmac_key_fails_startup() -> None:
    with pytest.raises(ValueError):
        build_production_deps(make_settings(hmac_keys=()), store=MemoryStore(), clock=FakeClock())


def test_missing_optional_modules_degrade_to_defaults(monkeypatch: pytest.MonkeyPatch) -> None:
    real_import = builtins.__import__
    blocked = {"firestore", "remote_config", "metrics", "router", "demo_cache", "ondc.bap"}

    def fake_import(
        name: str, globals: Any = None, locals: Any = None, fromlist: Any = (), level: int = 0
    ) -> Any:
        if level == 1 and (name in blocked or (not name and set(fromlist or ()) & blocked)):
            raise ModuleNotFoundError(f"No module named {name!r}", name=f"ridemate_api.{name}")
        return real_import(name, globals, locals, fromlist, level)

    monkeypatch.setattr(builtins, "__import__", fake_import)
    deps = build_production_deps(make_settings(), store=MemoryStore(), clock=FakeClock())
    assert isinstance(deps.remote_config, DefaultRemoteConfig)
    assert isinstance(deps.metrics, NullMetrics)
    assert deps.firestore is None and deps.router is None and deps.demo_cache is None and deps.ondc is None
    assert deps.remote_config.get_bool("ai_reader_enabled", True) is True
    assert (
        deps.remote_config.get_json("judge_allowlist", {}) == {}
    )  # never a Remote Config key (contracts §2.7)


def test_a_broken_optional_module_is_contained(monkeypatch: pytest.MonkeyPatch) -> None:
    def broken(*args: Any, **kwargs: Any) -> Any:
        raise RuntimeError("boom")

    monkeypatch.setattr(deps_prod, "_demo_cache", broken)
    monkeypatch.setattr(deps_prod, "_metrics", broken)
    deps = build_production_deps(make_settings(), store=MemoryStore(), clock=FakeClock())
    assert deps.demo_cache is None and isinstance(deps.metrics, NullMetrics)


def test_the_audit_trail_is_wired_when_firestore_is(monkeypatch: pytest.MonkeyPatch) -> None:
    fs = FakeFirestore()
    monkeypatch.setattr(deps_prod, "_firestore", lambda *a: fs)
    deps = build_production_deps(make_settings(), store=MemoryStore(), clock=FakeClock())
    assert deps.firestore is fs and isinstance(deps.audit, AuditTrail)


def _degradation_lines(monkeypatch: pytest.MonkeyPatch, *, firestore_ok: bool) -> list[str]:
    def broken(*args: Any, **kwargs: Any) -> Any:
        raise RuntimeError("boom")

    builders = ["_metrics", "_remote_config", "_router", "_demo_cache", "_ondc", "_audit"]
    for name in builders:
        monkeypatch.setattr(deps_prod, name, broken)
    monkeypatch.setattr(deps_prod, "_firestore", (lambda *a: FakeFirestore()) if firestore_ok else broken)
    buf = io.StringIO()
    configure_logging(stream=buf)
    try:
        build_production_deps(make_settings(ondc_mode="mock"), store=MemoryStore(), clock=FakeClock())
    finally:
        configure_logging()
    lines = [json.loads(line) for line in buf.getvalue().splitlines()]
    return [line["msg"] for line in lines if line["logger"] == "ridemate_api.deps_prod"]


def test_degradation_logs_keep_the_feature_label_through_the_scrubber(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    messages = _degradation_lines(monkeypatch, firestore_ok=False) + _degradation_lines(
        monkeypatch, firestore_ok=True
    )
    labels = {m.split(" ", 1)[0] for m in messages}
    assert labels == {"metrics", "firestore", "remote_config", "router", "demo_cache", "ondc", "audit"}
    assert all(m.endswith("unavailable: RuntimeError") for m in messages)
    assert not any("[dropped]" in m for m in messages)


def test_a_missing_module_is_named_in_the_log(monkeypatch: pytest.MonkeyPatch) -> None:
    def missing(*args: Any, **kwargs: Any) -> Any:
        raise ModuleNotFoundError("No module named 'ridemate_api.demo_cache'", name="ridemate_api.demo_cache")

    monkeypatch.setattr(deps_prod, "_demo_cache", missing)
    buf = io.StringIO()
    configure_logging(stream=buf)
    try:
        build_production_deps(make_settings(), store=MemoryStore(), clock=FakeClock())
    finally:
        configure_logging()
    messages = [json.loads(line)["msg"] for line in buf.getvalue().splitlines()]
    assert "demo_cache unavailable: module ridemate_api.demo_cache missing" in messages


def test_router_is_built_when_the_model_endpoint_is_configured() -> None:
    settings = make_settings(
        model_base_url="https://example.modal.run", model_token_id="wk-1", model_token_secret="ws-2"
    )
    deps = build_production_deps(settings, store=MemoryStore(), clock=FakeClock())
    assert deps.router is not None
    assert deps.router.unavailable_reason() is None


def test_mock_ondc_mode_builds_the_demo_buyer() -> None:
    deps = build_production_deps(make_settings(ondc_mode="mock"), store=MemoryStore(), clock=FakeClock())
    assert deps.ondc is not None
