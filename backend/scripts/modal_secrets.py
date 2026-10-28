"""Modal environments and Secrets for deploy.sh, written without ever printing a value (architecture §4.11).

Values come from this process's environment (the caller sourced .env), from android/app/google-services.json
(the Firebase web API key) or are generated in memory (the HMAC key and the ONDC keys, which therefore exist only
in Modal Secrets and never on disk). They reach Modal as arguments of `modal secret create --force` (a child
process, no shell; its output is reduced to key names) or through `Secret.update()` when keys that can't be read
back (generated keys, the Template Doctor token) must be kept.

    python modal_secrets.py ensure-env NAME
    python modal_secrets.py exists NAME --env ENV                    # exit 0 when the secret exists
    python modal_secrets.py put NAME --env ENV --keys A,B [--optional C] [--merge]
    python modal_secrets.py core --env ENV --google-services PATH
    python modal_secrets.py sa-key NAME --env ENV --var VAR --role web|ops --key-file PATH
    python modal_secrets.py key-id --key-file PATH                   # a key's id (not secret) for old-key cleanup
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import secrets
import subprocess  # nosec B404 - runs only the Modal CLI, argv list, no shell
import sys
from collections.abc import Callable, Iterable, Mapping, Sequence
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

OWNER_EMAILS_DEFAULT = "ibaharul567@gmail.com"
ANDROID_PACKAGE = "in.ridemate.app"
HOT_DICT_DEFAULT = "ridemate-hot"
SA_KEYS_DICT_KEY = "ops:sa_keys"
CORE_SECRET = "ridemate-core"  # nosec B105 - a secret name
# Non-secret settings re-applied on every deploy, with their defaults.
CORE_CONFIG_DEFAULTS: Mapping[str, str | None] = {
    "FIREBASE_PROJECT_ID": None,  # required
    "OWNER_EMAILS": OWNER_EMAILS_DEFAULT,
    "ONDC_MODE": "mock",  # hackathon: the in-process demo seller
    "BUDGET_HORIZON_START": "2026-10-28",
    "CREDIT_EXPIRY_DATE": "unknown",
}
GENERATED_KEYS = (
    "RIDEMATE_HMAC_KEY",
    "ONDC_SIGNING_PRIVATE_KEY",
    "ONDC_SIGNING_PUBLIC_KEY",
    "ONDC_ENCRYPTION_PRIVATE_KEY",
    "ONDC_ENCRYPTION_PUBLIC_KEY",
    "ONDC_UNIQUE_KEY_ID",
)

Runner = Callable[[Sequence[str]], subprocess.CompletedProcess[str]]


class HelperError(Exception):
    """A failure whose message is safe to print (names only, never values)."""


def say(message: str) -> None:
    print(f"  {message}", flush=True)


def _default_runner(args: Sequence[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(  # nosec B603 - fixed executable, argv list, no shell
        [sys.executable, "-m", "modal", *args], capture_output=True, text=True, check=False
    )


def redact(text: str, values: Iterable[str]) -> str:
    for value in sorted({v for v in values if v}, key=len, reverse=True):
        text = text.replace(value, "***")
    return text


def run_modal(args: Sequence[str], *, values: Iterable[str] = (), runner: Runner = _default_runner) -> str:
    """Run the Modal CLI; on failure raise with its stderr redacted of every value passed in."""
    result = runner(list(args))
    if result.returncode != 0:
        detail = redact((result.stderr or result.stdout or "").strip(), values)
        lines = [line[:300] for line in detail.splitlines()[-8:]]
        raise HelperError(
            f"modal {args[0]} {args[1] if len(args) > 1 else ''} failed:\n    " + "\n    ".join(lines)
        )
    return result.stdout


# ---- environments and secret listing -----------------------------------------------------------------------


def _names(stdout: str) -> set[str]:
    try:
        rows = json.loads(stdout or "[]")
    except ValueError as e:
        raise HelperError("could not parse the Modal CLI's JSON output") from e
    names: set[str] = set()
    for row in rows if isinstance(rows, list) else []:
        if isinstance(row, Mapping):
            name = row.get("Name") or row.get("name")
            if name:
                names.add(str(name))
    return names


def ensure_environment(name: str, *, runner: Runner = _default_runner) -> bool:
    """True when the environment had to be created."""
    if name in _names(run_modal(["environment", "list", "--json"], runner=runner)):
        say(f"Modal environment '{name}' exists")
        return False
    run_modal(["environment", "create", name], runner=runner)
    say(f"Modal environment '{name}' created")
    return True


def secret_exists(name: str, env: str, *, runner: Runner = _default_runner) -> bool:
    return name in _names(run_modal(["secret", "list", "--env", env, "--json"], runner=runner))


# ---- writing secrets ---------------------------------------------------------------------------------------


def create_force(name: str, env: str, values: Mapping[str, str], *, runner: Runner = _default_runner) -> None:
    if any(v == "-" for v in values.values()):
        # The CLI reads `KEY=-` from $EDITOR; refuse rather than open an editor.
        raise HelperError(f"a value for {name} is '-', which the Modal CLI can't take as a literal")
    args = ["secret", "create", "--force", name, *(f"{k}={v}" for k, v in values.items()), "--env", env]
    run_modal(args, values=values.values(), runner=runner)
    say(f"secret {name}: wrote {', '.join(sorted(values))}")


def merge_update(name: str, env: str, values: Mapping[str, str], *, sdk: Any = None) -> None:
    """Add or overwrite `values`, keeping every other key of the existing secret."""
    modal = sdk or __import__("modal")
    try:
        modal.Secret.from_name(name, environment_name=env).update(dict(values))
    except Exception as e:  # noqa: BLE001 - SDK errors may quote request data; report the type only
        raise HelperError(f"updating secret {name} failed ({type(e).__name__})") from None
    say(f"secret {name}: updated {', '.join(sorted(values))} (other keys kept)")


def values_from_env(
    keys: Sequence[str], optional: Sequence[str] = (), *, environ: Mapping[str, str] = os.environ
) -> dict[str, str]:
    missing = [k for k in keys if not environ.get(k)]
    if missing:
        raise HelperError("missing environment variables: " + " ".join(missing))
    values = {k: environ[k] for k in keys}
    values.update({k: environ[k] for k in optional if environ.get(k)})
    return values


def put(
    name: str,
    env: str,
    keys: Sequence[str],
    optional: Sequence[str],
    *,
    merge: bool,
    runner: Runner = _default_runner,
    sdk: Any = None,
    environ: Mapping[str, str] = os.environ,
) -> None:
    values = values_from_env(keys, optional, environ=environ)
    if merge and secret_exists(name, env, runner=runner):
        merge_update(name, env, values, sdk=sdk)
    else:
        create_force(name, env, values, runner=runner)


# ---- ridemate-core -----------------------------------------------------------------------------------------


def firebase_web_api_key(google_services: Path) -> str:
    try:
        data = json.loads(google_services.read_text(encoding="utf-8"))
    except (OSError, ValueError) as e:
        raise HelperError(f"cannot read {google_services.name} ({type(e).__name__})") from None
    clients = data.get("client") or []
    preferred = [
        c
        for c in clients
        if c.get("client_info", {}).get("android_client_info", {}).get("package_name") == ANDROID_PACKAGE
    ]
    for client in preferred or clients:
        for entry in client.get("api_key") or []:
            if entry.get("current_key"):
                return str(entry["current_key"])
    raise HelperError(f"no api_key.current_key in {google_services.name}")


def generate_core_keys() -> dict[str, str]:
    """The HMAC key for quota ids (H5) and the ONDC Ed25519 signing / X25519 encryption keys, in memory only.
    Signing keys use libsodium's layout (private = seed ‖ public, base64); encryption keys are DER, base64."""
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
    from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey

    raw = serialization.Encoding.Raw, serialization.PublicFormat.Raw
    signing = Ed25519PrivateKey.generate()
    seed = signing.private_bytes(
        serialization.Encoding.Raw, serialization.PrivateFormat.Raw, serialization.NoEncryption()
    )
    signing_public = signing.public_key().public_bytes(*raw)
    encryption = X25519PrivateKey.generate()
    return {
        "RIDEMATE_HMAC_KEY": secrets.token_hex(32),
        "ONDC_SIGNING_PRIVATE_KEY": base64.b64encode(seed + signing_public).decode(),
        "ONDC_SIGNING_PUBLIC_KEY": base64.b64encode(signing_public).decode(),
        "ONDC_ENCRYPTION_PRIVATE_KEY": base64.b64encode(
            encryption.private_bytes(
                serialization.Encoding.DER, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()
            )
        ).decode(),
        "ONDC_ENCRYPTION_PUBLIC_KEY": base64.b64encode(
            encryption.public_key().public_bytes(
                serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo
            )
        ).decode(),
        "ONDC_UNIQUE_KEY_ID": f"ridemate-{secrets.token_hex(4)}",
    }


