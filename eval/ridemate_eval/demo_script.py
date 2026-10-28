"""The scripted demo timeline (android/app/src/main/assets/demo/script.json) consumed by DemoSource.

Format: eval/demo/README.md. Every notification posted here is built from `providers.py` wordings, which
match the demo entries of notification_templates.json exactly (test_demo_script.py proves it).
"""

from __future__ import annotations

from typing import Any

from .providers import DEMO_TRIP, KESTREL, PEPPER, PEPPER_BETTER_DRIVER, TAMARIND, DemoProvider, Driver

DEMO_VERSION_CODE = 1
REASON_APP_CANCEL = "REASON_APP_CANCEL"  # NotificationListenerService.REASON_APP_CANCEL (8)
SCREENS = ("fare_options", "searching", "driver_assigned", "cancel_flow", "cancelled")

_UIDS = {"kestrel": 10501, "pepper": 10502, "tamarind": 10503}
_IDS = {"kestrel": 7001, "pepper": 7101, "tamarind": 7201}


def ongoing_key(p: DemoProvider) -> str:
    return f"0|{p.package}|{_IDS[p.key]}|ride|{_UIDS[p.key]}"


def alert_key(p: DemoProvider) -> str:
    return f"0|{p.package}|{_IDS[p.key] + 1}|alert|{_UIDS[p.key]}"


def screen_path(p: DemoProvider, screen: str) -> str:
    return f"demo/{p.key}/{screen}.png"


class Track:
    def __init__(self, track_id: str, description: str, requires: dict[str, Any]) -> None:
        self.id = track_id
        self.description = description
        self.requires = requires
        self.events: list[dict[str, Any]] = []
        self.checkpoints: list[dict[str, Any]] = []
        self._last_posted: dict[str, dict[str, Any]] = {}

    def post(
        self,
        p: DemoProvider,
        event: str,
        t_ms: int,
        step: str,
        *,
        screen: str | None = None,
        driver: Driver | None = None,
        ongoing: bool = True,
    ) -> None:
        title, text = p.notification(event, driver)
        key = ongoing_key(p) if ongoing else alert_key(p)
        ev: dict[str, Any] = {
            "id": f"{p.key}_{event.lower()}_{len(self.events) + 1}",
            "t_ms": t_ms,
            "requires_user_step": step,
            "action": "post",
            "package": p.package,
            "sbn_key": key,
            "channel_id": p.channel_ongoing if ongoing else p.channel_alerts,
            "category": "status" if ongoing else None,
            "ongoing": ongoing,
            "title": title,
            "text": text,
            "expect_event": event,
        }
        if event == "ASSIGNED":
            d = driver or p.driver
            ev["expect_groups"] = {"plate": d.plate, "eta": str(d.eta_min), "dist": d.distance_text}
        if screen:
            ev["screen"] = screen
        self.events.append(ev)
        self._last_posted[key] = ev

    def remove_ongoing(self, p: DemoProvider, t_ms: int, step: str) -> None:
        last = self._last_posted[ongoing_key(p)]
        self.events.append(
            {
                "id": f"{p.key}_ongoing_removed_{len(self.events) + 1}",
                "t_ms": t_ms,
                "requires_user_step": step,
                "action": "remove",
                "package": p.package,
                "sbn_key": last["sbn_key"],
                "channel_id": last["channel_id"],
                "category": last["category"],
                "ongoing": True,
                "title": last["title"],
                "text": last["text"],
                "removal_reason": REASON_APP_CANCEL,
            }
        )

    def cancel_by_user(self, p: DemoProvider) -> None:
        """The user taps "I tapped Cancel" on the demo cancel screen; the provider proves it (rungs 2, 3)."""
        step = f"tapped_cancel:{p.package}"
        self.post(p, "CANCELLED", 1500, step, screen="cancelled", ongoing=False)
        self.remove_ongoing(p, 1600, step)
        self.checkpoints.append(
            {
                "after_user_step": step,
                "at_ms": 1500,
                "expect": "cancel_confirmed",
                "package": p.package,
                "rung": 2,
            }
        )

    def ride_to_payment(
        self, p: DemoProvider, step: str, *, driver: Driver | None = None, base_ms: int = 0
    ) -> None:
        self.post(p, "ARRIVING", base_ms + 10000, step, driver=driver)
        self.post(p, "TRIP_STARTED", base_ms + 25000, step, driver=driver)
        self.post(p, "ENDED", base_ms + 45000, step, ongoing=False)
        self.remove_ongoing(p, base_ms + 45100, step)
        self.checkpoints.append(
            {
                "after_user_step": step,
                "at_ms": base_ms + 45000,
                "expect": "pay_handoff_offer",
                "package": p.package,
            }
        )

    def to_json(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "description": self.description,
            "requires": self.requires,
            "events": self.events,
            "checkpoints": self.checkpoints,
        }


def _book_all(track: Track) -> None:
    for p in (KESTREL, PEPPER, TAMARIND):
        track.post(p, "SEARCHING", 1200, f"book:{p.package}", screen="searching")
    track.post(PEPPER, "ASSIGNED", 9000, f"book:{TAMARIND.package}", screen="driver_assigned")
    track.checkpoints.append(
        {
            "after_user_step": f"book:{TAMARIND.package}",
            "at_ms": 9000,
            "expect": "first_driver_sheet",
            "package": PEPPER.package,
            "driver_eta_min": PEPPER.driver.eta_min,
            "driver_distance_text": PEPPER.driver.distance_text,
        }
    )


