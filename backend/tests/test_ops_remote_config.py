"""remote_config.py: template read, 60 s cache, fallbacks (last good → bundled → built-in → caller)."""

from __future__ import annotations

import json
import threading
from pathlib import Path

import httpx
import respx

from ridemate_api.clock import FakeClock
from ridemate_api.remote_config import (
    REMOTE_CONFIG_SCOPE,
    TEMPLATE_URL,
    RemoteConfigRest,
    build_remote_config,
    load_bundled_defaults,
    parse_template,
)
from tests.test_ops_fakes import (
    PROJECT,
    TOKEN_URI,
    FakeTokens,
    assertion_claims,
    make_settings,
    sa_json,
    token_response,
)

URL = TEMPLATE_URL.format(project=PROJECT)
REPO_DEFAULTS = Path(__file__).resolve().parents[2] / "config" / "remote_config"

TEMPLATE = {
    "conditions": [{"name": "rollout_10", "expression": "percent <= 10"}],
    "parameters": {
        "ai_reader_enabled": {
            "defaultValue": {"value": "false"},
            "conditionalValues": {"rollout_10": {"value": "true"}},
            "valueType": "BOOLEAN",
        },
        "min_supported_version": {"defaultValue": {"value": "3"}, "valueType": "NUMBER"},
        "cap_profile": {"defaultValue": {"useInAppDefault": True}, "valueType": "STRING"},
    },
    "parameterGroups": {
        "models": {
            "parameters": {
                "model_route": {
                    "defaultValue": {"value": '{"schema":"1.0","read":"other","parse":"modal_spark_open"}'},
                    "valueType": "JSON",
                }
            }
        }
    },
    "version": {"versionNumber": "7"},
}


def reader(clock: FakeClock | None = None, defaults_dir: Path | None = None, tokens=None) -> RemoteConfigRest:
    return RemoteConfigRest(
        project_id=PROJECT,
        tokens=tokens if tokens is not None else FakeTokens(),
        clock=clock or FakeClock(),
        defaults_dir=defaults_dir,
        background_refresh=False,
    )


def test_template_defaults_are_read_including_groups_and_ignoring_conditions():
    values = parse_template(TEMPLATE)
    assert values["ai_reader_enabled"] == "false"
    assert values["min_supported_version"] == "3"
    assert "cap_profile" not in values  # useInAppDefault leaves it to our defaults
    assert json.loads(values["model_route"])["read"] == "other"


@respx.mock
def test_live_values_are_typed_and_cached_for_60_seconds():
    route = respx.get(URL).mock(return_value=httpx.Response(200, json=TEMPLATE))
    clock = FakeClock()
    rc = reader(clock)
    assert rc.get_bool("ai_reader_enabled", True) is False
    assert rc.get_int("min_supported_version", 1) == 3
    assert rc.get_json("model_route", {})["read"] == "other"
    assert rc.get_str("cap_profile", "x") == "normal"  # built-in default
    assert route.call_count == 1
    assert route.calls[0].request.headers["Authorization"] == "Bearer fake-access-token"
    clock.advance(59)
    rc.get_bool("ai_reader_enabled", True)
    assert route.call_count == 1
    clock.advance(1)
    rc.get_bool("ai_reader_enabled", True)
    assert route.call_count == 2
    assert rc.live


@respx.mock
def test_errors_fall_back_to_the_last_good_template_without_hammering():
    route = respx.get(URL)
    route.side_effect = [httpx.Response(200, json=TEMPLATE), httpx.Response(503), httpx.ConnectError("down")]
    clock = FakeClock()
    rc = reader(clock)
    assert rc.get_bool("ai_reader_enabled", True) is False
    clock.advance(61)
    assert rc.get_bool("ai_reader_enabled", True) is False  # 503 → last good
    assert rc.last_error == "HTTPStatusError"
    rc.get_int("min_supported_version", 1)
    assert route.call_count == 2  # backs off for a cache period after a failure
    clock.advance(61)
    assert rc.get_int("min_supported_version", 1) == 3  # connect error → last good
    assert route.call_count == 3


