"""modal_app.py: import-safe definitions, function shapes, cron only in main, pinned image, compute heartbeat."""

from __future__ import annotations

import importlib.util
import re
from pathlib import Path
from types import ModuleType, SimpleNamespace

import modal
import pytest

BACKEND = Path(__file__).resolve().parents[1]


def load(monkeypatch, deploy_env: str | None, name: str) -> ModuleType:
    if deploy_env is None:
        monkeypatch.delenv("RIDEMATE_DEPLOY_ENV", raising=False)
    else:
        monkeypatch.setenv("RIDEMATE_DEPLOY_ENV", deploy_env)
    spec = importlib.util.spec_from_file_location(name, BACKEND / "modal_app.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


@pytest.fixture
def main_app(monkeypatch):
    return load(monkeypatch, "main", "modal_app_main_test")


def test_import_is_side_effect_free_and_defines_the_web_function(main_app):
    assert main_app.app.name == "ridemate-api"
    assert isinstance(main_app.web, modal.Function)
    assert isinstance(main_app.fire_test_alerts, modal.Function)


def test_web_function_shape(main_app, monkeypatch):
    assert main_app.WEB_OPTIONS == {
        "min_containers": 0,
        "max_containers": 3,
        "timeout": 30,
        "cpu": 0.25,
        "memory": 512,
        "scaledown_window": 60,
    }
    assert main_app.WEB_MAX_INPUTS == 20
    assert main_app.WEB_SECRETS == ("ridemate-model", "ridemate-core", "ridemate-web-sa")
    monkeypatch.setenv("MIN_CONTAINERS", "1")  # judging week, set in the deploying shell
    judging = load(monkeypatch, "main", "modal_app_judging_test")
    assert judging.WEB_OPTIONS["min_containers"] == 1


def test_the_dispatcher_cron_exists_only_in_main(main_app, monkeypatch):
    assert isinstance(main_app.dispatcher, modal.Function)
    assert main_app.DISPATCHER_SCHEDULE == "*/15 * * * *"
    assert main_app.OPS_SECRETS == ("ridemate-model", "ridemate-core", "ridemate-ops-sa", "ridemate-alerts")
    assert main_app.DISPATCHER_OPTIONS["timeout"] == 600
    assert main_app.DISPATCHER_OPTIONS["cpu"] == 0.25 and main_app.DISPATCHER_OPTIONS["memory"] == 512
    assert main_app.DISPATCHER_OPTIONS["max_containers"] == 1
    dev = load(monkeypatch, "dev", "modal_app_dev_test")
    assert not hasattr(dev, "dispatcher")
    assert isinstance(dev.web, modal.Function)
    monkeypatch.setenv("MODAL_ENVIRONMENT", "dev")  # what `modal deploy --env dev` sets
    from_cli = load(monkeypatch, None, "modal_app_cli_dev_test")
    assert from_cli.DEPLOY_ENV == "dev" and not hasattr(from_cli, "dispatcher")


def test_containers_learn_the_environment_they_were_deployed_to(main_app):
    env = main_app.function_env("web")
    assert env["RIDEMATE_DEPLOY_ENV"] == env["MODAL_ENVIRONMENT"] == "main"
    assert env["RIDEMATE_CONFIG_DIR"] == "/root/config/remote_config"


def test_the_base_image_is_pinned_by_digest(main_app):
    assert re.fullmatch(r"python:3\.12-slim@sha256:[0-9a-f]{64}", main_app.BASE_IMAGE)


def test_requirements_lock_pins_runtime_dependencies_with_hashes():
    text = (BACKEND / "requirements.lock").read_text()
    names = {m.group(1).lower() for m in re.finditer(r"^([A-Za-z0-9_.\-]+)==", text, re.MULTILINE)}
    assert {"fastapi", "httpx", "google-auth", "openai", "pynacl", "cryptography"} <= names
    assert not names & {"modal", "pytest", "respx", "ruff", "bandit", "uvicorn"}
    blocks = re.split(r"\n(?=[A-Za-z0-9_.\-]+==)", text.split("\n", 2)[2])
    assert all("--hash=sha256:" in block for block in blocks if "==" in block)


REPO = BACKEND.parent


def lock_packages(path: Path) -> dict[str, str]:
    text = path.read_text()
    return {
        m.group(1).lower(): m.group(2) for m in re.finditer(r"^([A-Za-z0-9_.\-]+)==(\S+)", text, re.MULTILINE)
    }


def test_the_dispatcher_image_carries_the_drift_run_and_the_web_image_does_not(main_app):
    web = {remote for _, remote in main_app.WEB_CONTENT}
    ops = {remote for _, remote in main_app.OPS_CONTENT}
    assert web == {"/root/config/remote_config"}
    assert {
        "/root/tools/template_doctor",
        "/root/config/ridemate_config",  # the Doctor imports it (doctorlib.paths adds /root/config)
        "/root/config/schemas",
        "/root/eval/demo_labels.json",
    } <= ops
    assert web <= ops


def test_local_virtualenvs_and_caches_never_go_into_an_image(main_app):
    from modal.file_pattern_matcher import FilePatternMatcher

    ignored = FilePatternMatcher(*main_app._IGNORE)
    for path in [
        ".venv/lib/python3.12/site-packages/re2.so",
        ".pytest_cache/v/x",
        "tests/test_doctor.py",
        "out/report.json",
        "doctorlib/__pycache__/paths.cpython-312.pyc",
        "node_modules/x/index.js",
    ]:
        assert ignored(Path(path)), path
    for path in ["doctor.py", "doctorlib/paths.py", "__init__.py", "regex.py", "fare_parsers.schema.json"]:
        assert not ignored(Path(path)), path


def test_the_doctor_lock_pins_its_own_dependencies_with_hashes_only():
    doctor = lock_packages(BACKEND / "requirements-doctor.lock")
    runtime = lock_packages(BACKEND / "requirements.lock")
    assert {"google-re2", "jsonschema"} <= set(doctor)
    assert "ridemate-api" not in doctor and not set(doctor) & {"fastapi", "openai", "httpx", "pydantic"}
    for name in set(doctor) & set(runtime):  # shared pins must agree, or the second install would move them
        assert doctor[name] == runtime[name], name
    text = (BACKEND / "requirements-doctor.lock").read_text()
    blocks = re.split(r"\n(?=[A-Za-z0-9_.\-]+==)", text.split("\n", 2)[2])
    assert all("--hash=sha256:" in block for block in blocks if "==" in block)


# Import name → distribution name where they differ.
DISTRIBUTIONS = {
    "re2": "google-re2",
    "google": "google-auth",
    "nacl": "pynacl",
    "multipart": "python-multipart",
}
LOCAL_MODULES = {"ridemate_api", "ridemate_config", "doctorlib", "doctor", "tools", "template_doctor"}


def third_party_imports(root: Path) -> set[str]:
    import ast
    import sys

    found: set[str] = set()
    for path in root.rglob("*.py"):
        if {"tests", ".venv", "out"} & set(path.relative_to(root).parts):
            continue
        for node in ast.walk(ast.parse(path.read_text(encoding="utf-8"))):
            if isinstance(node, ast.Import):
                names = [alias.name for alias in node.names]
            elif isinstance(node, ast.ImportFrom) and node.level == 0 and node.module:
                names = [node.module]
            else:
                continue
            for name in names:
                top = name.split(".")[0]
                if top not in sys.stdlib_module_names and top not in LOCAL_MODULES and top != "__future__":
                    found.add(top)
    return found


def test_everything_the_drift_run_imports_is_installed_in_the_dispatcher_image():
    installed = set(lock_packages(BACKEND / "requirements.lock")) | set(
        lock_packages(BACKEND / "requirements-doctor.lock")
    )
    for root in (REPO / "tools" / "template_doctor", REPO / "config" / "ridemate_config"):
        if not root.is_dir():
            continue
        for module in third_party_imports(root):
            assert DISTRIBUTIONS.get(module, module.replace("_", "-")).lower() in installed, (
                root.name,
                module,
            )


def test_the_web_container_fetches_remote_config_before_serving(main_app):
    calls = []
    deps = SimpleNamespace(remote_config=SimpleNamespace(refresh=lambda: calls.append(1) or True))
    main_app._prewarm_remote_config(deps)
    assert calls == [1]

    def broken() -> bool:
        raise RuntimeError("unexpected")

    main_app._prewarm_remote_config(SimpleNamespace(remote_config=SimpleNamespace(refresh=broken)))
    no_refresh = SimpleNamespace(remote_config=object())  # deps_prod.DefaultRemoteConfig has no refresh()
    main_app._prewarm_remote_config(no_refresh)


def test_the_base_image_digest_script_reads_the_registry_without_docker(tmp_path):
    import os
    import subprocess

    script = BACKEND / "scripts" / "base_image_digest.sh"
    assert subprocess.run(["bash", "-n", str(script)], check=False).returncode == 0
    digest = "sha256:" + "ab" * 32
    fake_curl = tmp_path / "curl"
    fake_curl.write_text(
        "#!/usr/bin/env bash\n"
        'case "$*" in\n'
        '  *auth.docker.io/token*) echo \'{"token": "anon"}\' ;;\n'
        f"  *registry-1.docker.io/v2/library/python/manifests/3.12-slim*) printf 'HTTP/2 200\\r\\n"
        f"Docker-Content-Digest: {digest}\\r\\n\\r\\n' ;;\n"
        "  *) exit 22 ;;\n"
        "esac\n"
    )
    fake_curl.chmod(0o755)
    env = {**os.environ, "PATH": f"{tmp_path}:{os.environ['PATH']}"}
    result = subprocess.run(["bash", str(script)], env=env, capture_output=True, text=True, check=False)
    assert result.returncode == 0, result.stderr
    assert result.stdout.strip() == f"python:3.12-slim@{digest}"
    bad = subprocess.run(["bash", str(script), "x;rm"], env=env, capture_output=True, text=True, check=False)
    assert bad.returncode == 64


class Recorder:
    def __init__(self, fail: int = 0) -> None:
        self.calls: list[dict] = []
        self.fail = fail

    def __call__(self, **kwargs):
        if self.fail:
            self.fail -= 1
            raise RuntimeError("meter unavailable")
        self.calls.append(kwargs)
        return 0.0


def test_compute_heartbeat_meters_uptime_and_carries_failed_seconds(main_app):
    now = [100.0]
    ticks = []
    record = Recorder(fail=1)
    hb = main_app.ComputeHeartbeat(
        record,
        cpu_cores=0.25,
        memory_mib=512,
        source="web",
        monotonic=lambda: now[0],
        on_tick=lambda: ticks.append(1),
    )
    now[0] = 160.0
    assert hb.tick() == 0.0  # meter down: kept
    now[0] = 220.0
    assert hb.tick() == 120.0
    assert record.calls == [{"cpu_cores": 0.25, "memory_mib": 512, "seconds": 120.0, "source": "web"}]
    now[0] = 225.0
    assert hb.stop() == 5.0  # the final record at shutdown
    assert len(ticks) == 2


def test_compute_heartbeat_thread_runs_and_stops(main_app):
    record = Recorder()
    hb = main_app.ComputeHeartbeat(record, cpu_cores=0.25, memory_mib=512, source="web", interval_s=0.01)
    hb.start()
    hb.start()  # idempotent
    import time

    time.sleep(0.05)
    hb.stop()
    assert len(record.calls) >= 2
    assert all(c["source"] == "web" for c in record.calls)


def test_the_dispatcher_runs_with_the_ops_key_everywhere(main_app, monkeypatch):
    from ridemate_api import deps_prod

    seen = {}
    monkeypatch.setattr(deps_prod, "build_production_deps", lambda settings: seen.setdefault("s", settings))
    monkeypatch.setenv("FIRESTORE_OPS_SA_JSON", '{"ops": true}')
    monkeypatch.setenv("FIRESTORE_WEB_SA_JSON", "")
    main_app.build_deps("ops")
    assert seen["s"].web_sa_json == '{"ops": true}'
    seen.clear()
    monkeypatch.setenv("FIRESTORE_WEB_SA_JSON", '{"web": true}')
    main_app.build_deps("web")
    assert seen["s"].web_sa_json == '{"web": true}'


def test_the_web_app_starts_its_heartbeat_and_registers_shutdown(main_app, monkeypatch):
    registered = []
    monkeypatch.setattr(main_app.atexit, "register", registered.append)
    spend = SimpleNamespace(record_compute=Recorder())
    deps = SimpleNamespace(spend=spend, metrics=SimpleNamespace(flush=lambda: True))
    hb = main_app._start_heartbeat(deps, cpu=0.25, memory=512, source="web")
    try:
        assert registered == [hb.stop]
    finally:
        hb.stop()
