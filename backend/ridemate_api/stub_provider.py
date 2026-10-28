"""Deterministic in-process model provider for load tests (architecture §4.12 "k6 … with a stub provider").

`StubProvider` has the same call surface as `model_gateway.ModelGateway` (the router's `ModelProvider`
protocol: `name`, `model_id`, `pinned_mode`, `complete()`, `list_models()`, `aclose()`), so the real
`ProviderRouter`, spend reservations, breaker and endpoint validation all run unchanged. It never opens a
socket: every answer is a canned, schema-valid reply chosen deterministically from the request's content, after
a configurable simulated latency.

It can only be built when BOTH hold, and raises `StubProviderForbidden` otherwise:
- `RIDEMATE_STUB_PROVIDER=1` in the environment, and
- the Modal environment is not `main` (neither `Settings.environment` nor `MODAL_ENVIRONMENT`; an unset or empty
  value counts as `main`, so production can never fall into the stub by omission).

Tuning (environment, all optional):
- `RIDEMATE_STUB_LATENCY_MS`  e.g. `read=2300,reread=1500,point=1200,parse=600,place=400`
- `RIDEMATE_STUB_JITTER`      fraction of the latency, ± (default 0.2), derived from the request hash
- `RIDEMATE_STUB_FAIL_EVERY`  every Nth call raises a 503-like `ModelUnavailable` (default 0 = never)
- `RIDEMATE_STUB_USAGE`       `1` reports token usage like the real endpoint (default), `0` reports zero tokens
"""

from __future__ import annotations

import asyncio
import hashlib
import itertools
import json
import math
import os
import re
from collections.abc import Mapping
from typing import Any

from .model_gateway import (
    ImagePart,
    ModelRequest,
    ModelResult,
    ModelUnavailable,
    StructuredMode,
    TextPart,
    Usage,
)
from .prompts import PLACE_NONE
from .router import CHARS_PER_TOKEN, DEFAULT_PROVIDER, ModelProvider, estimate_input_tokens
from .settings import PINNED_MODEL_ID, Settings

ENV_FLAG = "RIDEMATE_STUB_PROVIDER"
ENV_LATENCY = "RIDEMATE_STUB_LATENCY_MS"
ENV_JITTER = "RIDEMATE_STUB_JITTER"
ENV_FAIL_EVERY = "RIDEMATE_STUB_FAIL_EVERY"
ENV_USAGE = "RIDEMATE_STUB_USAGE"
PRODUCTION_ENVIRONMENT = "main"

# Typical Muse Spark Open latencies on Modal's shared endpoint (contracts §1.3 example: 2310 ms for a read).
DEFAULT_LATENCY_MS: Mapping[str, float] = {
    "read": 2300.0,
    "reread": 1500.0,
    "point": 1200.0,
    "parse": 600.0,
    "place": 400.0,
}
DEFAULT_JITTER = 0.2
TASKS = tuple(DEFAULT_LATENCY_MS)


class StubProviderForbidden(RuntimeError):
    """The stub was requested where it must never run (flag off, or the production Modal environment)."""


def stub_allowed(settings: Settings | None = None, environ: Mapping[str, str] | None = None) -> bool:
    env = os.environ if environ is None else environ
    if env.get(ENV_FLAG, "").strip() != "1":
        return False
    names = [env.get("MODAL_ENVIRONMENT", PRODUCTION_ENVIRONMENT)]
    if settings is not None:
        names.append(settings.environment)
    return all(_is_non_production(name) for name in names)


def ensure_stub_allowed(settings: Settings | None = None, environ: Mapping[str, str] | None = None) -> None:
    if not stub_allowed(settings, environ):
        raise StubProviderForbidden(
            f"the stub model provider needs {ENV_FLAG}=1 and a Modal environment other than "
            f"'{PRODUCTION_ENVIRONMENT}' (set MODAL_ENVIRONMENT, e.g. 'loadtest')"
        )


def _is_non_production(name: str | None) -> bool:
    value = (name or "").strip().lower()
    return bool(value) and value != PRODUCTION_ENVIRONMENT


