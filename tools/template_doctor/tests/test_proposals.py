"""RE2 validation, negative-sample rejection, the regression gate and config validation."""

from __future__ import annotations

import copy
from typing import Any

import pytest
from conftest import KESTREL, by_text, kestrel_screen

from doctor import DoctorInputs, DoctorSession
from doctorlib import paths
from doctorlib.proposals import (
    ProposalError,
    TemplateProposal,
    apply_parser_change,
    apply_template,
    check_regex,
    validate_template_proposal,
)
from doctorlib.regression import (
    config_errors,
    parser_regression,
    run_regression,
    run_validate_script,
    template_regression,
)
from doctorlib.templates import classify

GROUPS = frozenset({"eta", "dist", "plate", "name"})


# ---- RE2 validation ---------------------------------------------------------------------------------------


@pytest.mark.parametrize(
    ("pattern", "reason"),
    [
        ("(?i)rider(?= is)", "RE2 rejects"),  # lookahead
        ("(a)\\1", "RE2 rejects"),  # backreference
        ("(?<eta>\\d+) min", "not portable to RE2/J"),
        ("a\\Cb", "not portable to RE2/J"),
        ("(?P<fare>\\d+)", "not allowed"),
        ("rider <name> is", "mask token"),
        ("(?i)x*", "empty string"),
        ("", "non-empty"),
        ("a" * 401, "longer than"),
        ("[unclosed", "RE2 rejects"),
    ],
)
def test_bad_patterns_are_rejected(pattern: str, reason: str) -> None:
    with pytest.raises(ProposalError, match=reason):
        check_regex(pattern, allowed_groups=GROUPS)


def test_good_pattern_passes() -> None:
    check_regex(
        "(?i)^kestrel go\\nyour rider (?P<name>.+?) is (?P<eta>[0-9०-९]{1,2}) min away", allowed_groups=GROUPS
    )


def test_min_groups_enforced_for_parser_patterns(parsers: dict[str, Any]) -> None:
    with pytest.raises(ProposalError, match="capture group"):
        apply_parser_change(parsers, KESTREL, 0, "eta_pattern", "(?i)\\d+ min")


# ---- template proposals -----------------------------------------------------------------------------------


def _validate(
    templates: dict[str, Any], posts: list[Any], proposal: TemplateProposal, pos: list[str], neg=()
):
    return validate_template_proposal(
        proposal,
        staged=templates,
        baseline=templates,
        posts_by_id={p.id: p for p in posts},
        positive_ids=pos,
        negative_ids=list(neg),
    )


def test_valid_template_fixes_the_miss(templates: dict[str, Any], posts: list[Any]) -> None:
    miss = by_text(posts, "is 3 min away")
    proposal = TemplateProposal(
        KESTREL, 0, "ASSIGNED", "(?i)^kestrel go\\nyour rider (?P<name>.+?) is (?P<eta>\\d{1,2}) min away"
    )
    staged, fixed = _validate(templates, posts, proposal, [miss.id])
    assert fixed == [miss.id]
    got = classify(staged, miss)
    assert got.event == "ASSIGNED" and got.template_id.startswith("kestrel.assigned.doctor_v0_")
    assert classify(templates, miss) is None  # the input payload is never mutated


def test_template_matching_a_negative_sample_is_rejected(templates: dict[str, Any], posts: list[Any]) -> None:
    miss = by_text(posts, "is 3 min away")
    searching = by_text(posts, "Finding a rider")
    broad = TemplateProposal(KESTREL, 0, "ASSIGNED", "(?i)^kestrel go\\n")
    with pytest.raises(ProposalError, match=f"negative sample {searching.id}"):
        _validate(templates, posts, broad, [miss.id])


def test_template_matching_an_other_post_is_rejected(templates: dict[str, Any], posts: list[Any]) -> None:
    miss = by_text(posts, "is 3 min away")
    offer = by_text(posts, "Weekend offer")
    broad = TemplateProposal(KESTREL, 0, "ASSIGNED", "(?i)^kestrel go\\n(?:your rider|weekend)")
    with pytest.raises(ProposalError, match=f"negative sample {offer.id}.*expected OTHER"):
        _validate(templates, posts, broad, [miss.id])


