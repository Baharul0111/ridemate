"""Muse Spark Open gateway over the official `openai` SDK (architecture §4.2 "Model gateway").

One `ModelGateway` per provider. A call is sent in the pinned structured-output mode and falls back
json_schema → tool → prompt only when the endpoint answers 400 "unsupported"; a mode that was refused once
is skipped for the life of the process. SDK errors are reduced to the few outcomes the router meters
(sent or not, usage known or not) and the breaker counts. The router owns deadlines, spend and retries.
"""

from __future__ import annotations

import base64
import json
import logging
from dataclasses import dataclass
from enum import StrEnum
from typing import Any

import httpx
import openai
from openai import AsyncOpenAI

log = logging.getLogger(__name__)

REQUEST_TIMEOUT_S = 12.0
CONNECT_TIMEOUT_S = 3.0
MAX_REPLY_CHARS = 64 * 1024


class StructuredMode(StrEnum):
    JSON_SCHEMA = "json_schema"  # response_format json_schema, strict
    TOOL = "tool"  # forced named tool call whose parameters are the schema
    PROMPT = "prompt"  # schema in the prompt, validated in code


FALLBACK_ORDER: tuple[StructuredMode, ...] = (
    StructuredMode.JSON_SCHEMA,
    StructuredMode.TOOL,
    StructuredMode.PROMPT,
)


def parse_mode(value: object, default: StructuredMode) -> StructuredMode:
    try:
        return StructuredMode(str(value))
    except ValueError:
        return default


@dataclass(frozen=True)
class TextPart:
    text: str


@dataclass(frozen=True)
class ImagePart:
    data: bytes
    mime: str  # image/png | image/jpeg (sniffed from magic bytes by the caller)

    def data_url(self) -> str:
        return f"data:{self.mime};base64,{base64.b64encode(self.data).decode('ascii')}"


@dataclass(frozen=True)
class ModelRequest:
    task: str  # read | reread | point | parse | place (labels only)
    system: str
    parts: tuple[TextPart | ImagePart, ...]
    schema_name: str
    schema: dict[str, Any]
    max_tokens: int

    @property
    def image_count(self) -> int:
        return sum(1 for p in self.parts if isinstance(p, ImagePart))

    @property
    def text_chars(self) -> int:
        """Characters of text the provider will tokenise, including the schema in its worst-case placement."""
        texts = sum(len(p.text) for p in self.parts if isinstance(p, TextPart))
        return len(self.system) + texts + len(json.dumps(self.schema)) + len(PROMPT_MODE_SUFFIX)


@dataclass(frozen=True)
class Usage:
    input_tokens: int  # all prompt tokens, cached ones included (OpenAI semantics)
    cached_input_tokens: int  # subset of input_tokens billed at the cached price
    output_tokens: int  # completion tokens, reasoning included
    reasoning_tokens: int = 0


@dataclass(frozen=True)
class ModelResult:
    data: dict[str, Any]
    usage: Usage | None  # None when the provider sent no usage block (the router keeps the reservation)
    mode: StructuredMode
    model: str


class ModelError(Exception):
    """A model call that produced no usable result.

    `sent` is False only when the request provably never reached the provider (connect failure), so its
    reservation can be released. `usage` is set when the provider billed tokens for a reply we could not use.
    """

    def __init__(
        self, detail: str, *, sent: bool = True, status: int | None = None, usage: Usage | None = None
    ) -> None:
        super().__init__(detail)
        self.sent = sent
        self.status = status
        self.usage = usage


class ModelTimeout(ModelError):
    pass


class ModelUnavailable(ModelError):
    """Connection failure, rate limit, auth failure or a 5xx."""


class ModelUnsupported(ModelError):
    """400 'unsupported' for the structured-output mode that was tried."""


class ModelBadOutput(ModelError):
    """The reply was not the single JSON object the schema asks for."""


PROMPT_MODE_SUFFIX = (
    "\n\nReply with exactly one JSON object and nothing else: no prose, no code fences. "
    "It must validate against this JSON Schema:\n"
)
TOOL_MODE_SUFFIX = "\n\nGive your answer by calling the function `{name}` exactly once."
_UNSUPPORTED_MARKERS = ("unsupported", "not supported", "does not support", "not implemented")
# The feature each mode adds to the request. A 400 "unsupported" downgrades a mode only when it names that
# feature: "unsupported image format" says nothing about structured output and must not poison the mode for
# every later request (the downgrade lasts for the life of the process).
_MODE_FEATURE_MARKERS: dict[StructuredMode, tuple[str, ...]] = {
    StructuredMode.JSON_SCHEMA: (
        "response_format",
        "json_schema",
        "json schema",
        "structured",
        "guided",
        "grammar",
    ),
    StructuredMode.TOOL: ("tool", "function"),
    StructuredMode.PROMPT: (),
}


