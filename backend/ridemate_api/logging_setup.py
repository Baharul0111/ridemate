"""JSON logs for Modal's log view (1-day retention) with a scrubber (architecture §4.2 "Observability", §4.11).

Every line is one JSON object. Only allowlisted fields survive, and each surviving value must be a number, a
boolean or a short token-like string (ids, enums, routes) that is not an email or phone number: free text such as
OCR or notification text, tokens, keys and base64 blobs never reach the log. The message is the code's own format
string; its %-arguments pass the same value check (exceptions become their class name), so text interpolated from
a request can't slip in that way. The result is then redacted pattern by pattern and truncated, and exceptions are
logged as their class name plus code locations, never their message.

Request-scoped fields (session_id, request_id, uid_hash, app, version_code) are bound once per request with
`bind_request_context`; per-line fields (latency_ms, outcome, ...) go in `extra=`.
"""

from __future__ import annotations

import json
import logging
import re
import sys
import traceback
from collections.abc import Iterator, Mapping
from contextlib import contextmanager
from contextvars import ContextVar, Token
from datetime import UTC, datetime
from enum import Enum
from types import MappingProxyType
from typing import Any, TextIO

CONTEXT_FIELDS: tuple[str, ...] = ("session_id", "request_id", "uid_hash", "app", "version_code")

ALLOWED_FIELDS: frozenset[str] = frozenset(
    {
        *CONTEXT_FIELDS,
        "latency_ms",
        "outcome",
        "route",
        "method",
        "status",
        "code",
        "reason",
        "task",
        "purpose",
        "provider",
        "model",
        "mode",
        "attempts",
        "cost_usd",
        "job",
        "alerts",
        "count",
        "duration_ms",
        "error_type",
        "window",
        "env",
        "kind",
    }
)
# Never logged even if someone allowlists them by mistake.
_SENSITIVE_KEY = re.compile(
    r"(?i)authorization|token|secret|password|passwd|api[_-]?key|private|cookie|credential"
)
_TOKEN_VALUE = re.compile(r"^[A-Za-z0-9_.:/@+\-=,#%|]{0,96}$")
_BASE64_RUN = re.compile(r"[A-Za-z0-9+/_\-]{40,}={0,2}")
_HEX_RUN = re.compile(r"(?<![0-9A-Za-z])[0-9a-fA-F]{32,}(?![0-9A-Za-z])")  # hex keys / HMAC secrets

MAX_MESSAGE_CHARS = 300
MAX_STACK_FRAMES = 12
DROPPED = "[dropped]"

_REDACTIONS: tuple[tuple[re.Pattern[str], str], ...] = (
    (
        re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----.*?(?:-----END [A-Z ]*PRIVATE KEY-----|$)", re.DOTALL),
        "[key]",
    ),
    (re.compile(r"(?i)\bauthorization\b\s*[:=]?\s*(?:bearer\s+)?\S+"), "authorization=[redacted]"),
    (re.compile(r"(?i)\bbearer\s+\S+"), "bearer [redacted]"),
    (
        re.compile(
            r"(?i)\b(\w*(?:token|secret|password|passwd|api[_-]?key|private[_-]?key)\w*)\s*[:=]\s*\S+"
        ),
        r"\1=[redacted]",
    ),
    (re.compile(r"\beyJ[\w-]+\.[\w-]+\.[\w-]*"), "[jwt]"),
    (re.compile(r"\bw[ks]-[A-Za-z0-9]{6,}"), "[modal-token]"),
    (re.compile(r"\bAIza[0-9A-Za-z_\-]{30,}"), "[api-key]"),
    (re.compile(r"(?i)data:[\w/+.\-]+;base64,\S+"), "[data-url]"),
    (re.compile(r"(?i)https?://hc-ping\.com/\S+"), "[ping-url]"),
)
# Applied after key material is gone, so a digit run inside a blob isn't mistaken for a phone number.
_PERSONAL: tuple[tuple[re.Pattern[str], str], ...] = (
    (re.compile(r"[\w.+\-]+@[\w\-]+\.[\w.\-]+"), "[email]"),
    (re.compile(r"(?<!\d)(?:\+?91[\s\-]?)?[6-9]\d{4}[\s\-]?\d{5}(?!\d)"), "[phone]"),
)

_context: ContextVar[Mapping[str, Any]] = ContextVar("ridemate_log_context", default=MappingProxyType({}))

# Attributes every LogRecord has; anything else on a record came from `extra=`.
_RECORD_ATTRS = frozenset(vars(logging.LogRecord("", 0, "", 0, "", None, None))) | {
    "message",
    "asctime",
    "taskName",
}


def _is_blob(run: str) -> bool:
    """Random key material and base64 mix upper case, lower case and digits; long snake_case names don't."""
    return any(c.isupper() for c in run) and any(c.islower() for c in run) and any(c.isdigit() for c in run)


def _redact_blobs(text: str) -> str:
    text = _HEX_RUN.sub("[hex]", text)
    return _BASE64_RUN.sub(lambda m: "[blob]" if _is_blob(m.group(0)) else m.group(0), text)


def _has_blob(value: str) -> bool:
    return bool(_HEX_RUN.search(value)) or any(_is_blob(m.group(0)) for m in _BASE64_RUN.finditer(value))