@respx.mock
def test_without_any_template_the_bundled_then_builtin_then_caller_defaults_apply(tmp_path):
    respx.get(URL).mock(return_value=httpx.Response(500))
    (tmp_path / "ai_reader_enabled.json").write_text('{"value": false}')
    (tmp_path / "min_supported_version.json").write_text('{"value": 2}')
    (tmp_path / "model_route.json").write_text('{"schema": "1.0", "read": "bundled", "parse": "bundled"}')
    (tmp_path / "broken.json").write_text("{nope")
    rc = reader(defaults_dir=tmp_path)
    assert rc.get_bool("ai_reader_enabled", True) is False
    assert rc.get_int("min_supported_version", 9) == 2
    assert rc.get_json("model_route", {})["read"] == "bundled"
    assert rc.get_str("cap_profile", "x") == "normal"
    assert rc.get_bool("ondc_enabled", False) is True
    assert rc.get_str("no_such_key", "caller") == "caller"
    assert rc.get_json("no_such_key", {"a": 1}) == {"a": 1}


class ExplodingTokens:
    def token(self) -> str:
        raise RuntimeError("an error nobody anticipated")


@respx.mock
def test_an_unexpected_fetch_error_never_reaches_the_request():
    route = respx.get(URL)
    rc = reader(tokens=ExplodingTokens())
    assert rc.get_bool("ai_reader_enabled", False) is True  # built-in default
    assert rc.last_error == "RuntimeError"
    assert route.call_count == 0
    respx.get(URL).mock(side_effect=RuntimeError("client closed"))
    rc = reader()
    assert rc.get_int("min_supported_version", 9) == 1
    assert rc.last_error == "RuntimeError"


@respx.mock
def test_a_live_value_of_the_wrong_type_falls_back_to_the_bundled_default(tmp_path):
    (tmp_path / "ai_reader_enabled.json").write_text('{"value": false}')
    (tmp_path / "budget_horizon_days.json").write_text('{"value": 45}')
    template = {
        "parameters": {
            "ai_reader_enabled": {"defaultValue": {"value": "flase"}},  # a typo in the console
            "budget_horizon_days": {"defaultValue": {"value": "ninety"}},
            "model_route": {"defaultValue": {"value": "{not json"}},
        }
    }
    respx.get(URL).mock(return_value=httpx.Response(200, json=template))
    rc = reader(defaults_dir=tmp_path)
    assert rc.get_bool("ai_reader_enabled", True) is False  # the bundled kill-switch value, not the caller's
    assert rc.get_int("budget_horizon_days", 90) == 45
    assert rc.get_json("model_route", {})["read"] == "modal_spark_open"  # built-in default
    assert rc.get_str("ai_reader_enabled", "x") == "flase"  # a string is whatever is live


def test_the_repository_defaults_load():
    values = load_bundled_defaults(REPO_DEFAULTS)
    if not REPO_DEFAULTS.is_dir():
        assert values == {}
        return
    assert values["ai_reader_enabled"] in ("true", "false")
    assert json.loads(values["model_route"])["schema"].startswith("1.")
    # Identity lists are server-only (contracts §2.7): neither ships in the client template's defaults.
    assert "owner_emails_sha256" not in values and "judge_allowlist" not in values
    assert values["better_ride_enabled"] in ("true", "false")
    assert values["cap_profile"] in ("normal", "judging")  # scalar files unwrap {"value": ...}
    # The day-1 spike decides this default (docs/spikes.md); either way the bool file unwraps to "true"/"false".
    spiked = json.loads((REPO_DEFAULTS / "point_supported.json").read_text(encoding="utf-8"))["value"]
    assert isinstance(spiked, bool) and values["point_supported"] == str(spiked).lower()


def test_point_supported_defaults_to_false_even_without_the_bundled_files():
    rc = RemoteConfigRest(project_id="", tokens=None, clock=FakeClock(), defaults_dir=None)
    assert rc.get_bool("point_supported", True) is False  # the built-in default wins over a caller's guess


def test_other_default_file_shapes(tmp_path):
    (tmp_path / "flags.json").write_text('{"demo_enabled": false, "budget_horizon_days": 30}')
    (tmp_path / "template.json").write_text(json.dumps(TEMPLATE))
    (tmp_path / "api_base_url.json").write_text('"https://example.modal.run"')
    values = load_bundled_defaults(tmp_path)
    assert values["demo_enabled"] == "false"
    assert values["budget_horizon_days"] == "30"
    assert values["min_supported_version"] == "3"
    assert values["api_base_url"] == "https://example.modal.run"
    assert load_bundled_defaults(tmp_path / "missing") == {}


