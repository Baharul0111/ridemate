"""ridemate-api on Modal (architecture §4.2 backend header, §4.13, §4.18 m2/G9/C4).

Deploy with `./backend/deploy.sh` (or `modal deploy backend/modal_app.py --env main`). Every function setting
lives here: image, secrets, containers, concurrency, timeouts, the one cron. Importing this file has no side
effects beyond building Modal's lazy definitions, so tests can import it without Modal credentials.

- `web`: the FastAPI app. cpu 0.25 / 512 MiB, scale to zero (MIN_CONTAINERS=1 only for judging week, set in
  the deploying shell), at most 3 containers, 20 concurrent requests each. A background thread meters the
  container's uptime × cpu/memory into the spend meter every 60 s and at shutdown.
- `dispatcher`: the single 15-minute cron, defined only when deploying to the `main` environment (crons run only
  in main; Starter allows 5, RideMate uses 1). It holds the ops key (FCM sender) and the golden-set Volume.
- `fire_test_alerts`: run by hand (`modal run backend/modal_app.py::fire_test_alerts`) to prove alert delivery.
"""

from __future__ import annotations

import atexit
import logging
import os
import threading
import time
from collections.abc import Callable
from pathlib import Path
from typing import Any

import modal

APP_NAME = "ridemate-api"
# python:3.12-slim (OCI index, linux/amd64 included), pinned by digest from Docker Hub's registry API on
# 2026-10-28. Refresh monthly (C4): backend/scripts/base_image_digest.sh prints the current digest.
BASE_IMAGE = "python:3.12-slim@sha256:aa943ce411423c7582288f18707ff66eddcbaeb510502d747a72aeb34f30d159"

BACKEND_DIR = Path(__file__).resolve().parent
REPO_DIR = BACKEND_DIR.parent
REMOTE_ROOT = "/root"
GOLDEN_MOUNT = "/golden"
REQUIREMENTS_LOCK = BACKEND_DIR / "requirements.lock"
# The Template Doctor's own runtime dependencies (google-re2, jsonschema), exported with hashes from
# tools/template_doctor/uv.lock by deploy.sh; installed only into the dispatcher's image.
DOCTOR_REQUIREMENTS_LOCK = BACKEND_DIR / "requirements-doctor.lock"
TRV10_FETCH_SCRIPT = BACKEND_DIR / "scripts" / "fetch_trv10_examples.py"
TRV10_EXAMPLES_DIR = "/opt/ridemate/trv10_examples"
BUILD_ONLY_YAML = "pyyaml==6.0.2"

# The deploying shell's `modal deploy --env X` sets MODAL_ENVIRONMENT before this module is imported; the same
# value is passed to the containers as RIDEMATE_DEPLOY_ENV so their import defines the same functions.
DEPLOY_ENV = os.environ.get("RIDEMATE_DEPLOY_ENV") or os.environ.get("MODAL_ENVIRONMENT") or "main"
CRON_ENVIRONMENT = "main"

WEB_CPU = 0.25
WEB_MEMORY_MIB = 512
CRON_CPU = 0.25
CRON_MEMORY_MIB = 512
COMPUTE_HEARTBEAT_S = 60.0

WEB_OPTIONS: dict[str, Any] = {
    "min_containers": int(os.environ.get("MIN_CONTAINERS", "0")),
    "max_containers": 3,
    "timeout": 30,
    "cpu": WEB_CPU,
    "memory": WEB_MEMORY_MIB,
    "scaledown_window": 60,
}
WEB_MAX_INPUTS = 20
DISPATCHER_OPTIONS: dict[str, Any] = {
    "cpu": CRON_CPU,
    "memory": CRON_MEMORY_MIB,
    "timeout": 600,
    "max_containers": 1,  # never two dispatcher runs at once
    "scaledown_window": 10,
}
DISPATCHER_SCHEDULE = "*/15 * * * *"