def parse_latency_ms(spec: str | None) -> dict[str, float]:
    """`read=2300,parse=600` → per-task milliseconds over the defaults. Unknown tasks or bad numbers raise."""
    latency = dict(DEFAULT_LATENCY_MS)
    if not spec or not spec.strip():
        return latency
    for item in spec.split(","):
        if not item.strip():
            continue
        task, sep, value = item.partition("=")
        task = task.strip()
        if not sep or task not in DEFAULT_LATENCY_MS:
            raise ValueError(f"{ENV_LATENCY}: bad item {item!r} (tasks: {', '.join(TASKS)})")
        ms = float(value)
        if not math.isfinite(ms) or ms < 0:
            raise ValueError(f"{ENV_LATENCY}: latency for {task} must be a non-negative number")
        latency[task] = ms
    return latency


# ---- canned answers ---------------------------------------------------------------------------------------
# Fictional demo providers only (contracts §5); values are plausible but made up.


def _option(
    vehicle_class: str,
    label: str,
    low: int,
    high: int,
    eta: int | None,
    box: list[int],
    struck: str | None = None,
) -> dict[str, Any]:
    return {
        "vehicle_class": vehicle_class,
        "label_text": label,
        "price_min": low,
        "price_max": high,
        "price_struck_text": struck,
        "eta_min": eta,
        "bbox_0_1000": box,
    }


CANNED_READS: tuple[dict[str, Any], ...] = (
    {
        "app": "demo_kestrel",
        "screen_type": "fare_options",
        "options": [
            _option("bike", "Bike", 48, 48, 2, [40, 420, 960, 500]),
            _option("auto", "Auto", 110, 110, 3, [40, 510, 960, 590], struck="₹140"),
            _option("cab_economy", "Kestrel Mini", 182, 195, 5, [40, 600, 960, 680]),
        ],
        "fee_text": None,
        "driver_eta_min": None,
        "driver_distance_text": None,
    },
    {
        "app": "demo_pepper",
        "screen_type": "fare_options",
        "options": [
            _option("auto", "Pepper Auto", 104, 104, 4, [30, 400, 970, 480]),
            _option("cab_economy", "Pepper Go", 176, 176, 6, [30, 490, 970, 570]),
            _option("cab_premium", "Pepper Prime", 241, 260, 7, [30, 580, 970, 660], struck="₹299"),
        ],
        "fee_text": None,
        "driver_eta_min": None,
        "driver_distance_text": None,
    },
    {
        "app": "demo_tamarind",
        "screen_type": "fare_options",
        "options": [
            _option("bike", "Moto", 52, 52, 3, [20, 380, 980, 450]),
            _option("auto", "Auto", 118, 118, 4, [20, 460, 980, 530]),
            _option("cab_economy", "Cab", 189, 189, 5, [20, 540, 980, 610]),
            _option("cab_xl", "Cab XL", 268, 268, 9, [20, 620, 980, 690]),
        ],
        "fee_text": None,
        "driver_eta_min": None,
        "driver_distance_text": None,
    },
    {
        "app": "demo_kestrel",
        "screen_type": "driver_assigned",
        "options": [],
        "fee_text": "Cancellation fee ₹25 after 2 min",
        "driver_eta_min": 4,
        "driver_distance_text": "1.2 km",
    },
)

CANNED_POINT: dict[str, Any] = {"box_0_1000": [620, 880, 940, 940]}

# (label, phrases) in priority order; the first phrase found in title + "\n" + text decides.
_PARSE_RULES: tuple[tuple[str, tuple[str, ...]], ...] = (
    (
        "driver_cancelled",
        ("driver cancelled", "captain cancelled", "cancelled by driver", "cancelled by captain"),
    ),
    ("no_drivers", ("no drivers", "no captains", "couldn't find", "could not find", "no rides available")),
    ("cancelled", ("ride cancelled", "booking cancelled", "trip cancelled", "cancelled", "रद्द")),
    ("searching", ("looking for", "finding", "searching")),
    ("arriving", ("has arrived", "arrived", "arriving", "is here", "reached")),
    ("assigned", ("on the way", "is coming", "accepted", "assigned", "confirmed")),
    ("trip_started", ("trip started", "ride started", "enjoy your ride")),
    ("ended", ("trip ended", "ride ended", "trip completed", "ride completed", "reached your destination")),
)
_NOTIFICATION_PREFIX = "Notification (untrusted data):\n"
_PLACE_PREFIX = "Input (untrusted data):\n"
_WORD_RE = re.compile(r"\w{3,}", re.UNICODE)