def parse_usage(usage: Any) -> Usage | None:
    if usage is None:
        return None
    prompt = _int_attr(usage, "prompt_tokens")
    completion = _int_attr(usage, "completion_tokens")
    cached = _int_attr(_field(usage, "prompt_tokens_details"), "cached_tokens")
    reasoning = _int_attr(_field(usage, "completion_tokens_details"), "reasoning_tokens")
    # OpenAI semantics count reasoning inside completion_tokens. A server that reports more reasoning than
    # completion tokens is reporting them separately, so add them: over-metering is the safe direction.
    output = completion + reasoning if reasoning > completion else completion
    return Usage(
        input_tokens=prompt,
        cached_input_tokens=min(cached, prompt),
        output_tokens=output,
        reasoning_tokens=reasoning,
    )


def _field(obj: Any, name: str) -> Any:
    """Attribute of an SDK model or key of a raw dict (servers may add fields the SDK doesn't model)."""
    if obj is None:
        return None
    return obj.get(name) if isinstance(obj, dict) else getattr(obj, name, None)


def _int_attr(obj: Any, name: str) -> int:
    value = _field(obj, name)
    if isinstance(value, bool) or not isinstance(value, int | float):
        return 0
    return max(0, int(value))


def parse_json_object(text: str | None) -> dict[str, Any]:
    """The reply's JSON object. Tolerates code fences and stray prose around it (prompt mode)."""
    if not text or len(text) > MAX_REPLY_CHARS:
        raise ValueError("empty or oversized reply")
    s = text.strip()
    if s.startswith("```"):
        s = s.split("\n", 1)[1] if "\n" in s else ""
        s = s.rsplit("```", 1)[0].strip()
    try:
        obj = json.loads(s)
    except json.JSONDecodeError:
        start, end = s.find("{"), s.rfind("}")
        if start < 0 or end <= start:
            raise ValueError("no JSON object in reply") from None
        obj = json.loads(s[start : end + 1])
    if not isinstance(obj, dict):
        raise TypeError("reply is not a JSON object")
    return obj


def _is_unsupported(err: openai.BadRequestError, mode: StructuredMode) -> bool:
    text = f"{err.message} {json.dumps(err.body, default=str) if err.body is not None else ''}".lower()
    return any(marker in text for marker in _UNSUPPORTED_MARKERS) and any(
        feature in text for feature in _MODE_FEATURE_MARKERS[mode]
    )


def _never_sent(err: Exception) -> bool:
    # By name, so both httpx and the SDK's bundled httpx2 transports are recognised.
    return type(err.__cause__).__name__ in ("ConnectError", "ConnectTimeout")


def _v1_url(base_url: str) -> str:
    base = base_url.rstrip("/")
    return base if base.endswith("/v1") else f"{base}/v1"