# Secret names, not values (bandit B105 would read them as passwords).
SECRET_MODEL = "ridemate-model"  # nosec B105 - MODAL_SPARK_OPEN_URL, MODAL_PROXY_TOKEN_ID, MODAL_PROXY_TOKEN_SECRET
SECRET_CORE = "ridemate-core"  # nosec B105 - project ids, HMAC keys, owner emails, ONDC mode/keys, budget horizon
SECRET_WEB_SA = "ridemate-web-sa"  # nosec B105 - FIRESTORE_WEB_SA_JSON (Datastore user + Remote Config viewer)
SECRET_OPS_SA = "ridemate-ops-sa"  # nosec B105 - FIRESTORE_OPS_SA_JSON (Datastore user + FCM + Remote Config viewer)
SECRET_ALERTS = "ridemate-alerts"  # nosec B105 - HEALTHCHECKS_HEARTBEAT_URL, HEALTHCHECKS_ALERT_URL, OWNER_FCM_TOKEN
WEB_SECRETS = (SECRET_MODEL, SECRET_CORE, SECRET_WEB_SA)
OPS_SECRETS = (SECRET_MODEL, SECRET_CORE, SECRET_OPS_SA, SECRET_ALERTS)
GOLDEN_VOLUME = "ridemate-golden"

# Optional content copied next to the package when present locally: (repo-relative path, remote path).
WEB_CONTENT: tuple[tuple[str, str], ...] = (
    ("config/remote_config", f"{REMOTE_ROOT}/config/remote_config"),  # bundled Remote Config defaults
)
# The dispatcher also needs the probe's ground truth and the weekly drift run (Template Doctor + the config
# package it imports, and the schemas that package validates against).
OPS_CONTENT: tuple[tuple[str, str], ...] = (
    *WEB_CONTENT,
    ("eval/demo_labels.json", f"{REMOTE_ROOT}/eval/demo_labels.json"),  # demo manifest (probe ground truth)
    ("eval/recorded/channel_a", f"{REMOTE_ROOT}/eval/recorded/channel_a"),  # recorded Muse Spark Open outputs
    ("android/app/src/main/assets/demo", f"{REMOTE_ROOT}/android/app/src/main/assets/demo"),  # demo screens
    ("config/ridemate_config", f"{REMOTE_ROOT}/config/ridemate_config"),
    ("config/schemas", f"{REMOTE_ROOT}/config/schemas"),
    ("tools/template_doctor", f"{REMOTE_ROOT}/tools/template_doctor"),  # weekly drift run
)
# Local virtualenvs (macOS binaries, tens of MB), caches, tests and outputs never go into an image.
_IGNORE = [
    "**/__pycache__/**",
    "**/*.pyc",
    "**/.DS_Store",
    "**/.venv/**",
    "**/.pytest_cache/**",
    "**/.ruff_cache/**",
    "**/node_modules/**",
    "**/tests/**",
    "**/out/**",
]


def function_env(role: str) -> dict[str, str]:
    return {
        "RIDEMATE_DEPLOY_ENV": DEPLOY_ENV,
        "MODAL_ENVIRONMENT": DEPLOY_ENV,
        "RIDEMATE_ROLE": role,
        "RIDEMATE_ROOT": REMOTE_ROOT,
        "RIDEMATE_CONFIG_DIR": f"{REMOTE_ROOT}/config/remote_config",
    }


