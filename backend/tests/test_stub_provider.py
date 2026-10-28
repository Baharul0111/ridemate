"""Stub model provider: gating, determinism, schema-valid canned answers, and use through the real router."""

from __future__ import annotations

import time
from typing import Any

import pytest

from ridemate_api.clock import FakeClock
from ridemate_api.interfaces import AiStatus, ApiError, Principal, Purpose, Reservation
from ridemate_api.model_gateway import ImagePart, ModelUnavailable, StructuredMode
from ridemate_api.prompts import (
    PARSE_SCHEMA,
    POINT_SCHEMA,
    READ_SCHEMA,
    parse_request,
    place_request,
    point_request,
    read_request,
    reread_request,
    validate_parse,
    validate_place,
    validate_point,
    validate_read,
    validate_reread,
)
from ridemate_api.router import DEFAULT_PROVIDER, ProviderRouter, TaskKind
from ridemate_api.settings import PINNED_MODEL_ID, Settings
from ridemate_api.stub_provider import (
    CANNED_READS,
    DEFAULT_LATENCY_MS,
    StubProvider,
    StubProviderForbidden,
    build_stub_providers,
    ensure_stub_allowed,
    parse_latency_ms,
    stub_allowed,
)

OK_ENV = {"RIDEMATE_STUB_PROVIDER": "1", "MODAL_ENVIRONMENT": "loadtest"}
PNG = b"\x89PNG\r\n\x1a\n" + b"\x00" * 64


def _stub(**kwargs: Any) -> StubProvider:
    kwargs.setdefault("environ", OK_ENV)
    kwargs.setdefault("latency_ms", {task: 0.0 for task in DEFAULT_LATENCY_MS})
    return StubProvider(**kwargs)


# ---- a small JSON-Schema checker for the subset prompts.py uses -------------------------------------------


def _check(schema: dict[str, Any], value: Any, path: str = "$") -> None:
    types = schema.get("type")
    if types is not None:
        allowed = types if isinstance(types, list) else [types]
        py = {
            "object": dict,
            "array": list,
            "string": str,
            "integer": int,
            "null": type(None),
            "boolean": bool,
            "number": (int, float),
        }
        ok = any(
            isinstance(value, py[t]) and not (t in ("integer", "number") and isinstance(value, bool))
            for t in allowed
        )
        assert ok, f"{path}: {value!r} is not {allowed}"
    if value is None:
        return
    if "enum" in schema:
        assert value in schema["enum"], f"{path}: {value!r} not in enum"
    if isinstance(value, dict):
        props = schema.get("properties", {})
        for key in schema.get("required", []):
            assert key in value, f"{path}: missing {key}"
        if schema.get("additionalProperties") is False:
            extra = set(value) - set(props)
            assert not extra, f"{path}: extra keys {extra}"
        for key, sub in props.items():
            if key in value:
                _check(sub, value[key], f"{path}.{key}")
    if isinstance(value, list):
        assert len(value) <= schema.get("maxItems", len(value)), f"{path}: too many items"
        assert len(value) >= schema.get("minItems", 0), f"{path}: too few items"
        for i, item in enumerate(value):
            _check(schema.get("items", {}), item, f"{path}[{i}]")
    if isinstance(value, int) and not isinstance(value, bool):
        assert value >= schema.get("minimum", value), f"{path}: below minimum"
        assert value <= schema.get("maximum", value), f"{path}: above maximum"
    if isinstance(value, str):
        assert len(value) <= schema.get("maxLength", len(value)), f"{path}: too long"


# ---- gating -----------------------------------------------------------------------------------------------


