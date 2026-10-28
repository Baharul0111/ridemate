"""Production wiring: `build_production_deps(settings)` → Deps for `create_app` (contracts §3).

The platform pieces (hot store, keyring, auth, limits, spend meter) are built here directly. The ops modules
(firestore, remote_config, metrics) and the AI/ONDC modules (router, demo_cache, ondc) are imported lazily and
each import is guarded: a missing or broken module degrades that feature (no Firestore → AI keeps working on
Dict quotas; no router → AI reports unavailable) instead of stopping the server. A missing HMAC key is the
exception: quotas can't be keyed without it, so startup fails loudly.
"""

from __future__ import annotations

import logging
from collections.abc import Callable
from typing import Any

from .auth import FirebaseAuth
from .clock import Clock, SystemClock
from .crypto import HmacKeyring
from .deps import Deps
from .interfaces import Firestore, Metrics, RemoteConfig
from .limits import IpLimiter, QuotaManager
from .settings import Settings
from .spend import SpendMeter
from .store import HotStore, ModalDictStore

log = logging.getLogger(__name__)


class DefaultRemoteConfig:
    """Used only when remote_config.py can't be imported: every key answers its in-code default."""

    def get_bool(self, key: str, default: bool) -> bool:
        return default

    def get_int(self, key: str, default: int) -> int:
        return default

    def get_str(self, key: str, default: str) -> str:
        return default

    def get_json(self, key: str, default: Any) -> Any:
        return default


class NullMetrics:
    def incr(self, name: str, n: int = 1, **labels: str) -> None:
        return None

    def observe_ms(self, name: str, value_ms: float, **labels: str) -> None:
        return None


def _optional[T](what: str, build: Callable[[], T]) -> T | None:
    try:
        return build()
    except ImportError as e:
        log.warning("%s unavailable: module %s missing", what, getattr(e, "name", "?"))
    except Exception as e:  # noqa: BLE001 - a broken optional module must not stop the platform serving
        log.error("%s unavailable: %s", what, type(e).__name__)
    return None


def build_production_deps(
    settings: Settings | None = None, *, store: HotStore | None = None, clock: Clock | None = None
) -> Deps:
    settings = settings or Settings.from_env()
    clock = clock or SystemClock()
    store = (
        store
        if store is not None
        else ModalDictStore(settings.hot_dict_name, environment_name=settings.environment)
    )
    keyring = HmacKeyring(settings.hmac_keys, store=store, clock=clock)

    metrics = _optional("metrics", lambda: _metrics(store, clock)) or NullMetrics()
    firestore = _optional("firestore", lambda: _firestore(settings, store, clock, metrics))
    remote_config = (
        _optional("remote config", lambda: _remote_config(settings, clock)) or DefaultRemoteConfig()
    )

    quotas = QuotaManager(store, clock, firestore=firestore)
    spend = SpendMeter(
        store=store,
        clock=clock,
        settings=settings,
        remote_config=remote_config,
        tiers=quotas,
        firestore=firestore,
    )
    auth = FirebaseAuth(
        settings=settings, keyring=keyring, clock=clock, remote_config=remote_config, firestore=firestore
    )
    deps = Deps(
        settings=settings,
        clock=clock,
        store=store,
        auth=auth,
        ip_limiter=IpLimiter(store, clock, keyring),
        quotas=quotas,
        spend=spend,
        firestore=firestore,
        remote_config=remote_config,
        metrics=metrics,
    )
    deps.router = _optional("model router", lambda: _router(settings, spend, remote_config, metrics, clock))
    deps.demo_cache = _optional("demo cache", lambda: _demo_cache(store, metrics))
    deps.ondc = _optional("ondc buyer", lambda: _ondc(settings, firestore, auth, clock, metrics, store))
    return deps


def _firestore(settings: Settings, store: HotStore, clock: Clock, metrics: Metrics) -> Firestore | None:
    from .firestore import build_firestore
    from .metrics import firestore_event_hook

    return build_firestore(settings, store=store, clock=clock, on_event=firestore_event_hook(metrics))


def _remote_config(settings: Settings, clock: Clock) -> RemoteConfig:
    from .remote_config import build_remote_config

    return build_remote_config(settings, clock=clock)


def _metrics(store: HotStore, clock: Clock) -> Metrics:
    from .metrics import DictMetrics

    return DictMetrics(store, clock)


def _router(settings: Settings, spend: SpendMeter, rc: RemoteConfig, metrics: Metrics, clock: Clock) -> Any:
    from . import router as router_mod

    providers = router_mod.build_providers(settings)
    if not providers:
        log.warning("model endpoint not configured; AI reading is unavailable")
        return None
    return router_mod.ProviderRouter(
        providers=providers, spend=spend, remote_config=rc, metrics=metrics, clock=clock
    )


def _demo_cache(store: HotStore, metrics: Metrics) -> Any:
    from .demo_cache import DemoCache

    return DemoCache.from_manifest(store=store, metrics=metrics)


def _ondc(
    settings: Settings,
    firestore: Firestore | None,
    auth: FirebaseAuth,
    clock: Clock,
    metrics: Metrics,
    store: HotStore,
) -> Any:
    from .ondc.bap import build_buyer

    return build_buyer(settings, firestore=firestore, auth=auth, clock=clock, metrics=metrics, store=store)
