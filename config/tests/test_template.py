from __future__ import annotations

import json
import xml.etree.ElementTree as ET
from pathlib import Path

import pytest
from ridemate_config.keys import (
    ANDROID_ASSETS_CONFIG_DIR,
    ANDROID_DEFAULTS_XML,
    KEYS,
    KEYS_BY_NAME,
    SERVER_ONLY_KEYS,
)
from ridemate_config.template import BuildError, build_template, bundled_outputs, stale_bundled_files
from ridemate_config.validation import default_value

URL = "https://ibaharul567--ridemate-api.modal.run"


def test_template_has_every_key_with_rest_shape() -> None:
    params = build_template(URL)["parameters"]
    assert list(params) == [k.name for k in KEYS]
    for key in KEYS:
        p = params[key.name]
        assert set(p) == {"defaultValue", "valueType", "description"}
        assert p["valueType"] == key.value_type
        assert isinstance(p["defaultValue"]["value"], str)
        assert 0 < len(p["description"]) <= 256


def test_template_values_round_trip_to_defaults() -> None:
    params = build_template(URL)["parameters"]
    for key in KEYS:
        raw = params[key.name]["defaultValue"]["value"]
        if key.name == "api_base_url":
            assert raw == URL
        elif key.value_type == "JSON":
            assert json.loads(raw) == default_value(key.name)
        elif key.value_type == "BOOLEAN":
            assert raw == ("true" if default_value(key.name) else "false")
        elif key.value_type == "NUMBER":
            assert int(raw) == default_value(key.name)
        else:
            assert raw == default_value(key.name)


def test_json_values_keep_unicode_and_are_compact() -> None:
    raw = build_template(URL)["parameters"]["notification_templates"]["defaultValue"]["value"]
    assert "रद्द" in raw and "\\u" not in raw
    assert ": " not in raw.split('"pattern"')[0]


@pytest.mark.parametrize("bad", ["http://insecure.example", "https://x.example/?q=1", "", "ftp://x"])
def test_api_base_url_must_be_https(bad: str) -> None:
    with pytest.raises(BuildError):
        build_template(bad)


def test_no_identity_list_is_published_or_bundled() -> None:
    """Judge/tester and owner email hashes stay server-side (contracts §2.7): not in the template, not in the APK."""
    params = build_template(URL)["parameters"]
    assert not SERVER_ONLY_KEYS & set(params)
    bundled = {path.name for path in bundled_outputs()}
    assert not {f"{key}.json" for key in SERVER_ONLY_KEYS} & bundled
    xml = next(content for path, content in bundled_outputs().items() if path.suffix == ".xml")
    assert not any(key in xml for key in SERVER_ONLY_KEYS)


def test_bundled_defaults_are_up_to_date() -> None:
    assert stale_bundled_files() == [], (
        "run: uv run --project eval python config/build_template.py --bundled-only"
    )


def test_bundled_assets_match_defaults_and_api_base_url_is_empty() -> None:
    outputs = bundled_outputs()
    for key in KEYS:
        asset = ANDROID_ASSETS_CONFIG_DIR / f"{key.name}.json"
        if key.is_json:
            assert json.loads(outputs[asset]) == default_value(key.name)
        else:
            assert asset not in outputs
    root = ET.fromstring(outputs[ANDROID_DEFAULTS_XML].encode())
    entries = {e.findtext("key"): e.findtext("value") or "" for e in root.findall("entry")}
    assert set(entries) == set(KEYS_BY_NAME)
    assert entries["api_base_url"] == ""
    assert entries["ai_reader_enabled"] == "true"
    assert entries["min_supported_version"] == "1"
    assert json.loads(entries["fare_parsers"]) == default_value("fare_parsers")


def test_cli_writes_template_and_bundled(tmp_path: Path) -> None:
    import build_template as cli

    out = tmp_path / "rc.json"
    assert cli.main(["--api-base-url", URL, "--out", str(out)]) == 0
    assert json.loads(out.read_text())["parameters"]["api_base_url"]["defaultValue"]["value"] == URL
    assert stale_bundled_files() == []


def test_cli_requires_url_unless_bundled_only() -> None:
    import build_template as cli

    with pytest.raises(SystemExit):
        cli.main([])
    assert cli.main(["--bundled-only"]) == 0
