"""The three fictional demo providers (contracts §5) and everything their screens and notifications show.

This module is the single source for the demo screens, demo_labels.json and demo/script.json, so a fare,
driver or notification wording can't drift between them. The notification wordings must match the demo
entries of config/remote_config/notification_templates.json (checked by eval/tests/test_demo_script.py).
Names, plates (state code "XX", series "DM") and the phone number are invented placeholders.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field
from typing import Literal

from .numbers import Digits
from .render import RGB

Event = Literal[
    "SEARCHING",
    "ASSIGNED",
    "ARRIVING",
    "DRIVER_CANCELLED",
    "NO_DRIVERS",
    "CANCELLED",
    "ENDED",
    "TRIP_STARTED",
]

DEMO_BANNER = "DEMO · fictional provider"
DEMO_BANNER_COLOR: RGB = (142, 26, 91)
FAKE_PHONE = "+91 98765 43210"
ROAD_FACTOR = 1.35


@dataclass(frozen=True)
class Place:
    label: str
    lat: float
    lng: float


@dataclass(frozen=True)
class Trip:
    pickup: Place
    drop: Place

    @property
    def route_text(self) -> str:
        return f"{self.pickup.label} to {self.drop.label}"

    @property
    def straight_km(self) -> float:
        """Haversine km between pickup and drop. This is what labels and script.json carry as `trip_km`: the
        phone's gate receives it as tripKm and multiplies it by sanity_bands.road_factor itself (core
        SanityBands), so a label must not be pre-multiplied."""
        lat1, lng1, lat2, lng2 = map(
            math.radians, (self.pickup.lat, self.pickup.lng, self.drop.lat, self.drop.lng)
        )
        h = (
            math.sin((lat2 - lat1) / 2) ** 2
            + math.cos(lat1) * math.cos(lat2) * math.sin((lng2 - lng1) / 2) ** 2
        )
        return round(2 * 6371.0088 * math.asin(math.sqrt(h)), 2)

    @property
    def road_km(self) -> float:
        """Haversine × road factor: the distance d the sanity bands are applied to (contracts §2.3)."""
        return round(self.straight_km * ROAD_FACTOR, 1)


DEMO_TRIP = Trip(
    pickup=Place("Riverside Gate", 26.14450, 91.73620),
    drop=Place("Airport Terminal", 26.11120, 91.63442),
)


@dataclass(frozen=True)
class FareRow:
    label: str
    vehicle_class: str
    price_min: int
    price_max: int
    eta_min: int
    struck_price: int | None = None
    digits: Digits = "latin"
    tagline: str = ""
    trip_time: str | None = None  # second ETA-like figure on the row ("23 min trip", "Drop 7:05 pm")

    @property
    def tags(self) -> list[str]:
        tags = ["eta"]
        if self.struck_price is not None:
            tags.append("strikethrough")
        if self.price_max != self.price_min:
            tags.append("range")
        if self.digits == "deva":
            tags.append("devanagari_digits")
        if max(self.price_min, self.price_max, self.struck_price or 0) >= 1000:
            tags.append("grouping")
        if self.trip_time:
            tags.append("eta_trip_time")
        return tags


@dataclass(frozen=True)
class Driver:
    name: str
    plate: str
    eta_min: int
    distance_text: str
    vehicle: str
    code: str

    def fields(self) -> dict[str, str]:
        return {"name": self.name, "plate": self.plate, "eta": str(self.eta_min), "dist": self.distance_text}


@dataclass(frozen=True)
class Brand:
    primary: RGB
    on_primary: RGB
    tint: RGB


@dataclass(frozen=True)
class DemoProvider:
    key: str
    name: str
    logo: str
    brand: Brand
    vehicle_classes: tuple[str, ...]
    fare_heading: str
    searching_heading: str
    assigned_heading: str
    cancelled_heading: str
    code_label: str
    fare_rows: tuple[FareRow, ...]
    booked_row: int
    driver: Driver
    cancel_from: Literal["searching", "driver_assigned"]
    fee_text: str
    channel_ongoing: str
    channel_alerts: str
    notifications: dict[str, tuple[str, str]] = field(default_factory=dict)

    @property
    def package(self) -> str:
        return f"demo.{self.key}"

    @property
    def wire_id(self) -> str:
        return f"demo_{self.key}"

    @property
    def booked(self) -> FareRow:
        return self.fare_rows[self.booked_row]

    def notification(
        self, event: str, driver: Driver | None = None, fare: int | None = None
    ) -> tuple[str, str]:
        title, text = self.notifications[event]
        values = (driver or self.driver).fields() | {
            "fare": str(fare if fare is not None else self.booked.price_min)
        }
        return title.format(**values), text.format(**values)


KESTREL = DemoProvider(
    key="kestrel",
    name="Kestrel Go",
    logo="K",
    brand=Brand(primary=(0, 105, 112), on_primary=(255, 255, 255), tint=(224, 242, 241)),
    vehicle_classes=("bike", "auto", "cab_economy"),
    fare_heading="Choose a ride",
    searching_heading="Finding a rider…",
    assigned_heading="Rider on the way",
    cancelled_heading="Trip cancelled",
    code_label="OTP",
    fare_rows=(
        FareRow("Bike", "bike", 112, 112, 2, struck_price=135, tagline="Beat the traffic"),
        FareRow("Auto", "auto", 180, 210, 4, tagline="No bargaining"),
        FareRow("Kestrel Mini", "cab_economy", 289, 289, 6, tagline="Compact AC cars"),
    ),
    booked_row=1,
    driver=Driver("Arjun", "XX 00 DM 1001", 4, "1.6 km", "Auto · Demo Motors", "4719"),
    cancel_from="searching",
    fee_text="Free to cancel while we search",
    channel_ongoing="kestrel_trip",
    channel_alerts="kestrel_alerts",
    notifications={
        "SEARCHING": ("Kestrel Go", "Finding a rider for your Auto…"),
        "ASSIGNED": ("Rider on the way", "{name} · {plate} · {eta} min away · {dist}"),
        "ARRIVING": ("Rider has arrived", "{name} is waiting at Riverside Gate. Share OTP 4719 to start."),
        "DRIVER_CANCELLED": (
            "Rider cancelled",
            "Sorry, your rider had to cancel. We're finding you another rider.",
        ),
        "NO_DRIVERS": ("No riders nearby", "We couldn't find a rider for this trip. Please try again."),
        "CANCELLED": ("Trip cancelled", "Your Kestrel Go trip was cancelled. No fee charged."),
        "TRIP_STARTED": ("Trip started", "Enjoy your ride with Kestrel Go."),
        "ENDED": ("Trip completed", "You have arrived. Pay ₹{fare} in Kestrel Go."),
    },
)

PEPPER = DemoProvider(
    key="pepper",
    name="Pepper Rides",
    logo="P",
    brand=Brand(primary=(163, 22, 33), on_primary=(255, 255, 255), tint=(252, 232, 232)),
    vehicle_classes=("auto", "cab_economy", "cab_premium"),
    fare_heading="Pick your ride",
    searching_heading="Searching for nearby drivers…",
    assigned_heading="Driver assigned",
    cancelled_heading="Booking cancelled",
    code_label="PIN",
    fare_rows=(
        FareRow("ऑटो", "auto", 172, 172, 3, digits="deva"),
        FareRow("Pepper Go", "cab_economy", 264, 264, 5, struck_price=299, tagline="Everyday cars"),
        FareRow("Pepper Prime Sedan", "cab_premium", 398, 398, 7, tagline="Top-rated drivers"),
    ),
    booked_row=0,
    driver=Driver("Ravi", "XX 00 DM 4821", 9, "2.8 km", "Auto · Demo Motors", "5302"),
    cancel_from="driver_assigned",
    fee_text="Cancellation fee up to ₹50 may apply",
    channel_ongoing="pepper_ride_status",
    channel_alerts="pepper_updates",
    notifications={
        "SEARCHING": ("Pepper Rides", "Searching for nearby drivers…"),
        "ASSIGNED": ("Driver assigned", "{name} · {plate} · arriving in {eta} min · {dist} away"),
        "ARRIVING": ("Driver has arrived", "{name} is at your pickup point. Your PIN is 5302."),
        "DRIVER_CANCELLED": ("Driver cancelled", "Your driver cancelled. Finding you a new driver…"),
        "NO_DRIVERS": ("No drivers found", "All drivers are busy right now. Please try again."),
        "CANCELLED": ("Booking cancelled", "Your Pepper Rides booking is cancelled. No charge applied."),
        "TRIP_STARTED": ("Ride started", "Have a safe ride!"),
        "ENDED": ("Ride completed", "Please pay ₹{fare} in Pepper Rides."),
    },
)

# Pepper re-matches with a much closer driver after its first driver cancels
# (assets/demo/pepper/driver_assigned_better.png).
PEPPER_BETTER_DRIVER = Driver("Imran", "XX 00 DM 7305", 3, "0.5 km", "Auto · Demo Motors", "5302")

TAMARIND = DemoProvider(
    key="tamarind",
    name="Tamarind Cabs",
    logo="T",
    brand=Brand(primary=(109, 62, 34), on_primary=(255, 255, 255), tint=(244, 234, 226)),
    vehicle_classes=("bike", "auto", "cab_economy", "cab_xl"),
    fare_heading="Choose a cab",
    searching_heading="Looking for a driver…",
    assigned_heading="Driver found",
    cancelled_heading="Ride cancelled",
    code_label="OTP",
    fare_rows=(
        FareRow("Moto", "bike", 104, 104, 3, tagline="Solo rides"),
        FareRow("Auto", "auto", 176, 176, 5, struck_price=205, tagline="Pay after the ride"),
        FareRow("Tamarind Cab", "cab_economy", 272, 272, 4, tagline="AC hatchbacks"),
        FareRow("Tamarind XL", "cab_xl", 1040, 1040, 8, tagline="Room for six"),
    ),
    booked_row=1,
    driver=Driver("Sunil", "XX 00 DM 2290", 2, "0.6 km", "Auto · Demo Motors", "6185"),
    cancel_from="searching",
    fee_text="Free to cancel while we search",
    channel_ongoing="tamarind_live",
    channel_alerts="tamarind_info",
    notifications={
        "SEARCHING": ("Tamarind Cabs", "ड्राइवर खोज रहे हैं · Looking for a driver…"),
        "ASSIGNED": ("ड्राइवर मिल गया · Driver found", "{name} · {plate} · {eta} मिनट दूर · {dist}"),
        "ARRIVING": ("ड्राइवर पहुँच गया · Driver arrived", "Your Tamarind cab is at the pickup point."),
        "DRIVER_CANCELLED": ("Driver cancelled", "ड्राइवर ने राइड रद्द की। Finding another driver…"),
        "NO_DRIVERS": ("No cabs available", "अभी कोई कैब उपलब्ध नहीं है। Please try again later."),
        "CANCELLED": ("राइड रद्द · Ride cancelled", "Your Tamarind Cabs booking was cancelled."),
        "TRIP_STARTED": ("यात्रा शुरू · Trip started", "Have a good trip with Tamarind Cabs."),
        "ENDED": ("Trip ended", "Please pay ₹{fare} in Tamarind Cabs."),
    },
)

PROVIDERS: tuple[DemoProvider, ...] = (KESTREL, PEPPER, TAMARIND)
BY_KEY: dict[str, DemoProvider] = {p.key: p for p in PROVIDERS}
BY_WIRE_ID: dict[str, DemoProvider] = {p.wire_id: p for p in PROVIDERS}