@pytest.mark.parametrize(
    "env",
    [
        {},
        {"RIDEMATE_STUB_PROVIDER": "1"},  # unset environment counts as main
        {"RIDEMATE_STUB_PROVIDER": "1", "MODAL_ENVIRONMENT": "main"},
        {"RIDEMATE_STUB_PROVIDER": "1", "MODAL_ENVIRONMENT": " Main "},
        {"RIDEMATE_STUB_PROVIDER": "1", "MODAL_ENVIRONMENT": ""},
        {"RIDEMATE_STUB_PROVIDER": "true", "MODAL_ENVIRONMENT": "dev"},
        {"RIDEMATE_STUB_PROVIDER": "0", "MODAL_ENVIRONMENT": "dev"},
    ],
)
def test_stub_refused_without_flag_or_in_main(env: dict[str, str]) -> None:
    assert not stub_allowed(environ=env)
    with pytest.raises(StubProviderForbidden):
        StubProvider(environ=env)
    with pytest.raises(StubProviderForbidden):
        build_stub_providers(environ=env)


def test_stub_refused_when_settings_say_main_even_if_env_does_not() -> None:
    with pytest.raises(StubProviderForbidden):
        ensure_stub_allowed(Settings(environment="main"), OK_ENV)
    ensure_stub_allowed(Settings(environment="dev"), OK_ENV)


