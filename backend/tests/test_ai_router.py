"""ProviderRouter: spend reservation lifecycle, deadlines and the retry-budget rule, breaker, pinned model id."""

from __future__ import annotations

import asyncio
import threading
from typing import Any

import pytest

from ridemate_api import prompts
from ridemate_api.clock import FakeClock, ist_day
from ridemate_api.interfaces import ApiError, Principal, Purpose, Reservation
from ridemate_api.model_gateway import (
    ImagePart,
    ModelBadOutput,
    ModelTimeout,
    ModelUnavailable,
    StructuredMode,
    Usage,
)
from ridemate_api.router import (
    DEFAULT_POLICIES,
    IMAGE_TOKENS_ESTIMATE,
    STRUCTURED_MODE_ENV,
    BreakerState,
    ProviderRouter,
    TaskKind,
    TaskPolicy,
    attempt_budget_s,
    build_providers,
    estimate_input_tokens,
    max_cost_usd,
)
from ridemate_api.settings import (
    DEADLINE_READ_S,
    MAX_TOKENS_READ,
    PRICE_INPUT_PER_M,
    PRICE_OUTPUT_PER_M,
    RETRY_HEADROOM_S,
    RETRY_MIN_REMAINING_S,
)
from ridemate_api.testing import make_test_deps

from .test_ai_fakes import FakeMetrics, FakeProvider, FakeRemoteConfig, FakeSpend, make_settings

PNG = b"\x89PNG\r\n\x1a\n" + b"\x00" * 16
OK = {"app": "uber", "screen_type": "fare_options", "options": []}


def read_req():
    return prompts.read_request(ImagePart(PNG, "image/png"))


def parse_req():
    return prompts.parse_request({"app": "uber", "title": "Captain", "text": "is on the way"})


class Rig:
    def __init__(self, rc: dict | None = None, policies: dict | None = None) -> None:
        self.clock = FakeClock()
        self.provider = FakeProvider()
        self.spend = FakeSpend()
        self.metrics = FakeMetrics()
        self.rc = FakeRemoteConfig(rc)
        self.router = ProviderRouter(
            providers={"modal_spark_open": self.provider},
            spend=self.spend,
            remote_config=self.rc,
            metrics=self.metrics,
            clock=self.clock,
            policies=policies,
        )

    async def read(self, deadline_at: float | None = None) -> object:
        return await self.router.call(
            read_req(),
            task=TaskKind.READ,
            purpose=Purpose.READ,
            principal=None,
            request_id="rid",
            deadline_at=deadline_at,
        )

    async def parse(self) -> object:
        return await self.router.call(
            parse_req(), task=TaskKind.PARSE, purpose=Purpose.PARSE, principal=None, request_id="rid"
        )


async def test_success_reserves_worst_case_then_settles_actual_usage() -> None:
    rig = Rig()
    rig.provider.script("read", OK)
    result = await rig.read()
    assert result.data == OK and result.attempts == 1
    (reservation,) = rig.spend.reservations
    assert reservation.purpose is Purpose.READ
    assert reservation.reserved_usd == pytest.approx(max_cost_usd(read_req()))
    assert rig.spend.settled == [("r1", 1500, 0, 300)]
    assert not rig.spend.kept and not rig.spend.released


