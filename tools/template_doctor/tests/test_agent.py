"""The tool-calling loop with a fake model, spend metering and refusal, the bundle, run_report and the PR plan."""

from __future__ import annotations

import json
from collections.abc import Callable
from pathlib import Path
from typing import Any

import pytest
from conftest import KESTREL, RECORDER_POSTS, FakeSpend, by_text, kestrel_screen
from ridemate_api.interfaces import ApiError, Purpose
from ridemate_api.model_gateway import Usage

import doctor
from doctor import DoctorInputs, parse_args, run_doctor, run_report
from doctorlib import paths
from doctorlib.budget import (
    MESSAGE_OVERHEAD_TOKENS,
    PER_RUN_CAP_USD,
    DoctorBudget,
    DoctorRefused,
    evals_remaining_usd,
)
from doctorlib.model import ChatTurn, ModelCallError, ToolCall
from doctorlib.pr import PrError, execute, plan_pr, remote_points_at
from doctorlib.samples import parse_posts

GOOD_PATTERN = "(?i)^kestrel go\\nyour rider (?P<name>.+?) is (?P<eta>\\d{1,2}) min away"


def _inputs(
    templates: dict[str, Any], parsers: dict[str, Any], screens: list[Any] | None = None
) -> DoctorInputs:
    return DoctorInputs(
        posts=parse_posts({"posts": RECORDER_POSTS}, "t.json"),
        screens=screens or [],
        templates=templates,
        parsers=parsers,
    )


def call(name: str, **args: Any) -> ToolCall:
    return ToolCall(id=f"c-{name}-{len(json.dumps(args))}", name=name, arguments=json.dumps(args))


class FakeClient:
    """Plays a script; each step sees the conversation so far and returns the next turn."""

    model_id = "fake"

    def __init__(
        self,
        steps: list[Callable[[list[dict[str, Any]]], ChatTurn]],
        usage: Usage | None = None,
        worst_case: bool = False,
    ) -> None:
        self.steps = steps
        self.usage = usage or Usage(input_tokens=4000, cached_input_tokens=0, output_tokens=300)
        self.worst_case = worst_case  # bill exactly the turn's upper bound
        self.seen: list[list[dict[str, Any]]] = []

    async def chat(
        self, messages: list[dict[str, Any]], tools: list[dict[str, Any]], max_tokens: int
    ) -> ChatTurn:
        self.seen.append([dict(m) for m in messages])
        step = self.steps[min(len(self.seen) - 1, len(self.steps) - 1)]
        turn = step(messages)
        usage = self.usage
        if self.worst_case:
            payload = json.dumps(messages, ensure_ascii=False) + json.dumps(tools, ensure_ascii=False)
            tokens = len(payload.encode()) + MESSAGE_OVERHEAD_TOKENS * (len(messages) + 1)
            usage = Usage(input_tokens=tokens, cached_input_tokens=0, output_tokens=max_tokens)
        return ChatTurn(turn.content, turn.tool_calls, usage, "fake")


def tools(*calls: ToolCall) -> Callable[[list[dict[str, Any]]], ChatTurn]:
    return lambda _messages: ChatTurn(None, calls, None, "fake")


def say(text: str) -> Callable[[list[dict[str, Any]]], ChatTurn]:
    return lambda _messages: ChatTurn(text, (), None, "fake")


def last_tool_result(messages: list[dict[str, Any]]) -> dict[str, Any]:
    return json.loads(next(m for m in reversed(messages) if m["role"] == "tool")["content"])