class ModelGateway:
    """An OpenAI-compatible chat endpoint serving one pinned model."""

    def __init__(
        self,
        *,
        name: str,
        base_url: str,
        api_key: str,
        model_id: str,
        mode: StructuredMode = StructuredMode.JSON_SCHEMA,
        reasoning_effort: str = "low",
        http_client: httpx.AsyncClient | None = None,
    ) -> None:
        self.name = name
        self.model_id = model_id
        self.pinned_mode = mode
        self._reasoning_effort = reasoning_effort
        self._refused: set[StructuredMode] = set()
        timeout = httpx.Timeout(REQUEST_TIMEOUT_S, connect=CONNECT_TIMEOUT_S)
        # A plain httpx client keeps the transport swappable (respx in tests) with the same timeouts.
        self._client = AsyncOpenAI(
            base_url=_v1_url(base_url),
            api_key=api_key,
            timeout=timeout,
            max_retries=0,
            http_client=http_client or httpx.AsyncClient(timeout=timeout),
        )

    def modes_for(self, pinned: StructuredMode | None = None) -> list[StructuredMode]:
        start = FALLBACK_ORDER.index(pinned or self.pinned_mode)
        chain = [m for m in FALLBACK_ORDER[start:] if m not in self._refused]
        return chain or [StructuredMode.PROMPT]

    async def complete(self, request: ModelRequest, *, mode: StructuredMode | None = None) -> ModelResult:
        refused: ModelUnsupported | None = None
        for m in self.modes_for(mode):
            try:
                return await self._complete_once(request, m)
            except ModelUnsupported as e:
                refused = e
                if m is not StructuredMode.PROMPT:
                    self._refused.add(m)
                    log.warning("structured mode %s refused by %s; falling back", m.value, self.name)
        raise refused or ModelUnsupported("no structured mode available", status=400)

    async def list_models(self) -> list[str]:
        """GET /models (free) — used to confirm the pinned id is still served."""
        try:
            page = await self._client.models.list()
        except openai.APITimeoutError as e:
            raise ModelTimeout("list_models timeout", sent=not _never_sent(e)) from e
        except openai.APIConnectionError as e:
            raise ModelUnavailable("list_models connection", sent=not _never_sent(e)) from e
        except openai.APIStatusError as e:
            raise ModelUnavailable("list_models status", status=e.status_code) from e
        return [m.id for m in page.data]

    async def aclose(self) -> None:
        await self._client.close()

    async def _complete_once(self, request: ModelRequest, mode: StructuredMode) -> ModelResult:
        kwargs: dict[str, Any] = {
            "model": self.model_id,
            "messages": self._messages(request, mode),
            "max_tokens": request.max_tokens,
            "temperature": 0,
            "reasoning_effort": self._reasoning_effort,
        }
        if mode is StructuredMode.JSON_SCHEMA:
            kwargs["response_format"] = {
                "type": "json_schema",
                "json_schema": {"name": request.schema_name, "schema": request.schema, "strict": True},
            }
        elif mode is StructuredMode.TOOL:
            kwargs["tools"] = [
                {
                    "type": "function",
                    "function": {
                        "name": request.schema_name,
                        "description": "Return the structured result.",
                        "parameters": request.schema,
                        "strict": True,
                    },
                }
            ]
            kwargs["tool_choice"] = {"type": "function", "function": {"name": request.schema_name}}

        try:
            completion = await self._client.chat.completions.create(**kwargs)
        except openai.APITimeoutError as e:
            if _never_sent(e):
                raise ModelUnavailable("connect timeout", sent=False) from e
            raise ModelTimeout("request timeout") from e
        except openai.APIConnectionError as e:
            raise ModelUnavailable("connection error", sent=not _never_sent(e)) from e
        except openai.BadRequestError as e:
            if _is_unsupported(e, mode):
                raise ModelUnsupported(f"{mode.value} unsupported", status=400) from e
            raise ModelUnavailable("bad request", status=400) from e
        except openai.APIStatusError as e:
            raise ModelUnavailable("status error", status=e.status_code) from e

        usage = parse_usage(completion.usage)
        try:
            data = parse_json_object(self._reply_text(completion, request.schema_name, mode))
        except (ValueError, TypeError, IndexError, AttributeError) as e:
            raise ModelBadOutput("unparseable reply", usage=usage) from e
        return ModelResult(data=data, usage=usage, mode=mode, model=completion.model or self.model_id)

    @staticmethod
    def _messages(request: ModelRequest, mode: StructuredMode) -> list[dict[str, Any]]:
        system = request.system
        if mode is StructuredMode.PROMPT:
            system += PROMPT_MODE_SUFFIX + json.dumps(request.schema, ensure_ascii=False)
        elif mode is StructuredMode.TOOL:
            system += TOOL_MODE_SUFFIX.format(name=request.schema_name)
        content: list[dict[str, Any]] = []
        for part in request.parts:
            if isinstance(part, ImagePart):
                content.append({"type": "image_url", "image_url": {"url": part.data_url()}})
            else:
                content.append({"type": "text", "text": part.text})
        return [{"role": "system", "content": system}, {"role": "user", "content": content}]

    @staticmethod
    def _reply_text(completion: Any, schema_name: str, mode: StructuredMode) -> str | None:
        message = completion.choices[0].message
        if mode is StructuredMode.TOOL:
            for call in message.tool_calls or []:
                function = getattr(call, "function", None)
                if function is not None and function.name == schema_name:
                    return function.arguments
            raise ValueError("no call to the requested tool")
        return message.content
