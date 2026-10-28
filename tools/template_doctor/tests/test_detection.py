"""Loading, scrubbing, RE2 matching parity with the reference matcher, miss detection and the parser port."""

from __future__ import annotations

import copy
import json
from pathlib import Path
from typing import Any

import pytest
from conftest import KESTREL, by_text, kestrel_screen
from ridemate_config.matching import match as reference_match

from doctorlib import paths
from doctorlib.fare_rules import parse_price_text, parse_screen, parser_entry
from doctorlib.misses import notification_misses, parser_misses, screen_issues
from doctorlib.samples import (
    InputError,
    load_golden,
    load_recorder_exports,
    normalize_label,
    parse_posts,
    scrub,
)
from doctorlib.templates import classify

# ---- samples ----------------------------------------------------------------------------------------------


def test_recorder_export_aliases_dedupe_and_allowlist() -> None:
    doc = [
        {
            "packageName": KESTREL,
            "versionCode": 3,
            "channelId": "c",
            "isOngoing": True,
            "title": "A",
            "text": "B",
        },
        {
            "packageName": KESTREL,
            "versionCode": 3,
            "channelId": "c",
            "isOngoing": True,
            "title": "A",
            "text": "B",
        },
        {"package": "com.whatsapp", "title": "hi", "text": "there"},
    ]
    samples = parse_posts(doc, "x.json")
    assert len(samples) == 1
    s = samples[0]
    assert (s.package, s.version_code, s.channel_id, s.is_ongoing, s.occurrences) == (
        KESTREL,
        3,
        "c",
        True,
        2,
    )


def test_exports_merge_across_files(tmp_path: Path) -> None:
    for name in ("a.json", "b.json"):
        (tmp_path / name).write_text(json.dumps({"posts": [{"package": KESTREL, "title": "T", "text": "x"}]}))
    merged = load_recorder_exports(sorted(tmp_path.glob("*.json")))
    assert len(merged) == 1 and merged[0].occurrences == 2


@pytest.mark.parametrize(
    "doc",
    [{"posts": "nope"}, 42, [{"package": KESTREL, "title": 5}], [{"package": KESTREL, "is_ongoing": "yes"}]],
)
def test_bad_exports_raise(doc: Any) -> None:
    with pytest.raises(InputError):
        parse_posts(doc, "bad.json")


def test_labels_normalise() -> None:
    assert normalize_label("assigned") == "ASSIGNED"
    assert normalize_label("other") == "OTHER"
    assert normalize_label("unknown") is None
    with pytest.raises(InputError):
        normalize_label("BOOKED")


def test_scrub_remasks_pii() -> None:
    out = scrub("Call +91 98765 43210, OTP: 4821, plate AS 01 AB 1234")
    assert "98765" not in out and "4821" not in out and "1234" not in out
    assert "<phone>" in out and "OTP: <otp>" in out and "<plate>" in out


def test_golden_loader_finds_ocr_in_priority_order(tmp_path: Path) -> None:
    sha = "a" * 64
    (tmp_path / "labels.json").write_text(
        json.dumps({"schema": "1.0", "screens": [{"sha256": sha, "app": "demo_kestrel", "rows": []}]})
    )
    (tmp_path / "rec").mkdir()
    (tmp_path / "rec" / f"{sha}.json").write_text(
        json.dumps({"width": 10, "height": 20, "tokens": [{"text": "Auto", "box": [0, 0, 5, 5], "line": 1}]})
    )
    screens, notes = load_golden(tmp_path / "labels.json", [tmp_path / "missing", tmp_path / "rec"])
    assert screens[0].frame is not None and screens[0].frame.tokens[0].text == "Auto" and not notes
    screens, notes = load_golden(tmp_path / "labels.json", [tmp_path / "missing"])
    assert screens[0].frame is None and notes


def test_golden_loader_rejects_bad_sha(tmp_path: Path) -> None:
    (tmp_path / "labels.json").write_text(json.dumps({"screens": [{"sha256": "XYZ", "app": "demo_kestrel"}]}))
    with pytest.raises(InputError):
        load_golden(tmp_path / "labels.json", [])


# ---- matching parity --------------------------------------------------------------------------------------


def test_classify_matches_reference_matcher(templates: dict[str, Any], posts: list[Any]) -> None:
    for sample in posts:
        if sample.is_empty:
            continue
        ours = classify(templates, sample)
        ref = reference_match(
            templates,
            sample.package,
            *sample.haystack.split("\n", 1),
            channel_id=sample.channel_id,
            ongoing=sample.is_ongoing,
            version_code=sample.version_code,
        )
        assert (ours.template_id if ours else None) == (ref.template_id if ref else None)