async def test_fake_model_loop_produces_a_validated_bundle(
    tmp_path: Path, templates: dict[str, Any], parsers: dict[str, Any]
) -> None:
    inputs = _inputs(templates, parsers)
    miss = by_text(inputs.posts, "is 3 min away")
    searching = by_text(inputs.posts, "Finding a rider")

    def check_list(messages: list[dict[str, Any]]) -> ChatTurn:
        listed = last_tool_result(messages)
        assert listed["count"] == 2 and miss.id in {m["sample_id"] for m in listed["misses"]}
        return ChatTurn(None, (call("read_sample", sample_id=miss.id),), None, "fake")

    def bad_then_good(messages: list[dict[str, Any]]) -> ChatTurn:
        sample = last_tool_result(messages)
        assert sample["type"] == "notification" and "<name>" in sample["text"]
        return ChatTurn(
            None,
            (
                call("propose_template", package=KESTREL, min_version_code=0, event="ASSIGNED",
                     pattern="(?i)^kestrel go\\n", positive_sample_ids=[miss.id], rationale="too broad"),
            ),
            None,
            "fake",
        )  # fmt: skip

    def good(messages: list[dict[str, Any]]) -> ChatTurn:
        rejected = last_tool_result(messages)
        assert rejected["accepted"] is False and searching.id in rejected["errors"][0]
        return ChatTurn(
            None,
            (
                call("propose_template", package=KESTREL, min_version_code=0, event="ASSIGNED", pattern=GOOD_PATTERN,
                     positive_sample_ids=[miss.id], negative_sample_ids=[searching.id], rationale="new wording"),
            ),
            None,
            "fake",
        )  # fmt: skip

    def regress(messages: list[dict[str, Any]]) -> ChatTurn:
        assert last_tool_result(messages)["accepted"] is True
        return ChatTurn(None, (call("run_regression"),), None, "fake")

    def finish(messages: list[dict[str, Any]]) -> ChatTurn:
        assert last_tool_result(messages)["ok"] is True
        return ChatTurn("Added one ASSIGNED template for the new Kestrel wording.", (), None, "fake")

    client = FakeClient([tools(call("list_misses")), check_list, bad_then_good, good, regress, finish])
    spend = FakeSpend()
    result = await run_doctor(inputs, client=client, spend=spend, out_root=tmp_path, run_script=False)

    assert result.status == "proposed" and result.stop_reason == "done"
    assert [p.accepted for p in result.proposals] == [False, True]
    assert result.model_calls == 6 and len(client.seen) == 6
    assert result.regression is not None and result.regression.ok
    assert {m.kind for m in result.misses_after} == {"redacted_unmatched"}
    # Metering: preflight released, then one DOCTOR reservation per turn, settled at real prices, under the cap.
    assert all(r.purpose is Purpose.DOCTOR for r in spend.reserved)
    assert spend.released == [f"eval-run:doctor-{result.run_id}:preflight"]
    assert len(spend.reserved) == 7
    assert result.spend_usd == pytest.approx(spend.charged)
    assert 0 < result.spend_usd <= PER_RUN_CAP_USD

    bundle = result.bundle_dir
    assert bundle is not None and bundle.parent == tmp_path
    assert result.patched_keys == [paths.TEMPLATES_KEY]
    patched = json.loads((bundle / "notification_templates.json").read_text())
    new = [t for t in patched["apps"][KESTREL][0]["templates"] if t["pattern"] == GOOD_PATTERN]
    assert len(new) == 1 and new[0]["event"] == "ASSIGNED"
    diff = (bundle / "patch.diff").read_text()
    assert diff.startswith("--- a/config/remote_config/notification_templates.json") and "doctor_v0_1" in diff
    report = (bundle / "report.md").read_text()
    for raw in RECORDER_POSTS:  # no notification text leaks into the report
        for field in ("title", "text"):
            if raw[field] and raw[field] not in ("Kestrel Go",):
                assert raw[field] not in report
    assert "status **proposed**" in report and miss.id in report
    summary = json.loads((bundle / "proposal.json").read_text())
    assert summary["status"] == "proposed" and summary["patched_keys"] == [paths.TEMPLATES_KEY]


async def test_untrusted_tool_arguments_do_not_crash_the_loop(
    tmp_path: Path, templates: dict[str, Any], parsers: dict[str, Any]
) -> None:
    client = FakeClient(
        [
            tools(
                ToolCall("x1", "delete_everything", "{}"),
                ToolCall("x2", "read_sample", "not json"),
                ToolCall("x3", "propose_template", json.dumps({"package": KESTREL, "pattern": 7})),
                ToolCall(
                    "x4", "propose_parser_change", json.dumps({"package": KESTREL, "field": "eta_pattern"})
                ),
            ),
            say("done"),
        ]
    )
    result = await run_doctor(
        _inputs(templates, parsers), client=client, spend=FakeSpend(), out_root=tmp_path
    )
    tool_msgs = [m for m in client.seen[-1] if m["role"] == "tool"]
    assert len(tool_msgs) == 4 and all(json.loads(m["content"])["ok"] is False for m in tool_msgs)
    assert result.status == "no_proposals" and not result.patched_keys
    assert not (result.bundle_dir / "notification_templates.json").exists()