def test_explicit_negative_ids_are_enforced(templates: dict[str, Any], posts: list[Any]) -> None:
    miss = by_text(posts, "is 3 min away")
    # An unlabelled look-alike: no auto-negative, but the model named it as one.
    extra = posts[0].__class__(
        **{**miss.__dict__, "id": "nX", "text": "Your rider is 9 min away", "label": None}
    )
    proposal = TemplateProposal(KESTREL, 0, "ASSIGNED", "(?i)your rider .*min away")
    with pytest.raises(ProposalError, match="nX.*explicit negative"):
        _validate(templates, [*posts, extra], proposal, [miss.id], ["nX"])


def test_template_must_match_its_positives(templates: dict[str, Any], posts: list[Any]) -> None:
    miss = by_text(posts, "is 3 min away")
    with pytest.raises(ProposalError, match="does not match positive"):
        _validate(templates, posts, TemplateProposal(KESTREL, 0, "ASSIGNED", "(?i)captain"), [miss.id])
    labelled = by_text(posts, "Meet at pickup")
    with pytest.raises(ProposalError, match="labelled ARRIVING"):
        _validate(templates, posts, TemplateProposal(KESTREL, 0, "ASSIGNED", "(?i)pickup"), [labelled.id])
    with pytest.raises(ProposalError, match="unknown sample"):
        _validate(templates, posts, TemplateProposal(KESTREL, 0, "ASSIGNED", "(?i)x"), ["nope"])
    with pytest.raises(ProposalError, match="event must be"):
        _validate(templates, posts, TemplateProposal(KESTREL, 0, "BOOKED", "(?i)x"), [miss.id])


def test_new_version_entry_keeps_old_versions(templates: dict[str, Any], posts: list[Any]) -> None:
    miss = by_text(posts, "is 3 min away")  # version_code 10
    proposal = TemplateProposal(
        KESTREL, 5, "ASSIGNED", "(?i)your rider (?P<name>.+?) is (?P<eta>\\d+) min away"
    )
    staged, _ = _validate(templates, posts, proposal, [miss.id])
    codes = [e["min_version_code"] for e in staged["apps"][KESTREL]]
    assert codes == [0, 5]
    old = miss.__class__(**{**miss.__dict__, "version_code": 1})
    assert classify(staged, old) is None and classify(staged, miss).event == "ASSIGNED"
    assert len(staged["apps"][KESTREL][1]["templates"]) == len(staged["apps"][KESTREL][0]["templates"]) + 1
    # The copied entry's templates get their own ids, so the payload still validates (ids are unique).
    old_ids = [t["id"] for t in staged["apps"][KESTREL][0]["templates"]]
    new_ids = [t["id"] for t in staged["apps"][KESTREL][1]["templates"]]
    assert set(old_ids).isdisjoint(new_ids) and f"{old_ids[0]}_vc5" in new_ids
    assert config_errors({paths.TEMPLATES_KEY: staged}) == []


def test_new_version_proposal_passes_the_session_gate(
    templates: dict[str, Any], parsers: dict[str, Any], posts: list[Any]
) -> None:
    miss = by_text(posts, "is 3 min away")  # version_code 10
    inputs = DoctorInputs(posts=posts, screens=[], templates=templates, parsers=parsers)
    session = DoctorSession(inputs, run_script=False)  # in-process schema + semantic validation
    first_id = templates["apps"][KESTREL][0]["templates"][0]["id"]
    reply = session.tool_propose_template(
        {
            "package": KESTREL,
            "min_version_code": 10,
            "event": "ASSIGNED",
            "pattern": "(?i)your rider (?P<name>.+?) is (?P<eta>\\d+) min away",
            "before_template_id": first_id,  # the original id names its copy in the new entry
            "positive_sample_ids": [miss.id],
            "rationale": "new wording in versionCode 10",
        }
    )
    assert reply["ok"] and reply["accepted"], reply
    entry = session.staged[paths.TEMPLATES_KEY]["apps"][KESTREL][1]
    assert entry["min_version_code"] == 10 and entry["templates"][0]["pattern"].startswith("(?i)your rider")
    gate = session.tool_run_regression({})
    assert gate["ok"] is True, gate


