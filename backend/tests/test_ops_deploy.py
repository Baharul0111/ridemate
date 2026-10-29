"""deploy.sh and scripts/modal_secrets.py: plan-only dry runs, refusing missing configuration, never printing a
value, idempotent secrets that keep generated keys."""

from __future__ import annotations

import base64
import importlib.util
import json
import os
import shutil
import subprocess
from datetime import UTC
from pathlib import Path
from types import SimpleNamespace

import pytest

from tests.test_ops_fakes import sa_json

BACKEND = Path(__file__).resolve().parents[1]
DEPLOY = BACKEND / "deploy.sh"
FAKE = {
    "FIREBASE_PROJECT_ID": "demo-ridemate",
    "FIREBASE_DEV_PROJECT_ID": "demo-ridemate-dev",
    "MODAL_SPARK_OPEN_URL": "https://fake-SECRETVALUE-1.example",
    "MODAL_PROXY_TOKEN_ID": "wk-SECRETVALUE2",
    "MODAL_PROXY_TOKEN_SECRET": "ws-SECRETVALUE3",
    "HEALTHCHECKS_HEARTBEAT_URL": "https://hc-ping.com/SECRETVALUE4",
    "HEALTHCHECKS_ALERT_URL": "https://hc-ping.com/SECRETVALUE5",
}


def deploy(*args: str, env: dict[str, str] | None = None) -> subprocess.CompletedProcess[str]:
    base = {"PATH": os.environ["PATH"], "HOME": os.environ.get("HOME", "/tmp")}
    return subprocess.run(
        ["bash", str(DEPLOY), *args],
        env={**base, **(env or {})},
        capture_output=True,
        text=True,
        check=False,
        timeout=60,
    )


# ---- deploy.sh ---------------------------------------------------------------------------------------------


def test_deploy_script_parses():
    assert subprocess.run(["bash", "-n", str(DEPLOY)], check=False).returncode == 0
    if shutil.which("shellcheck"):
        assert subprocess.run(["shellcheck", str(DEPLOY)], check=False).returncode == 0


def test_dry_run_prints_the_whole_plan_and_no_value():
    result = deploy("--dry-run", env=FAKE)
    assert result.returncode == 0, result.stderr
    out = result.stdout
    assert "SECRETVALUE" not in out + result.stderr
    for expected in [
        "DRY RUN",
        "(a) Modal environments and secrets",
        "would write secret ridemate-model: MODAL_SPARK_OPEN_URL, MODAL_PROXY_TOKEN_ID, MODAL_PROXY_TOKEN_SECRET",
        "ridemate-core",
        "ridemate-alerts",
        (
            "gcloud services enable firestore.googleapis.com iam.googleapis.com iamcredentials.googleapis.com "
            "firebaseremoteconfig.googleapis.com fcm.googleapis.com --project demo-ridemate"
        ),
        "--role roles/firebasecloudmessaging.admin",
        "ridemate-web-sa (FIRESTORE_WEB_SA_JSON",
        "Firestore '(default)' in asia-south1",
        "firebase deploy --only firestore:rules,firestore:indexes --project demo-ridemate",
        "modal deploy",
        "--env main",
        "--only remoteconfig --project demo-ridemate-dev",
        "--only remoteconfig --project demo-ridemate --config",
    ]:
        assert expected in out, expected
    assert out.index("--project demo-ridemate-dev") < out.index(
        "--only remoteconfig --project demo-ridemate --config"
    )
    assert "billing" not in out.replace("billing is never linked", "")


def test_missing_configuration_is_refused_by_name_only():
    partial = {"FIREBASE_PROJECT_ID": "demo-ridemate", "MODAL_PROXY_TOKEN_ID": "wk-SECRETVALUE2"}
    result = deploy("--dry-run", env=partial)
    assert result.returncode == 2
    assert "MODAL_SPARK_OPEN_URL" in result.stderr and "HEALTHCHECKS_ALERT_URL" in result.stderr
    assert "FIREBASE_DEV_PROJECT_ID" in result.stderr
    assert "MODAL_PROXY_TOKEN_ID" not in result.stderr  # present, so not listed
    assert "SECRETVALUE" not in result.stdout + result.stderr


