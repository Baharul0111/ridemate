"""Spend control for one Template Doctor run (architecture §4.2 credit budget, §4.18 L1).

Every model call is reserved in the backend's all-spend meter under `Purpose.DOCTOR` (the evals/Template Doctor
sub-budget, ≤ $5 in total) before it is sent, then settled with the real token usage (kept in full on a timeout,
released only when the request provably never left). On top of the meter, a run has a hard cap of $0.50
(`SUB_BUDGET_EVAL_RUN_USD`): a turn whose worst-case cost would cross it is never sent.

Request ids are `eval-run:doctor-<run id>:<step>`, so the meter also applies its own per-eval-run cap to the
whole run. A run refuses to start when the evals sub-budget is exhausted: either the meter's summary says so, or a
preflight reservation of one worst-case turn is refused (`ai_paused`, `meter_unavailable`, …).
"""

from __future__ import annotations

import json
import logging
import math
from collections.abc import Awaitable, Callable, Mapping
from typing import Any

from ridemate_api.interfaces import ApiError, Purpose, SpendMeter
from ridemate_api.settings import PRICE_INPUT_PER_M, PRICE_OUTPUT_PER_M, SUB_BUDGET_EVAL_RUN_USD
from ridemate_api.spend import EVAL_RUN_REQUEST_PREFIX
from ridemate_api.store import StoreUnavailable

from .model import ChatTurn, ModelCallError

log = logging.getLogger(__name__)

PER_RUN_CAP_USD = SUB_BUDGET_EVAL_RUN_USD
MESSAGE_OVERHEAD_TOKENS = 16  # chat-template tokens around each message


class DoctorRefused(Exception):
    """The run must not start (sub-budget exhausted, meter unreadable, AI paused)."""

    def __init__(self, reason: str) -> None:
        super().__init__(reason)
        self.reason = reason


class BudgetExhausted(Exception):
    """The next turn would cross the per-run cap, or the meter refused it mid-run."""

    def __init__(self, reason: str) -> None:
        super().__init__(reason)
        self.reason = reason


def turn_max_cost_usd(messages: list[dict[str, Any]], tools: list[dict[str, Any]], max_tokens: int) -> float:
    """A true upper bound for one turn, so the per-run cap is hard: a byte-level tokenizer never produces more
    tokens than UTF-8 bytes, so every input byte is priced as one uncached token (plus a per-message template
    allowance), and every allowed output token at the output price."""
    payload = json.dumps(messages, ensure_ascii=False) + json.dumps(tools, ensure_ascii=False)
    input_tokens = len(payload.encode("utf-8")) + MESSAGE_OVERHEAD_TOKENS * (len(messages) + 1)
    return (input_tokens * PRICE_INPUT_PER_M + max_tokens * PRICE_OUTPUT_PER_M) / 1_000_000


def _dig(obj: Any, *keys: str) -> Any:
    for key in keys:
        if not isinstance(obj, Mapping):
            return None
        obj = obj.get(key)
    return obj


def evals_remaining_usd(spend: SpendMeter) -> float | None:
    """ADAPTER (the one place that knows the shape of `SpendMeter.summary()`): the USD left in the
    evals/Template Doctor sub-budget, or None when the summary does not report it (the preflight reservation
    then decides). Raises DoctorRefused when the meter cannot be read (fail closed)."""
    try:
        summary = spend.summary()
    except (StoreUnavailable, ApiError) as e:
        raise DoctorRefused("meter_unavailable") from e
    for path in (
        ("sub_budgets", "evals", "remaining_usd"),
        ("sub_budgets", "eval", "remaining_usd"),
        ("sub_budgets", "doctor", "remaining_usd"),
        ("evals", "remaining_usd"),
        ("evals_remaining_usd",),
    ):
        value = _dig(summary, *path)
        if isinstance(value, int | float) and not isinstance(value, bool) and math.isfinite(value):
            return float(value)
    budget = _dig(summary, "sub_budgets", "evals", "budget_usd")
    spent = _dig(summary, "sub_budgets", "evals", "spent_usd")
    if isinstance(budget, int | float) and isinstance(spent, int | float):
        return float(budget) - float(spent)
    return None


