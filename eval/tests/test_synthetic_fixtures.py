"""Synthetic OCR and Channel A fixtures: right shape, one per screen, `expect` blocks a gate really yields."""

from __future__ import annotations

import pytest
from ridemate_eval.jsonio import load_json
from ridemate_eval.paths import SYNTHETIC_DIR
from ridemate_eval.reference_gate import ReferenceGate

FARE_VARIANTS = {
    "one_digit_off",
    "struck_as_payable",
    "label_swapped",
    "bbox_shifted",
    "wrong_app",
    "prompt_injection_label",
}
DRIVER_VARIANTS = {"driver_eta_off", "driver_distance_off", "wrong_app"}
NOISY_VARIANTS = {"rupee_as_Z", "rupee_as_R", "rupee_as_pct", "merged_eta", "split_price"}


@pytest.fixture(scope="module")
def gate() -> ReferenceGate:
    return ReferenceGate()


def variants(sub: str, sha: str) -> dict[str, dict]:
    return {p.name.split(".")[1]: load_json(p) for p in (SYNTHETIC_DIR / sub).glob(f"{sha}.*.json")}


def test_one_ocr_and_one_channel_a_file_per_screen(labels) -> None:
    shas = {s["sha256"] for s in labels["screens"]}
    assert {p.stem for p in (SYNTHETIC_DIR / "ocr").glob("*.json")} == shas
    assert {p.stem for p in (SYNTHETIC_DIR / "channel_a").glob("*.json")} == shas
    for sub in ("ocr_noisy", "channel_a_adversarial"):
        assert {p.name.split(".")[0] for p in (SYNTHETIC_DIR / sub).glob("*.json")} <= shas


def test_fixtures_match_schemas(labels, schema_validator) -> None:
    for s in labels["screens"]:
        sha = s["sha256"]
        assert schema_validator("ocr", load_json(SYNTHETIC_DIR / "ocr" / f"{sha}.json")) == []
        assert schema_validator("channel_a", load_json(SYNTHETIC_DIR / "channel_a" / f"{sha}.json")) == []
        for doc in variants("ocr_noisy", sha).values():
            assert schema_validator("ocr", doc) == []
        for doc in variants("channel_a_adversarial", sha).values():
            assert schema_validator("channel_a", doc) == []
            assert doc["image_sha256"] == sha and "expect" in doc


def test_perfect_reader_mirrors_labels(labels) -> None:
    for s in labels["screens"]:
        doc = load_json(SYNTHETIC_DIR / "channel_a" / f"{s['sha256']}.json")
        read = doc["read"]
        assert doc["cost_usd"] == 0 and doc["model"] == "synthetic-perfect-reader"
        assert read["app"] == s["app"] and read["screen_type"] == s["screen_type"]
        assert read["driver_eta_min"] == s["driver_eta_min"]
        assert read["driver_distance_text"] == s["driver_distance_text"]
        assert len(read["options"]) == len(s["rows"])
        for o, r in zip(read["options"], s["rows"], strict=True):
            assert (o["vehicle_class"], o["label_text"], o["price_min"], o["price_max"], o["eta_min"]) == (
                r["vehicle_class"],
                r["label"],
                r["price_min"],
                r["price_max"],
                r["eta_min"],
            )
            assert o["bbox_0_1000"] == r["bbox_0_1000"]
            assert (o["price_struck_text"] is None) == (r["struck_price"] is None)


def test_perfect_reader_with_ideal_ocr_verifies_everything(labels, gate) -> None:
    for s in labels["screens"]:
        sha = s["sha256"]
        doc = load_json(SYNTHETIC_DIR / "channel_a" / f"{sha}.json")
        outcome = gate.evaluate(s, load_json(SYNTHETIC_DIR / "ocr" / f"{sha}.json"), doc["read"])
        assert outcome == doc["expect"], s["file"]


def test_adversarial_variants_exist_and_are_never_verified(labels, gate) -> None:
    for s in labels["screens"]:
        sha = s["sha256"]
        found = variants("channel_a_adversarial", sha)
        if s["screen_type"] == "fare_options":
            assert FARE_VARIANTS <= set(found), s["file"]
        if s["screen_type"] == "driver_assigned":
            assert DRIVER_VARIANTS <= set(found), s["file"]
        ocr = load_json(SYNTHETIC_DIR / "ocr" / f"{sha}.json")
        for name, doc in found.items():
            outcome = gate.evaluate(s, ocr, doc["read"])
            assert ReferenceGate.satisfies(outcome, doc["expect"]), (s["file"], name, outcome, doc["expect"])
            assert "UNREADABLE" in outcome["rows"] or "SEE_IN_APP" in outcome.values(), (s["file"], name)


def test_bbox_shift_exceeds_gate_tolerance(labels) -> None:
    for s in labels["screens"]:
        doc = variants("channel_a_adversarial", s["sha256"]).get("bbox_shifted")
        if doc:
            assert doc["expect"]["shift_0_1000"] > 40


def test_injection_label_is_never_displayed(labels) -> None:
    for s in labels["screens"]:
        doc = variants("channel_a_adversarial", s["sha256"]).get("prompt_injection_label")
        if doc:
            assert "Ignore previous instructions" in doc["read"]["options"][0]["label_text"]
            assert doc["expect"]["model_text_displayed"] is False


def test_noisy_ocr_variants_meet_expectations(labels, gate) -> None:
    for s in labels["screens"]:
        if s["screen_type"] != "fare_options":
            continue
        sha = s["sha256"]
        found = variants("ocr_noisy", sha)
        assert set(found) == NOISY_VARIANTS
        perfect = load_json(SYNTHETIC_DIR / "channel_a" / f"{sha}.json")["read"]
        for name, doc in found.items():
            outcome = gate.evaluate(s, doc, perfect)
            assert ReferenceGate.satisfies(outcome, doc["expect"]), (s["file"], name, outcome)
        z = " ".join(t["text"] for t in found["rupee_as_Z"]["tokens"])
        assert "₹" not in z and "Z" in z
        assert any(t["text"].endswith("min") and t["text"] != "min" for t in found["merged_eta"]["tokens"])
        assert (
            len(found["split_price"]["tokens"])
            == len(load_json(SYNTHETIC_DIR / "ocr" / f"{sha}.json")["tokens"]) + 1
        )