def _digest(request: ModelRequest) -> bytes:
    h = hashlib.sha256()
    h.update(request.task.encode())
    h.update(request.system.encode())
    for part in request.parts:
        if isinstance(part, ImagePart):
            h.update(b"\x00img")
            h.update(part.data)
        else:
            h.update(b"\x00txt")
            h.update(part.text.encode())
    return h.digest()


def _texts(request: ModelRequest) -> list[str]:
    return [p.text for p in request.parts if isinstance(p, TextPart)]


def _json_after(prefix: str, request: ModelRequest) -> dict[str, Any]:
    for text in _texts(request):
        if text.startswith(prefix):
            try:
                value = json.loads(text[len(prefix) :])
            except json.JSONDecodeError:
                return {}
            return value if isinstance(value, dict) else {}
    return {}


def _enum_at(schema: Mapping[str, Any], *path: str) -> list[str]:
    node: Any = schema
    for key in path:
        if not isinstance(node, Mapping):
            return []
        node = node.get(key)
    enum = node.get("enum") if isinstance(node, Mapping) else None
    return [v for v in enum if isinstance(v, str)] if isinstance(enum, list) else []


def answer_read(digest: bytes) -> dict[str, Any]:
    return json.loads(json.dumps(CANNED_READS[digest[0] % len(CANNED_READS)]))


def answer_reread(request: ModelRequest) -> dict[str, Any]:
    row_ids = _enum_at(request.schema, "properties", "rows", "items", "properties", "row_id")
    rows = []
    for row_id in row_ids:
        h = hashlib.sha256(row_id.encode()).digest()
        price = 90 + h[0] % 120
        rows.append(
            {
                "row_id": row_id,
                "vehicle_class": "auto",
                "label_text": "Auto",
                "price_min": price,
                "price_max": price,
                "eta_min": 2 + h[1] % 7,
            }
        )
    return {"rows": rows}


def answer_parse(request: ModelRequest) -> dict[str, Any]:
    notification = _json_after(_NOTIFICATION_PREFIX, request)
    title = notification.get("title") if isinstance(notification.get("title"), str) else ""
    text = notification.get("text") if isinstance(notification.get("text"), str) else ""
    combined = f"{title}\n{text}"
    for label, phrases in _PARSE_RULES:
        for phrase in phrases:
            match = re.search(re.escape(phrase), combined, re.IGNORECASE)
            if match:
                return {"label": label, "evidence_span": combined[match.start() : match.end()]}
    return {"label": "unknown", "evidence_span": None}


def answer_place(request: ModelRequest) -> dict[str, Any]:
    allowed = _enum_at(request.schema, "properties", "pick")
    data = _json_after(_PLACE_PREFIX, request)
    transcript = data.get("transcript") if isinstance(data.get("transcript"), str) else ""
    words = {w.lower() for w in _WORD_RE.findall(transcript)}
    candidates = data.get("candidates") if isinstance(data.get("candidates"), list) else []
    for candidate in candidates:
        if not isinstance(candidate, dict):
            continue
        cid, label = candidate.get("id"), candidate.get("label")
        if not isinstance(cid, str) or cid not in allowed or not isinstance(label, str):
            continue
        if words & {w.lower() for w in _WORD_RE.findall(label)}:
            return {"pick": cid}
    return {"pick": PLACE_NONE}


# ---- the provider -----------------------------------------------------------------------------------------