def main_track() -> Track:
    t = Track(
        "main",
        "Book all three; Pepper finds the first driver; 'Cancel the others?' → Yes; Kestrel and Tamarind are "
        "cancelled with proof (rung 2); reminders while any is pending; Pepper's ride ends → pay in "
        "Pepper Rides.",
        {"keep_searching": False},
    )
    _book_all(t)
    t.checkpoints.append(
        {
            "after_user_step": "cancel_others:yes",
            "expect": "cancel_chain",
            "order": [KESTREL.package, TAMARIND.package],
        }
    )
    t.checkpoints.append(
        {
            "after_user_step": "cancel_others:yes",
            "at_ms": [30000, 60000, 120000, 240000],
            "expect": "reminder_while_pending",
            "packages": [KESTREL.package, TAMARIND.package],
        }
    )
    t.cancel_by_user(KESTREL)
    t.cancel_by_user(TAMARIND)
    t.ride_to_payment(PEPPER, f"tapped_cancel:{TAMARIND.package}")
    return t


def keep_searching_track() -> Track:
    t = Track(
        "keep_searching",
        "Keep searching is on: Pepper's driver is 9 min away, the user keeps searching, Tamarind then "
        "finds a "
        "driver 2 min away → better-ride offer → Yes → Kestrel and Pepper are cancelled with proof; Tamarind "
        "carries the trip to payment.",
        {"keep_searching": True},
    )
    _book_all(t)
    t.post(TAMARIND, "ASSIGNED", 8000, "cancel_others:not_now", screen="driver_assigned")
    gain = PEPPER.driver.eta_min - TAMARIND.driver.eta_min
    t.checkpoints.append(
        {
            "after_user_step": "cancel_others:not_now",
            "at_ms": 8000,
            "expect": "better_ride_offer",
            "current": PEPPER.package,
            "better": TAMARIND.package,
            "eta_gain_min": gain,
        }
    )
    t.checkpoints.append(
        {
            "after_user_step": "better_ride:yes",
            "expect": "cancel_chain",
            "order": [KESTREL.package, PEPPER.package],
        }
    )
    t.cancel_by_user(KESTREL)
    t.cancel_by_user(PEPPER)
    t.ride_to_payment(TAMARIND, f"tapped_cancel:{PEPPER.package}")
    return t


def rematch_track() -> Track:
    t = Track(
        "rematch",
        "Pepper only: the first driver cancels, Pepper re-matches with a much closer driver (new plate → new "
        "generation, driver_assigned_better.png), then the ride ends → pay in Pepper Rides.",
        {"keep_searching": False},
    )
    step = f"book:{PEPPER.package}"
    t.post(PEPPER, "SEARCHING", 1200, step, screen="searching")
    t.post(PEPPER, "ASSIGNED", 9000, step, screen="driver_assigned")
    t.post(PEPPER, "DRIVER_CANCELLED", 20000, step, screen="searching")
    t.post(PEPPER, "ASSIGNED", 30000, step, screen="driver_assigned_better", driver=PEPPER_BETTER_DRIVER)
    t.checkpoints.append(
        {
            "after_user_step": step,
            "at_ms": 20000,
            "expect": "driver_cancelled_notice",
            "package": PEPPER.package,
        }
    )
    t.checkpoints.append(
        {
            "after_user_step": step,
            "at_ms": 30000,
            "expect": "assigned_new_generation",
            "package": PEPPER.package,
            "driver_eta_min": PEPPER_BETTER_DRIVER.eta_min,
            "driver_distance_text": PEPPER_BETTER_DRIVER.distance_text,
        }
    )
    t.ride_to_payment(PEPPER, step, driver=PEPPER_BETTER_DRIVER, base_ms=30000)
    return t


USER_STEPS = {
    "book:<package>": "The user taps 'Booked here' after reading that provider's fare screen.",
    "cancel_others:yes": "The user taps 'Yes, cancel the others' on the first-driver sheet.",
    "cancel_others:not_now": "The user keeps searching instead (only offered when keep searching is on).",
    "tapped_cancel:<package>": "On that provider's demo cancel screen the user taps 'Cancel ride'.",
    "better_ride:yes": "The user accepts the better-ride offer.",
}


def build_script() -> dict[str, Any]:
    providers = {}
    for p in (KESTREL, PEPPER, TAMARIND):
        screens = {s: screen_path(p, s) for s in SCREENS}
        if p is PEPPER:
            screens["driver_assigned_better"] = screen_path(p, "driver_assigned_better")
        b = p.booked
        providers[p.package] = {
            "name": p.name,
            "wire_id": p.wire_id,
            "version_code": DEMO_VERSION_CODE,
            "screens": screens,
            "cancel_flow_from": p.cancel_from,
            "booked": {
                "vehicle_class": b.vehicle_class,
                "label": b.label,
                "price_min": b.price_min,
                "price_max": b.price_max,
            },
        }
    return {
        "schema": "1.0",
        "notes": (
            "Fictional demo providers only (labelled DEMO). Posts go straight into the RideWatch pipeline "
            "from "
            "DemoSource and never mix with live legs. Wordings match the demo.* notification_templates. "
            "trip_km is the straight-line (haversine) distance; the gate applies road_factor itself."
        ),
        "trip": {
            "pickup": {
                "label": DEMO_TRIP.pickup.label,
                "lat": DEMO_TRIP.pickup.lat,
                "lng": DEMO_TRIP.pickup.lng,
            },
            "drop": {"label": DEMO_TRIP.drop.label, "lat": DEMO_TRIP.drop.lat, "lng": DEMO_TRIP.drop.lng},
            "trip_km": DEMO_TRIP.straight_km,
        },
        "providers": providers,
        "user_steps": USER_STEPS,
        "tracks": [main_track().to_json(), keep_searching_track().to_json(), rematch_track().to_json()],
    }
