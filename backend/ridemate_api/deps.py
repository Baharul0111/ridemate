"""The dependency container. `create_app(deps)` stores it on `app.state.deps`; endpoints fetch it with `get_deps`.

Production wiring lives in `build_production_deps()` (deps_prod.py); tests build a Deps with fakes.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from fastapi import Request

from .clock import Clock
from .interfaces import Auth, Firestore, IpLimiter, Metrics, QuotaManager, RemoteConfig, SpendMeter
from .settings import Settings
from .store import HotStore


@dataclass
class Deps:
    settings: Settings
    clock: Clock
    store: HotStore
    auth: Auth
    ip_limiter: IpLimiter
    quotas: QuotaManager
    spend: SpendMeter
    firestore: Firestore | None  # None when no service account is configured (AI paths keep working)
    remote_config: RemoteConfig
    metrics: Metrics
    router: Any = None  # router.ProviderRouter
    demo_cache: Any = None  # demo_cache.DemoCache
    ondc: Any = None  # ondc.bap.OndcBuyer


def get_deps(request: Request) -> Deps:
    return request.app.state.deps
