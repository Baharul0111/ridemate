"""deps_prod.build_production_deps: real wiring, lazy optional modules, graceful degradation, no network."""

from __future__ import annotations

import builtins
from typing import Any

import httpx
import pytest
from fastapi.testclient import TestClient

from ridemate_api import deps_prod
from ridemate_api.app import create_app
from ridemate_api.auth import FirebaseAuth
from ridemate_api.clock import FakeClock
from ridemate_api.deps_prod import DefaultRemoteConfig, NullMetrics, build_production_deps
from ridemate_api.limits import IpLimiter, QuotaManager
from ridemate_api.spend import SpendMeter
from ridemate_api.store import MemoryStore
from ridemate_api.testing import make_settings


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
    client = TestClient(create_app(deps))
    assert client.get("/healthz").json() == {"ok": True}
    assert client.get("/readyz").json()["ok"] is True
    body = client.get("/v1/config-check").json()
    assert body["reason"] == "signed_out" and body["min_supported_version"] == 1


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
    assert deps.remote_config.get_json("judge_allowlist", {}) == {}


def test_a_broken_optional_module_is_contained(monkeypatch: pytest.MonkeyPatch) -> None:
    def broken(*args: Any, **kwargs: Any) -> Any:
        raise RuntimeError("boom")

    monkeypatch.setattr(deps_prod, "_demo_cache", broken)
    monkeypatch.setattr(deps_prod, "_metrics", broken)
    deps = build_production_deps(make_settings(), store=MemoryStore(), clock=FakeClock())
    assert deps.demo_cache is None and isinstance(deps.metrics, NullMetrics)


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
