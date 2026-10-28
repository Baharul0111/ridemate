"""Server-side read of the Firebase Remote Config client template (architecture §4.2 "Config", contracts §2).

The server reads the same template the phones get: kill switches (`ai_reader_enabled`, per-app readers,
`ondc_enabled`), `model_route`, `cap_profile`, budget horizon and so on. It is fetched with the web
service account (scope firebase.remoteconfig, role Remote Config Viewer) and cached for 60 s; only one caller
refreshes at a time while the others keep using the cached values.

Lookups never fail a request. Order of precedence: the live template → the last good template → the bundled
defaults in `config/remote_config/*.json` → the built-in defaults below → the caller's default.
"""

from __future__ import annotations

import json
import logging
import os
import threading
from collections.abc import Mapping
from pathlib import Path
from typing import Any

import httpx

from .clock import Clock
from .firestore import ServiceAccountTokens, TokenSource
from .settings import Settings

log = logging.getLogger(__name__)

REMOTE_CONFIG_SCOPE = "https://www.googleapis.com/auth/firebase.remoteconfig"
TEMPLATE_URL = "https://firebaseremoteconfig.googleapis.com/v1/projects/{project}/remoteConfig"
CACHE_S = 60.0
FETCH_TIMEOUT_S = 3.0

# contracts §2.7 (scalar keys) plus the JSON keys the server itself reads.
BUILTIN_DEFAULTS: Mapping[str, Any] = {
    "ai_reader_enabled": True,
    "reader_enabled_uber": True,
    "reader_enabled_ola": True,
    "reader_enabled_rapido": True,
    "ondc_enabled": True,
    "demo_enabled": True,
    "model_route": {"schema": "1.0", "read": "modal_spark_open", "parse": "modal_spark_open"},
    "latest_version": {
        "schema": "1.0",
        "version_code": 1,
        "version_name": "1.0.0-hackathon",
        "url": "https://github.com/Baharul0111/ridemate/releases/latest",
        "sha256": "",
    },
    "min_supported_version": 1,
    "cap_profile": "normal",
    "credit_expiry_date": "unknown",
    "budget_horizon_days": 90,
    "budget_horizon_start": "2026-10-28",
    "api_base_url": "",
    "judge_allowlist": {"schema": "1.0", "sha256_emails": []},
    "owner_emails_sha256": [],
}
# JSON-valued keys whose bundled default file holds exactly that key's payload.
JSON_KEYS: frozenset[str] = frozenset(
    {
        "notification_templates",
        "fare_parsers",
        "sanity_bands",
        "cancel_cards",
        "fee_rules",
        "launch_templates",
        "model_route",
        "latest_version",
        "judge_allowlist",
        "owner_emails_sha256",
    }
)

_TRUE = frozenset({"1", "true", "t", "yes", "y", "on"})
_FALSE = frozenset({"0", "false", "f", "no", "n", "off"})


def _to_raw(value: Any) -> str:
    """Values are kept as Remote Config keeps them: strings."""
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, str):
        return value
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def parse_template(template: Mapping[str, Any]) -> dict[str, str]:
    """Each parameter's default value, including parameters inside groups. Conditional values target phones
    (rollouts, app versions); the server uses the default. `useInAppDefault` leaves the key to our defaults."""
    params: dict[str, Any] = dict(template.get("parameters") or {})
    for group in (template.get("parameterGroups") or {}).values():
        if isinstance(group, Mapping):
            params.update(group.get("parameters") or {})
    values: dict[str, str] = {}
    for key, param in params.items():
        default = param.get("defaultValue") if isinstance(param, Mapping) else None
        if isinstance(default, Mapping) and "value" in default and not default.get("useInAppDefault"):
            values[str(key)] = str(default["value"])
    return values


def default_config_dir() -> Path | None:
    configured = os.environ.get("RIDEMATE_CONFIG_DIR")
    if configured:
        return Path(configured)
    here = Path(__file__).resolve()
    for root in (here.parents[1], here.parents[2] if len(here.parents) > 2 else here.parents[1]):
        candidate = root / "config" / "remote_config"
        if candidate.is_dir():
            return candidate
    return None


def load_bundled_defaults(directory: Path | None) -> dict[str, str]:
    """`config/remote_config/<key>.json` (config/README.md): a scalar key's file holds `{"value": <scalar>}`, a
    JSON key's file holds the payload itself. Also accepted: a file holding a template (`parameters`), and an
    object without `schema` that maps several keys to values."""
    values: dict[str, str] = {}
    if directory is None or not directory.is_dir():
        return values
    for path in sorted(directory.glob("*.json")):
        try:
            payload = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            log.warning("remote config: bundled default %s is unreadable; skipped", path.name)
            continue
        key = path.stem
        if isinstance(payload, Mapping) and set(payload) == {"value"}:
            values[key] = _to_raw(payload["value"])
        elif key in JSON_KEYS or key in BUILTIN_DEFAULTS:
            values[key] = _to_raw(payload)
        elif isinstance(payload, Mapping) and "parameters" in payload:
            values.update(parse_template(payload))
        elif isinstance(payload, Mapping) and "schema" not in payload:
            values.update({str(k): _to_raw(v) for k, v in payload.items()})
        else:
            values[key] = _to_raw(payload)
    return values


