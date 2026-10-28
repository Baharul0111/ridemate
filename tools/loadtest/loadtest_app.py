"""The ridemate-api app wired for the local load test (architecture §4.12): the real FastAPI app, endpoints,
validation, quota manager, all-spend meter, demo cache and provider router, with three substitutions only:

- the model provider is `ridemate_api.stub_provider.StubProvider` (no network; refuses to run in Modal `main`),
- auth accepts exactly `Authorization: Bearer loadtest` and makes one judge-tier Google principal per
  `X-Session-Id` (each k6 session is its own user, as on real phones; no Firebase call),
- the per-IP limiter is open, because every virtual user shares 127.0.0.1 (real users each have their own IP).

Hot state lives in a `MemoryStore`, Remote Config comes from the bundled defaults in `config/remote_config/`,
Firestore and ONDC are off, and the model endpoint credentials are blanked so nothing can reach a paid service.

    uvicorn --factory loadtest_app:build_app --app-dir tools/loadtest      (run_local.sh does this)
"""

from __future__ import annotations

import dataclasses
import hashlib
import os
import re
from typing import Any

from fastapi import Request
from ridemate_api.clock import SystemClock
from ridemate_api.deps import Deps
from ridemate_api.interfaces import ApiError, Principal
from ridemate_api.settings import Settings
from ridemate_api.store import MemoryStore
from ridemate_api.stub_provider import build_stub_providers, ensure_stub_allowed

BEARER = "Bearer loadtest"
_SESSION_RE = re.compile(r"^[A-Za-z0-9-]{8,64}$")


class LoadTestAuth:
    """`interfaces.Auth` for the load test: the fixed bearer `loadtest`, one principal per session id."""

    async def verify(self, request: Request, *, require_google: bool) -> Principal:
        header = request.headers.get("authorization")
        if not header:
            raise ApiError(401, "auth_required")
        if header != BEARER:
            raise ApiError(401, "auth_invalid")
        session = request.headers.get("x-session-id", "")
        uid = f"lt-{session}" if _SESSION_RE.match(session) else "lt-shared"
        return Principal(
            uid=uid,
            provider="google.com",
            sub_hmac=hashlib.sha256(uid.encode()).hexdigest(),
            email_verified=True,
            is_judge=True,  # judge-tier fair share: 20 reads / 40 parses per user per day
        )

    async def check_not_revoked(self, principal: Principal) -> None:
        return None


class OpenIpLimiter:
    def check(self, request: Request) -> None:
        return None


def loadtest_settings() -> Settings:
    """Environment settings with the model endpoint, Firebase and owner fields blanked (no live calls)."""
    base = Settings.from_env()
    return dataclasses.replace(
        base,
        model_base_url="",
        model_token_id="",
        model_token_secret="",
        firebase_project_id="",
        firebase_web_api_key="",
        owner_emails=(),
        healthchecks_heartbeat_url="",
        healthchecks_alert_url="",
        ondc_mode="off",
        web_sa_json="",
        ops_sa_json="",
        owner_fcm_token="",
        hmac_keys=base.hmac_keys or ("loadtest-only-hmac-key",),
    )


def build_deps() -> Deps:
    """ADAPTER: the one place that knows how the backend's components are constructed. If a constructor
    changes, only this function needs updating."""
    from ridemate_api.demo_cache import DemoCache
    from ridemate_api.limits import QuotaManager
    from ridemate_api.metrics import DictMetrics
    from ridemate_api.remote_config import RemoteConfigRest, default_config_dir
    from ridemate_api.router import ProviderRouter
    from ridemate_api.spend import SpendMeter

    settings = loadtest_settings()
    ensure_stub_allowed(settings)  # RIDEMATE_STUB_PROVIDER=1 and a non-main Modal environment, or refuse
    clock = SystemClock()
    store = MemoryStore()
    remote_config = RemoteConfigRest(
        project_id="", tokens=None, clock=clock, defaults_dir=default_config_dir()
    )
    metrics = DictMetrics(store, clock, shard="loadtest")
    quotas = QuotaManager(store, clock)
    spend = SpendMeter(
        store=store,
        clock=clock,
        settings=settings,
        remote_config=remote_config,
        tiers=quotas,
        shard="loadtest",
    )
    router = ProviderRouter(
        providers=build_stub_providers(settings),
        spend=spend,
        remote_config=remote_config,
        metrics=metrics,
        clock=clock,
    )
    return Deps(
        settings=settings,
        clock=clock,
        store=store,
        auth=LoadTestAuth(),
        ip_limiter=OpenIpLimiter(),
        quotas=quotas,
        spend=spend,
        firestore=None,
        remote_config=remote_config,
        metrics=metrics,
        router=router,
        demo_cache=DemoCache.from_manifest(store=store, metrics=metrics),
        ondc=None,
    )


def build_app() -> Any:
    """uvicorn factory: `create_app(build_deps())`."""
    from ridemate_api.app import create_app

    return create_app(build_deps())


def check() -> None:
    """Fail fast (non-zero exit with the reason) when the backend app factory or a component is missing."""
    try:
        from ridemate_api.app import create_app  # noqa: F401
    except ImportError as e:
        raise SystemExit(f"backend app factory not importable yet: {e}") from None
    build_deps()


if __name__ == "__main__":
    check()
    print(f"ok: stub provider allowed in Modal environment {os.environ.get('MODAL_ENVIRONMENT')!r}")