def test_skip_flags_shrink_what_is_required_and_done():
    result = deploy(
        "--dry-run",
        "--skip-secrets",
        "--skip-firebase",
        "--env",
        "dev",
        env={"FIREBASE_PROJECT_ID": "demo-ridemate"},
    )
    assert result.returncode == 0, result.stderr
    assert "skipped (--skip-secrets)" in result.stdout
    assert "skipped (--skip-firebase)" in result.stdout
    assert "--env dev" in result.stdout
    assert "would run: gcloud" not in result.stdout and "firebase deploy" not in result.stdout


def test_bad_options_are_rejected():
    assert deploy("--env", "prod", env=FAKE).returncode == 64
    assert deploy("--bogus", env=FAKE).returncode == 64
    assert deploy("--help").returncode == 0


def test_each_environment_has_its_own_service_accounts():
    main = deploy("--dry-run", env=FAKE).stdout
    dev = deploy("--dry-run", "--env", "dev", env=FAKE).stdout
    assert "ridemate-web@demo-ridemate.iam.gserviceaccount.com" in main
    assert "ridemate-ops@demo-ridemate.iam.gserviceaccount.com" in main
    assert "ridemate-web-dev@demo-ridemate.iam.gserviceaccount.com" in dev
    assert "ridemate-ops-dev@demo-ridemate.iam.gserviceaccount.com" in dev
    assert "ridemate-web@" not in dev and "ridemate-ops@" not in dev


# Stand-ins for the CLIs a real run calls. Each appends its argv (and RIDEMATE_DEPLOY_ENV) to $FAKE_LOG.
FAKE_UV = r"""#!/usr/bin/env bash
echo "uv $* | deploy_env=${RIDEMATE_DEPLOY_ENV:-}" >>"$FAKE_LOG"
case "$*" in
  *modal_secrets.py\ exists*) exit 1 ;;  # no secret exists yet: keys get created
  *modal_secrets.py\ key-id*) sed -E 's/.*"private_key_id": *"([^"]+)".*/\1/' "${@: -1}" ;;
  *" modal deploy "*) echo "Created web function web => https://ws--ridemate-api-web.modal.run" ;;
esac
exit 0
"""
FAKE_GCLOUD = r"""#!/usr/bin/env bash
echo "gcloud $*" >>"$FAKE_LOG"
args=("$@")
case "$*" in
  "iam service-accounts keys create "*)  # keys create FILE --iam-account EMAIL
    email="${args[6]}"
    printf '{"type": "service_''account", "private_key_id": "new-%s", "private_key": "k"}' "${email%%@*}" >"${args[4]}" ;;
  "iam service-accounts keys list "*)  # keys list --iam-account EMAIL
    email="${args[5]}"
    printf 'old-%s\nnew-%s\n' "${email%%@*}" "${email%%@*}" ;;
esac
exit 0
"""
FAKE_FIREBASE = """#!/usr/bin/env bash
echo "firebase $*" >>"$FAKE_LOG"
"""
# /readyz of the fresh deployment, as Modal's proxy would forward it (FAKE_READYZ overrides the body).
FAKE_CURL = r"""#!/usr/bin/env bash
echo "curl $*" >>"$FAKE_LOG"
ready='{"ok":true,"checks":{},"client_bucketed":true}'
printf '%s' "${FAKE_READYZ:-$ready}"
"""


def fake_run(
    tmp_path: Path, *args: str, extra_env: dict[str, str] | None = None
) -> tuple[subprocess.CompletedProcess[str], list[str]]:
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    for name, body in {
        "uv": FAKE_UV,
        "gcloud": FAKE_GCLOUD,
        "firebase": FAKE_FIREBASE,
        "curl": FAKE_CURL,
    }.items():
        (bin_dir / name).write_text(body)
        (bin_dir / name).chmod(0o755)
    log = tmp_path / "calls.log"
    env = {**FAKE, "PATH": f"{bin_dir}:{os.environ['PATH']}", "FAKE_LOG": str(log), **(extra_env or {})}
    result = deploy(*args, env=env)
    calls = log.read_text().splitlines()
    for call in calls:
        if call.startswith("gcloud iam service-accounts keys create "):
            key_file = Path(call.split()[5])
            assert not key_file.exists() and not key_file.parent.exists(), "a key file was left behind"
    return result, calls


