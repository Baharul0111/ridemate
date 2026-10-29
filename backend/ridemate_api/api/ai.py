"""AI endpoints (contracts §1.3): read-screen, reread, point, parse-notification, place-pick.

Every endpoint runs the same order: IP limit → auth (Google, unless the image is an allowlisted demo image on
read-screen or point) → kill switches → body and magic-byte checks → quota (read or parse; refunded when the server
fails to answer) → router call → validation → metrics/audit → response. Demo images never use the caller's quota
(M9); what anonymous demo traffic can cost is bounded instead: by the 16 bundled images × the point targets
(prompts.POINT_TARGETS, enforced before any model call), the demo cache's global daily count of live misses, and
the demo sub-budget's own daily cap, which is separate from the public's day cap. /v1/reread always needs Google
sign-in (the app never rereads demo frames, and row ids would make every request a fresh cache key). The upload
itself (already size-capped by the body limit) is read before auth only to hash it for the demo allowlist.
Bodies are parsed by hand rather than by FastAPI parameters so that this order holds and every failure renders
as the §1.2 error envelope.

Every hot-store and Firestore call (IP bucket, run claim, quota consume/refund, demo cache, spend meter inside the
router, audit record) is blocking, so it runs in the worker-thread pool (offload.py): a slow Modal Dict call
never stalls the other requests this container serves.
"""

from __future__ import annotations

import contextlib
import functools
import json
import logging
import uuid
from collections.abc import AsyncIterator
from dataclasses import dataclass
from typing import Annotated, Any, Literal

from fastapi import APIRouter, Depends, Request
from pydantic import BaseModel, ConfigDict, Field, ValidationError
from starlette.datastructures import FormData, UploadFile

from .. import prompts
from ..audit import AuditEntry
from ..clock import ist_day
from ..demo_cache import DemoCache, sha256_hex
from ..deps import Deps, get_deps
from ..interfaces import ApiError, FirestoreUnavailable, Principal, Purpose, QuotaKind
from ..middleware import header_uint, normalize_id
from ..model_gateway import ImagePart
from ..offload import off_loop, off_loop_shielded
from ..router import ProviderRouter, TaskKind
from ..settings import MAX_IMAGE_BYTES, MAX_JSON_BYTES, PINNED_MODEL_ID, TAP_BUDGET_S
from ..store import StoreUnavailable

router = APIRouter()
log = logging.getLogger(__name__)
audit_log = logging.getLogger("ridemate.audit")

TAP_BUDGET_MS = int(TAP_BUDGET_S * 1000)
MAX_REREAD_ROWS = prompts.MAX_OPTIONS
MAX_META_CHARS = 4096
JPEG_MAGIC = b"\xff\xd8\xff"
PNG_MAGIC = b"\x89PNG\r\n\x1a\n"
# Model-backed runs allowed per (user, quota kind, X-Request-Id): the first try and the phone's one retry (§4.12).
# Keyed by the quota kind, not the endpoint: read-screen, reread and point share one 'read' unit per request id, so
# reusing an id across them must not multiply the runs that unit pays for.
MAX_RUNS_PER_REQUEST_ID = 2

AppId = Literal["uber", "ola", "rapido", "demo_kestrel", "demo_pepper", "demo_tamarind"]
DepsDep = Annotated[Deps, Depends(get_deps)]


# ---- request bodies --------------------------------------------------------------------------------------------


class _Body(BaseModel):
    model_config = ConfigDict(extra="ignore", str_strip_whitespace=False)


class ReadMeta(_Body):
    app: AppId
    frame_w: int | None = Field(default=None, ge=1, le=10_000)
    frame_h: int | None = Field(default=None, ge=1, le=10_000)
    demo: bool = False


class RowRef(_Body):
    row_id: str = Field(pattern=prompts.ROW_ID_PATTERN)


class RereadMeta(_Body):
    app: AppId
    rows: list[RowRef] = Field(min_length=1, max_length=MAX_REREAD_ROWS)


class PointMeta(_Body):
    app: AppId
    target: str = Field(pattern=prompts.TARGET_PATTERN)


class ParseNotificationIn(_Body):
    app: AppId
    version_code: int | None = Field(default=None, ge=0)
    title: str = Field(default="", max_length=1000)
    text: str = Field(default="", max_length=4000)
    channel_id: str | None = Field(default=None, max_length=128)
    category: str | None = Field(default=None, max_length=64)
    is_ongoing: bool | None = None
    leg_state: str | None = Field(default=None, max_length=32, pattern=r"^[A-Z_]+$")