def scrub_text(text: str) -> str:
    for pattern, replacement in _REDACTIONS:
        text = pattern.sub(replacement, text)
    text = _redact_blobs(text)
    for pattern, replacement in _PERSONAL:
        text = pattern.sub(replacement, text)
    text = " ".join(text.split())
    return text if len(text) <= MAX_MESSAGE_CHARS else text[: MAX_MESSAGE_CHARS - 1] + "…"


def _is_personal(value: str) -> bool:
    return any(pattern.search(value) for pattern, _ in _PERSONAL)


def scrub_value(value: Any) -> Any:
    """A loggable value or None (dropped): numbers, booleans and short token-like strings only."""
    if value is None or isinstance(value, bool):
        return value
    if isinstance(value, int | float):
        return value
    if isinstance(value, str):
        if not _TOKEN_VALUE.match(value) or _has_blob(value) or _is_personal(value):
            return None
        return value
    if isinstance(value, list | tuple | set | frozenset):
        items = [scrub_value(v) for v in list(value)[:20]]
        return [v for v in items if v is not None]
    return None


def scrub_fields(fields: Mapping[str, Any]) -> dict[str, Any]:
    out: dict[str, Any] = {}
    for key, value in fields.items():
        if key not in ALLOWED_FIELDS or _SENSITIVE_KEY.search(key):
            continue
        clean = scrub_value(value)
        out[key] = DROPPED if clean is None and value is not None else clean
    return out


def bind_request_context(**fields: Any) -> Token[Mapping[str, Any]]:
    """Bind request-scoped fields for every log line in this context; undo with `reset_request_context`."""
    merged = {**_context.get(), **{k: v for k, v in fields.items() if k in CONTEXT_FIELDS and v is not None}}
    return _context.set(merged)


def reset_request_context(token: Token[Mapping[str, Any]]) -> None:
    _context.reset(token)


@contextmanager
def request_context(**fields: Any) -> Iterator[None]:
    token = bind_request_context(**fields)
    try:
        yield
    finally:
        reset_request_context(token)


def _safe_arg(value: Any) -> Any:
    """A %-argument as it may appear in the message: checked like a field value, exceptions as their class."""
    if isinstance(value, BaseException):
        return type(value).__name__
    if isinstance(value, Enum):
        value = value.value
    clean = scrub_value(value)
    if clean is None and value is not None:
        return DROPPED
    return clean if not isinstance(clean, list) else DROPPED


def render_message(record: logging.LogRecord) -> str:
    """The record's message with every argument passed through `_safe_arg` (never `record.getMessage()`, which
    would interpolate request text as is)."""
    msg = record.msg
    if not isinstance(msg, str):
        return _safe_arg(msg) if msg is not None else ""
    args = record.args
    if not args:
        return msg
    try:
        if isinstance(args, Mapping):
            return msg % {k: _safe_arg(v) for k, v in args.items()}
        return msg % tuple(_safe_arg(a) for a in args)
    except (TypeError, ValueError, KeyError):
        return msg


def _stack(exc_info: Any) -> list[str]:
    _, _, tb = exc_info
    frames = traceback.extract_tb(tb)[-MAX_STACK_FRAMES:]
    return [f"{frame.filename.rsplit('/', 1)[-1]}:{frame.lineno}:{frame.name}" for frame in frames]


class JsonFormatter(logging.Formatter):
    def __init__(self, *, static: Mapping[str, Any] | None = None) -> None:
        super().__init__()
        self._static = dict(static or {})

    def format(self, record: logging.LogRecord) -> str:
        message = str(render_message(record))
        line: dict[str, Any] = {
            "ts": datetime.fromtimestamp(record.created, UTC).isoformat(timespec="milliseconds"),
            "level": record.levelname.lower(),
            "logger": record.name,
            "msg": scrub_text(message),
        }
        fields = {**self._static, **_context.get()}
        fields.update({k: v for k, v in vars(record).items() if k not in _RECORD_ATTRS})
        line.update(scrub_fields(fields))
        if record.exc_info and record.exc_info[0] is not None:
            line["error_type"] = record.exc_info[0].__name__
            line["stack"] = _stack(record.exc_info)
        return json.dumps(line, ensure_ascii=False, default=str)


_QUIET_LOGGERS = {
    "uvicorn.access": logging.WARNING,  # the request line is logged by our middleware, without the client IP
    "httpx": logging.WARNING,  # INFO lines carry full URLs (ping URLs are secrets)
    "httpcore": logging.WARNING,
    "hpack": logging.WARNING,
    "google": logging.WARNING,
    "urllib3": logging.WARNING,
}


def configure_logging(
    level: int | str = logging.INFO, *, stream: TextIO | None = None, **static: Any
) -> None:
    """Route every logger through one JSON handler on stdout. `static` fields (e.g. env='main') go on every line
    and pass the same scrubber. Safe to call more than once."""
    handler = logging.StreamHandler(stream or sys.stdout)
    handler.setFormatter(JsonFormatter(static=static))
    root = logging.getLogger()
    root.handlers[:] = [handler]
    root.setLevel(level)
    for name in ("uvicorn", "uvicorn.error", "uvicorn.access", "fastapi"):
        lg = logging.getLogger(name)
        lg.handlers[:] = []
        lg.propagate = True
    for name, lvl in _QUIET_LOGGERS.items():
        logging.getLogger(name).setLevel(lvl)