def build_image(role: str = "web") -> modal.Image:
    """The web image, or ('ops') the dispatcher's: the same layers plus the Template Doctor's dependencies and
    the probe/drift content. Build steps all come before the local files (Modal requires that order)."""
    image = modal.Image.from_registry(BASE_IMAGE)
    if REQUIREMENTS_LOCK.is_file():
        # Exact versions with hashes, exported from uv.lock (runtime dependencies only).
        image = image.uv_pip_install(requirements=[str(REQUIREMENTS_LOCK)], extra_options="--require-hashes")
    if TRV10_FETCH_SCRIPT.is_file():
        # The ONDC demo seller replays the official TRV10 examples, fetched at build time and never committed.
        # PyYAML is needed only for this step and removed again; a failed fetch only warns (built-in payloads).
        image = image.add_local_file(TRV10_FETCH_SCRIPT, "/opt/ridemate/fetch_trv10_examples.py", copy=True)
        image = image.run_commands(
            f"python -m pip install --no-cache-dir --disable-pip-version-check {BUILD_ONLY_YAML}"
            f" && python /opt/ridemate/fetch_trv10_examples.py --out {TRV10_EXAMPLES_DIR}"
            " ; python -m pip uninstall -y -q pyyaml"
        )
    if role == "ops" and DOCTOR_REQUIREMENTS_LOCK.is_file():
        image = image.uv_pip_install(
            requirements=[str(DOCTOR_REQUIREMENTS_LOCK)], extra_options="--require-hashes"
        )
    image = image.env({"PYTHONUNBUFFERED": "1", "PYTHONDONTWRITEBYTECODE": "1"})
    image = image.add_local_dir(BACKEND_DIR / "ridemate_api", f"{REMOTE_ROOT}/ridemate_api", ignore=_IGNORE)
    for relative, remote in OPS_CONTENT if role == "ops" else WEB_CONTENT:
        local = REPO_DIR / relative
        if local.is_dir():
            image = image.add_local_dir(local, remote, ignore=_IGNORE)
        elif local.is_file():
            image = image.add_local_file(local, remote)
    return image


class ComputeHeartbeat:
    """Meters this container's uptime × configured cpu/memory into the spend meter: every `interval_s` and once
    more at shutdown. Seconds that fail to record (meter unreadable) are carried to the next tick."""

    def __init__(
        self,
        record: Callable[..., Any],
        *,
        cpu_cores: float,
        memory_mib: float,
        source: str,
        interval_s: float = COMPUTE_HEARTBEAT_S,
        monotonic: Callable[[], float] = time.monotonic,
        on_tick: Callable[[], Any] | None = None,
    ) -> None:
        self._record = record
        self._cpu = cpu_cores
        self._memory = memory_mib
        self._source = source
        self._interval = interval_s
        self._monotonic = monotonic
        self._on_tick = on_tick
        self._last = monotonic()
        self._pending = 0.0
        self._lock = threading.Lock()
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None

    def tick(self) -> float:
        """Record the seconds since the last tick; returns the seconds recorded (0 when the meter failed)."""
        with self._lock:
            now = self._monotonic()
            self._pending += max(0.0, now - self._last)
            self._last = now
            seconds = self._pending
            if seconds <= 0:
                return 0.0
            try:
                self._record(
                    cpu_cores=self._cpu, memory_mib=self._memory, seconds=seconds, source=self._source
                )
            except Exception:  # noqa: BLE001 - keep the seconds for the next tick
                logging.getLogger(__name__).warning("compute heartbeat: meter unavailable; will retry")
                return 0.0
            self._pending = 0.0
        if self._on_tick is not None:
            try:
                self._on_tick()
            except Exception:
                logging.getLogger(__name__).debug("compute heartbeat: on_tick failed", exc_info=True)
        return seconds

    def start(self) -> None:
        if self._thread is not None:
            return
        self._thread = threading.Thread(target=self._loop, name="compute-heartbeat", daemon=True)
        self._thread.start()

    def stop(self) -> float:
        self._stop.set()
        if self._thread is not None:
            self._thread.join(timeout=5)
        return self.tick()

    def _loop(self) -> None:
        while not self._stop.wait(self._interval):
            self.tick()


def build_deps(role: str) -> Any:
    """Production dependencies (ridemate_api/deps_prod.py). Each function holds only its own service-account
    key: the dispatcher ('ops') has the ops key, which then serves every Firestore and Remote Config client."""
    from dataclasses import replace

    from ridemate_api.deps_prod import build_production_deps
    from ridemate_api.settings import Settings

    settings = Settings.from_env()
    if role == "ops":
        settings = replace(settings, web_sa_json=settings.ops_sa_json)
    return build_production_deps(settings)