class Candidate(_Body):
    id: str = Field(pattern=prompts.CANDIDATE_ID_PATTERN)
    label: str = Field(min_length=1, max_length=300)


class PlacePickIn(_Body):
    transcript: str = Field(min_length=1, max_length=500)
    lang: str | None = Field(default=None, max_length=16, pattern=r"^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$")
    candidates: list[Candidate] = Field(default_factory=list, max_length=5)


# ---- per-request context ---------------------------------------------------------------------------------------


@dataclass
class _Call:
    deps: Deps
    endpoint: str
    request_id: str
    started: float
    deadline_at: float  # monotonic end of the phone's tap budget
    session_id: str | None = None
    version_code: int | None = None
    app: str | None = None  # known once the body is parsed
    principal: Principal | None = None
    cost_usd: float = 0.0

    @classmethod
    def start(cls, deps: Deps, request: Request, endpoint: str) -> _Call:
        started = deps.clock.monotonic()
        return cls(
            deps,
            endpoint,
            _request_id(request),
            started,
            started + _tap_budget_ms(request) / 1000,
            session_id=_session_id(request),
            version_code=header_uint(request.headers.get("x-app-version")),
        )

    def latency_ms(self) -> int:
        return int((self.deps.clock.monotonic() - self.started) * 1000)

    async def audit(self, outcome: str, *, source: str | None = None) -> None:
        latency = self.latency_ms()
        self.deps.metrics.incr("api_ai", endpoint=self.endpoint, outcome=outcome)
        self.deps.metrics.observe_ms("api_ai_latency_ms", latency, endpoint=self.endpoint)
        # Metadata only: never image bytes, notification text or model output.
        audit_log.info(
            json.dumps(
                {
                    "event": "ai_request",
                    "endpoint": self.endpoint,
                    "request_id": self.request_id,
                    "uid_hash": self.principal.uid_hash if self.principal else None,
                    "outcome": outcome,
                    "source": source,
                    "latency_ms": latency,
                    "cost_usd": round(self.cost_usd, 6),
                }
            )
        )
        trail = self.deps.audit
        if trail is not None and self.principal is not None and self.principal.is_google:
            entry = AuditEntry(
                endpoint=self.endpoint,
                outcome=outcome,
                latency_ms=latency,
                cost_usd=self.cost_usd,
                source=source,
                app=self.app,
                version_code=self.version_code,
            )
            await off_loop(trail.record, self.principal, self.session_id, entry)


def _request_id(request: Request) -> str:
    rid = getattr(request.state, "request_id", None) or request.headers.get("x-request-id", "")
    if isinstance(rid, str) and 8 <= len(rid) <= 64 and all(c.isalnum() or c == "-" for c in rid):
        return rid
    return str(uuid.uuid4())


def _outcome(error: Exception) -> str:
    """The audited outcome of a failed request: the §1.2 error code the caller receives (app.py maps the
    storage exceptions the same way)."""
    if isinstance(error, ApiError):
        return error.code
    if isinstance(error, StoreUnavailable):
        return "meter_unavailable"
    if isinstance(error, FirestoreUnavailable):
        return "storage_unavailable"
    return "internal"


def _session_id(request: Request) -> str | None:
    """X-Session-Id as the request middleware normalised it (or the header itself when there is no middleware)."""
    sid = getattr(request.state, "session_id", None)
    return sid if isinstance(sid, str) else normalize_id(request.headers.get("x-session-id"))


def _tap_budget_ms(request: Request) -> int:
    raw = request.headers.get("x-tap-deadline-ms")
    try:
        ms = int(raw) if raw is not None else TAP_BUDGET_MS
    except ValueError:
        ms = TAP_BUDGET_MS
    return max(0, min(ms, TAP_BUDGET_MS))


# ---- shared steps ----------------------------------------------------------------------------------------------


async def _form(request: Request, *, max_files: int) -> FormData:
    try:
        return await request.form(max_files=max_files, max_fields=2, max_part_size=MAX_META_CHARS)
    except Exception as e:  # malformed multipart, too many parts, non-form body
        raise ApiError(422, "invalid") from e


async def _upload_bytes(value: Any) -> bytes:
    """The upload's bytes, reading at most one byte past the 1 MB limit."""
    if not isinstance(value, UploadFile):
        raise ApiError(422, "invalid")
    data = await value.read(MAX_IMAGE_BYTES + 1)
    if len(data) > MAX_IMAGE_BYTES:
        raise ApiError(413, "too_large")
    if not data:
        raise ApiError(422, "invalid")
    return data


