"""The Doctor against the backend's real all-spend meter (in-memory store): metered under the evals sub-budget,
grouped as one eval run, and refused once the evals sub-budget is exhausted."""

from __future__ import annotations

from pathlib import Path
from typing import Any

import pytest
from conftest import RECORDER_POSTS
from ridemate_api.clock import FakeClock
from ridemate_api.interfaces import ApiError, Purpose
from ridemate_api.settings import SUB_BUDGET_EVAL_RUN_USD, SUB_BUDGET_EVALS_USD, Settings
from ridemate_api.spend import SpendMeter
from ridemate_api.store import MemoryStore
from test_agent import FakeClient, call, say, tools

from doctor import DoctorInputs, run_doctor
from doctorlib.samples import parse_posts


class RC:
    def get_bool(self, key: str, default: bool) -> bool:
        return default

    def get_int(self, key: str, default: int) -> int:
        return default

    def get_str(self, key: str, default: str) -> str:
        return default

    def get_json(self, key: str, default: Any) -> Any:
        return default


def _meter() -> SpendMeter:
    return SpendMeter(
        store=MemoryStore(),
        clock=FakeClock(),
        # main: a dev deployment would also be held to the $1 'dev' sub-budget, which isn't what these tests measure
        settings=Settings(),
        remote_config=RC(),
        shard="t",
    )


def _inputs(templates: dict[str, Any], parsers: dict[str, Any]) -> DoctorInputs:
    return DoctorInputs(
        posts=parse_posts({"posts": RECORDER_POSTS}), screens=[], templates=templates, parsers=parsers
    )


async def test_run_is_charged_to_the_evals_sub_budget(
    tmp_path: Path, templates: dict[str, Any], parsers: dict[str, Any]
) -> None:
    meter = _meter()
    client = FakeClient([tools(call("list_misses")), say("nothing to change")])
    result = await run_doctor(_inputs(templates, parsers), client=client, spend=meter, out_root=tmp_path)
    assert result.model_calls == 2 and result.spend_usd > 0
    summary = meter.summary()
    assert summary["sub_budgets"]["evals"]["spent_usd"] == pytest.approx(result.spend_usd, abs=1e-5)
    assert summary["ai_spent_usd"] == pytest.approx(result.spend_usd, abs=1e-5)
    assert summary["ai_reserved_usd"] == 0  # the preflight was released and every turn settled


async def test_refused_when_the_real_evals_sub_budget_is_exhausted(
    tmp_path: Path, templates: dict[str, Any], parsers: dict[str, Any]
) -> None:
    meter = _meter()
    runs = round(SUB_BUDGET_EVALS_USD / SUB_BUDGET_EVAL_RUN_USD)
    for i in range(runs):  # other eval runs used the whole $5
        meter.keep(
            meter.reserve(
                purpose=Purpose.EVAL,
                max_usd=SUB_BUDGET_EVAL_RUN_USD,
                principal=None,
                request_id=f"eval-run:r{i}:1",
            )
        )
    with pytest.raises(ApiError):
        meter.reserve(purpose=Purpose.DOCTOR, max_usd=0.01, principal=None, request_id="eval-run:x:1")
    client = FakeClient([say("never")])
    result = await run_doctor(_inputs(templates, parsers), client=client, spend=meter, out_root=tmp_path)
    assert result.status == "refused" and result.refused_reason == "evals sub-budget exhausted"
    assert client.seen == []


async def test_cap_holds_in_metered_dollars_when_the_meter_applies_a_drift_factor(
    tmp_path: Path, templates: dict[str, Any], parsers: dict[str, Any]
) -> None:
    meter = _meter()
    assert meter.set_drift_factor(1.5) == pytest.approx(1.5)
    client = FakeClient([tools(call("list_misses"), call("read_sample", sample_id="nope"))], worst_case=True)
    result = await run_doctor(
        _inputs(templates, parsers), client=client, spend=meter, out_root=tmp_path, max_turns=400
    )
    # A turn billed at exactly its (drift-scaled) reservation is not an overrun; the run stops on the cap.
    assert result.model_calls > 1 and result.stop_reason.startswith("budget")
    assert "upper bound" not in result.stop_reason
    summary = meter.summary()
    assert summary["sub_budgets"]["evals"]["spent_usd"] == pytest.approx(result.spend_usd, abs=1e-5)
    assert result.spend_usd <= SUB_BUDGET_EVAL_RUN_USD
    assert summary["ai_reserved_usd"] == 0
