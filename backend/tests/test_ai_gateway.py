"""ModelGateway against a respx-mocked OpenAI-compatible endpoint (no network)."""

from __future__ import annotations

import json
from typing import Any

import httpx
import pytest
import respx

from ridemate_api import prompts
from ridemate_api.model_gateway import (
    ImagePart,
    ModelBadOutput,
    ModelGateway,
    ModelTimeout,
    ModelUnavailable,
    ModelUnsupported,
    StructuredMode,
    TextPart,
    parse_json_object,
    parse_usage,
)
from ridemate_api.router import ProviderRouter, TaskKind
from ridemate_api.settings import MAX_TOKENS_READ, PINNED_MODEL_ID

from .test_ai_fakes import FakeMetrics, FakeRemoteConfig, FakeSpend

BASE = "https://spark.example.test"
CHAT = f"{BASE}/v1/chat/completions"
PNG = b"\x89PNG\r\n\x1a\n" + b"\x00" * 32
READ = {"app": "uber", "screen_type": "fare_options", "options": [], "fee_text": None,
        "driver_eta_min": None, "driver_distance_text": None}  # fmt: skip


def completion(
    *, content: str | None = None, tool_args: str | None = None, usage: dict[str, Any] | None = None
) -> dict[str, Any]:
    message: dict[str, Any] = {"role": "assistant", "content": content}
    if tool_args is not None:
        message["tool_calls"] = [
            {
                "id": "call_1",
                "type": "function",
                "function": {"name": "channel_a_read", "arguments": tool_args},
            }
        ]
    body: dict[str, Any] = {
        "id": "cmpl-1",
        "object": "chat.completion",
        "created": 1,
        "model": PINNED_MODEL_ID,
        "choices": [{"index": 0, "message": message, "finish_reason": "stop"}],
    }
    if usage is not None:
        body["usage"] = usage
    return body


USAGE = {
    "prompt_tokens": 1500,
    "completion_tokens": 300,
    "total_tokens": 1800,
    "prompt_tokens_details": {"cached_tokens": 200},
    "completion_tokens_details": {"reasoning_tokens": 120},
}


def unsupported(what: str) -> httpx.Response:
    return httpx.Response(400, json={"object": "error", "message": f"{what} is not supported", "code": 400})


def gateway(**kwargs: Any) -> ModelGateway:
    return ModelGateway(
        name="modal_spark_open", base_url=BASE, api_key="wk-id.ws-secret", model_id=PINNED_MODEL_ID, **kwargs
    )


def read_request() -> Any:
    return prompts.read_request(ImagePart(PNG, "image/png"))


@respx.mock
async def test_json_schema_mode_sends_strict_schema_low_reasoning_and_image_data_url() -> None:
    route = respx.post(CHAT).mock(
        return_value=httpx.Response(200, json=completion(content=json.dumps(READ), usage=USAGE))
    )
    result = await gateway().complete(read_request())

    assert result.mode is StructuredMode.JSON_SCHEMA
    assert result.data == READ
    assert result.model == PINNED_MODEL_ID
    sent = json.loads(route.calls.last.request.content)
    assert route.calls.last.request.headers["authorization"] == "Bearer wk-id.ws-secret"
    assert sent["model"] == PINNED_MODEL_ID
    assert sent["reasoning_effort"] == "low"
    assert sent["max_tokens"] == MAX_TOKENS_READ
    assert sent["temperature"] == 0
    assert sent["response_format"]["type"] == "json_schema"
    assert sent["response_format"]["json_schema"]["strict"] is True
    assert sent["response_format"]["json_schema"]["schema"]["properties"]["options"]["maxItems"] == 12
    assert "tools" not in sent
    user_content = sent["messages"][1]["content"]
    image = next(part for part in user_content if part["type"] == "image_url")
    assert image["image_url"]["url"].startswith("data:image/png;base64,")


@respx.mock
async def test_usage_is_parsed_for_settlement() -> None:
    respx.post(CHAT).mock(
        return_value=httpx.Response(200, json=completion(content=json.dumps(READ), usage=USAGE))
    )
    result = await gateway().complete(read_request())
    assert result.usage is not None
    assert result.usage.input_tokens == 1500
    assert result.usage.cached_input_tokens == 200
    assert result.usage.output_tokens == 300  # reasoning already inside completion_tokens
    assert result.usage.reasoning_tokens == 120