def core_config(google_services: Path, *, environ: Mapping[str, str] = os.environ) -> dict[str, str]:
    values: dict[str, str] = {}
    missing = []
    for key, default in CORE_CONFIG_DEFAULTS.items():
        value = environ.get(key) or default
        if value is None:
            missing.append(key)
        else:
            values[key] = value
    if missing:
        raise HelperError("missing environment variables: " + " ".join(missing))
    values["FIREBASE_WEB_API_KEY"] = firebase_web_api_key(google_services)
    return values


def put_core(
    env: str,
    google_services: Path,
    *,
    runner: Runner = _default_runner,
    sdk: Any = None,
    environ: Mapping[str, str] = os.environ,
    generate: Callable[[], dict[str, str]] = generate_core_keys,
) -> str:
    """First deploy: config + freshly generated keys. Later deploys: refresh the config and keep the keys (they
    can't be read back). RIDEMATE_HMAC_KEY / _PREVIOUS in the environment rotate the HMAC key (runbook)."""
    values = core_config(google_services, environ=environ)
    rotation = {k: environ[k] for k in ("RIDEMATE_HMAC_KEY", "RIDEMATE_HMAC_KEY_PREVIOUS") if environ.get(k)}
    if secret_exists(CORE_SECRET, env, runner=runner):
        merge_update(CORE_SECRET, env, {**values, **rotation}, sdk=sdk)
        return "updated"
    create_force(CORE_SECRET, env, {**values, **generate(), **rotation}, runner=runner)
    say("generated RIDEMATE_HMAC_KEY and ONDC keys (stored only in the Modal Secret)")
    return "created"