def test_insert_before_unknown_template_rejected(templates: dict[str, Any]) -> None:
    with pytest.raises(ProposalError, match="before_template_id"):
        apply_template(
            templates, TemplateProposal(KESTREL, 0, "ENDED", "(?i)done", before_template_id="nope.v1")
        )


# ---- regression gate --------------------------------------------------------------------------------------


def test_template_regression_flags_lost_classification(templates: dict[str, Any], posts: list[Any]) -> None:
    broken = copy.deepcopy(templates)
    broken["apps"][KESTREL][0]["templates"] = [
        t for t in broken["apps"][KESTREL][0]["templates"] if t["event"] != "SEARCHING"
    ]
    failures, checked = template_regression(templates, broken, posts)
    searching = by_text(posts, "Finding a rider")
    assert checked >= 4
    assert failures == [f"{searching.id}: was correct, now unmatched"]


def test_parser_change_that_breaks_another_screen_is_rejected(parsers: dict[str, Any]) -> None:
    good, scooty = kestrel_screen("sA"), kestrel_screen("sB", bike_label="Scooty")
    session = DoctorSession(
        DoctorInputs(posts=[], screens=[good, scooty], templates=_templates(), parsers=parsers),
        run_script=False,
    )
    bad = session.tool_propose_parser_change(
        {
            "package": KESTREL,
            "min_version_code": 0,
            "field": "vehicle_label",
            "value": {"pattern": "(?i)scooty|\\bauto\\b", "vehicle_class": "bike", "position": "first"},
            "positive_sample_ids": ["sB"],
        }
    )
    assert bad["accepted"] is False
    assert any("still disagrees" in e or "regression" in e for e in bad["errors"])
    ok = session.tool_propose_parser_change(
        {
            "package": KESTREL,
            "min_version_code": 0,
            "field": "vehicle_label",
            "value": {"pattern": "(?i)\\bscooty\\b", "vehicle_class": "bike", "position": "first"},
            "positive_sample_ids": ["sB"],
        }
    )
    assert ok["accepted"] is True and ok["remaining_misses"] == 0
    failures, checked = parser_regression(
        session.baseline[paths.PARSERS_KEY], session.staged[paths.PARSERS_KEY], [good]
    )
    assert failures == [] and checked == 1


def test_parser_regression_reports_broken_screens(parsers: dict[str, Any]) -> None:
    broken, _ = apply_parser_change(parsers, KESTREL, 0, "price_token_pattern", "(?i)inr\\s*[0-9]+")
    failures, _ = parser_regression(parsers, broken, [kestrel_screen()])
    assert failures and failures[0].startswith("sA: agreed before")


def test_config_validation_runs_on_staged_payloads(
    templates: dict[str, Any], parsers: dict[str, Any]
) -> None:
    assert config_errors({paths.TEMPLATES_KEY: templates, paths.PARSERS_KEY: parsers}) == []
    bad = copy.deepcopy(templates)
    bad["apps"][KESTREL][0]["templates"][0]["pattern"] = "(?<x>a)"
    assert config_errors({paths.TEMPLATES_KEY: bad})
    result = run_regression(
        baseline_templates=templates,
        staged_templates=bad,
        baseline_parsers=parsers,
        staged_parsers=parsers,
        posts=[],
        screens=[],
    )
    assert not result.ok and result.config_errors


def test_validate_script_passes_on_a_scratch_copy(templates: dict[str, Any], parsers: dict[str, Any]) -> None:
    if not (paths.CONFIG_DIR / "validate.py").exists():
        pytest.skip("config/validate.py not in this checkout")
    status, lines = run_validate_script({paths.TEMPLATES_KEY: templates, paths.PARSERS_KEY: parsers})
    assert status == "passed", lines
    bad = copy.deepcopy(templates)
    del bad["apps"]["demo.pepper"]
    status, lines = run_validate_script({paths.TEMPLATES_KEY: bad})
    assert status == "failed" and lines


def _templates() -> dict[str, Any]:
    import json

    return json.loads((paths.DEFAULTS_DIR / "notification_templates.json").read_text(encoding="utf-8"))
