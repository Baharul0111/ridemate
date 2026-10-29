"""Request plumbing (architecture §4.18 B8, contracts §1.1–§1.2).

Pure ASGI middleware, outermost first:
- `RequestContextMiddleware`: request/session ids (X-Request-Id is echoed back), log context, per-route request
  metrics, and the last-resort 500 envelope that never carries exception text.
- `BodyLimitMiddleware`: JSON ≤ 64 KB, multipart ≤ 1 MB + 16 KB, enforced BEFORE the body is read: an
  over-limit Content-Length is refused at once, and a streamed body is cut off as soon as it passes the limit.
- `IpLimitMiddleware`: the per-IP bucket on every /v1/* call.
`read_image` checks an uploaded image's size and magic bytes (JPEG FF D8 FF, PNG 89 50 4E 47 0D 0A 1A 0A).
"""

from __future__ import annotations

import logging
import math
import re
import time
import traceback
import uuid
from collections.abc import Awaitable, Callable, MutableMapping
from datetime import datetime
from typing import Any

from starlette.concurrency import run_in_threadpool
from starlette.datastructures import Headers
from starlette.requests import Request
from starlette.responses import JSONResponse

from .clock import ist_hhmm
from .interfaces import ApiError
from .settings import MAX_IMAGE_BYTES, MAX_JSON_BYTES

log = logging.getLogger(__name__)

Scope = MutableMapping[str, Any]
Message = MutableMapping[str, Any]
Receive = Callable[[], Awaitable[Message]]
Send = Callable[[Message], Awaitable[None]]
ASGIApp = Callable[[Scope, Receive, Send], Awaitable[None]]

MULTIPART_OVERHEAD_BYTES = 16 * 1024
MAX_MULTIPART_BYTES = MAX_IMAGE_BYTES + MULTIPART_OVERHEAD_BYTES
_ID_RE = re.compile(r"^[A-Za-z0-9-]{8,64}$")
# ASCII digits only and bounded: str.isdigit() also accepts digits like '²' that int() rejects, and int() refuses
# strings over 4300 digits, so either would turn a header into an unhandled error.
_UINT_RE = re.compile(r"^[0-9]{1,18}$")
JPEG_MAGIC = b"\xff\xd8\xff"
PNG_MAGIC = b"\x89PNG\r\n\x1a\n"


def normalize_id(value: str | None) -> str | None:
    """A client-supplied id (UUID-like) safe to use in keys and logs, or None."""
    return value if value and _ID_RE.match(value) else None


def header_uint(value: str | None) -> int | None:
    """A non-negative integer header (Content-Length, X-App-Version), or None when absent or malformed."""
    value = value.strip() if value else ""
    return int(value) if _UINT_RE.match(value) else None


def request_id_of(scope_or_request: Any) -> str | None:
    scope = scope_or_request.scope if isinstance(scope_or_request, Request) else scope_or_request
    state = scope.get("state") or {}
    return state.get("request_id") if isinstance(state, dict) else getattr(state, "request_id", None)


# ---- the §1.2 error envelope -------------------------------------------------------------------------------


def error_response(
    status: int,
    code: str,
    *,
    request_id: str | None,
    now: datetime | None = None,
    resume_at: datetime | None = None,
    retry_after_s: int | None = None,
    reason: str | None = None,
) -> JSONResponse:
    if retry_after_s is None and resume_at is not None and now is not None:
        retry_after_s = max(1, math.ceil((resume_at - now).total_seconds()))
    body = {
        "error": {
            "code": code,
            "resume_at_ist": ist_hhmm(resume_at) if resume_at is not None else None,
            "retry_after_s": retry_after_s,
            "reason": reason,
            "request_id": request_id,
        }
    }
    headers = {"Retry-After": str(retry_after_s)} if retry_after_s is not None else None
    return JSONResponse(body, status_code=status, headers=headers)


def api_error_response(exc: ApiError, *, request_id: str | None, now: datetime | None) -> JSONResponse:
    return error_response(
        exc.status,
        exc.code,
        request_id=request_id,
        now=now,
        resume_at=exc.resume_at,
        retry_after_s=exc.retry_after_s,
        reason=exc.reason,
    )


def _now(scope: Scope) -> datetime | None:
    deps = getattr(getattr(scope.get("app"), "state", None), "deps", None)
    return deps.clock.now_utc() if deps is not None else None


# ---- middleware --------------------------------------------------------------------------------------------


class RequestContextMiddleware:
    def __init__(self, app: ASGIApp) -> None:
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        headers = Headers(scope=scope)
        request_id = normalize_id(headers.get("x-request-id")) or str(uuid.uuid4())
        session_id = normalize_id(headers.get("x-session-id"))
        state = scope.setdefault("state", {})
        state["request_id"] = request_id
        state["session_id"] = session_id
        log_token = _bind_log_context(request_id, session_id, header_uint(headers.get("x-app-version")))
        started_at = time.monotonic()
        status = 500
        response_started = False

        async def send_wrapper(message: Message) -> None:
            nonlocal status, response_started
            if message["type"] == "http.response.start":
                response_started = True
                status = int(message["status"])
                message.setdefault("headers", [])
                message["headers"] = [*message["headers"], (b"x-request-id", request_id.encode())]
            await send(message)

        try:
            await self.app(scope, receive, send_wrapper)
        except Exception as exc:  # noqa: BLE001 - last resort; exception text never reaches client or log
            _count_exception(scope, exc)
            frames = traceback.extract_tb(exc.__traceback__)[-3:]
            where = ", ".join(f"{f.filename.rsplit('/', 1)[-1]}:{f.lineno}" for f in frames)
            log.error("unhandled %s at %s", type(exc).__name__, where)
            if not response_started:
                response = error_response(500, "internal", request_id=request_id)
                await response(scope, receive, send_wrapper)
        finally:
            route = getattr(scope.get("route"), "path", None) or "unmatched"
            _record_request(scope, route, status, (time.monotonic() - started_at) * 1000)
            _reset_log_context(log_token)