def test_usage_reported_separately_is_added_and_missing_usage_is_none() -> None:
    separate = parse_usage(
        {"prompt_tokens": 10, "completion_tokens": 5, "completion_tokens_details": {"reasoning_tokens": 40}}
    )
    assert separate is not None and separate.output_tokens == 45
    clamped = parse_usage(
        {"prompt_tokens": 10, "completion_tokens": 1, "prompt_tokens_details": {"cached_tokens": 99}}
    )
    assert clamped is not None and clamped.cached_input_tokens == 10
    assert parse_usage(None) is None


@respx.mock
async def test_tool_mode_after_json_schema_is_unsupported_and_the_downgrade_sticks() -> None:
    def reply(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        if "response_format" in body:
            return unsupported("response_format json_schema")
        assert body["tool_choice"] == {"type": "function", "function": {"name": "channel_a_read"}}
        assert body["tools"][0]["function"]["parameters"]["properties"]["options"]["maxItems"] == 12
        return httpx.Response(200, json=completion(tool_args=json.dumps(READ), usage=USAGE))

    route = respx.post(CHAT).mock(side_effect=reply)
    gw = gateway()
    first = await gw.complete(read_request())
    assert first.mode is StructuredMode.TOOL and first.data == READ
    assert route.call_count == 2
    second = await gw.complete(read_request())
    assert second.mode is StructuredMode.TOOL
    assert route.call_count == 3  # json_schema is not retried once refused


@respx.mock
async def test_prompt_mode_when_schema_and_tools_are_unsupported() -> None:
    def reply(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        if "response_format" in body:
            return unsupported("response_format")
        if "tools" in body:
            return unsupported("tool_choice")
        assert "JSON Schema" in body["messages"][0]["content"]
        return httpx.Response(200, json=completion(content=f"```json\n{json.dumps(READ)}\n```", usage=USAGE))

    respx.post(CHAT).mock(side_effect=reply)
    result = await gateway().complete(read_request())
    assert result.mode is StructuredMode.PROMPT
    assert result.data == READ


@respx.mock
async def test_pinned_prompt_mode_skips_structured_modes() -> None:
    route = respx.post(CHAT).mock(
        return_value=httpx.Response(
            200, json=completion(content="Sure: " + json.dumps(READ) + " done", usage=USAGE)
        )
    )
    result = await gateway(mode=StructuredMode.PROMPT).complete(read_request())
    assert result.data == READ
    body = json.loads(route.calls.last.request.content)
    assert "response_format" not in body and "tools" not in body


@respx.mock
async def test_a_400_that_is_not_unsupported_does_not_fall_back() -> None:
    route = respx.post(CHAT).mock(return_value=httpx.Response(400, json={"message": "image too large"}))
    with pytest.raises(ModelUnavailable) as err:
        await gateway().complete(read_request())
    assert err.value.status == 400
    assert route.call_count == 1


@respx.mock
async def test_unsupported_input_never_downgrades_the_structured_mode() -> None:
    # One odd upload ("image input is not supported") must not switch every later request to prompt mode.
    route = respx.post(CHAT).mock(return_value=unsupported("image input"))
    gw = gateway()
    with pytest.raises(ModelUnavailable) as err:
        await gw.complete(read_request())
    assert err.value.status == 400 and not isinstance(err.value, ModelUnsupported)
    assert route.call_count == 1
    assert gw.modes_for() == [StructuredMode.JSON_SCHEMA, StructuredMode.TOOL, StructuredMode.PROMPT]


@respx.mock
async def test_a_mode_is_dropped_only_when_the_refusal_names_its_feature() -> None:
    def reply(request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        if "response_format" in body:
            return unsupported("guided decoding with json_schema")
        if "tools" in body:
            return unsupported("image input")  # about the input, not about tools: no further downgrade
        return httpx.Response(200, json=completion(content=json.dumps(READ), usage=USAGE))

    route = respx.post(CHAT).mock(side_effect=reply)
    gw = gateway()
    with pytest.raises(ModelUnavailable):
        await gw.complete(read_request())
    assert route.call_count == 2
    assert gw.modes_for() == [StructuredMode.TOOL, StructuredMode.PROMPT]


@respx.mock
async def test_read_timeout_is_a_model_timeout_and_connect_error_was_never_sent() -> None:
    respx.post(CHAT).mock(side_effect=httpx.ReadTimeout("slow"))
    with pytest.raises(ModelTimeout) as timeout:
        await gateway().complete(read_request())
    assert timeout.value.sent is True

    respx.post(CHAT).mock(side_effect=httpx.ConnectError("refused"))
    with pytest.raises(ModelUnavailable) as connect:
        await gateway().complete(read_request())
    assert connect.value.sent is False

    respx.post(CHAT).mock(side_effect=httpx.ConnectTimeout("syn"))
    with pytest.raises(ModelUnavailable) as connect_timeout:
        await gateway().complete(read_request())
    assert connect_timeout.value.sent is False


@respx.mock
async def test_server_errors_and_rate_limits_carry_their_status() -> None:
    respx.post(CHAT).mock(return_value=httpx.Response(503, json={"message": "overloaded"}))
    with pytest.raises(ModelUnavailable) as err:
        await gateway().complete(read_request())
    assert err.value.status == 503
    respx.post(CHAT).mock(return_value=httpx.Response(429, json={"message": "slow down"}))
    with pytest.raises(ModelUnavailable) as limited:
        await gateway().complete(read_request())
    assert limited.value.status == 429


@respx.mock
async def test_unparseable_reply_is_bad_output_with_usage() -> None:
    respx.post(CHAT).mock(
        return_value=httpx.Response(200, json=completion(content="I cannot help", usage=USAGE))
    )
    with pytest.raises(ModelBadOutput) as err:
        await gateway().complete(read_request())
    assert err.value.usage is not None and err.value.usage.output_tokens == 300


@respx.mock
async def test_list_models_and_v1_suffix_handling() -> None:
    route = respx.get(f"{BASE}/v1/models").mock(
        return_value=httpx.Response(
            200,
            json={
                "object": "list",
                "data": [{"id": PINNED_MODEL_ID, "object": "model", "created": 0, "owned_by": "meta"}],
            },
        )
    )
    gw = ModelGateway(name="m", base_url=f"{BASE}/v1/", api_key="k", model_id=PINNED_MODEL_ID)
    assert await gw.list_models() == [PINNED_MODEL_ID]
    assert route.called


def test_parse_json_object_edge_cases() -> None:
    assert parse_json_object('{"a": 1}') == {"a": 1}
    assert parse_json_object('```\n{"a": 1}\n```') == {"a": 1}
    for bad in ("", "[1, 2]", "no json", None):
        with pytest.raises((ValueError, TypeError)):
            parse_json_object(bad)


def test_request_size_estimate_counts_text_and_images() -> None:
    request = prompts.read_request(ImagePart(PNG, "image/png"))
    assert request.image_count == 1
    assert request.text_chars > len(prompts.READ_SYSTEM)
    text_only = prompts.parse_request({"app": "uber", "title": "t", "text": "x"})
    assert text_only.image_count == 0
    assert any(isinstance(p, TextPart) for p in text_only.parts)


@respx.mock
async def test_router_keeps_the_reservation_when_the_real_gateway_times_out() -> None:
    respx.get(f"{BASE}/v1/models").mock(
        return_value=httpx.Response(
            200,
            json={
                "object": "list",
                "data": [{"id": PINNED_MODEL_ID, "object": "model", "created": 0, "owned_by": "m"}],
            },
        )
    )
    respx.post(CHAT).mock(side_effect=httpx.ReadTimeout("slow"))
    from ridemate_api.clock import FakeClock
    from ridemate_api.interfaces import ApiError, Purpose

    spend = FakeSpend()
    router = ProviderRouter(
        providers={"modal_spark_open": gateway()},
        spend=spend,
        remote_config=FakeRemoteConfig(),
        metrics=FakeMetrics(),
        clock=FakeClock(),
    )
    with pytest.raises(ApiError) as err:
        await router.call(
            read_request(), task=TaskKind.READ, purpose=Purpose.READ, principal=None, request_id="rid-1"
        )
    assert (err.value.status, err.value.code) == (504, "model_timeout")
    # A read tries once more after a timeout; both timed-out attempts keep their reservations in full.
    assert spend.kept == ["r1", "r2"] and not spend.settled and not spend.released