def _image(data: bytes) -> ImagePart:
    if data.startswith(JPEG_MAGIC):
        return ImagePart(data, "image/jpeg")
    if data.startswith(PNG_MAGIC):
        return ImagePart(data, "image/png")
    raise ApiError(415, "bad_media")


def _meta[M: BaseModel](raw: Any, model: type[M]) -> M:
    if not isinstance(raw, str) or len(raw) > MAX_META_CHARS:
        raise ApiError(422, "invalid")
    try:
        return model.model_validate_json(raw)
    except ValidationError as e:
        raise ApiError(422, "invalid") from e


async def _json_body[M: BaseModel](request: Request, model: type[M]) -> M:
    raw = await request.body()
    if len(raw) > MAX_JSON_BYTES:
        raise ApiError(413, "too_large")
    try:
        return model.model_validate_json(raw)
    except ValidationError as e:
        raise ApiError(422, "invalid") from e


def _demo_cache(deps: Deps) -> DemoCache | None:
    cache = deps.demo_cache
    if cache is None or not deps.remote_config.get_bool("demo_enabled", True):
        return None
    return cache


def _ai_switched_on(deps: Deps, app: str | None, *, screen: bool) -> bool:
    """Remote Config kill switches: `ai_reader_enabled` for all AI, `reader_enabled_<app>` for screen reads."""
    rc = deps.remote_config
    if not rc.get_bool("ai_reader_enabled", True):
        return False
    return not (screen and app in prompts.LIVE_APPS and not rc.get_bool(f"reader_enabled_{app}", True))


def _router(deps: Deps) -> ProviderRouter:
    if deps.router is None:
        raise ApiError(503, "ai_unavailable", reason="model_missing")  # no model configured (contracts §1.3)
    return deps.router


def _claim_run(call: _Call, principal: Principal, kind: QuotaKind) -> tuple[str, int]:
    """Quota counting is idempotent on X-Request-Id (contracts §1.1), so without this a replayed id would buy
    unlimited model calls for one quota unit. Each (user, quota kind, id) gets MAX_RUNS_PER_REQUEST_ID runs; failed
    runs are not given back, because a timed-out call was still paid for. The keys sit under the user's daily quota
    prefix ('quota:{hmac}:{day}:…'), so the hot-store cleanup drops them with the quota slots.
    Returns (the run keys' base, this run's index)."""
    deps = call.deps
    base = f"quota:{principal.sub_hmac}:{ist_day(deps.clock.now_utc())}:run:{kind}:{call.request_id}"
    try:
        for n in range(MAX_RUNS_PER_REQUEST_ID):
            if deps.store.put_if_absent(f"{base}:{n}", 1):
                return base, n
    except StoreUnavailable:
        raise ApiError(503, "meter_unavailable", reason="meter") from None
    deps.metrics.incr("api_ai_replay_refused", endpoint=call.endpoint)
    raise ApiError(422, "invalid")


def _give_back(call: _Call, principal: Principal, kind: QuotaKind, base: str, run: int) -> None:
    """Refund the unit this run counted, unless a later run of the same request id has been claimed meanwhile: that
    run's consume found the unit already counted and is answered on it, so refunding here would make it free (an
    answered run, then a replay of its id that fails or is cancelled, would otherwise give the unit back)."""
    deps = call.deps
    try:
        later = any(
            deps.store.get(f"{base}:{n}") is not None for n in range(run + 1, MAX_RUNS_PER_REQUEST_ID)
        )
    except StoreUnavailable:
        log.warning("quota refund skipped: store unavailable")  # the unit stays used: fail closed
        return
    if later:
        deps.metrics.incr("api_ai_refund_withheld", endpoint=call.endpoint)
        return
    try:
        deps.quotas.refund(principal, kind, call.request_id)
    except Exception:
        log.exception("quota refund failed")


@contextlib.asynccontextmanager
async def _quota(call: _Call, kind: QuotaKind) -> AsyncIterator[None]:
    """Claim a run for this request id, consume one unit, and give the unit back if the server fails to answer
    (or if the request is cancelled while the unit is being counted). Only a run that counted the unit itself can
    give it back: a replay whose id was already counted (consume returns False) never refunds."""
    deps, principal, request_id = call.deps, call.principal, call.request_id
    if principal is None:  # auth always runs first; never count a unit for nobody
        raise ApiError(401, "auth_required")
    base, run = await off_loop(_claim_run, call, principal, kind)
    give_back = functools.partial(_give_back, call, principal, kind, base, run)
    counted = await off_loop_shielded(
        deps.quotas.consume, principal, kind, request_id, undo=lambda c: give_back() if c else None
    )
    try:
        yield
    except BaseException:
        if counted:
            await off_loop_shielded(give_back)
        raise