class RemoteConfigRest:
    """`interfaces.RemoteConfig` over the Remote Config REST API."""

    def __init__(
        self,
        *,
        project_id: str,
        tokens: TokenSource | None,
        clock: Clock,
        http_client: httpx.Client | None = None,
        defaults_dir: Path | None = None,
        cache_s: float = CACHE_S,
        timeout_s: float = FETCH_TIMEOUT_S,
        background_refresh: bool = True,
    ) -> None:
        self._project = project_id
        self._tokens = tokens
        self._clock = clock
        self._client = http_client or httpx.Client()
        self._cache_s = cache_s
        self._timeout_s = timeout_s
        self._defaults = {k: _to_raw(v) for k, v in BUILTIN_DEFAULTS.items()}
        self._defaults.update(load_bundled_defaults(defaults_dir))
        self._live: dict[str, str] | None = None
        self._fetched_at = float("-inf")
        self._refresh_lock = threading.Lock()
        self._background = background_refresh
        self._bg_lock = threading.Lock()
        self._bg_running = False
        self.last_error: str | None = None

    # ---- interfaces.RemoteConfig ---------------------------------------------------------------------------

    # A live value that doesn't parse as the requested type falls through to the bundled/built-in default, so a
    # typo in the console (`ai_reader_enabled = "flase"`) can't silently switch a kill switch to the caller's
    # default.

    def get_bool(self, key: str, default: bool) -> bool:
        for raw in self._candidates(key):
            lowered = raw.strip().lower()
            if lowered in _TRUE:
                return True
            if lowered in _FALSE:
                return False
        return default

    def get_int(self, key: str, default: int) -> int:
        for raw in self._candidates(key):
            try:
                return int(raw.strip())
            except ValueError:
                try:
                    number = float(raw)
                except ValueError:
                    continue
                if number.is_integer():
                    return int(number)
        return default

    def get_str(self, key: str, default: str) -> str:
        for raw in self._candidates(key):
            return raw
        return default

    def get_json(self, key: str, default: Any) -> Any:
        for raw in self._candidates(key):
            try:
                return json.loads(raw)
            except ValueError:
                continue
        return default

    # ---- status (readyz, developer mode) -------------------------------------------------------------------

    @property
    def live(self) -> bool:
        """True once a template has been fetched successfully."""
        return self._live is not None

    def refresh(self) -> bool:
        """Fetch now (single flight). False when another caller is already fetching or the fetch failed."""
        if not self._refresh_lock.acquire(blocking=False):
            return False
        try:
            values = self._fetch()
        except Exception as e:  # noqa: BLE001 - lookups never fail a request: any fetch error keeps what we have
            self.last_error = type(e).__name__
            log.warning("remote config: fetch failed (%s); using cached or default values", self.last_error)
            self._fetched_at = self._clock.monotonic()  # back off for one cache period
            return False
        finally:
            self._refresh_lock.release()
        self._live = values
        self._fetched_at = self._clock.monotonic()
        self.last_error = None
        return True

    # ---- internals -----------------------------------------------------------------------------------------

    @property
    def configured(self) -> bool:
        return self._tokens is not None and bool(self._project)

    def _candidates(self, key: str) -> list[str]:
        """The live (or last good) value, then the bundled/built-in default, whichever exist."""
        if self.configured and self._clock.monotonic() - self._fetched_at >= self._cache_s:
            if self._fetched_at == float("-inf") or not self._background:
                # The first lookup waits (≤ 3 s) so kill switches hold from a container's first request.
                self.refresh()
            else:
                self._refresh_in_background()  # stale values serve meanwhile; requests never wait on a refresh
        out: list[str] = []
        live = self._live
        if live is not None and key in live:
            out.append(live[key])
        if key in self._defaults:
            out.append(self._defaults[key])
        return out

    def _refresh_in_background(self) -> None:
        with self._bg_lock:
            if self._bg_running:
                return
            self._bg_running = True

        def run() -> None:
            try:
                self.refresh()
            finally:
                with self._bg_lock:
                    self._bg_running = False

        threading.Thread(target=run, name="remote-config-refresh", daemon=True).start()

    def _fetch(self) -> dict[str, str]:
        if self._tokens is None:
            raise KeyError("remote config not configured")
        response = self._client.get(
            TEMPLATE_URL.format(project=self._project),
            headers={"Authorization": f"Bearer {self._tokens.token()}", "Accept-Encoding": "gzip"},
            timeout=self._timeout_s,
        )
        response.raise_for_status()
        template = response.json()
        if not isinstance(template, Mapping):
            raise TypeError("template is not an object")
        return parse_template(template)


def build_remote_config(
    settings: Settings,
    *,
    clock: Clock,
    sa_json: str | None = None,
    http_client: httpx.Client | None = None,
    defaults_dir: Path | None = None,
) -> RemoteConfigRest:
    """Production wiring: the web key by default (the dispatcher passes the ops key). Without a key or project
    the reader serves bundled/built-in defaults only."""
    client = http_client or httpx.Client()
    key = settings.web_sa_json if sa_json is None else sa_json
    tokens: TokenSource | None = None
    if key:
        try:
            tokens = ServiceAccountTokens(key, [REMOTE_CONFIG_SCOPE], http_client=client)
        except ValueError:
            log.error("remote config: service-account key is not valid JSON; serving defaults only")
    return RemoteConfigRest(
        project_id=settings.firebase_project_id,
        tokens=tokens,
        clock=clock,
        http_client=client,
        defaults_dir=defaults_dir if defaults_dir is not None else default_config_dir(),
    )