@pytest.mark.parametrize("cap", [PER_RUN_CAP_USD, 0.1])
async def test_hard_per_run_cap_holds_when_every_turn_bills_its_worst_case(
    tmp_path: Path, templates: dict[str, Any], parsers: dict[str, Any], cap: float
) -> None:
    client = FakeClient([tools(call("list_misses"), call("read_sample", sample_id="nope"))], worst_case=True)
    spend = FakeSpend()
    result = await run_doctor(
        _inputs(templates, parsers),
        client=client,
        spend=spend,
        out_root=tmp_path,
        budget_usd=cap,
        max_turns=400,
    )
    assert result.stop_reason.startswith("budget")
    assert result.cap_usd == pytest.approx(cap)
    assert spend.charged == pytest.approx(result.spend_usd)
    assert result.spend_usd <= cap
    assert result.spend_usd > cap * 0.8  # it used the budget, it did not stop early for no reason


async def test_a_turn_billed_above_its_upper_bound_stops_the_run(
    tmp_path: Path, templates: dict[str, Any], parsers: dict[str, Any]
) -> None:
    client = FakeClient([tools(call("list_misses"))], usage=Usage(120_000, 0, 1500))
    result = await run_doctor(
        _inputs(templates, parsers), client=client, spend=FakeSpend(), out_root=tmp_path
    )
    assert result.model_calls == 1 and "upper bound" in result.stop_reason


@pytest.mark.parametrize(
    ("spend", "reason"),
    [
        (FakeSpend(summary={"sub_budgets": {"evals": {"remaining_usd": 0.0}}}), "evals sub-budget exhausted"),
        (FakeSpend(summary={"sub_budgets": {"evals": {"budget_usd": 5.0, "spent_usd": 5.2}}}), "exhausted"),
        (FakeSpend(refuse=ApiError(429, "ai_paused", reason="budget")), "budget"),
        (FakeSpend(refuse=ApiError(503, "meter_unavailable")), "meter_unavailable"),
    ],
)
async def test_refuses_to_start_when_the_sub_budget_is_exhausted(
    tmp_path: Path, templates: dict[str, Any], parsers: dict[str, Any], spend: FakeSpend, reason: str
) -> None:
    client = FakeClient([say("should never be called")])
    result = await run_doctor(_inputs(templates, parsers), client=client, spend=spend, out_root=tmp_path)
    assert result.status == "refused" and reason in result.refused_reason
    assert client.seen == [] and result.model_calls == 0 and spend.charged == 0
    assert "Refused to start" in (result.bundle_dir / "report.md").read_text()


async def test_refuses_without_a_meter_or_when_the_meter_is_unreadable(
    tmp_path: Path, templates: dict[str, Any], parsers: dict[str, Any]
) -> None:
    client = FakeClient([say("x")])
    result = await run_doctor(_inputs(templates, parsers), client=client, spend=None, out_root=tmp_path)
    assert result.status == "refused" and "metered" in result.refused_reason
    broken = FakeSpend()
    broken.summary_fails = True
    result = await run_doctor(_inputs(templates, parsers), client=client, spend=broken, out_root=tmp_path)
    assert result.status == "refused" and result.refused_reason == "meter_unavailable"
    assert client.seen == []


def test_remaining_sub_budget_caps_the_run() -> None:
    spend = FakeSpend(summary={"sub_budgets": {"evals": {"remaining_usd": 0.2}}})
    assert evals_remaining_usd(spend) == 0.2
    budget = DoctorBudget(spend, run_id="r")
    budget.preflight(0.01)
    assert budget.cap_usd == pytest.approx(0.2)
    with pytest.raises(DoctorRefused):
        DoctorBudget(spend, run_id="r").preflight(0.3)