async def _model(
    call: _Call, request: Any, *, task: TaskKind, purpose: Purpose
) -> tuple[dict[str, Any], str]:
    result = await _router(call.deps).call(
        request,
        task=task,
        purpose=purpose,
        principal=call.principal,
        request_id=call.request_id,
        deadline_at=call.deadline_at,
    )
    call.cost_usd += result.cost_usd
    return result.data, result.model


# ---- endpoints -------------------------------------------------------------------------------------------------


@router.post("/v1/read-screen")
async def read_screen(request: Request, deps: DepsDep) -> dict[str, Any]:
    call = _Call.start(deps, request, "read_screen")
    try:
        await off_loop(deps.ip_limiter.check, request)
        form = await _form(request, max_files=1)
        data = await _upload_bytes(form.get("image"))
        sha = sha256_hex(data)
        cache = _demo_cache(deps)
        demo = cache is not None and cache.is_allowlisted(sha)
        call.principal = await deps.auth.verify(request, require_google=not demo)
        meta = _meta(form.get("meta"), ReadMeta)
        call.app = meta.app
        ai_on = _ai_switched_on(deps, meta.app, screen=True)
        if not ai_on and not demo:
            raise ApiError(503, "ai_unavailable", reason="kill_switch")
        image = _image(data)

        async def live(purpose: Purpose) -> tuple[dict[str, Any], str]:
            raw, model = await _model(call, prompts.read_request(image), task=TaskKind.READ, purpose=purpose)
            return prompts.validate_read(raw), model

        if cache is not None and demo:
            answer = await cache.read(
                sha, (lambda: live(Purpose.DEMO)) if ai_on and deps.router is not None else None
            )
            read, source, model = answer.payload, answer.source, answer.model
        else:
            _router(deps)
            async with _quota(call, "read"):
                read, model = await live(Purpose.READ)
            source = "live"
        await call.audit("ok", source=source)
        return {
            "read": read,
            "source": source,
            "model": model or PINNED_MODEL_ID,
            "latency_ms": call.latency_ms(),
            "request_id": call.request_id,
        }
    except Exception as e:
        await call.audit(_outcome(e))
        raise


@router.post("/v1/reread")
async def reread(request: Request, deps: DepsDep) -> dict[str, Any]:
    call = _Call.start(deps, request, "reread")
    try:
        await off_loop(deps.ip_limiter.check, request)
        form = await _form(request, max_files=MAX_REREAD_ROWS)
        crops: dict[str, bytes] = {}
        total = 0
        for key, value in form.multi_items():
            if key.startswith("crop_"):
                crop = await _upload_bytes(value)
                crops[key.removeprefix("crop_")] = crop
                total += len(crop)
        if total > MAX_IMAGE_BYTES:
            raise ApiError(413, "too_large")
        # Always Google: crops of demo images are not a demo path (the app never sends them), and their
        # caller-chosen row ids would make every anonymous request a fresh, model-backed cache miss.
        call.principal = await deps.auth.verify(request, require_google=True)
        meta = _meta(form.get("meta"), RereadMeta)
        call.app = meta.app
        row_ids = [row.row_id for row in meta.rows]
        if len(set(row_ids)) != len(row_ids) or set(row_ids) != set(crops):
            raise ApiError(422, "invalid")
        if not _ai_switched_on(deps, meta.app, screen=True):
            raise ApiError(503, "ai_unavailable", reason="kill_switch")
        parts = [(row_id, _image(crops[row_id])) for row_id in row_ids]
        _router(deps)
        async with _quota(call, "read"):
            raw, _ = await _model(
                call, prompts.reread_request(parts), task=TaskKind.REREAD, purpose=Purpose.REREAD
            )
        rows = prompts.validate_reread(raw, row_ids)
        await call.audit("ok", source="live")
        return {"rows": rows, "source": "live", "request_id": call.request_id}
    except Exception as e:
        await call.audit(_outcome(e))
        raise


