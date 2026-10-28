"""Golden generator: quotas by construction, labels that the provider parsers accept, deterministic output."""

from __future__ import annotations

import hashlib
import importlib.util
from pathlib import Path

import pytest
from ridemate_config.regex import compile_re2
from ridemate_config.validation import default_value
from ridemate_eval.golden import (
    DEFAULT_COUNT,
    MIN_PER_PROVIDER,
    plan,
    planned_tags,
    quota_shortfalls,
    rotate_box,
)
from ridemate_eval.jsonio import load_json
from ridemate_eval.paths import EVAL_DIR
from ridemate_eval.providers import PROVIDERS

FARE = default_value("fare_parsers")
BANDS = default_value("sanity_bands")


def _cli():
    spec = importlib.util.spec_from_file_location("generate_golden", EVAL_DIR / "golden/generate_golden.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


@pytest.fixture(scope="module")
def specs():
    return plan()


def test_default_plan_meets_every_quota(specs) -> None:
    assert len(specs) == DEFAULT_COUNT >= 150
    assert quota_shortfalls(specs) == []
    for p in PROVIDERS:
        tags = [planned_tags(s) for s in specs if s.provider is p]
        for tag, minimum in MIN_PER_PROVIDER.items():
            assert sum(tag in t for t in tags) >= minimum, (p.key, tag)
    every = set().union(*(planned_tags(s) for s in specs))
    for tag in (
        "range",
        "devanagari_digits",
        "lakh_grouping",
        "dark_mode",
        "jpeg",
        "blur",
        "rotated",
        "noise",
        "long_label",
        "twelve_options",
        "injection",
        "rs_prefix",
        "grouping",
        "driver_eta",
    ):
        assert tag in every, tag


def test_plan_is_deterministic(specs) -> None:
    again = plan()
    assert [(s.name, s.rows, s.driver, s.rotate_deg, s.jpeg_quality) for s in again] == [
        (s.name, s.rows, s.driver, s.rotate_deg, s.jpeg_quality) for s in specs
    ]


def test_every_label_maps_to_its_class_with_the_provider_parser(specs) -> None:
    for s in specs:
        rules = FARE["apps"][s.provider.package][0]["vehicle_labels"]
        for r in s.rows:
            got = next(
                (rule["vehicle_class"] for rule in rules if compile_re2(rule["pattern"]).search(r.label)),
                None,
            )
            assert got == r.vehicle_class, (s.name, r.label)
            assert r.vehicle_class in (*s.provider.vehicle_classes, "other")


def test_prices_sit_inside_sanity_bands_unless_marked_out_of_band(specs) -> None:
    for s in specs:
        d = s.trip_km * BANDS["road_factor"]  # trip_km is straight-line km, as the phone's gate receives it
        for i, r in enumerate(s.rows):
            lo, hi = BANDS["per_km_inr"][r.vehicle_class]
            inside = all(
                BANDS["price_min_inr"] <= p <= BANDS["price_max_inr"]
                and lo * d - BANDS["base_allowance_inr"] <= p <= hi * d + BANDS["base_allowance_inr"]
                for p in (r.price_min, r.price_max)
            )
            assert inside == (s.row_expect.get(i) != "UNREADABLE"), (s.name, i)


def test_rotate_box() -> None:
    box = (500, 1150, 580, 1250)
    assert rotate_box(box, 0) == box
    left, top, right, bottom = rotate_box((100, 200, 300, 260), 1.0)
    assert left < 100 + 30 and right > 300 - 30 and bottom - top > 60


def test_generator_writes_a_valid_self_consistent_sample(tmp_path, schema_validator) -> None:
    out = tmp_path / "golden"
    assert _cli().main(["--out", str(out), "--count", "27"]) == 0
    labels = load_json(out / "labels.json")
    assert schema_validator("labels", labels) == []
    assert len(labels["screens"]) == 27
    for s in labels["screens"]:
        data = (out / s["file"]).read_bytes()
        assert hashlib.sha256(data).hexdigest() == s["sha256"]
        assert schema_validator("ocr", load_json(out / "ocr" / f"{s['sha256']}.json")) == []
        assert schema_validator("channel_a", load_json(out / "channel_a" / f"{s['sha256']}.json")) == []
    kinds = {s["screen_type"] for s in labels["screens"]}
    assert kinds == {"fare_options", "driver_assigned"}
    first = {s["file"]: s["sha256"] for s in labels["screens"]}
    assert _cli().main(["--out", str(out), "--count", "27"]) == 0
    assert {s["file"]: s["sha256"] for s in load_json(out / "labels.json")["screens"]} == first


def test_generator_refuses_tracked_repo_paths() -> None:
    with pytest.raises(SystemExit, match="refusing"):
        _cli().main(["--out", str(EVAL_DIR / "golden" / "out"), "--count", "3"])
    assert not (EVAL_DIR / "golden" / "out").exists()


def test_default_output_is_gitignored() -> None:
    import subprocess

    from ridemate_eval.paths import DEFAULT_GOLDEN_OUT, REPO_ROOT

    probe = Path(DEFAULT_GOLDEN_OUT) / "labels.json"
    assert (
        subprocess.run(
            ["git", "-C", str(REPO_ROOT), "check-ignore", "-q", str(probe)], check=False
        ).returncode
        == 0
    )


@pytest.mark.parametrize("name", ["generate_golden.py", "README.md"])
def test_generator_sources_are_not_gitignored(name: str) -> None:
    """The repo-root `golden/` rule must not hide the generator itself (eval/.gitignore re-includes it)."""
    import subprocess

    from ridemate_eval.paths import REPO_ROOT

    probe = EVAL_DIR / "golden" / name
    ignored = subprocess.run(["git", "-C", str(REPO_ROOT), "check-ignore", "-q", str(probe)], check=False)
    assert ignored.returncode == 1
