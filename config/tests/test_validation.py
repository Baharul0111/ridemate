from __future__ import annotations

import copy

import pytest
from ridemate_config.keys import DEMO_PACKAGES, KEYS_BY_NAME, LAUNCH_MODES, REAL_PACKAGES, UNVERIFIED_NOTE
from ridemate_config.regex import PatternError, check_pattern, compile_re2
from ridemate_config.validation import EVENTS, default_value, load_default, validate_all, validate_key


def test_all_defaults_are_valid() -> None:
    assert {k: v for k, v in validate_all().items() if v} == {}


def test_contract_scalar_defaults() -> None:
    assert default_value("ai_reader_enabled") is True
    assert default_value("ondc_enabled") is True
    assert default_value("demo_enabled") is True
    assert default_value("min_supported_version") == 1
    assert default_value("cap_profile") == "normal"
    assert default_value("credit_expiry_date") == "unknown"
    assert default_value("budget_horizon_days") == 90
    assert default_value("budget_horizon_start") == "2026-10-28"
    assert default_value("api_base_url") == ""
    assert default_value("owner_emails_sha256") == []
    assert default_value("model_route") == {
        "schema": "1.0",
        "read": "modal_spark_open",
        "parse": "modal_spark_open",
    }


def test_every_json_object_payload_has_schema_major_1() -> None:
    for key in KEYS_BY_NAME.values():
        value = load_default(key)
        if key.is_json and isinstance(value, dict):
            assert value["schema"].split(".")[0] == "1", key.name


@pytest.mark.parametrize("pattern", ["(?=lookahead)", "(?<=x)y", "(a)\\1", "(?<name>a)", "a\\C"])
def test_re2_rejects_non_portable_patterns(pattern: str) -> None:
    with pytest.raises(PatternError):
        compile_re2(pattern)


def test_named_group_whitelist_and_group_count() -> None:
    check_pattern(r"(?P<eta>\d+)", allowed_groups=frozenset({"eta"}))
    with pytest.raises(PatternError):
        check_pattern(r"(?P<otp>\d+)", allowed_groups=frozenset({"eta"}))
    with pytest.raises(PatternError):
        check_pattern(r"\d+ min", min_groups=1)


def test_schema_rejects_bad_template_regex() -> None:
    key = KEYS_BY_NAME["notification_templates"]
    payload = copy.deepcopy(load_default(key))
    payload["apps"]["demo.pepper"][0]["templates"][0]["pattern"] = "(?=driver)"
    errors = validate_key(key, payload)
    assert any("RE2" in e for e in errors)


def test_schema_rejects_unknown_event_and_extra_field() -> None:
    key = KEYS_BY_NAME["notification_templates"]
    payload = copy.deepcopy(load_default(key))
    payload["apps"]["demo.pepper"][0]["templates"][0]["event"] = "PICKED_UP"
    payload["apps"]["demo.pepper"][0]["extra"] = 1
    assert len(validate_key(key, payload)) >= 2


def test_schema_major_mismatch_is_rejected() -> None:
    key = KEYS_BY_NAME["fare_parsers"]
    payload = copy.deepcopy(load_default(key))
    payload["schema"] = "2.0"
    assert validate_key(key, payload)


def test_missing_package_and_missing_unverified_note_are_errors() -> None:
    key = KEYS_BY_NAME["cancel_cards"]
    payload = copy.deepcopy(load_default(key))
    del payload["apps"]["demo.tamarind"]
    payload["notes"]["com.ubercab"] = "looks fine"
    errors = validate_key(key, payload)
    assert any("demo.tamarind" in e for e in errors)
    assert any("com.ubercab" in e and UNVERIFIED_NOTE in e for e in errors)


def test_duplicate_template_ids_and_missing_demo_events_are_errors() -> None:
    key = KEYS_BY_NAME["notification_templates"]
    payload = copy.deepcopy(load_default(key))
    templates = payload["apps"]["demo.kestrel"][0]["templates"]
    templates[1]["id"] = templates[0]["id"]
    payload["apps"]["demo.pepper"][0]["templates"] = payload["apps"]["demo.pepper"][0]["templates"][:2]
    errors = validate_key(key, payload)
    assert any("duplicate template id" in e for e in errors)
    assert any("demo.pepper lacks events" in e for e in errors)