async def test_model_errors_are_metered_correctly() -> None:
    spend = FakeSpend()
    budget = DoctorBudget(spend, run_id="r")

    async def never_sent() -> ChatTurn:
        raise ModelCallError("connect", sent=False)

    async def timed_out() -> ChatTurn:
        raise ModelCallError("timeout", timeout=True)

    with pytest.raises(ModelCallError):
        await budget.call(0.01, never_sent)
    assert spend.released == ["eval-run:doctor-r:turn1"] and spend.charged == 0
    with pytest.raises(ModelCallError):
        await budget.call(0.01, timed_out)
    assert spend.charged == pytest.approx(0.01) and budget.spent_usd == pytest.approx(0.01)


async def test_model_error_ends_the_run_gracefully(
    tmp_path: Path, templates: dict[str, Any], parsers: dict[str, Any]
) -> None:
    class Failing(FakeClient):
        async def chat(self, *a: Any, **k: Any) -> ChatTurn:
            raise ModelCallError("status 503")

    result = await run_doctor(
        _inputs(templates, parsers), client=Failing([]), spend=FakeSpend(), out_root=tmp_path
    )
    assert result.status == "no_proposals" and result.stop_reason.startswith("model error")


async def test_dry_run_and_no_misses_never_call_the_model(
    tmp_path: Path, templates: dict[str, Any], parsers: dict[str, Any]
) -> None:
    result = await run_doctor(
        _inputs(templates, parsers), client=None, spend=None, out_root=tmp_path, dry_run=True
    )
    assert result.status == "dry_run" and len(result.misses_before) == 2
    clean = DoctorInputs(posts=[], screens=[kestrel_screen()], templates=templates, parsers=parsers)
    result = await run_doctor(clean, client=None, spend=None, out_root=tmp_path)
    assert result.status == "no_misses"


async def test_parser_fix_through_the_loop(
    tmp_path: Path, templates: dict[str, Any], parsers: dict[str, Any]
) -> None:
    screens = [kestrel_screen("sA"), kestrel_screen("sB", bike_label="Scooty")]
    inputs = DoctorInputs(posts=[], screens=screens, templates=templates, parsers=parsers)
    fix = call(
        "propose_parser_change", package=KESTREL, min_version_code=0, field="vehicle_label",
        value={"pattern": "(?i)\\bscooty\\b", "vehicle_class": "bike"}, positive_sample_ids=["sB"], rationale="new label",
    )  # fmt: skip
    client = FakeClient([tools(call("read_sample", sample_id="sB")), tools(fix), say("fixed")])
    result = await run_doctor(inputs, client=client, spend=FakeSpend(), out_root=tmp_path, run_script=False)
    assert result.status == "proposed" and result.patched_keys == [paths.PARSERS_KEY]
    seen_sample = json.loads(next(m for m in client.seen[1] if m["role"] == "tool")["content"])
    assert seen_sample["parser_output"]["issues"] == ["row_missing:bike"]
    report = (result.bundle_dir / "report.md").read_text()
    assert "112" not in report and "289" not in report  # no fare values in the report


# ---- run_report (weekly drift cron) -----------------------------------------------------------------------


class FakeMetrics:
    def __init__(self) -> None:
        self.events: list[tuple[str, dict[str, str]]] = []

    def incr(self, name: str, n: int = 1, **labels: str) -> None:
        self.events.append((name, labels))

    def observe_ms(self, name: str, value_ms: float, **labels: str) -> None:
        return None


class FakeRC:
    def __init__(self, values: dict[str, Any]) -> None:
        self.values = values

    def get_json(self, key: str, default: Any) -> Any:
        return self.values.get(key, default)


class FakeDeps:
    def __init__(self, spend: FakeSpend, rc: FakeRC) -> None:
        from ridemate_api.settings import Settings

        self.settings = Settings(environment="dev")
        self.spend = spend
        self.remote_config = rc
        self.metrics = FakeMetrics()


async def test_run_report_is_report_mode_and_metered(
    tmp_path: Path, templates: dict[str, Any], parsers: dict[str, Any], monkeypatch: pytest.MonkeyPatch
) -> None:
    golden = tmp_path / "golden"
    (golden / "recorder").mkdir(parents=True)
    (golden / "recorder" / "export.json").write_text(json.dumps({"posts": RECORDER_POSTS}))
    monkeypatch.setenv("TEMPLATE_DOCTOR_GOLDEN_DIR", str(golden))
    deps = FakeDeps(FakeSpend(), FakeRC({paths.TEMPLATES_KEY: templates, paths.PARSERS_KEY: parsers}))
    client = FakeClient([tools(call("list_misses")), say("nothing safe to propose")])
    summary = await run_report(deps, 5.0, client=client)
    assert summary["mode"] == "report" and summary["status"] == "no_proposals"
    assert summary["cap_usd"] == PER_RUN_CAP_USD and summary["model_calls"] == 2
    assert Path(summary["bundle_dir"]).parent == golden / "doctor_reports"
    assert deps.metrics.events == [("template_doctor_run", {"status": "no_proposals"})]


