"""The committed demo screens: present, deterministic, fictional, labelled, covering the required cases."""

from __future__ import annotations

import hashlib
import io
import re
from collections import Counter

from PIL import Image
from ridemate_eval.demo_assets import build_demo, stale_files
from ridemate_eval.fonts import font_for
from ridemate_eval.jsonio import load_json as load
from ridemate_eval.paths import FONTS_DIR, REPO_ROOT, SYNTHETIC_DIR
from ridemate_eval.providers import BY_WIRE_ID, DEMO_TRIP

REAL_BRANDS = re.compile(r"\b(uber|ola|rapido)\b", re.I)
EXPECTED_FILES = {
    f"android/app/src/main/assets/demo/{p}/{s}.png"
    for p in ("kestrel", "pepper", "tamarind")
    for s in ("fare_options", "searching", "driver_assigned", "cancel_flow", "cancelled")
} | {"android/app/src/main/assets/demo/pepper/driver_assigned_better.png"}


def ocr_for(screen: dict) -> dict:
    return load(SYNTHETIC_DIR / "ocr" / f"{screen['sha256']}.json")


def test_committed_artifacts_match_a_fresh_build(demo_build) -> None:
    assert stale_files(demo_build) == [], "run: cd eval && uv run python demo/generate_demo.py"


def test_two_builds_are_byte_identical(demo_build) -> None:
    again = build_demo()
    assert {p: hashlib.sha256(d).hexdigest() for p, d in again.files.items()} == {
        p: hashlib.sha256(d).hexdigest() for p, d in demo_build.files.items()
    }


def test_every_demo_screen_exists_with_matching_sha(labels) -> None:
    files = {s["file"] for s in labels["screens"]}
    assert files == EXPECTED_FILES
    for s in labels["screens"]:
        data = (REPO_ROOT / s["file"]).read_bytes()
        assert hashlib.sha256(data).hexdigest() == s["sha256"]
        img = Image.open(io.BytesIO(data))
        assert img.format == "PNG" and img.size == (1080, 2400) == (s["frame_w"], s["frame_h"])
    assert len({s["sha256"] for s in labels["screens"]}) == len(labels["screens"])


def test_labels_match_schema(labels, schema_validator) -> None:
    assert schema_validator("labels", labels) == []


def test_screen_types_per_file(labels) -> None:
    for s in labels["screens"]:
        stem = s["file"].rsplit("/", 1)[1].removesuffix(".png")
        assert s["screen_type"] == ("driver_assigned" if stem == "driver_assigned_better" else stem)


def test_fare_screens_cover_required_formats(labels) -> None:
    fares = [s for s in labels["screens"] if s["screen_type"] == "fare_options"]
    assert len(fares) == 3
    tags = Counter(t for s in fares for t in set(s["tags"]))
    for s in fares:
        assert 3 <= len(s["rows"]) <= 4
        assert any(r["struck_price"] is not None for r in s["rows"]), s["file"]
        assert all(r["eta_min"] is not None for r in s["rows"])
        assert {r["vehicle_class"] for r in s["rows"]} <= set(BY_WIRE_ID[s["app"]].vehicle_classes)
        for r in s["rows"]:
            assert r["struck_price"] is None or r["struck_price"] > r["price_max"]
    assert tags["range"] >= 1 and tags["devanagari_digits"] >= 1 and tags["grouping"] >= 1


def test_fare_rows_pass_sanity_bands(labels) -> None:
    from ridemate_config.validation import default_value

    bands = default_value("sanity_bands")
    for s in labels["screens"]:
        # Straight-line km, as the phone's gate receives it; the gate applies road_factor (14.6 km by road).
        assert s["trip_km"] == DEMO_TRIP.straight_km == 10.81
        d = s["trip_km"] * bands["road_factor"]
        assert round(d, 1) == DEMO_TRIP.road_km == 14.6
        for r in s["rows"]:
            lo, hi = bands["per_km_inr"][r["vehicle_class"]]
            for p in (r["price_min"], r["price_max"]):
                assert bands["price_min_inr"] <= p <= bands["price_max_inr"]
                assert lo * d - bands["base_allowance_inr"] <= p <= hi * d + bands["base_allowance_inr"]


def test_every_screen_is_marked_demo_and_names_no_real_brand(labels) -> None:
    for s in labels["screens"]:
        words = [t["text"] for t in ocr_for(s)["tokens"]]
        assert " ".join(words[:12]).count("DEMO · fictional provider") == 1, s["file"]
        assert not REAL_BRANDS.search(" ".join(words)), s["file"]
        assert not any(REAL_BRANDS.search(r["label"]) for r in s["rows"])