@respx.mock
def test_typed_getters_never_raise_on_bad_values():
    template = {
        "parameters": {
            "b": {"defaultValue": {"value": "maybe"}},
            "on": {"defaultValue": {"value": "ON"}},
            "zero": {"defaultValue": {"value": "0"}},
            "i": {"defaultValue": {"value": "90.0"}},
            "half": {"defaultValue": {"value": "2.5"}},
            "word": {"defaultValue": {"value": "ninety"}},
            "j": {"defaultValue": {"value": "{bad json"}},
        }
    }
    respx.get(URL).mock(return_value=httpx.Response(200, json=template))
    rc = reader()
    assert rc.get_bool("b", True) is True
    assert rc.get_bool("on", False) is True
    assert rc.get_bool("zero", True) is False
    assert rc.get_int("i", 1) == 90
    assert rc.get_int("half", 1) == 1
    assert rc.get_int("word", 7) == 7
    assert rc.get_json("j", ["d"]) == ["d"]


@respx.mock
def test_unconfigured_reader_never_calls_out():
    route = respx.get(URL)
    rc = RemoteConfigRest(project_id=PROJECT, tokens=None, clock=FakeClock())
    assert rc.get_bool("ai_reader_enabled", False) is True
    assert route.call_count == 0
    assert not rc.configured


def test_only_one_caller_refreshes_at_a_time():
    rc = reader()
    rc._refresh_lock.acquire()
    try:
        assert rc.refresh() is False
        assert rc.get_bool("ai_reader_enabled", False) is True  # served from defaults meanwhile
    finally:
        rc._refresh_lock.release()


@respx.mock
def test_production_wiring_uses_the_remote_config_scope():
    token_route = respx.post(TOKEN_URI).mock(return_value=token_response("rc-token"))
    route = respx.get(URL).mock(return_value=httpx.Response(200, json=TEMPLATE))
    rc = build_remote_config(make_settings(web_sa_json=sa_json()), clock=FakeClock())
    assert rc.get_bool("ai_reader_enabled", True) is False
    assert assertion_claims(token_route.calls[0].request)["scope"] == REMOTE_CONFIG_SCOPE
    assert route.calls[0].request.headers["Authorization"] == "Bearer rc-token"


def test_production_wiring_without_a_key_serves_defaults():
    rc = build_remote_config(make_settings(web_sa_json=""), clock=FakeClock())
    assert not rc.configured
    rc_bad = build_remote_config(make_settings(web_sa_json="not json"), clock=FakeClock())
    assert not rc_bad.configured
    assert rc_bad.get_str("cap_profile", "x") == "normal"


def test_concurrent_reads_are_safe():
    rc = reader(tokens=None)
    results: list[bool] = []
    threads = [
        threading.Thread(target=lambda: results.append(rc.get_bool("demo_enabled", False))) for _ in range(8)
    ]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    assert results == [True] * 8


@respx.mock
def test_stale_values_are_served_while_a_background_refresh_runs():
    started, release = threading.Event(), threading.Event()
    fresh = {"parameters": {"ai_reader_enabled": {"defaultValue": {"value": "true"}}}}

    def respond(request):
        if started.is_set():
            release.wait(5)
            return httpx.Response(200, json=fresh)
        started.set()
        return httpx.Response(200, json=TEMPLATE)

    respx.get(URL).mock(side_effect=respond)
    clock = FakeClock()
    rc = RemoteConfigRest(project_id=PROJECT, tokens=FakeTokens(), clock=clock)
    assert rc.get_bool("ai_reader_enabled", True) is False  # the first lookup waits for the template
    clock.advance(61)
    assert rc.get_bool("ai_reader_enabled", True) is False  # stale value, refresh started elsewhere
    assert rc.get_bool("ai_reader_enabled", True) is False  # no second refresh while one is in flight
    release.set()
    for thread in [t for t in threading.enumerate() if t.name == "remote-config-refresh"]:
        thread.join(5)
    assert rc.get_bool("ai_reader_enabled", False) is True
    assert respx.calls.call_count == 2
