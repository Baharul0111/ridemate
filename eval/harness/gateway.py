"""In-process Channel A reads through the backend's ProviderRouter and production spend meter (gateway mode).

Run it inside the backend environment (`cd backend && uv run python ../eval/harness/record_channel_a.py
--mode gateway`). Each read sends exactly what `/v1/read-screen` sends, built by the backend's own code:

    request = prompts.read_request(ImagePart(image, media_type))
    result  = await deps.router.call(request, task=TaskKind.READ, purpose=Purpose.EVAL, principal=None,
                                     request_id="eval-run:<run id>:<uuid>")
    read    = prompts.validate_read(result.data)

`Purpose.EVAL` books the call on the evals sub-budget ($5), and the `eval-run:<run id>:` request-id prefix
makes the meter enforce its own per-run cap ($0.50, architecture §4.2) for the whole run, retries included.
So the server-side meter bounds the run even if this script's own guard were wrong.

The meter is mandatory. No reader is built without `deps.spend`, each call is refused while `ai_status` is
disabled, and the run aborts if `summary()["ai_spent_usd"]` did not grow by at least the call's settled cost
(a bypass) or the meter cannot be read.
"""

from __future__ import annotations

import asyncio
import time
import uuid
from collections.abc import Callable
from dataclasses import dataclass
from typing import Any

from record_channel_a import Job, ReadResult, StopRun

METER_TOLERANCE_USD = 1e-6


class MeterBypass(StopRun):
    """Money may have been spent without the meter seeing it: the run stops at once."""


@dataclass(frozen=True)
class BackendCalls:
    """The backend pieces one read needs. `from_production` takes them from ridemate_api; tests pass fakes."""

    build_request: Callable[[bytes, str], Any]  # (image bytes, media type) -> ModelRequest
    validate: Callable[[Any], dict[str, Any]]  # raw model JSON -> ChannelARead dict (contracts §1.3)
    task: Any  # router.TaskKind.READ
    purpose: Any  # interfaces.Purpose.EVAL


class GatewayReader:
    def __init__(self, router: Any, spend: Any, calls: BackendCalls, run_id: str | None = None) -> None:
        if router is None:
            raise SystemExit("gateway mode: deps.router is not wired")
        if spend is None:
            raise SystemExit("gateway mode: no spend meter; live calls without the meter are not allowed")
        self.router = router
        self.spend = spend
        self.calls = calls
        self.run_id = run_id or uuid.uuid4().hex[:16]
        # One loop for the whole run: the router's HTTP client and locks must not hop between event loops.
        self._loop = asyncio.new_event_loop()

    @classmethod
    def from_production(cls) -> GatewayReader:
        try:
            from ridemate_api import prompts
            from ridemate_api.deps_prod import build_production_deps
            from ridemate_api.interfaces import Purpose
            from ridemate_api.model_gateway import ImagePart
            from ridemate_api.router import TaskKind
        except ImportError as e:
            raise SystemExit(
                f"gateway mode must run in the backend environment (cd backend && uv run …): {e}"
            ) from e
        deps = build_production_deps()
        calls = BackendCalls(
            build_request=lambda data, media_type: prompts.read_request(ImagePart(data, media_type)),
            validate=prompts.validate_read,
            task=TaskKind.READ,
            purpose=Purpose.EVAL,
        )
        return cls(deps.router, deps.spend, calls)

    def close(self) -> None:
        self._loop.close()

    def _spent(self) -> float:
        try:
            summary = self.spend.summary()
        except Exception as e:  # noqa: BLE001 - an unreadable meter must stop the run, whatever the cause
            raise MeterBypass(
                f"spend meter unreadable ({type(e).__name__}); cannot prove calls are metered"
            ) from e
        if "ai_spent_usd" not in summary:
            raise MeterBypass("spend meter summary has no ai_spent_usd; cannot prove the call was metered")
        return float(summary["ai_spent_usd"])

    def read(self, job: Job, image: bytes) -> ReadResult:
        status = self.spend.ai_status(None)
        if not status.enabled:
            raise StopRun(f"AI paused by the spend meter ({status.reason})")
        before = self._spent()
        request_id = f"eval-run:{self.run_id}:{uuid.uuid4().hex}"
        started = time.monotonic()
        try:
            request = self.calls.build_request(image, job.media_type)
            result = self._loop.run_until_complete(
                self.router.call(
                    request,
                    task=self.calls.task,
                    purpose=self.calls.purpose,
                    principal=None,
                    request_id=request_id,
                )
            )
        except Exception as e:  # noqa: BLE001 - any router failure (timeout, breaker, ApiError) ends the run
            raise StopRun(f"router refused or failed: {type(e).__name__}: {getattr(e, 'code', e)}") from e
        cost = float(result.cost_usd)
        metered = self._spent() - before
        if metered < cost - METER_TOLERANCE_USD:
            raise MeterBypass(f"call {request_id} cost ${cost:.6f} but the meter did not record it; aborting")
        return ReadResult(
            read=self.calls.validate(result.data),
            model=result.model,
            source="live",
            request_id=request_id,
            # The router's own timing of the model call (what the 12 s deadline and the read-p95 alert bound).
            latency_ms=int(result.latency_ms),
            usage=None,
            cost_usd=round(cost, 6),
            # The meter's growth also covers a failed first attempt that the router retried.
            charged_usd=round(max(metered, cost), 6),
            # This laptop's wall time for the whole call, Modal Dict reservation and settlement included.
            wall_ms=int((time.monotonic() - started) * 1000),
        )