def test_stub_refused_from_process_environment(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("RIDEMATE_STUB_PROVIDER", "1")
    monkeypatch.setenv("MODAL_ENVIRONMENT", "main")
    with pytest.raises(StubProviderForbidden):
        StubProvider()
    monkeypatch.setenv("MODAL_ENVIRONMENT", "loadtest")
    assert StubProvider().model_id == PINNED_MODEL_ID


def test_build_stub_providers_registers_default_name() -> None:
    providers = build_stub_providers(environ={**OK_ENV, "RIDEMATE_STUB_LATENCY_MS": "read=5"})
    assert list(providers) == [DEFAULT_PROVIDER]
    stub = providers[DEFAULT_PROVIDER]
    assert stub.model_id == PINNED_MODEL_ID


# ---- configuration ----------------------------------------------------------------------------------------


def test_parse_latency_overrides_defaults() -> None:
    latency = parse_latency_ms("read=100, parse=5")
    assert latency["read"] == 100 and latency["parse"] == 5
    assert latency["reread"] == DEFAULT_LATENCY_MS["reread"]
    assert parse_latency_ms("") == dict(DEFAULT_LATENCY_MS)


@pytest.mark.parametrize("spec", ["bogus=1", "read", "read=-1", "read=nan", "read=abc"])
def test_parse_latency_rejects_bad_specs(spec: str) -> None:
    with pytest.raises(ValueError):
        parse_latency_ms(spec)


def test_constructor_rejects_bad_values() -> None:
    with pytest.raises(ValueError):
        _stub(jitter=1.0)
    with pytest.raises(ValueError):
        _stub(fail_every=-1)


def test_latency_is_deterministic_and_within_jitter() -> None:
    stub = _stub(latency_ms={"read": 1000.0}, jitter=0.2)
    req = read_request(ImagePart(PNG, "image/png"))
    first = stub.latency_s(req)
    assert first == stub.latency_s(req)
    assert 0.8 <= first <= 1.2
    other = read_request(ImagePart(PNG + b"x", "image/png"))
    assert 0.8 <= stub.latency_s(other) <= 1.2


async def test_latency_is_actually_waited() -> None:
    stub = _stub(latency_ms={"parse": 50.0}, jitter=0.0)
    req = parse_request({"title": "t", "text": "Captain is on the way"})
    started = time.monotonic()
    await stub.complete(req)
    assert time.monotonic() - started >= 0.045


# ---- answers ----------------------------------------------------------------------------------------------


def test_canned_reads_are_fixed_points_of_validation() -> None:
    for read in CANNED_READS:
        _check(READ_SCHEMA, read)
        assert validate_read(read) == read


async def test_read_is_deterministic_and_schema_valid() -> None:
    stub = _stub()
    seen = set()
    for i in range(40):
        req = read_request(ImagePart(PNG + bytes([i]), "image/png"))
        a = await stub.complete(req)
        b = await stub.complete(req)
        assert a.data == b.data
        _check(READ_SCHEMA, a.data)
        assert validate_read(a.data) == a.data
        assert a.model == PINNED_MODEL_ID and a.mode is StructuredMode.JSON_SCHEMA
        assert (
            a.usage is not None and a.usage.input_tokens > 0 and 0 < a.usage.output_tokens <= req.max_tokens
        )
        seen.add(a.data["screen_type"])
    assert {"fare_options", "driver_assigned"} <= seen  # the hash spreads over the canned screens
    assert stub.calls["read"] == 80


async def test_read_result_is_a_copy() -> None:
    stub = _stub()
    req = read_request(ImagePart(PNG, "image/png"))
    first = await stub.complete(req)
    first.data["options"].clear()
    assert (await stub.complete(req)).data == validate_read((await stub.complete(req)).data)


async def test_reread_answers_every_row_id() -> None:
    stub = _stub()
    crops = [(rid, ImagePart(PNG + rid.encode(), "image/png")) for rid in ("r1", "r2", "r3")]
    req = reread_request(crops)
    result = await stub.complete(req)
    _check(req.schema, result.data)
    rows = validate_reread(result.data, ["r1", "r2", "r3"])
    assert [r["row_id"] for r in rows] == ["r1", "r2", "r3"]
    assert all(r["price_min"] is not None and r["price_min"] == r["price_max"] for r in rows)


async def test_point_returns_a_valid_box() -> None:
    result = await _stub().complete(point_request(ImagePart(PNG, "image/png"), "cancel_button"))
    _check(POINT_SCHEMA, result.data)
    assert validate_point(result.data) == result.data["box_0_1000"]


@pytest.mark.parametrize(
    ("title", "text", "label"),
    [
        ("Rapido", "Captain Ravi is on the way", "assigned"),
        ("Uber", "Your driver cancelled the trip. Finding you a new driver", "driver_cancelled"),
        ("Ola", "Looking for a driver near you", "searching"),
        ("Kestrel Go", "Your ride was cancelled", "cancelled"),
        ("Ola", "Your driver has arrived", "arriving"),
        ("Rapido", "आपकी राइड रद्द हो गई", "cancelled"),
        ("Pepper", "Trip completed. Thanks!", "ended"),
        ("Uber", "No drivers available right now", "no_drivers"),
    ],
)
async def test_parse_labels_with_a_real_substring(title: str, text: str, label: str) -> None:
    result = await _stub().complete(parse_request({"app": "rapido", "title": title, "text": text}))
    _check(PARSE_SCHEMA, result.data)
    assert result.data["label"] == label
    # The server's substring rule keeps the label.
    assert validate_parse(result.data, title, text) == (label, result.data["evidence_span"])


async def test_parse_unknown_when_nothing_matches() -> None:
    result = await _stub().complete(parse_request({"title": "Promo", "text": "50% off this weekend"}))
    assert result.data == {"label": "unknown", "evidence_span": None}
    _check(PARSE_SCHEMA, result.data)


async def test_place_pick_matches_words_or_none() -> None:
    candidates = [("c1", "Paltan Bazaar, Guwahati"), ("c2", "LGBI Airport, Guwahati")]
    req = place_request("take me to the airport", "en-IN", candidates)
    result = await _stub().complete(req)
    _check(req.schema, result.data)
    assert validate_place(result.data, ["c1", "c2"]) == "c2"
    none = await _stub().complete(place_request("zoo", "en-IN", candidates))
    assert none.data == {"pick": "none"}


async def test_usage_can_be_zero() -> None:
    result = await _stub(report_usage=False).complete(read_request(ImagePart(PNG, "image/png")))
    assert result.usage is not None
    assert (result.usage.input_tokens, result.usage.output_tokens) == (0, 0)


async def test_requested_mode_is_echoed() -> None:
    result = await _stub().complete(read_request(ImagePart(PNG, "image/png")), mode=StructuredMode.TOOL)
    assert result.mode is StructuredMode.TOOL


async def test_fail_every_injects_unavailable() -> None:
    stub = _stub(fail_every=2)
    req = parse_request({"title": "x", "text": "on the way"})
    await stub.complete(req)
    with pytest.raises(ModelUnavailable) as err:
        await stub.complete(req)
    assert err.value.status == 503 and err.value.sent
    await stub.complete(req)


async def test_list_models_and_close() -> None:
    stub = _stub()
    assert await stub.list_models() == [PINNED_MODEL_ID]
    await stub.aclose()


# ---- through the real ProviderRouter ----------------------------------------------------------------------


class _Spend:
    def __init__(self) -> None:
        self.reserved: list[Reservation] = []
        self.settled: list[tuple[str, int, int]] = []

    def reserve(
        self, *, purpose: Purpose, max_usd: float, principal: Principal | None, request_id: str
    ) -> Reservation:
        r = Reservation(
            id=request_id, purpose=purpose, reserved_usd=max_usd, shard="s0", request_id=request_id
        )
        self.reserved.append(r)
        return r

    def settle(
        self, reservation: Reservation, *, input_tokens: int, cached_input_tokens: int, output_tokens: int
    ) -> float:
        self.settled.append((reservation.id, input_tokens, output_tokens))
        return 0.0

    def keep(self, reservation: Reservation) -> float:
        return reservation.reserved_usd

    def release(self, reservation: Reservation) -> None:
        return None

    def ai_status(self, principal: Principal | None) -> AiStatus:
        return AiStatus(True)

    def record_compute(self, **_: Any) -> float:
        return 0.0

    def summary(self) -> dict[str, Any]:
        return {}


class _RC:
    def get_bool(self, key: str, default: bool) -> bool:
        return default

    def get_int(self, key: str, default: int) -> int:
        return default

    def get_str(self, key: str, default: str) -> str:
        return default

    def get_json(self, key: str, default: Any) -> Any:
        return (
            {"schema": "1.0", "read": DEFAULT_PROVIDER, "parse": DEFAULT_PROVIDER}
            if key == "model_route"
            else default
        )


class _Metrics:
    def incr(self, name: str, n: int = 1, **labels: str) -> None:
        return None

    def observe_ms(self, name: str, value_ms: float, **labels: str) -> None:
        return None


def _router(stub: StubProvider, spend: _Spend) -> ProviderRouter:
    return ProviderRouter(
        providers={stub.name: stub}, spend=spend, remote_config=_RC(), metrics=_Metrics(), clock=FakeClock()
    )


async def test_router_meters_and_returns_stub_answers() -> None:
    spend = _Spend()
    stub = _stub()
    router = _router(stub, spend)
    result = await router.call(
        read_request(ImagePart(PNG, "image/png")),
        task=TaskKind.READ,
        purpose=Purpose.READ,
        principal=None,
        request_id="req-1",
    )
    assert result.provider == DEFAULT_PROVIDER and result.model == PINNED_MODEL_ID
    assert validate_read(result.data) == result.data
    assert await router.model_available() is True
    assert len(spend.reserved) == 1 and spend.reserved[0].purpose is Purpose.READ
    assert spend.settled and spend.settled[0][1] > 0


async def test_router_retries_a_read_after_an_injected_failure() -> None:
    spend = _Spend()
    stub = _stub(fail_every=1)
    router = _router(stub, spend)
    with pytest.raises(ApiError) as err:
        await router.call(
            read_request(ImagePart(PNG, "image/png")),
            task=TaskKind.READ,
            purpose=Purpose.READ,
            principal=None,
            request_id="req-2",
        )
    assert err.value.status == 503 and err.value.code == "ai_unavailable"
    assert stub.calls["read"] == 2  # one attempt + the one retry the tap budget allows