class StubProvider:
    """Drop-in `ModelProvider` returning canned schema-valid replies after a simulated latency."""

    def __init__(
        self,
        *,
        settings: Settings | None = None,
        environ: Mapping[str, str] | None = None,
        name: str = DEFAULT_PROVIDER,
        model_id: str = PINNED_MODEL_ID,
        latency_ms: Mapping[str, float] | None = None,
        jitter: float = DEFAULT_JITTER,
        fail_every: int = 0,
        report_usage: bool = True,
        mode: StructuredMode = StructuredMode.JSON_SCHEMA,
    ) -> None:
        ensure_stub_allowed(settings, environ)
        if not 0 <= jitter < 1:
            raise ValueError("jitter must be in [0, 1)")
        if fail_every < 0:
            raise ValueError("fail_every must be ≥ 0")
        self.name = name
        self.model_id = model_id
        self.pinned_mode = mode
        self._latency_ms = dict(
            DEFAULT_LATENCY_MS if latency_ms is None else {**DEFAULT_LATENCY_MS, **latency_ms}
        )
        self._jitter = jitter
        self._fail_every = fail_every
        self._report_usage = report_usage
        self._counter = itertools.count(1)
        self.calls: dict[str, int] = {task: 0 for task in TASKS}

    @classmethod
    def from_env(
        cls, settings: Settings | None = None, environ: Mapping[str, str] | None = None
    ) -> StubProvider:
        env = os.environ if environ is None else environ
        return cls(
            settings=settings,
            environ=env,
            latency_ms=parse_latency_ms(env.get(ENV_LATENCY)),
            jitter=float(env.get(ENV_JITTER, DEFAULT_JITTER)),
            fail_every=int(env.get(ENV_FAIL_EVERY, "0") or 0),
            report_usage=env.get(ENV_USAGE, "1").strip() != "0",
        )

    def latency_s(self, request: ModelRequest) -> float:
        """Simulated latency: the task's base value ± jitter, fixed for a given request content."""
        base = self._latency_ms.get(request.task, DEFAULT_LATENCY_MS["read"]) / 1000.0
        fraction = int.from_bytes(_digest(request)[1:3], "big") / 0xFFFF  # 0..1
        return max(0.0, base * (1 + self._jitter * (2 * fraction - 1)))

    async def complete(self, request: ModelRequest, *, mode: StructuredMode | None = None) -> ModelResult:
        n = next(self._counter)
        if request.task in self.calls:
            self.calls[request.task] += 1
        await asyncio.sleep(self.latency_s(request))
        if self._fail_every and n % self._fail_every == 0:
            raise ModelUnavailable("stub injected failure", status=503)
        data = self.answer(request)
        return ModelResult(
            data=data, usage=self._usage(request, data), mode=mode or self.pinned_mode, model=self.model_id
        )

    def answer(self, request: ModelRequest) -> dict[str, Any]:
        if request.task == "read":
            return answer_read(_digest(request))
        if request.task == "reread":
            return answer_reread(request)
        if request.task == "point":
            return json.loads(json.dumps(CANNED_POINT))
        if request.task == "parse":
            return answer_parse(request)
        if request.task == "place":
            return answer_place(request)
        raise ModelUnavailable(f"stub has no answer for task {request.task!r}", status=400)

    def _usage(self, request: ModelRequest, data: dict[str, Any]) -> Usage:
        if not self._report_usage:
            return Usage(input_tokens=0, cached_input_tokens=0, output_tokens=0)
        output = math.ceil(len(json.dumps(data, ensure_ascii=False)) / CHARS_PER_TOKEN)
        return Usage(
            input_tokens=estimate_input_tokens(request),
            cached_input_tokens=0,
            output_tokens=min(output, request.max_tokens),
        )

    async def list_models(self) -> list[str]:
        return [self.model_id]

    async def aclose(self) -> None:
        return None


def build_stub_providers(
    settings: Settings | None = None, environ: Mapping[str, str] | None = None
) -> dict[str, ModelProvider]:
    """Same shape as `router.build_providers`, with the stub registered under the default provider name."""
    provider = StubProvider.from_env(settings, environ)
    return {provider.name: provider}