def _start_heartbeat(deps: Any, *, cpu: float, memory: float, source: str) -> ComputeHeartbeat:
    flush = getattr(deps.metrics, "flush", None)
    heartbeat = ComputeHeartbeat(
        deps.spend.record_compute, cpu_cores=cpu, memory_mib=memory, source=source, on_tick=flush
    )
    heartbeat.start()
    atexit.register(heartbeat.stop)
    return heartbeat


def create_web_app() -> Any:
    from ridemate_api.app import create_app
    from ridemate_api.logging_setup import configure_logging

    configure_logging(env=DEPLOY_ENV)
    deps = build_deps("web")
    _prewarm_remote_config(deps)
    _start_heartbeat(deps, cpu=WEB_CPU, memory=WEB_MEMORY_MIB, source="web")
    return create_app(deps)


def _prewarm_remote_config(deps: Any) -> None:
    """Fetch the template once while the container starts, so kill switches hold from the first request and no
    request blocks the event loop on that first fetch (the reader's later refreshes run in the background)."""
    refresh = getattr(deps.remote_config, "refresh", None)
    if not callable(refresh):
        return
    try:
        refresh()
    except Exception:  # noqa: BLE001 - the reader falls back to its defaults; startup must not fail on this
        logging.getLogger(__name__).warning("remote config prewarm failed; serving defaults")


app = modal.App(APP_NAME)
image = build_image("web")
ops_image = build_image("ops")
golden = modal.Volume.from_name(GOLDEN_VOLUME, create_if_missing=True)


def _secrets(names: tuple[str, ...]) -> list[modal.Secret]:
    return [modal.Secret.from_name(name) for name in names]


@app.function(image=image, secrets=_secrets(WEB_SECRETS), env=function_env("web"), **WEB_OPTIONS)
@modal.concurrent(max_inputs=WEB_MAX_INPUTS)
@modal.asgi_app()
def web() -> Any:
    return create_web_app()


async def _dispatch() -> dict[str, Any]:
    from ridemate_api.cron import default_hooks, run_dispatcher
    from ridemate_api.logging_setup import configure_logging

    configure_logging(env=DEPLOY_ENV, job="dispatcher")
    deps = build_deps("ops")
    golden_dir = Path(GOLDEN_MOUNT)
    hooks = default_hooks(root=Path(REMOTE_ROOT), golden_dir=golden_dir if golden_dir.is_dir() else None)
    report = await run_dispatcher(deps, deps.clock.now_utc(), hooks=hooks)
    flush = getattr(deps.metrics, "flush", None)
    if callable(flush):
        flush()
    return report.as_dict()


if DEPLOY_ENV == CRON_ENVIRONMENT:

    @app.function(
        image=ops_image,
        schedule=modal.Cron(DISPATCHER_SCHEDULE),
        secrets=_secrets(OPS_SECRETS),
        volumes={GOLDEN_MOUNT: golden},
        env=function_env("ops"),
        **DISPATCHER_OPTIONS,
    )
    async def dispatcher() -> dict[str, Any]:
        return await _dispatch()


@app.function(
    image=ops_image,
    secrets=_secrets(OPS_SECRETS),
    env=function_env("ops"),
    cpu=CRON_CPU,
    memory=CRON_MEMORY_MIB,
    timeout=120,
    max_containers=1,
)
def fire_test_alerts() -> dict[str, Any]:
    """Send every alert once, labelled TEST, through healthchecks.io and FCM."""
    from ridemate_api import alerts
    from ridemate_api.logging_setup import configure_logging

    configure_logging(env=DEPLOY_ENV, job="test_fire")
    deps = build_deps("ops")
    started = time.monotonic()
    result = alerts.test_fire(alerts.build_delivery(deps.settings, deps.store))
    try:
        deps.spend.record_compute(
            cpu_cores=CRON_CPU,
            memory_mib=CRON_MEMORY_MIB,
            seconds=time.monotonic() - started + 10,
            source="test_fire",
        )
    except Exception:  # noqa: BLE001
        logging.getLogger(__name__).warning("test_fire: compute not recorded")
    return {"healthchecks": result.healthchecks, "pushes": result.pushes, "alerts": len(alerts.CATALOG)}