async def test_run_report_without_model_config_refuses_cleanly(
    tmp_path: Path, templates: dict[str, Any], parsers: dict[str, Any]
) -> None:
    deps = FakeDeps(FakeSpend(), FakeRC({}))
    summary = await run_report(deps, 0.5, inputs=_inputs(templates, parsers), out_root=tmp_path)
    assert summary["status"] == "refused" and summary["refused_reason"] == "no model client"


async def test_run_report_skips_when_no_inputs(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("TEMPLATE_DOCTOR_GOLDEN_DIR", str(tmp_path / "empty"))
    summary = await run_report(FakeDeps(FakeSpend(), FakeRC({})), 0.5)
    assert summary["status"] == "skipped"


# ---- PR plan and CLI --------------------------------------------------------------------------------------


def test_pr_plan_prints_by_default_and_never_pushes_without_flag(tmp_path: Path) -> None:
    bundle = tmp_path / "20261028T100000Z"
    plan = plan_pr(bundle, [paths.TEMPLATES_KEY], repo="Baharul0111/ridemate")
    text = plan.render()
    assert "git switch -c template-doctor/20261028t100000z" in text
    assert "gh pr create --repo Baharul0111/ridemate" in text
    assert all(cmd[:2] != ("git", "push") for cmd in plan.local)

    ran: list[tuple[str, ...]] = []
    execute(plan, push=False, runner=lambda cmd, cwd: ran.append(tuple(cmd)) or 0, cwd=tmp_path)
    assert ("git", "diff", "--quiet", "--", "config/remote_config", *plan.preconditions[0][5:]) == ran[0]
    assert not any(c[:2] in (("git", "push"), ("gh", "pr")) for c in ran)
    assert any(c[:2] == ("git", "commit") for c in ran)

    ran.clear()
    execute(
        plan,
        push=True,
        runner=lambda cmd, cwd: ran.append(tuple(cmd)) or 0,
        cwd=tmp_path,
        remote_url=lambda remote, cwd: "git@github.com:Baharul0111/ridemate.git",
    )
    assert ran[-2][:2] == ("git", "push") and ran[-1][:3] == ("gh", "pr", "create")


@pytest.mark.parametrize(
    "url",
    [None, "https://github.com/someone-else/ridemate.git", "git@github.com:Baharul0111/ridemate-golden.git"],
)
def test_pr_push_refused_unless_the_remote_is_the_chosen_repo(tmp_path: Path, url: str | None) -> None:
    plan = plan_pr(tmp_path / "b", [paths.TEMPLATES_KEY], repo="Baharul0111/ridemate", remote="upstream")
    assert "`upstream` must point at Baharul0111/ridemate" in plan.render()
    ran: list[tuple[str, ...]] = []
    with pytest.raises(PrError, match="--remote"):
        execute(
            plan,
            push=True,
            runner=lambda cmd, cwd: ran.append(tuple(cmd)) or 0,
            cwd=tmp_path,
            remote_url=lambda remote, cwd: url,
        )
    assert ran == []  # nothing ran: no branch, no commit, no push
    assert remote_points_at("https://github.com/Baharul0111/ridemate", "baharul0111/RideMate")


def test_pr_plan_stops_on_dirty_tree_and_failures(tmp_path: Path) -> None:
    plan = plan_pr(tmp_path / "b", [paths.PARSERS_KEY], repo="o/r")
    with pytest.raises(PrError, match="uncommitted"):
        execute(plan, push=False, runner=lambda cmd, cwd: 1, cwd=tmp_path)
    calls: list[tuple[str, ...]] = []

    def fail_commit(cmd: Any, cwd: Path) -> int:
        calls.append(tuple(cmd))
        return 1 if cmd[:2] == ("git", "commit") else 0

    with pytest.raises(PrError, match="git commit"):
        execute(
            plan,
            push=True,
            runner=fail_commit,
            cwd=tmp_path,
            remote_url=lambda r, c: "https://github.com/o/r",
        )
    assert not any(c[:2] == ("git", "push") for c in calls)
    with pytest.raises(PrError):
        plan_pr(tmp_path, [paths.TEMPLATES_KEY], repo="not a repo")
    with pytest.raises(PrError):
        plan_pr(tmp_path, [], repo="o/r")


@pytest.mark.parametrize(
    "argv",
    [
        ["--budget", "0.6"],
        ["--budget", "0"],
        ["--open-pr"],
        ["--execute"],
        ["--open-pr", "--repo", "o/r", "--push"],
    ],
)
def test_cli_rejects_unsafe_flag_combinations(argv: list[str]) -> None:
    with pytest.raises(SystemExit):
        parse_args(argv)


def test_cli_dry_run_end_to_end(tmp_path: Path, capsys: pytest.CaptureFixture[str]) -> None:
    export = tmp_path / "export.json"
    export.write_text(json.dumps({"posts": RECORDER_POSTS}))
    code = doctor.main(["--recorder", str(export), "--dry-run", "--out", str(tmp_path / "out")])
    out = json.loads(capsys.readouterr().out)
    assert code == 0 and out["status"] == "dry_run" and out["misses_before"] == 2
    assert doctor.main(["--recorder", str(tmp_path / "missing*.json"), "--dry-run"]) == 2


async def test_run_report_cron_keyword_style_and_package_import(
    tmp_path: Path, templates: dict[str, Any], parsers: dict[str, Any]
) -> None:
    import importlib
    import sys

    sys.path.insert(0, str(paths.REPO_ROOT))
    try:
        package = importlib.import_module("tools.template_doctor")
    finally:
        sys.path.remove(str(paths.REPO_ROOT))
    spend = FakeSpend()
    client = FakeClient([tools(call("list_misses")), say("done")])
    summary = await package.run_report(
        router=object(),
        spend=spend,
        max_usd=0.2,
        purpose=Purpose.DOCTOR,
        client=client,
        inputs=_inputs(templates, parsers),
        out_root=tmp_path,
    )
    assert summary["mode"] == "report" and summary["cap_usd"] == pytest.approx(0.2)
    assert summary["model_calls"] == 2 and all(r.purpose is Purpose.DOCTOR for r in spend.reserved)
    with pytest.raises(ValueError, match="Purpose.DOCTOR"):
        await package.run_report(spend=spend, purpose=Purpose.READ, inputs=_inputs(templates, parsers))


def test_model_rationale_is_scrubbed_of_quotes_and_numbers() -> None:
    raw = 'Matches "Your rider Asha is 3 min away" (fare ₹112, Rs 1,234); the rider\'s new wording'
    clean = doctor.safe_rationale(raw)
    assert "Asha" not in clean and "112" not in clean and "1,234" not in clean and "3 min" not in clean
    assert "new wording" in clean and len(doctor.safe_rationale("x" * 2000)) == doctor.MAX_RATIONALE_CHARS
    assert doctor.safe_rationale(None) == "" and doctor.safe_rationale(42) == ""


async def test_report_never_carries_text_or_fares_from_the_rationale(
    tmp_path: Path, templates: dict[str, Any], parsers: dict[str, Any]
) -> None:
    inputs = _inputs(templates, parsers)
    miss = by_text(inputs.posts, "is 3 min away")
    leaky = 'copies "Your rider <name> is 3 min away" and the ₹112 fare'
    client = FakeClient(
        [
            tools(
                call("propose_template", package=KESTREL, min_version_code=0, event="ASSIGNED",
                     pattern=GOOD_PATTERN, positive_sample_ids=[miss.id], rationale=leaky)
            ),
            say("done"),
        ]
    )  # fmt: skip
    result = await run_doctor(inputs, client=client, spend=FakeSpend(), out_root=tmp_path, run_script=False)
    assert result.status == "proposed"
    for name in ("report.md", "proposal.json"):
        text = (result.bundle_dir / name).read_text()
        assert "Your rider" not in text and "112" not in text and "3 min" not in text