def test_per_app_payloads_cover_their_packages_and_mark_real_apps_unverified() -> None:
    for name in ("notification_templates", "fare_parsers", "cancel_cards", "fee_rules"):
        payload = load_default(KEYS_BY_NAME[name])
        assert set(payload["apps"]) == set(REAL_PACKAGES) | set(DEMO_PACKAGES), name
        for pkg in REAL_PACKAGES:
            assert UNVERIFIED_NOTE in payload["notes"][pkg], (name, pkg)
    launch = load_default(KEYS_BY_NAME["launch_templates"])
    assert set(launch["apps"]) == set(REAL_PACKAGES)
    assert all(UNVERIFIED_NOTE in launch["notes"][pkg] for pkg in REAL_PACKAGES)


def test_launch_modes_are_the_phones_three() -> None:
    """core LaunchMode knows uri / launch_paste / launch only: any other mode rejects the whole payload."""
    for tpl in load_default(KEYS_BY_NAME["launch_templates"])["apps"].values():
        assert tpl["mode"] in LAUNCH_MODES and tpl.get("fallback", "launch") in ("launch", "launch_paste")


@pytest.mark.parametrize(
    "entry",
    [
        {"mode": "demo", "asset_dir": "demo/kestrel"},
        {"mode": "uri", "uri": "https://m.example/?to={dlat},{dlng}&via={stop}", "fallback": "launch"},
        {"mode": "uri", "uri": "https://m.example/?to={dlat}", "fallback": "uri"},
        {"mode": "uri", "uri": "http://m.example/?to={dlat}", "fallback": "launch"},
    ],
)
def test_launch_templates_reject_what_the_phone_rejects(entry: dict) -> None:
    key = KEYS_BY_NAME["launch_templates"]
    payload = copy.deepcopy(load_default(key))
    payload["apps"]["com.ubercab"] = entry
    assert validate_key(key, payload)


def test_launch_templates_require_every_real_app() -> None:
    key = KEYS_BY_NAME["launch_templates"]
    payload = copy.deepcopy(load_default(key))
    del payload["apps"]["com.rapido.passenger"]
    assert any("com.rapido.passenger" in e for e in validate_key(key, payload))


def test_demo_providers_cover_every_event() -> None:
    payload = load_default(KEYS_BY_NAME["notification_templates"])
    for pkg in DEMO_PACKAGES:
        events = {t["event"] for t in payload["apps"][pkg][0]["templates"]}
        assert events == set(EVENTS), pkg


def test_contract_fee_rules_and_launch_templates_are_exact() -> None:
    fee = load_default(KEYS_BY_NAME["fee_rules"])
    assert fee["default"] == {"pct": 10, "max_inr": 100, "free_while_searching": True}
    assert fee["apps"]["com.olacabs.customer"] == {
        "free_within_s_after_accept": 180,
        "free_if_driver_late_min": 5,
        "max_inr": 100,
    }
    assert fee["apps"]["com.ubercab"] == {"free_within_s_after_accept": 120, "max_inr": 100}
    assert fee["apps"]["com.rapido.passenger"] == {"free_within_s_after_accept": 60, "max_inr": 50}
    assert fee["states"] == {"AS": {"max_inr": 100}}
    launch = load_default(KEYS_BY_NAME["launch_templates"])["apps"]
    assert launch["com.olacabs.customer"] == {"mode": "launch_paste"}
    assert launch["com.rapido.passenger"] == {"mode": "launch_paste"}
    assert launch["com.ubercab"]["fallback"] == "launch"
    for placeholder in ("{plat}", "{plng}", "{pname}", "{dlat}", "{dlng}", "{dname}"):
        assert placeholder in launch["com.ubercab"]["uri"]
    assert "client_id" not in launch["com.ubercab"]["uri"]