# ---- service-account keys ----------------------------------------------------------------------------------


def read_sa_key(key_file: Path) -> tuple[str, str]:
    """(JSON text, key id) of a service-account key file, validated without echoing anything from it."""
    try:
        text = key_file.read_text(encoding="utf-8")
        data = json.loads(text)
    except (OSError, ValueError) as e:
        raise HelperError(f"cannot read the key file ({type(e).__name__})") from None
    if data.get("type") != "service_account" or not data.get("private_key") or not data.get("private_key_id"):
        raise HelperError("the key file is not a service-account key")
    return text, str(data["private_key_id"])


def record_key_created(
    env: str, role: str, created: str, *, sdk: Any = None, dict_name: str = HOT_DICT_DEFAULT
) -> None:
    """Key creation time for the dispatcher's key-age alert (80 days), in the environment's hot store."""
    modal = sdk or __import__("modal")
    try:
        store = modal.Dict.from_name(dict_name, environment_name=env, create_if_missing=True)
        current = store.get(SA_KEYS_DICT_KEY, {}) or {}
        store.put(SA_KEYS_DICT_KEY, {**dict(current), role: created})
    except Exception as e:  # noqa: BLE001
        raise HelperError(f"recording the key age failed ({type(e).__name__})") from None


def put_sa_key(
    name: str,
    env: str,
    var: str,
    role: str,
    key_file: Path,
    *,
    runner: Runner = _default_runner,
    sdk: Any = None,
    now: datetime | None = None,
) -> str:
    text, key_id = read_sa_key(key_file)
    created = (now or datetime.now(UTC)).isoformat(timespec="seconds")
    values = {var: text, f"{var.removesuffix('_JSON')}_CREATED_AT": created}
    if secret_exists(name, env, runner=runner):
        merge_update(name, env, values, sdk=sdk)  # keeps e.g. TEMPLATE_DOCTOR_GITHUB_TOKEN in ridemate-ops-sa
    else:
        create_force(name, env, values, runner=runner)
    record_key_created(
        env, role, created, sdk=sdk, dict_name=os.environ.get("RIDEMATE_HOT_DICT", HOT_DICT_DEFAULT)
    )
    return key_id


# ---- CLI ---------------------------------------------------------------------------------------------------


def _csv(value: str | None) -> list[str]:
    return [v.strip() for v in (value or "").split(",") if v.strip()]


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Modal environments and Secrets for deploy.sh")
    sub = parser.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("ensure-env")
    p.add_argument("name")
    p = sub.add_parser("exists")
    p.add_argument("name")
    p.add_argument("--env", required=True)
    p = sub.add_parser("put")
    p.add_argument("name")
    p.add_argument("--env", required=True)
    p.add_argument("--keys", required=True)
    p.add_argument("--optional", default="")
    p.add_argument("--merge", action="store_true")
    p = sub.add_parser("core")
    p.add_argument("--env", required=True)
    p.add_argument("--google-services", type=Path, required=True)
    p = sub.add_parser("sa-key")
    p.add_argument("name")
    p.add_argument("--env", required=True)
    p.add_argument("--var", required=True)
    p.add_argument("--role", required=True, choices=("web", "ops"))
    p.add_argument("--key-file", type=Path, required=True)
    p = sub.add_parser("key-id")
    p.add_argument("--key-file", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        if args.cmd == "ensure-env":
            ensure_environment(args.name)
        elif args.cmd == "exists":
            return 0 if secret_exists(args.name, args.env) else 1
        elif args.cmd == "put":
            put(args.name, args.env, _csv(args.keys), _csv(args.optional), merge=args.merge)
        elif args.cmd == "core":
            put_core(args.env, args.google_services)
        elif args.cmd == "sa-key":
            put_sa_key(args.name, args.env, args.var, args.role, args.key_file)
        elif args.cmd == "key-id":
            print(read_sa_key(args.key_file)[1])
    except HelperError as e:
        print(f"error: {e}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