class BodyLimitMiddleware:
    def __init__(
        self, app: ASGIApp, *, json_bytes: int = MAX_JSON_BYTES, multipart_bytes: int = MAX_MULTIPART_BYTES
    ) -> None:
        self.app = app
        self.json_bytes = json_bytes
        self.multipart_bytes = multipart_bytes

    def limit_for(self, content_type: str) -> int:
        return self.multipart_bytes if content_type.lower().startswith("multipart/") else self.json_bytes

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        headers = Headers(scope=scope)
        limit = self.limit_for(headers.get("content-type", ""))
        declared = header_uint(headers.get("content-length"))
        if declared is not None and declared > limit:
            await self._too_large(scope, receive, send)
            return

        received = 0
        exceeded = False
        response_started = False

        async def limited_receive() -> Message:
            nonlocal received, exceeded
            if exceeded:
                return {"type": "http.disconnect"}
            message = await receive()
            if message["type"] == "http.request":
                received += len(message.get("body", b""))
                if received > limit:
                    # Stop here: the app sees a disconnect and the rest of the body is never read.
                    exceeded = True
                    return {"type": "http.disconnect"}
            return message

        async def guarded_send(message: Message) -> None:
            nonlocal response_started
            if exceeded:
                return  # whatever the app answers to the cut-off body is replaced by the 413 below
            if message["type"] == "http.response.start":
                response_started = True
            await send(message)

        try:
            await self.app(scope, limited_receive, guarded_send)
        except Exception:
            if not exceeded:
                raise
        if exceeded and not response_started:
            await self._too_large(scope, receive, send)

    @staticmethod
    async def _too_large(scope: Scope, receive: Receive, send: Send) -> None:
        response = error_response(413, "too_large", request_id=request_id_of(scope))
        response.headers["Connection"] = "close"
        await response(scope, receive, send)


class IpLimitMiddleware:
    """The per-IP bucket for every HTTP request (limits.IpLimiter; idempotent if an endpoint checks again).

    Every path, /healthz and /readyz included: an unauthenticated request a minute is enough to keep a container
    warm, and compute is billed whether or not the request costs a model call."""

    def __init__(self, app: ASGIApp, *, prefix: str = "/") -> None:
        self.app = app
        self.prefix = prefix

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] == "http" and scope.get("path", "").startswith(self.prefix):
            deps = scope["app"].state.deps
            try:
                await run_in_threadpool(deps.ip_limiter.check, Request(scope))
            except ApiError as exc:
                response = api_error_response(exc, request_id=request_id_of(scope), now=_now(scope))
                await response(scope, receive, send)
                return
        await self.app(scope, receive, send)


# ---- uploads -----------------------------------------------------------------------------------------------


def image_mime(data: bytes) -> str | None:
    if data.startswith(JPEG_MAGIC):
        return "image/jpeg"
    if data.startswith(PNG_MAGIC):
        return "image/png"
    return None


async def read_image(upload: Any, *, max_bytes: int = MAX_IMAGE_BYTES) -> bytes:
    """Read an uploaded image (UploadFile or bytes): > max_bytes → 413 too_large; not JPEG/PNG → 415 bad_media."""
    if isinstance(upload, bytes | bytearray):
        data = bytes(upload)
    elif hasattr(upload, "read"):
        data = await upload.read(max_bytes + 1)
    else:
        raise ApiError(422, "invalid")
    if len(data) > max_bytes:
        raise ApiError(413, "too_large")
    if image_mime(data) is None:
        raise ApiError(415, "bad_media")
    return data


# ---- observability hooks (logging_setup / metrics are the ops modules) -------------------------------------


def _bind_log_context(request_id: str, session_id: str | None, version_code: int | None) -> Any:
    # Like metrics, log context must never fail a request: a broken logging module costs context, not traffic.
    try:
        from .logging_setup import bind_request_context

        return bind_request_context(request_id=request_id, session_id=session_id, version_code=version_code)
    except Exception:  # noqa: BLE001
        return None


def _reset_log_context(token: Any) -> None:
    if token is None:
        return
    try:
        from .logging_setup import reset_request_context

        reset_request_context(token)
    except Exception:  # noqa: BLE001
        log.debug("log context not reset")


def _metrics(scope: Scope) -> Any:
    deps = getattr(getattr(scope.get("app"), "state", None), "deps", None)
    return getattr(deps, "metrics", None)


def _record_request(scope: Scope, route: str, status: int, latency_ms: float) -> None:
    metrics = _metrics(scope)
    if metrics is None:
        return
    try:
        from .metrics import record_request

        record_request(metrics, route, status, latency_ms)
    except Exception:  # noqa: BLE001 - metrics must never fail a request
        log.debug("request metrics not recorded")


def _count_exception(scope: Scope, exc: BaseException) -> None:
    metrics = _metrics(scope)
    if metrics is None:
        return
    try:
        from .metrics import record_exception

        record_exception(metrics, exc)
    except Exception:  # noqa: BLE001
        log.debug("exception metric not recorded")