class DoctorBudget:
    def __init__(self, spend: SpendMeter, *, run_id: str, cap_usd: float = PER_RUN_CAP_USD) -> None:
        if not 0 < cap_usd:
            raise ValueError("cap_usd must be positive")
        self._spend = spend
        self.run_id = run_id
        self.cap_usd = min(cap_usd, PER_RUN_CAP_USD)
        self.spent_usd = 0.0
        self.calls = 0
        self.overrun = False  # the provider billed more than a turn's upper bound: stop the run

    def request_id(self, step: str) -> str:
        """`eval-run:doctor-<run>:<step>`: the meter groups these into one eval run with its own ≤ $0.50 cap."""
        return f"{EVAL_RUN_REQUEST_PREFIX}doctor-{self.run_id}:{step}"

    def preflight(self, probe_usd: float) -> None:
        remaining = evals_remaining_usd(self._spend)
        if remaining is not None:
            if remaining <= 0:
                raise DoctorRefused("evals sub-budget exhausted")
            self.cap_usd = min(self.cap_usd, remaining)
        if probe_usd > self.cap_usd:
            raise DoctorRefused(
                f"one worst-case turn (${probe_usd:.4f}) exceeds this run's cap (${self.cap_usd:.4f})"
            )
        try:
            reservation = self._spend.reserve(
                purpose=Purpose.DOCTOR,
                max_usd=probe_usd,
                principal=None,
                request_id=self.request_id("preflight"),
            )
        except ApiError as e:
            raise DoctorRefused(e.reason or e.code) from e
        except StoreUnavailable as e:
            raise DoctorRefused("meter_unavailable") from e
        self._spend.release(reservation)
        metered = _reserved_usd(reservation)
        if metered > self.cap_usd:
            raise DoctorRefused(
                f"one worst-case turn as metered (${metered:.4f}) exceeds this run's cap (${self.cap_usd:.4f})"
            )

    def can_afford(self, max_usd: float) -> bool:
        return not self.overrun and self.spent_usd + max_usd <= self.cap_usd + 1e-12

    async def call(self, max_usd: float, send: Callable[[], Awaitable[ChatTurn]]) -> ChatTurn:
        if self.overrun:
            raise BudgetExhausted("a turn was billed above its upper bound; run stopped")
        if not self.can_afford(max_usd):
            raise BudgetExhausted(
                f"next turn could cost ${max_usd:.4f}; ${self.cap_usd - self.spent_usd:.4f} left of the run cap"
            )
        try:
            reservation = self._spend.reserve(
                purpose=Purpose.DOCTOR,
                max_usd=max_usd,
                principal=None,
                request_id=self.request_id(f"turn{self.calls + 1}"),
            )
        except ApiError as e:
            raise BudgetExhausted(e.reason or e.code) from e
        except StoreUnavailable as e:
            raise BudgetExhausted("meter_unavailable") from e
        # The meter scales reservations and charges by its billing drift factor (≥ 1), so the run cap is checked
        # against what the meter actually reserved: that is what this turn can cost, in the meter's dollars.
        bound = max(max_usd, _reserved_usd(reservation))
        if self.spent_usd + bound > self.cap_usd + 1e-12:
            self._spend.release(reservation)
            raise BudgetExhausted(
                f"next turn could cost ${bound:.4f} as metered; "
                f"${self.cap_usd - self.spent_usd:.4f} left of the run cap"
            )
        self.calls += 1
        try:
            turn = await send()
        except ModelCallError as e:
            if not e.sent:
                self._spend.release(reservation)
            elif e.usage is not None:
                self.spent_usd += self._spend.settle(
                    reservation,
                    input_tokens=e.usage.input_tokens,
                    cached_input_tokens=e.usage.cached_input_tokens,
                    output_tokens=e.usage.output_tokens,
                )
            else:
                self.spent_usd += self._spend.keep(reservation)
            raise
        except BaseException:
            self.spent_usd += self._spend.keep(reservation)  # timeout/cancel: it may have been processed
            raise
        if turn.usage is None:
            cost = self._spend.keep(reservation)
        else:
            cost = self._spend.settle(
                reservation,
                input_tokens=turn.usage.input_tokens,
                cached_input_tokens=turn.usage.cached_input_tokens,
                output_tokens=turn.usage.output_tokens,
            )
        self.spent_usd += cost
        if cost > bound + 1e-9:
            self.overrun = True
            log.error("template doctor turn billed $%.4f over its $%.4f upper bound; stopping", cost, bound)
        return turn


def _reserved_usd(reservation: Any) -> float:
    value = getattr(reservation, "reserved_usd", 0.0)
    return float(value) if isinstance(value, int | float) and math.isfinite(value) else 0.0
