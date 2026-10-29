"""Every key in docs/contracts.md §2 has a default, a schema and a registry entry — and nothing else does."""

from __future__ import annotations

import re

from ridemate_config.keys import DEFAULTS_DIR, KEYS, KEYS_BY_NAME, REPO_ROOT, SCHEMAS_DIR, SERVER_ONLY_KEYS


def contract_keys() -> set[str]:
    text = (REPO_ROOT / "docs/contracts.md").read_text(encoding="utf-8")
    section = text.split("## 2. Remote Config", 1)[1].split("\n## 3.", 1)[0]
    keys = set(re.findall(r"^### 2\.\d+ `([a-z_0-9]+)`", section, flags=re.M))
    for row in re.findall(r"^\| (`[^|]+) \|", section, flags=re.M):
        names = re.findall(r"`([a-z_0-9]+)`", row)
        first = names[0]
        keys.add(first)
        prefix = first.rsplit("_", 1)[0]
        keys.update(prefix + n for n in names[1:] if n.startswith("_"))
    return keys


def test_contract_parser_sees_known_keys() -> None:
    keys = contract_keys()
    assert {"notification_templates", "launch_templates", "reader_enabled_ola", "better_ride_enabled"} <= keys


def test_every_contract_key_present() -> None:
    assert contract_keys() == set(KEYS_BY_NAME)


def test_each_key_has_default_and_schema_and_nothing_extra() -> None:
    defaults = {p.stem for p in DEFAULTS_DIR.glob("*.json")}
    schemas = {p.name.removesuffix(".schema.json") for p in SCHEMAS_DIR.glob("*.schema.json")}
    names = {k.name for k in KEYS}
    assert defaults == names
    assert schemas == names


def test_value_types_match_contract_table() -> None:
    expected = {
        "ai_reader_enabled": "BOOLEAN",
        "reader_enabled_uber": "BOOLEAN",
        "ondc_enabled": "BOOLEAN",
        "demo_enabled": "BOOLEAN",
        "better_ride_enabled": "BOOLEAN",
        "point_supported": "BOOLEAN",
        "min_supported_version": "NUMBER",
        "budget_horizon_days": "NUMBER",
        "cap_profile": "STRING",
        "credit_expiry_date": "STRING",
        "budget_horizon_start": "STRING",
        "api_base_url": "STRING",
        "model_route": "JSON",
        "latest_version": "JSON",
        "notification_templates": "JSON",
    }
    for name, value_type in expected.items():
        assert KEYS_BY_NAME[name].value_type == value_type, name


def test_identity_lists_are_never_client_config() -> None:
    """Every install can fetch the client template: judge, tester and owner identities must not be in it."""
    assert not SERVER_ONLY_KEYS & set(KEYS_BY_NAME)
    assert not SERVER_ONLY_KEYS & contract_keys()
    for key in SERVER_ONLY_KEYS:
        assert not (DEFAULTS_DIR / f"{key}.json").exists()
        assert not (SCHEMAS_DIR / f"{key}.schema.json").exists()