def test_ocr_is_one_token_per_word_inside_the_frame(labels) -> None:
    for s in labels["screens"]:
        ocr = ocr_for(s)
        assert ocr["image_sha256"] == s["sha256"] and ocr["engine"] == "synthetic"
        for t in ocr["tokens"]:
            assert t["text"] and " " not in t["text"]
            left, top, right, bottom = t["box"]
            assert 0 <= left < right <= s["frame_w"] and 0 <= top < bottom <= s["frame_h"]


def test_each_row_price_label_and_eta_are_tokens_inside_the_row(labels) -> None:
    for s in labels["screens"]:
        tokens = ocr_for(s)["tokens"]
        for r in s["rows"]:
            x0, y0, x1, y1 = (
                r["bbox_0_1000"][0] * 1.08,
                r["bbox_0_1000"][1] * 2.4,
                r["bbox_0_1000"][2] * 1.08,
                r["bbox_0_1000"][3] * 2.4,
            )
            inside = [
                t
                for t in tokens
                if x0 <= (t["box"][0] + t["box"][2]) / 2 <= x1 and y0 <= (t["box"][1] + t["box"][3]) / 2 <= y1
            ]
            text = " ".join(t["text"] for t in inside)
            assert r["label"].split(" ")[0] in text
            assert any(t["text"].startswith("₹") for t in inside)
            if r["struck_price"] is not None:
                heights = sorted(t["box"][3] - t["box"][1] for t in inside if t["text"].startswith("₹"))
                assert heights[0] < 0.8 * heights[-1], "struck price must be drawn smaller"


def test_driver_screens_show_eta_distance_and_fake_pii(labels) -> None:
    drivers = [s for s in labels["screens"] if s["screen_type"] == "driver_assigned"]
    assert len(drivers) == 4
    for s in drivers:
        text = " ".join(t["text"] for t in ocr_for(s)["tokens"])
        assert f"{s['driver_eta_min']} min away" in text
        assert f"{s['driver_distance_text']} from pickup" in text
        assert re.search(r"\bXX 00 DM \d{4}\b", text)
        assert "+91 98765 43210" in text
        assert re.search(r"\b(OTP|PIN) \d{4}\b", text)
        assert {"pii_plate", "pii_phone", "pii_otp"} <= set(s["tags"])
    better = next(s for s in drivers if s["file"].endswith("driver_assigned_better.png"))
    first = next(s for s in drivers if s["file"].endswith("pepper/driver_assigned.png"))
    assert better["driver_eta_min"] < first["driver_eta_min"]


def test_cancel_flow_has_a_show_me_target_on_the_cancel_button(labels) -> None:
    flows = [s for s in labels["screens"] if s["screen_type"] == "cancel_flow"]
    assert len(flows) == 3
    for s in flows:
        x0, y0, x1, y1 = s["targets"]["cancel_button"]
        assert x1 - x0 > 500 and y1 - y0 > 40
        words = [
            t["text"]
            for t in ocr_for(s)["tokens"]
            if x0 * 1.08 <= (t["box"][0] + t["box"][2]) / 2 <= x1 * 1.08
            and y0 * 2.4 <= (t["box"][1] + t["box"][3]) / 2 <= y1 * 2.4
        ]
        assert words == ["Cancel", "ride"]
        assert s["fee_text"]


def test_cancelled_screens_contain_cancelled_keywords(labels) -> None:
    from ridemate_config.validation import default_value

    cards = default_value("cancel_cards")["apps"]
    for s in labels["screens"]:
        if s["screen_type"] == "cancelled":
            text = " ".join(t["text"] for t in ocr_for(s)["tokens"]).lower()
            pkg = BY_WIRE_ID[s["app"]].package
            assert any(k in text for k in cards[pkg]["cancelled_keywords"])


def test_fonts_are_bundled_with_licenses_and_cover_every_rendered_glyph(labels) -> None:
    for name in ("NotoSans-Regular.ttf", "NotoSans-Bold.ttf", "Mukta-Regular.ttf", "Mukta-Bold.ttf"):
        assert (FONTS_DIR / name).stat().st_size > 100_000
    for lic in ("OFL-NotoSans.txt", "OFL-Mukta.txt"):
        assert "SIL OPEN FONT LICENSE Version 1.1" in (FONTS_DIR / lic).read_text()
    chars = {c for s in labels["screens"] for t in ocr_for(s)["tokens"] for c in t["text"]}
    for c in chars:
        f = font_for(c, "regular", 40)
        assert bytes(f.getmask(c)) != bytes(f.getmask("")), f"missing glyph {c!r}"