@pytest.mark.skipif(
    not (BACKEND.parent / "android/app/google-services.json").is_file(), reason="no app config"
)
@pytest.mark.parametrize("env_name", ["dev", "main"])
def test_a_real_run_only_ever_touches_its_own_environments_keys(tmp_path, env_name):
    result, calls = fake_run(tmp_path, "--env", env_name)
    assert result.returncode == 0, result.stderr
    assert "SECRETVALUE" not in result.stdout + result.stderr + "\n".join(calls)
    suffix = "-dev" if env_name == "dev" else ""
    other = "" if env_name == "dev" else "-dev"
    key_calls = [c for c in calls if c.startswith("gcloud iam service-accounts keys")]
    assert key_calls
    for call in key_calls:
        assert f"ridemate-web{suffix}@" in call or f"ridemate-ops{suffix}@" in call, call
        assert f"ridemate-web{other}@" not in call and f"ridemate-ops{other}@" not in call, call
    deleted = [c.split()[5] for c in key_calls if c.startswith("gcloud iam service-accounts keys delete")]
    assert sorted(deleted) == sorted([f"old-ridemate-web{suffix}", f"old-ridemate-ops{suffix}"])
    # the fresh keys went into this environment's secrets, and the old ones were deleted only after the deploy
    assert any(f"sa-key ridemate-web-sa --env {env_name}" in c for c in calls)
    deploy_at = next(i for i, c in enumerate(calls) if " modal deploy " in c)
    assert f"deploy_env={env_name}" in calls[deploy_at] and f"--env {env_name}" in calls[deploy_at]
    assert all(i > deploy_at for i, c in enumerate(calls) if "keys delete" in c)
    remote_config = [c for c in calls if "--only remoteconfig" in c]
    expected = ["demo-ridemate-dev"] if env_name == "dev" else ["demo-ridemate-dev", "demo-ridemate"]
    assert [c.split("--project ")[1].split()[0] for c in remote_config] == expected
    # the post-deploy check asked the new deployment itself
    assert calls[-1] == "curl -sS --max-time 30 https://ws--ridemate-api-web.modal.run/readyz"
    assert "per-network limits apply" in result.stdout


@pytest.mark.skipif(
    not (BACKEND.parent / "android/app/google-services.json").is_file(), reason="no app config"
)
def test_a_deploy_whose_proxy_hides_the_client_address_fails_loudly(tmp_path):
    readyz = '{"ok":true,"checks":{},"client_bucketed":false}'
    result, _ = fake_run(tmp_path, "--env", "dev", extra_env={"FAKE_READYZ": readyz})
    assert result.returncode == 1
    assert "per-network (per-IP) limits are OFF" in result.stderr


def test_dry_run_plans_the_post_deploy_check():
    result = deploy("--dry-run", env=FAKE)
    assert "(f) post-deploy check" in result.stdout
    assert '"client_bucketed": true' in result.stdout


# ---- scripts/modal_secrets.py ------------------------------------------------------------------------------