def test_max_cost_counts_image_estimate_text_and_full_output_at_list_prices() -> None:
    request = read_req()
    tokens = estimate_input_tokens(request)
    assert tokens == IMAGE_TOKENS_ESTIMATE + -(-request.text_chars // 3)
    assert max_cost_usd(request) == pytest.approx(
        (tokens * PRICE_INPUT_PER_M + MAX_TOKENS_READ * PRICE_OUTPUT_PER_M) / 1_000_000
    )


async def test_missing_usage_keeps_the_whole_reservation() -> None:
    rig = Rig()
    rig.provider.usage = None  # type: ignore[assignment]
    rig.provider.script("read", OK)
    await rig.read()
    assert rig.spend.kept == ["r1"]


# Production timing: the phone's 20 s tap budget starts at the user's tap, and capture, OCR and masking use about
# 2.5 s of it before the upload, so the server sees X-Tap-Deadline-Ms ≈ 17 500.
PHONE_SPENT_BEFORE_UPLOAD_S = 2.5
SERVER_TAP_REMAINING_S = 20.0 - PHONE_SPENT_BEFORE_UPLOAD_S


def _slow_until(rig: Rig, first_timeout_s: float, second_s: float | None = None) -> None:
    """The model hangs until the router's first-attempt deadline; the second attempt takes `second_s`."""
    calls: list[float] = []

    def on_call() -> None:
        calls.append(rig.clock.monotonic())
        rig.clock.advance(first_timeout_s if len(calls) == 1 else (second_s or 0.0))

    rig.provider.on_call = on_call


async def test_a_slow_model_gets_one_more_try_within_the_production_tap_budget() -> None:
    rig = Rig()
    deadline_at = rig.clock.monotonic() + SERVER_TAP_REMAINING_S
    first = attempt_budget_s(
        DEFAULT_POLICIES[TaskKind.READ], attempt=1, elapsed_s=0.0, remaining_s=SERVER_TAP_REMAINING_S
    )
    assert first == pytest.approx(SERVER_TAP_REMAINING_S - RETRY_MIN_REMAINING_S - RETRY_HEADROOM_S)
    _slow_until(rig, first, second_s=4.0)
    rig.provider.script("read", ModelTimeout("slow"), OK)
    result = await rig.read(deadline_at=deadline_at)
    assert result.attempts == 2 and rig.provider.calls == 2
    assert rig.spend.kept == ["r1"]  # the timed-out attempt may have been billed: kept in full
    assert rig.spend.settled[-1][0] == "r2"
    assert [r.request_id for r in rig.spend.reservations] == ["rid:read:1", "rid:read:2"]
    assert rig.metrics.total("ai_retry", task="read") == 1


async def test_two_timeouts_end_in_model_timeout_so_the_phone_offers_typing_the_price() -> None:
    rig = Rig()
    deadline_at = rig.clock.monotonic() + SERVER_TAP_REMAINING_S
    first = attempt_budget_s(
        DEFAULT_POLICIES[TaskKind.READ], attempt=1, elapsed_s=0.0, remaining_s=SERVER_TAP_REMAINING_S
    )
    _slow_until(rig, first, second_s=RETRY_MIN_REMAINING_S)
    rig.provider.script("read", ModelTimeout("slow"), ModelTimeout("slow again"), OK)
    with pytest.raises(ApiError) as err:
        await rig.read(deadline_at=deadline_at)
    assert (err.value.status, err.value.code) == (504, "model_timeout")  # → ManualMode(TIMEOUT) on the phone
    assert rig.provider.calls == 2  # never a third
    assert rig.spend.kept == ["r1", "r2"]
    assert rig.clock.monotonic() <= deadline_at


async def test_a_timeout_is_not_retried_when_too_little_of_the_tap_budget_is_left() -> None:
    rig = Rig()
    deadline_at = rig.clock.monotonic() + 9.0  # under 2 × 6 s: one attempt gets all of it

    def on_call() -> None:
        rig.clock.advance(9.0)

    rig.provider.on_call = on_call
    rig.provider.script("read", ModelTimeout("slow"), OK)
    with pytest.raises(ApiError) as err:
        await rig.read(deadline_at=deadline_at)
    assert err.value.code == "model_timeout"
    assert rig.provider.calls == 1 and rig.spend.kept == ["r1"]


async def test_parse_timeouts_are_never_retried() -> None:
    rig = Rig()
    rig.provider.script("parse", ModelTimeout("slow"), {"label": "assigned"})
    with pytest.raises(ApiError) as err:
        await rig.parse()
    assert err.value.code == "model_timeout"
    assert rig.provider.calls == 1 and rig.spend.kept == ["r1"]


@pytest.mark.parametrize(
    ("remaining_s", "expected_s"),
    [
        (None, DEADLINE_READ_S),  # no tap budget known: the read's own deadline
        (20.0, DEADLINE_READ_S),  # 20 − 7 = 13 > 12: the read deadline already leaves room
        (
            SERVER_TAP_REMAINING_S,
            SERVER_TAP_REMAINING_S - 7.0,
        ),  # production: 10.5 s, then ≥ 6 s for the retry
        (13.0, 6.0),  # the smallest budget that still fits both attempts
        (12.9, 12.0),  # too small for two: one attempt gets everything up to its deadline
        (9.0, 9.0),
    ],
)
def test_the_first_read_attempt_leaves_room_for_the_retry(remaining_s, expected_s) -> None:
    budget = attempt_budget_s(
        DEFAULT_POLICIES[TaskKind.READ], attempt=1, elapsed_s=0.0, remaining_s=remaining_s
    )
    assert budget == pytest.approx(expected_s)


def test_tasks_without_retry_keep_their_whole_deadline() -> None:
    reread = DEFAULT_POLICIES[TaskKind.REREAD]
    assert attempt_budget_s(reread, attempt=1, elapsed_s=0.0, remaining_s=SERVER_TAP_REMAINING_S) == 12.0
    second = attempt_budget_s(DEFAULT_POLICIES[TaskKind.READ], attempt=2, elapsed_s=11.0, remaining_s=6.5)
    assert second == pytest.approx(6.5)


async def test_router_deadline_cancels_a_slow_call_and_keeps_the_reservation() -> None:
    policies = dict(DEFAULT_POLICIES)
    policies[TaskKind.PARSE] = TaskPolicy(0.05, retry=False, tap_budget=False, route_key="parse")
    rig = Rig(policies=policies)

    async def slow(_request: object) -> dict:
        await asyncio.sleep(5)
        return {}

    rig.provider.script("parse", slow)
    with pytest.raises(ApiError) as err:
        await rig.parse()
    assert err.value.code == "model_timeout"
    assert rig.spend.kept == ["r1"]


async def test_read_retries_once_when_six_seconds_remain_and_first_attempt_did_not_time_out() -> None:
    rig = Rig()
    rig.provider.script("read", ModelUnavailable("boom", status=502), OK)
    result = await rig.read(deadline_at=rig.clock.monotonic() + 20)
    assert result.attempts == 2
    assert rig.provider.calls == 2
    assert rig.spend.kept == ["r1"]  # 5xx: usage unknown, conservative
    assert rig.spend.settled[-1][0] == "r2"
    assert [r.request_id for r in rig.spend.reservations] == ["rid:read:1", "rid:read:2"]


async def test_read_does_not_retry_when_less_than_six_seconds_remain() -> None:
    rig = Rig()

    def spend_time() -> None:
        rig.clock.advance(15)  # first attempt took 15 s of the 20 s budget

    rig.provider.on_call = spend_time
    rig.provider.script("read", ModelUnavailable("boom", status=502), OK)
    with pytest.raises(ApiError) as err:
        await rig.read(deadline_at=rig.clock.monotonic() + 20)
    assert (err.value.status, err.value.code) == (503, "ai_unavailable")
    assert rig.provider.calls == 1


async def test_retry_attempt_is_bounded_by_the_remaining_tap_budget() -> None:
    rig = Rig()
    rig.clock.advance(0)
    seen: list[float] = []

    def first_takes_seven_seconds() -> None:
        if not seen:
            rig.clock.advance(7)
        seen.append(rig.clock.monotonic())

    rig.provider.on_call = first_takes_seven_seconds
    rig.provider.script("read", ModelBadOutput("garbage", usage=Usage(10, 0, 5)), OK)
    result = await rig.read(deadline_at=rig.clock.monotonic() + 20)
    assert result.attempts == 2
    assert rig.spend.settled[0] == ("r1", 10, 0, 5)  # bad output still billed what it used


async def test_no_budget_left_fails_fast_without_reserving() -> None:
    rig = Rig()
    with pytest.raises(ApiError) as err:
        await rig.read(deadline_at=rig.clock.monotonic() - 1)
    assert err.value.code == "model_timeout"
    assert not rig.spend.reservations and rig.provider.calls == 0


async def test_parse_never_retries_and_rate_limits_settle_at_zero() -> None:
    rig = Rig()
    rig.provider.script("parse", ModelUnavailable("limited", status=429), {"label": "assigned"})
    with pytest.raises(ApiError):
        await rig.parse()
    assert rig.provider.calls == 1
    assert rig.spend.settled == [("r1", 0, 0, 0)]


async def test_connection_failure_before_sending_releases_the_reservation() -> None:
    rig = Rig()
    rig.provider.script("parse", ModelUnavailable("refused", sent=False))
    with pytest.raises(ApiError):
        await rig.parse()
    assert rig.spend.released == ["r1"]


async def test_spend_pause_propagates_without_calling_the_model() -> None:
    rig = Rig()
    rig.spend.paused = True
    with pytest.raises(ApiError) as err:
        await rig.parse()
    assert err.value.code == "ai_paused"
    assert rig.provider.calls == 0


async def test_breaker_opens_after_five_failures_in_sixty_seconds_then_half_opens_once() -> None:
    rig = Rig()
    rig.provider.default["parse"] = ModelUnavailable("down", status=503)
    for _ in range(5):
        with pytest.raises(ApiError):
            await rig.parse()
        rig.clock.advance(5)
    breaker = rig.router.breaker()
    assert breaker.state is BreakerState.OPEN
    assert rig.router.unavailable_reason(TaskKind.PARSE) == "breaker"

    calls_before = rig.provider.calls
    with pytest.raises(ApiError) as err:
        await rig.parse()
    assert (err.value.status, err.value.code, err.value.reason) == (503, "ai_unavailable", "breaker")
    assert err.value.retry_after_s is not None and err.value.retry_after_s > 0
    assert rig.provider.calls == calls_before  # no model call, no reservation while open
    reservations_before = len(rig.spend.reservations)

    rig.clock.advance(60)
    gate = asyncio.Event()

    async def probe(_request: object) -> dict:
        await gate.wait()
        return {"label": "assigned", "evidence_span": "on the way"}

    rig.provider.script("parse", probe)
    probe_task = asyncio.create_task(rig.parse())
    await asyncio.sleep(0)
    assert breaker.state is BreakerState.HALF_OPEN
    with pytest.raises(ApiError) as concurrent:
        await rig.parse()  # only one probe at a time
    assert concurrent.value.reason == "breaker"
    gate.set()
    await probe_task
    assert breaker.state is BreakerState.CLOSED
    assert len(rig.spend.reservations) == reservations_before + 1


async def test_failed_half_open_probe_reopens_the_breaker() -> None:
    rig = Rig()
    rig.provider.default["parse"] = ModelUnavailable("down", status=503)
    for _ in range(5):
        with pytest.raises(ApiError):
            await rig.parse()
    rig.clock.advance(61)
    with pytest.raises(ApiError) as probe:
        await rig.parse()
    assert probe.value.reason is None  # the probe itself was sent and failed
    assert rig.router.breaker().state is BreakerState.OPEN
    with pytest.raises(ApiError) as err:
        await rig.parse()
    assert err.value.reason == "breaker"


async def test_failures_spread_beyond_the_window_do_not_open_the_breaker() -> None:
    rig = Rig()
    rig.provider.default["parse"] = ModelUnavailable("down", status=503)
    for _ in range(6):
        with pytest.raises(ApiError):
            await rig.parse()
        rig.clock.advance(16)
    assert rig.router.breaker().state is BreakerState.CLOSED


async def test_an_abandoned_probe_lets_the_next_caller_probe() -> None:
    rig = Rig()
    rig.provider.default["parse"] = ModelUnavailable("down", status=503)
    for _ in range(5):
        with pytest.raises(ApiError):
            await rig.parse()
    rig.clock.advance(61)
    rig.spend.paused = True
    with pytest.raises(ApiError) as paused:
        await rig.parse()
    assert paused.value.code == "ai_paused"
    rig.spend.paused = False
    rig.provider.script("parse", {"label": "unknown"})
    await rig.parse()
    assert rig.router.breaker().state is BreakerState.CLOSED


async def test_missing_pinned_model_turns_ai_off_with_a_metric_and_no_spend() -> None:
    rig = Rig()
    rig.provider.models = ["meta-models/some-other-model"]
    for _ in range(2):
        with pytest.raises(ApiError) as err:
            await rig.read()
        assert (err.value.status, err.value.code, err.value.reason) == (
            503,
            "ai_unavailable",
            "model_missing",
        )
    assert rig.provider.list_calls == 1  # cached
    assert rig.provider.calls == 0 and not rig.spend.reservations
    assert rig.metrics.total("ai_model_missing") == 1
    assert rig.router.unavailable_reason() == "model_missing"
    assert await rig.router.model_available() is False


async def test_model_present_is_checked_once_and_listing_failure_does_not_block_calls() -> None:
    rig = Rig()
    rig.provider.script("read", OK, OK)
    await rig.read()
    await rig.read()
    assert rig.provider.list_calls == 1

    failing = Rig()
    failing.provider.models = ModelUnavailable("down", sent=False)
    failing.provider.script("read", OK)
    await failing.read()
    assert failing.metrics.total("ai_model_check_failed") == 1
    assert failing.router.unavailable_reason() is None


async def test_model_route_picks_the_structured_mode_and_unknown_provider_falls_back() -> None:
    rig = Rig(
        rc={
            "model_route": {
                "schema": "1.0",
                "read": "meta_api",
                "parse": "modal_spark_open",
                "structured_mode": "tool",
            }
        }
    )
    rig.provider.script("read", OK)
    await rig.read()
    ((_request, mode),) = rig.provider.requests
    assert mode is StructuredMode.TOOL
    assert rig.metrics.total("ai_route_unknown", task="read") == 1


def test_router_requires_the_default_provider() -> None:
    with pytest.raises(ValueError):
        ProviderRouter(
            providers={},
            spend=FakeSpend(),
            remote_config=FakeRemoteConfig(),
            metrics=FakeMetrics(),
            clock=FakeClock(),
        )


async def test_the_model_check_runs_inside_the_first_attempts_deadline() -> None:
    rig = Rig()

    async def listing_that_uses_the_whole_budget() -> list[str]:
        rig.clock.advance(DEFAULT_POLICIES[TaskKind.PARSE].deadline_s)
        return [rig.provider.model_id]

    rig.provider.list_models = listing_that_uses_the_whole_budget  # type: ignore[method-assign]
    with pytest.raises(ApiError) as err:
        await rig.parse()
    assert err.value.code == "model_timeout"  # parse still answers within its 6 s (I9)
    assert rig.provider.calls == 0 and not rig.spend.reservations


async def test_a_hung_model_listing_is_cut_at_half_the_deadline_and_the_call_goes_ahead() -> None:
    policies = dict(DEFAULT_POLICIES)
    policies[TaskKind.PARSE] = TaskPolicy(0.2, retry=False, tap_budget=False, route_key="parse")
    rig = Rig(policies=policies)

    async def hung() -> list[str]:
        await asyncio.sleep(5)
        return []

    rig.provider.list_models = hung  # type: ignore[method-assign]
    rig.provider.script("parse", {"label": "unknown", "evidence_span": None})
    result = await rig.parse()
    assert result.attempts == 1
    assert rig.metrics.total("ai_model_check_failed") == 1


async def test_a_404_rechecks_the_pinned_model_and_turns_ai_off_when_it_is_gone() -> None:
    rig = Rig()
    rig.provider.script("parse", ModelUnavailable("model not found", status=404))
    with pytest.raises(ApiError) as first:
        await rig.parse()
    assert first.value.code == "ai_unavailable" and first.value.reason is None
    assert rig.provider.list_calls == 1

    rig.provider.models = ["meta-models/some-replacement"]
    with pytest.raises(ApiError) as second:
        await rig.parse()
    assert (second.value.code, second.value.reason) == ("ai_unavailable", "model_missing")
    assert rig.provider.list_calls == 2 and rig.provider.calls == 1
    assert rig.metrics.total("ai_model_missing") == 1
    assert rig.router.unavailable_reason(TaskKind.PARSE) == "model_missing"


async def test_structured_mode_is_pinned_by_the_environment(monkeypatch: pytest.MonkeyPatch) -> None:
    settings = make_settings()
    cases = [
        ("tool", StructuredMode.TOOL),
        (" PROMPT ", StructuredMode.PROMPT),
        ("bogus", StructuredMode.JSON_SCHEMA),
    ]
    for value, expected in cases:
        monkeypatch.setenv(STRUCTURED_MODE_ENV, value)
        (gateway,) = build_providers(settings).values()
        assert gateway.pinned_mode is expected  # type: ignore[attr-defined]
        await gateway.aclose()  # type: ignore[attr-defined]
    monkeypatch.delenv(STRUCTURED_MODE_ENV)
    (default,) = build_providers(settings).values()
    assert default.pinned_mode is StructuredMode.JSON_SCHEMA  # type: ignore[attr-defined]
    await default.aclose()  # type: ignore[attr-defined]
    (explicit,) = build_providers(settings, structured_mode="tool").values()
    assert explicit.pinned_mode is StructuredMode.TOOL  # type: ignore[attr-defined]
    await explicit.aclose()  # type: ignore[attr-defined]
    assert build_providers(make_settings(model_token_secret="")) == {}


# ---- the reservation and the deadline / cancellation ----------------------------------------------------------


async def test_a_reservation_that_used_up_the_deadline_is_released_and_nothing_is_sent() -> None:
    rig = Rig()
    rig.provider.script("read", OK)
    reserve = rig.spend.reserve

    def slow_reserve(**kwargs: Any) -> Reservation:
        rig.clock.advance(21)  # the hot store took longer than the whole tap budget
        return reserve(**kwargs)

    rig.spend.reserve = slow_reserve  # type: ignore[method-assign]
    with pytest.raises(ApiError) as err:
        await rig.read(deadline_at=rig.clock.monotonic() + 20)
    assert (err.value.status, err.value.code) == (504, "model_timeout")
    assert rig.provider.calls == 0
    assert rig.spend.released == ["r1"] and not rig.spend.kept
    assert rig.metrics.total("ai_call", outcome="no_budget") == 1
    rig.spend.reserve = reserve  # type: ignore[method-assign]
    assert (await rig.read()).attempts == 1  # the breaker slot was given back


def _meter_rig() -> tuple[ProviderRouter, FakeProvider, Any, Principal]:
    deps = make_test_deps()
    provider = FakeProvider()
    router = ProviderRouter(
        providers={"modal_spark_open": provider},
        spend=deps.spend,
        remote_config=deps.remote_config,
        metrics=deps.metrics,
        clock=deps.clock,
    )
    principal = deps.auth.principal(deps.auth.google("u1"))  # type: ignore[attr-defined]
    return router, provider, deps, principal


async def test_a_request_cancelled_while_its_reservation_is_made_never_leaves_it_outstanding() -> None:
    router, provider, deps, principal = _meter_rig()
    provider.script("read", OK)
    started, release, released = threading.Event(), threading.Event(), threading.Event()
    made: list[Reservation] = []
    reserve, real_release = deps.spend.reserve, deps.spend.release

    def gated_reserve(**kwargs: Any) -> Reservation:
        started.set()
        release.wait(5)
        made.append(reserve(**kwargs))
        return made[-1]

    def spy_release(reservation: Reservation) -> None:
        real_release(reservation)
        released.set()

    deps.spend.reserve, deps.spend.release = gated_reserve, spy_release
    task = asyncio.create_task(
        router.call(
            read_req(), task=TaskKind.READ, purpose=Purpose.READ, principal=principal, request_id="rid"
        )
    )
    assert await asyncio.to_thread(started.wait, 5)
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    release.set()
    assert await asyncio.to_thread(released.wait, 5)  # made after the request was gone, then given back
    assert len(made) == 1 and provider.calls == 0
    assert deps.spend.day_total_usd(ist_day(deps.clock.now_utc())) == 0.0


async def test_a_request_cancelled_mid_call_keeps_its_whole_reservation_in_the_real_meter() -> None:
    router, provider, deps, principal = _meter_rig()
    in_flight = asyncio.Event()

    async def hang(_request: object) -> dict:
        in_flight.set()
        await asyncio.sleep(30)
        return OK

    provider.script("read", hang)
    task = asyncio.create_task(
        router.call(
            read_req(), task=TaskKind.READ, purpose=Purpose.READ, principal=principal, request_id="rid"
        )
    )
    await asyncio.wait_for(in_flight.wait(), 5)
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    for _ in range(200):  # the keep runs in a worker thread, shielded from the cancellation
        if deps.spend.day_total_usd(ist_day(deps.clock.now_utc())) > 0:
            break
        await asyncio.sleep(0.01)
    # The request may have been processed, so it is paid for in full: never released.
    assert deps.spend.day_total_usd(ist_day(deps.clock.now_utc())) == pytest.approx(
        max_cost_usd(read_req()), rel=1e-6
    )
