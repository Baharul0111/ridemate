"""script.json drives DemoSource: every post must hit exactly its intended demo template (goal 19)."""

from __future__ import annotations

import re

from ridemate_config.keys import DEMO_PACKAGES
from ridemate_config.matching import all_hits
from ridemate_config.validation import default_value
from ridemate_eval.paths import DEMO_ASSETS_DIR, REPO_ROOT

NOTIF = default_value("notification_templates")
REAL_BRANDS = re.compile(r"\b(uber|ola|rapido)\b", re.I)


def posts(script):
    for track in script["tracks"]:
        for ev in track["events"]:
            if ev["action"] == "post":
                yield track, ev


def test_script_matches_schema(script, schema_validator) -> None:
    assert schema_validator("demo_script", script) == []


def test_every_post_hits_exactly_one_template_with_the_expected_event(script) -> None:
    for _, ev in posts(script):
        hits = all_hits(
            NOTIF,
            ev["package"],
            ev["title"],
            ev["text"],
            channel_id=ev["channel_id"],
            ongoing=ev["ongoing"],
            version_code=1,
        )
        assert len(hits) == 1, (ev["id"], hits)
        assert hits[0].event == ev["expect_event"], ev["id"]
        for name, value in ev.get("expect_groups", {}).items():
            assert hits[0].groups.get(name) == value, (ev["id"], name)


def test_script_covers_the_goal_19_story(script) -> None:
    tracks = {t["id"]: t for t in script["tracks"]}
    assert set(tracks) == {"main", "keep_searching", "rematch"}
    main = [(e["package"], e.get("expect_event")) for e in tracks["main"]["events"] if e["action"] == "post"]
    for pkg in DEMO_PACKAGES:
        assert (pkg, "SEARCHING") in main
    assert main.index(("demo.pepper", "ASSIGNED")) > max(main.index((p, "SEARCHING")) for p in DEMO_PACKAGES)
    assert ("demo.kestrel", "CANCELLED") in main and ("demo.tamarind", "CANCELLED") in main
    assert main[-1] == ("demo.pepper", "ENDED")
    better = next(c for c in tracks["keep_searching"]["checkpoints"] if c["expect"] == "better_ride_offer")
    assert better["better"] == "demo.tamarind" and better["current"] == "demo.pepper"
    assert tracks["keep_searching"]["requires"] == {"keep_searching": True}
    assert any(c["expect"] == "reminder_while_pending" for c in tracks["main"]["checkpoints"])


def test_cancelled_posts_follow_the_users_cancel_tap_and_remove_the_ongoing_post(script) -> None:
    for track in script["tracks"]:
        events = track["events"]
        for i, ev in enumerate(events):
            if ev.get("expect_event") == "CANCELLED":
                assert ev["requires_user_step"] == f"tapped_cancel:{ev['package']}"
                assert ev["ongoing"] is False
                removal = events[i + 1]
                assert removal["action"] == "remove" and removal["removal_reason"] == "REASON_APP_CANCEL"
                assert removal["package"] == ev["package"] and removal["t_ms"] > ev["t_ms"]
            if ev["action"] == "remove":
                earlier = [e for e in events[:i] if e["action"] == "post" and e["sbn_key"] == ev["sbn_key"]]
                assert earlier and earlier[-1]["title"] == ev["title"] and earlier[-1]["text"] == ev["text"]


def test_timeline_is_ordered_per_anchor(script) -> None:
    """Events sharing a user-step anchor are in firing order, so DemoSource can replay them as written."""
    for track in script["tracks"]:
        last: dict[str, int] = {}
        for ev in track["events"]:
            step = ev["requires_user_step"]
            assert ev["t_ms"] >= last.get(step, 0), ev["id"]
            last[step] = ev["t_ms"]
        ids = [ev["id"] for ev in track["events"]]
        assert len(ids) == len(set(ids))


def test_assigned_posts_agree_with_their_driver_screens(script, labels) -> None:
    by_asset = {s["file"].removeprefix("android/app/src/main/assets/"): s for s in labels["screens"]}
    for _, ev in posts(script):
        if ev["expect_event"] == "ASSIGNED":
            screen = by_asset[script["providers"][ev["package"]]["screens"][ev["screen"]]]
            assert screen["driver_eta_min"] == int(ev["expect_groups"]["eta"])
            assert screen["driver_distance_text"] == ev["expect_groups"]["dist"]


def test_better_ride_offer_meets_the_rule(script, labels) -> None:
    track = next(t for t in script["tracks"] if t["id"] == "keep_searching")
    assigned = {
        e["package"]: int(e["expect_groups"]["eta"])
        for e in track["events"]
        if e.get("expect_event") == "ASSIGNED"
    }
    current, better = assigned["demo.pepper"], assigned["demo.tamarind"]
    gain = current - better
    assert gain >= max(5, 0.4 * current)
    offer = next(c for c in track["checkpoints"] if c["expect"] == "better_ride_offer")
    assert offer["eta_gain_min"] == gain


def test_rematch_uses_a_new_plate_and_the_better_screen(script) -> None:
    track = next(t for t in script["tracks"] if t["id"] == "rematch")
    assigned = [e for e in track["events"] if e.get("expect_event") == "ASSIGNED"]
    assert (
        len(assigned) == 2 and assigned[0]["expect_groups"]["plate"] != assigned[1]["expect_groups"]["plate"]
    )
    assert assigned[1]["screen"] == "driver_assigned_better"
    kinds = [e.get("expect_event") for e in track["events"] if e["action"] == "post"]
    assert kinds.index("DRIVER_CANCELLED") == kinds.index("ASSIGNED") + 1


def test_referenced_screens_exist_and_trip_matches_labels(script, labels) -> None:
    for provider in script["providers"].values():
        for path in provider["screens"].values():
            assert (DEMO_ASSETS_DIR.parent / path).is_file(), path
    assert script["trip"]["trip_km"] == labels["screens"][0]["trip_km"]


def test_script_names_no_real_ride_app(script) -> None:
    for _, ev in posts(script):
        assert not REAL_BRANDS.search(ev["title"] + " " + ev["text"])
    assert not REAL_BRANDS.search((REPO_ROOT / "android/app/src/main/assets/demo/script.json").read_text())


def test_demo_fee_note_matches_fee_rules(labels) -> None:
    fees = default_value("fee_rules")["apps"]
    pepper = next(s for s in labels["screens"] if s["file"].endswith("pepper/cancel_flow.png"))
    assert f"₹{fees['demo.pepper']['max_inr']}" in pepper["fee_text"]