@router.post("/v1/point")
async def point(request: Request, deps: DepsDep) -> dict[str, Any]:
    call = _Call.start(deps, request, "point")
    try:
        await off_loop(deps.ip_limiter.check, request)
        form = await _form(request, max_files=1)
        data = await _upload_bytes(form.get("image"))
        sha = sha256_hex(data)
        cache = _demo_cache(deps)
        demo = cache is not None and cache.is_allowlisted(sha)
        call.principal = await deps.auth.verify(request, require_google=not demo)
        meta = _meta(form.get("meta"), PointMeta)
        call.app = meta.app
        if meta.target not in prompts.POINT_TARGETS:
            # A closed set: free-form targets would let anonymous demo requests mint unlimited cache keys.
            raise ApiError(422, "invalid")
        if not deps.remote_config.get_bool("point_supported", False):
            # Day-1 spike pinned grounding as unsupported: the phone hides "Show me". No model call.
            await call.audit("unsupported")
            return {"supported": False, "box_0_1000": None, "request_id": call.request_id}
        ai_on = _ai_switched_on(deps, meta.app, screen=True)
        if not ai_on and not demo:
            raise ApiError(503, "ai_unavailable", reason="kill_switch")
        image = _image(data)

        async def live(purpose: Purpose) -> tuple[dict[str, Any], str]:
            raw, model = await _model(
                call, prompts.point_request(image, meta.target), task=TaskKind.POINT, purpose=purpose
            )
            return {"box_0_1000": prompts.validate_point(raw)}, model

        if cache is not None and demo:
            answer = await cache.cached_call(
                "point",
                sha256_hex(f"{sha}:{meta.target}".encode()),
                (lambda: live(Purpose.DEMO)) if ai_on and deps.router is not None else None,
            )
            payload, source = answer.payload, answer.source
        else:
            _router(deps)
            async with _quota(call, "read"):
                payload, _ = await live(Purpose.POINT)
            source = "live"
        await call.audit("ok", source=source)
        return {
            "supported": True,
            "box_0_1000": payload["box_0_1000"],
            "source": source,
            "request_id": call.request_id,
        }
    except Exception as e:
        await call.audit(_outcome(e))
        raise


@router.post("/v1/parse-notification")
async def parse_notification(request: Request, deps: DepsDep) -> dict[str, Any]:
    call = _Call.start(deps, request, "parse_notification")
    try:
        await off_loop(deps.ip_limiter.check, request)
        call.principal = await deps.auth.verify(request, require_google=True)
        if not _ai_switched_on(deps, None, screen=False):
            raise ApiError(503, "ai_unavailable", reason="kill_switch")
        body = await _json_body(request, ParseNotificationIn)
        call.app = body.app
        if not body.title.strip() and not body.text.strip():
            await call.audit("empty")
            return {"label": "unknown", "evidence_span": None, "request_id": call.request_id}
        notification = body.model_dump(exclude={"version_code"})
        _router(deps)
        async with _quota(call, "parse"):
            raw, _ = await _model(
                call, prompts.parse_request(notification), task=TaskKind.PARSE, purpose=Purpose.PARSE
            )
        label, span = prompts.validate_parse(raw, body.title, body.text)
        await call.audit("ok", source="live")
        return {"label": label, "evidence_span": span, "request_id": call.request_id}
    except Exception as e:
        await call.audit(_outcome(e))
        raise


@router.post("/v1/place-pick")
async def place_pick(request: Request, deps: DepsDep) -> dict[str, Any]:
    call = _Call.start(deps, request, "place_pick")
    try:
        await off_loop(deps.ip_limiter.check, request)
        call.principal = await deps.auth.verify(request, require_google=True)
        if not _ai_switched_on(deps, None, screen=False):
            raise ApiError(503, "ai_unavailable", reason="kill_switch")
        body = await _json_body(request, PlacePickIn)
        ids = [c.id for c in body.candidates]
        if len(set(ids)) != len(ids) or prompts.PLACE_NONE in ids:
            raise ApiError(422, "invalid")
        if not ids:
            await call.audit("empty")
            return {"pick": prompts.PLACE_NONE, "request_id": call.request_id}
        _router(deps)
        # A place pick is a short text call and counts against the parse allowance.
        async with _quota(call, "parse"):
            raw, _ = await _model(
                call,
                prompts.place_request(body.transcript, body.lang, [(c.id, c.label) for c in body.candidates]),
                task=TaskKind.PLACE,
                purpose=Purpose.PLACE,
            )
        pick = prompts.validate_place(raw, ids)
        await call.audit("ok", source="live")
        return {"pick": pick, "request_id": call.request_id}
    except Exception as e:
        await call.audit(_outcome(e))
        raise
