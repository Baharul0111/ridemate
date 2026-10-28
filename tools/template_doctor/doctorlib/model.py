"""The Template Doctor's model client: Muse Spark Open on Modal's shared endpoint through the official `openai`
SDK with native tool calling. The client is injected (tests use a fake); every call is metered by `budget.py`.
"""

from __future__ import annotations

import asyncio
import json
from dataclasses import dataclass
from typing import Any, Protocol

import httpx
import openai
from openai import AsyncOpenAI
from ridemate_api.model_gateway import Usage, parse_usage
from ridemate_api.settings import PINNED_MODEL_ID, Settings

REQUEST_TIMEOUT_S = 90.0
CONNECT_TIMEOUT_S = 5.0


@dataclass(frozen=True)
class ToolCall:
    id: str
    name: str
    arguments: str  # JSON text as the model produced it


@dataclass(frozen=True)
class ChatTurn:
    content: str | None
    tool_calls: tuple[ToolCall, ...]
    usage: Usage | None
    model: str

    def assistant_message(self) -> dict[str, Any]:
        msg: dict[str, Any] = {"role": "assistant", "content": self.content or ""}
        if self.tool_calls:
            msg["tool_calls"] = [
                {"id": c.id, "type": "function", "function": {"name": c.name, "arguments": c.arguments}}
                for c in self.tool_calls
            ]
        return msg


class ModelCallError(Exception):
    """No usable reply. `sent=False` only when the request provably never reached the provider."""

    def __init__(
        self, detail: str, *, sent: bool = True, usage: Usage | None = None, timeout: bool = False
    ) -> None:
        super().__init__(detail)
        self.sent = sent
        self.usage = usage
        self.timeout = timeout


class ChatClient(Protocol):
    model_id: str

    async def chat(
        self, messages: list[dict[str, Any]], tools: list[dict[str, Any]], max_tokens: int
    ) -> ChatTurn: ...


def _never_sent(err: Exception) -> bool:
    return type(err.__cause__).__name__ in ("ConnectError", "ConnectTimeout")


class OpenAIChatClient:
    """Muse Spark Open via the OpenAI-compatible endpoint (base URL + proxy token from Settings)."""

    def __init__(self, settings: Settings, *, http_client: httpx.AsyncClient | None = None) -> None:
        if not settings.ai_configured:
            raise ModelCallError(
                "model endpoint not configured (MODAL_SPARK_OPEN_URL / proxy token)", sent=False
            )
        base = settings.model_base_url.rstrip("/")
        timeout = httpx.Timeout(REQUEST_TIMEOUT_S, connect=CONNECT_TIMEOUT_S)
        self.model_id = PINNED_MODEL_ID
        self._client = AsyncOpenAI(
            base_url=base if base.endswith("/v1") else f"{base}/v1",
            api_key=settings.model_api_key,
            timeout=timeout,
            max_retries=0,
            http_client=http_client or httpx.AsyncClient(timeout=timeout),
        )

    async def chat(
        self, messages: list[dict[str, Any]], tools: list[dict[str, Any]], max_tokens: int
    ) -> ChatTurn:
        try:
            completion = await self._client.chat.completions.create(
                model=self.model_id,
                messages=messages,
                tools=tools,
                tool_choice="auto",
                max_tokens=max_tokens,
                temperature=0,
            )
        except openai.APITimeoutError as e:
            raise ModelCallError("timeout", sent=not _never_sent(e), timeout=True) from e
        except openai.APIConnectionError as e:
            raise ModelCallError("connection error", sent=not _never_sent(e)) from e
        except openai.APIStatusError as e:
            # Rejected before inference (auth, rate limit, bad request): nothing billed.
            raise ModelCallError(
                f"status {e.status_code}", usage=Usage(0, 0, 0) if e.status_code < 500 else None
            ) from e
        except asyncio.CancelledError:
            raise
        usage = parse_usage(completion.usage)
        try:
            message = completion.choices[0].message
        except (IndexError, AttributeError) as e:
            raise ModelCallError("empty reply", usage=usage) from e
        calls = tuple(
            ToolCall(id=c.id, name=c.function.name, arguments=c.function.arguments or "{}")
            for c in (message.tool_calls or [])
            if getattr(c, "function", None) is not None
        )
        return ChatTurn(
            content=message.content, tool_calls=calls, usage=usage, model=completion.model or self.model_id
        )

    async def aclose(self) -> None:
        await self._client.close()


def tool_spec(name: str, description: str, properties: dict[str, Any], required: list[str]) -> dict[str, Any]:
    return {
        "type": "function",
        "function": {
            "name": name,
            "description": description,
            "parameters": {
                "type": "object",
                "properties": properties,
                "required": required,
                "additionalProperties": False,
            },
        },
    }


def parse_arguments(raw: str) -> dict[str, Any]:
    try:
        value = json.loads(raw or "{}")
    except json.JSONDecodeError as e:
        raise ValueError(f"arguments are not valid JSON: {e.msg}") from None
    if not isinstance(value, dict):
        raise TypeError("arguments must be a JSON object")
    return value