@pytest.fixture(scope="module")
def ms():
    spec = importlib.util.spec_from_file_location("modal_secrets", BACKEND / "scripts" / "modal_secrets.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class FakeModalCli:
    """Stands in for `python -m modal`; `secrets` maps environment → existing secret names."""

    def __init__(self, secrets: dict[str, set[str]] | None = None, fail: bool = False) -> None:
        self.secrets = secrets or {}
        self.calls: list[list[str]] = []
        self.environments = {"main"}
        self.fail = fail

    def __call__(self, args):
        args = list(args)
        self.calls.append(args)
        if self.fail:
            return subprocess.CompletedProcess(args, 1, "", "Error: could not create " + " ".join(args))
        if args[:2] == ["secret", "list"]:
            env = args[args.index("--env") + 1]
            return subprocess.CompletedProcess(
                args, 0, json.dumps([{"name": n} for n in self.secrets.get(env, ())]), ""
            )
        if args[:2] == ["environment", "list"]:
            return subprocess.CompletedProcess(
                args, 0, json.dumps([{"name": n} for n in self.environments]), ""
            )
        if args[:2] == ["environment", "create"]:
            self.environments.add(args[2])
        return subprocess.CompletedProcess(args, 0, "Created a new secret with the keys ...", "")


class FakeSdk:
    def __init__(self) -> None:
        self.updates: list[tuple[str, str, dict]] = []
        self.dicts: dict[str, dict] = {}
        outer = self

        class Secret:
            @staticmethod
            def from_name(name, environment_name):
                return SimpleNamespace(
                    update=lambda values: outer.updates.append((name, environment_name, values))
                )

        class Dict:
            @staticmethod
            def from_name(name, environment_name, create_if_missing):
                store = outer.dicts.setdefault(f"{environment_name}/{name}", {})
                return SimpleNamespace(get=lambda k, d=None: store.get(k, d), put=store.__setitem__)

        self.Secret = Secret
        self.Dict = Dict


def google_services(tmp_path: Path) -> Path:
    path = tmp_path / "google-services.json"
    path.write_text(
        json.dumps(
            {
                "client": [
                    {
                        "client_info": {"android_client_info": {"package_name": "other.app"}},
                        "api_key": [{"current_key": "WRONG"}],
                    },
                    {
                        "client_info": {"android_client_info": {"package_name": "in.ridemate.app"}},
                        "api_key": [{"current_key": "AIzaSECRETVALUEapikey"}],
                    },
                ]
            }
        )
    )
    return path


def test_secrets_are_written_with_the_modal_cli_and_only_key_names_are_printed(ms, capsys):
    cli = FakeModalCli()
    env = {k: FAKE[k] for k in ("MODAL_SPARK_OPEN_URL", "MODAL_PROXY_TOKEN_ID", "MODAL_PROXY_TOKEN_SECRET")}
    ms.put("ridemate-model", "main", list(env), [], merge=False, runner=cli, environ=env)
    (call,) = cli.calls
    assert call[:4] == ["secret", "create", "--force", "ridemate-model"]
    assert call[-2:] == ["--env", "main"]
    assert f"MODAL_PROXY_TOKEN_ID={FAKE['MODAL_PROXY_TOKEN_ID']}" in call
    out = capsys.readouterr().out
    assert "MODAL_PROXY_TOKEN_SECRET" in out and "SECRETVALUE" not in out


def test_cli_failures_are_reported_without_values(ms):
    cli = FakeModalCli(fail=True)
    with pytest.raises(ms.HelperError) as info:
        ms.create_force("ridemate-model", "main", {"K": "SECRETVALUE9"}, runner=cli)
    assert "SECRETVALUE" not in str(info.value) and "***" in str(info.value)
    with pytest.raises(ms.HelperError):
        ms.create_force("x", "main", {"K": "-"}, runner=FakeModalCli())  # would open $EDITOR


def test_merge_keeps_other_keys_of_an_existing_secret(ms):
    cli, sdk = FakeModalCli({"main": {"ridemate-alerts"}}), FakeSdk()
    env = {"HEALTHCHECKS_HEARTBEAT_URL": "h", "HEALTHCHECKS_ALERT_URL": "a"}
    ms.put(
        "ridemate-alerts",
        "main",
        list(env),
        ["OWNER_FCM_TOKEN"],
        merge=True,
        runner=cli,
        sdk=sdk,
        environ=env,
    )
    assert sdk.updates == [("ridemate-alerts", "main", env)]
    assert not any(c[:2] == ["secret", "create"] for c in cli.calls)
    with pytest.raises(ms.HelperError) as info:
        ms.values_from_env(["A", "B"], environ={"A": "1"})
    assert "B" in str(info.value) and "1" not in str(info.value)


def test_core_is_created_once_with_generated_keys_then_only_refreshed(ms, tmp_path):
    services = google_services(tmp_path)
    environ = {"FIREBASE_PROJECT_ID": "demo-ridemate", "CREDIT_EXPIRY_DATE": "2027-01-31"}
    cli, sdk = FakeModalCli(), FakeSdk()
    assert (
        ms.put_core(
            "main",
            services,
            runner=cli,
            sdk=sdk,
            environ=environ,
            generate=lambda: {"RIDEMATE_HMAC_KEY": "gen-hmac", "ONDC_SIGNING_PRIVATE_KEY": "gen-sign"},
        )
        == "created"
    )
    create = next(c for c in cli.calls if c[:2] == ["secret", "create"])
    assert "RIDEMATE_HMAC_KEY=gen-hmac" in create and "FIREBASE_WEB_API_KEY=AIzaSECRETVALUEapikey" in create
    assert "OWNER_EMAILS=ibaharul567@gmail.com" in create and "ONDC_MODE=off" in create
    assert "CREDIT_EXPIRY_DATE=2027-01-31" in create

    cli = FakeModalCli({"main": {"ridemate-core"}})
    assert (
        ms.put_core(
            "main",
            services,
            runner=cli,
            sdk=sdk,
            environ=environ,
            generate=lambda: pytest.fail("keys must never be regenerated"),
        )
        == "updated"
    )
    ((_, _, values),) = sdk.updates
    assert "RIDEMATE_HMAC_KEY" not in values and values["FIREBASE_PROJECT_ID"] == "demo-ridemate"
    sdk.updates.clear()
    ms.put_core(
        "main",
        services,
        runner=cli,
        sdk=sdk,
        environ={**environ, "RIDEMATE_HMAC_KEY": "new", "RIDEMATE_HMAC_KEY_PREVIOUS": "old"},
    )
    assert sdk.updates[0][2]["RIDEMATE_HMAC_KEY"] == "new"  # the rotation runbook


def test_generated_keys_have_the_expected_formats(ms):
    from cryptography.hazmat.primitives.serialization import load_der_private_key, load_der_public_key

    keys = ms.generate_core_keys()
    assert set(keys) == set(ms.GENERATED_KEYS)
    assert len(bytes.fromhex(keys["RIDEMATE_HMAC_KEY"])) == 32
    private, public = (
        base64.b64decode(keys["ONDC_SIGNING_PRIVATE_KEY"]),
        base64.b64decode(keys["ONDC_SIGNING_PUBLIC_KEY"]),
    )
    assert len(private) == 64 and private[32:] == public
    from nacl.signing import SigningKey

    assert bytes(SigningKey(private[:32]).verify_key) == public
    load_der_private_key(base64.b64decode(keys["ONDC_ENCRYPTION_PRIVATE_KEY"]), password=None)
    load_der_public_key(base64.b64decode(keys["ONDC_ENCRYPTION_PUBLIC_KEY"]))
    assert ms.generate_core_keys()["RIDEMATE_HMAC_KEY"] != keys["RIDEMATE_HMAC_KEY"]


def test_service_account_keys_go_into_the_secret_and_their_age_is_recorded(ms, tmp_path, monkeypatch):
    monkeypatch.delenv("RIDEMATE_HOT_DICT", raising=False)
    key_file = tmp_path / "ops.json"
    key_file.write_text(sa_json())
    cli, sdk = FakeModalCli({"main": {"ridemate-ops-sa"}}), FakeSdk()
    from datetime import datetime

    key_id = ms.put_sa_key(
        "ridemate-ops-sa",
        "main",
        "FIRESTORE_OPS_SA_JSON",
        "ops",
        key_file,
        runner=cli,
        sdk=sdk,
        now=datetime(2026, 10, 28, 10, 0, tzinfo=UTC),
    )
    assert key_id == json.loads(sa_json())["private_key_id"]
    ((name, env, values),) = sdk.updates  # merged: TEMPLATE_DOCTOR_GITHUB_TOKEN stays
    assert env == "main"
    assert name == "ridemate-ops-sa" and values["FIRESTORE_OPS_SA_JSON"] == sa_json()
    assert values["FIRESTORE_OPS_SA_CREATED_AT"] == "2026-10-28T10:00:00+00:00"
    assert sdk.dicts["main/ridemate-hot"]["ops:sa_keys"] == {"ops": "2026-10-28T10:00:00+00:00"}
    bad = tmp_path / "bad.json"
    bad.write_text('{"type": "authorized_user"}')
    with pytest.raises(ms.HelperError):
        ms.read_sa_key(bad)


def test_environments_are_created_only_when_missing(ms):
    cli = FakeModalCli()
    assert ms.ensure_environment("dev", runner=cli) is True
    assert ms.ensure_environment("main", runner=cli) is False
    assert ["environment", "create", "dev"] in cli.calls


def test_cli_entry_point_exit_codes(ms, tmp_path, capsys):
    key_file = tmp_path / "k.json"
    key_file.write_text(sa_json())
    assert ms.main(["key-id", "--key-file", str(key_file)]) == 0
    assert capsys.readouterr().out.strip() == json.loads(sa_json())["private_key_id"]
    assert ms.main(["key-id", "--key-file", str(tmp_path / "missing.json")]) == 2