def test_version_entry_selection(templates: dict[str, Any], posts: list[Any]) -> None:
    newer = copy.deepcopy(templates["apps"][KESTREL][0])
    newer["min_version_code"] = 500
    newer["templates"] = [
        {
            "id": "demo_kestrel.other.v500",
            "event": "ENDED",
            "pattern": "(?i)rider",
            "channel_id": None,
            "ongoing": None,
        }
    ]
    templates["apps"][KESTREL].append(newer)
    sample = by_text(posts, "Finding a rider")
    assert classify(templates, sample).event == "SEARCHING"  # version 10 → old entry
    newer_sample = sample.__class__(**{**sample.__dict__, "version_code": 900})
    assert classify(templates, newer_sample).template_id == "demo_kestrel.other.v500"


def test_redacted_structural_match(templates: dict[str, Any], posts: list[Any]) -> None:
    blank = next(s for s in posts if s.is_empty)
    assert classify(templates, blank) is None
    hit = blank.__class__(
        **{**blank.__dict__, "channel_id": "kestrel_trip", "is_ongoing": True, "category": "status"}
    )
    got = classify(templates, hit)
    assert got is not None and got.structural and got.event == "ASSIGNED"


# ---- miss detection ---------------------------------------------------------------------------------------


def test_notification_miss_kinds(templates: dict[str, Any], posts: list[Any]) -> None:
    misses = {m.sample_id: m for m in notification_misses(templates, posts)}
    new_wording = by_text(posts, "is 3 min away")
    assert misses[new_wording.id].kind == "unmatched"
    assert any(m.kind == "redacted_unmatched" for m in misses.values())
    assert len(misses) == 2  # every other post is classified as labelled

    wrong = parse_posts(
        [{"package": KESTREL, "title": "Kestrel Go", "text": "Finding a rider", "label": "ASSIGNED"}]
    )
    assert notification_misses(templates, wrong)[0].kind == "wrong_event"
    other = parse_posts(
        [{"package": KESTREL, "title": "Kestrel Go", "text": "Finding a rider", "label": "OTHER"}]
    )
    assert notification_misses(templates, other)[0].kind == "false_match"


def test_parser_port_agrees_with_labels(parsers: dict[str, Any]) -> None:
    screen = kestrel_screen()
    parsed = parse_screen(screen.frame, parser_entry(parsers, KESTREL, None))
    assert parsed.screen_type == "fare_options"
    assert [(r.vehicle_class, r.price_min, r.price_max, r.eta_min) for r in parsed.rows] == [
        ("bike", 112, 112, 2),
        ("auto", 180, 210, 4),
        ("cab_economy", 289, 289, 6),
    ]
    assert parsed.rows[0].has_struck and not parsed.rows[1].has_struck
    assert screen_issues(screen, parsed) == ()


def test_parser_disagreement_is_reported_without_values(parsers: dict[str, Any]) -> None:
    screen = kestrel_screen("sB", bike_label="Scooty")
    misses = parser_misses(parsers, [screen])
    assert misses and misses[0].issues == ("row_missing:bike",)
    assert not any(ch.isdigit() for issue in misses[0].issues for ch in issue)


@pytest.mark.parametrize(
    ("text", "expected"),
    [
        ("₹1,234", (1234, 1234)),
        ("Rs. 110 – 130", (110, 130)),
        ("₹१२०", (120, 120)),
        ("₹130-110", None),
        ("₹", None),
    ],
)
def test_price_text(text: str, expected: tuple[int, int] | None) -> None:
    assert parse_price_text(text) == expected


def test_ocr_rupee_confusion_is_normalised(parsers: dict[str, Any]) -> None:
    screen = kestrel_screen()
    frame = screen.frame
    tokens = tuple(t if t.text != "₹289" else t.__class__("Z289", t.box, t.line) for t in frame.tokens)
    parsed = parse_screen(
        frame.__class__(frame.width, frame.height, tokens), parser_entry(parsers, KESTREL, None)
    )
    assert parsed.rows[2].price_min == 289


def test_demo_golden_set_has_no_parser_misses(parsers: dict[str, Any]) -> None:
    labels = paths.REPO_ROOT / "eval" / "demo_labels.json"
    ocr = paths.REPO_ROOT / "eval" / "synthetic" / "ocr"
    if not labels.exists() or not ocr.is_dir():
        pytest.skip("demo eval fixtures not generated in this checkout")
    screens, _ = load_golden(labels, [ocr])
    assert screens and parser_misses(parsers, screens) == []
