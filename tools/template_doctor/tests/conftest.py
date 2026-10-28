from __future__ import annotations

import copy
import json
import sys
from pathlib import Path
from typing import Any

import pytest

TOOL_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(TOOL_DIR))

from ridemate_api.interfaces import AiStatus, ApiError, Principal, Purpose, Reservation
from ridemate_api.settings import PRICE_CACHED_INPUT_PER_M, PRICE_INPUT_PER_M, PRICE_OUTPUT_PER_M
from ridemate_api.store import StoreUnavailable

from doctorlib import paths
from doctorlib.samples import OcrFrame, OcrToken, ScreenSample, parse_posts

KESTREL = "demo.kestrel"


@pytest.fixture(scope="session")
def shipped_templates() -> dict[str, Any]:
    return json.loads((paths.DEFAULTS_DIR / "notification_templates.json").read_text(encoding="utf-8"))


@pytest.fixture(scope="session")
def shipped_parsers() -> dict[str, Any]:
    return json.loads((paths.DEFAULTS_DIR / "fare_parsers.json").read_text(encoding="utf-8"))


@pytest.fixture
def templates(shipped_templates: dict[str, Any]) -> dict[str, Any]:
    return copy.deepcopy(shipped_templates)


@pytest.fixture
def parsers(shipped_parsers: dict[str, Any]) -> dict[str, Any]:
    return copy.deepcopy(shipped_parsers)


def post(title: str, text: str, label: str | None = None, **extra: Any) -> dict[str, Any]:
    return {"package": KESTREL, "version_code": 10, "title": title, "text": text, "label": label, **extra}


# Masked Recorder posts for the fictional Kestrel Go provider (its templates are pinned to the demo script).
RECORDER_POSTS = [
    post("Kestrel Go", "Finding a rider", "SEARCHING"),
    post("Rider on the way", "AS 01 AB 1234 · 4 min away · 1.2 km", "ASSIGNED"),
    post("Rider has arrived", "Meet at pickup", "ARRIVING"),
    post("Trip cancelled", "Your Kestrel Go trip was cancelled", "CANCELLED"),
    post("Kestrel Go", "Your rider <name> is 3 min away", "ASSIGNED"),  # new wording: a miss
    post("Kestrel Go", "Weekend offer: 20% off", "OTHER"),
    post("", "", None, redacted=True, channel_id="kestrel_other", is_ongoing=False),  # redacted, no rule
]


@pytest.fixture
def posts() -> list[Any]:
    return parse_posts({"schema": "1.0", "posts": RECORDER_POSTS}, "test.json")


def by_text(samples: list[Any], needle: str) -> Any:
    return next(s for s in samples if needle in s.text)


def _tok(text: str, box: tuple[int, int, int, int], line: int) -> OcrToken:
    return OcrToken(text=text, box=box, line=line)


def kestrel_frame(*, cab_label: str = "Kestrel Mini", bike_label: str = "Bike") -> OcrFrame:
    tokens = (
        _tok("Choose", (60, 300, 200, 340), 1),
        _tok("a", (210, 300, 230, 340), 1),
        _tok("ride", (240, 300, 330, 340), 1),
        _tok(bike_label, (60, 1060, 180, 1100), 10),
        _tok("₹135", (700, 1065, 790, 1095), 10),  # struck: 30 px < 0.8 × 50 px
        _tok("₹112", (820, 1055, 960, 1105), 10),
        _tok("2", (60, 1110, 80, 1140), 11),
        _tok("min", (90, 1110, 160, 1140), 11),
        _tok("away", (170, 1110, 260, 1140), 11),
        _tok("Auto", (60, 1250, 160, 1290), 20),
        _tok("₹180–210", (780, 1245, 960, 1295), 20),
        _tok("4", (60, 1300, 80, 1330), 21),
        _tok("mins", (90, 1300, 180, 1330), 21),
        _tok(cab_label, (60, 1440, 300, 1480), 30),
        _tok("₹289", (820, 1435, 960, 1485), 30),
        _tok("6", (60, 1490, 80, 1520), 31),
        _tok("min", (90, 1490, 160, 1520), 31),
    )
    return OcrFrame(width=1080, height=2400, tokens=tokens, engine="synthetic")


def kestrel_screen(sid: str = "sA", **frame_kwargs: Any) -> ScreenSample:
    from doctorlib.samples import LabelRow

    return ScreenSample(
        id=sid,
        sha256=sid.ljust(64, "0"),
        app="demo_kestrel",
        package=KESTREL,
        screen_type="fare_options",
        version_code=None,
        rows=(
            LabelRow("bike", "Bike", 112, 112, 2, 135),
            LabelRow("auto", "Auto", 180, 210, 4),
            LabelRow("cab_economy", frame_kwargs.get("cab_label", "Kestrel Mini"), 289, 289, 6),
        ),
        frame=kestrel_frame(**frame_kwargs),
    )


class FakeSpend:
    """SpendMeter double: records reservations, settles at the real token prices, can refuse or fail."""

    def __init__(self, *, summary: dict[str, Any] | None = None, refuse: ApiError | None = None) -> None:
        self._summary = summary or {}
        self.refuse = refuse
        self.summary_fails = False
        self.reserved: list[Reservation] = []
        self.released: list[str] = []
        self.charged = 0.0

    def reserve(
        self, *, purpose: Purpose, max_usd: float, principal: Principal | None, request_id: str
    ) -> Reservation:
        if self.refuse is not None:
            raise self.refuse
        r = Reservation(
            id=request_id, purpose=purpose, reserved_usd=max_usd, shard="s0", request_id=request_id
        )
        self.reserved.append(r)
        return r

    def settle(
        self, reservation: Reservation, *, input_tokens: int, cached_input_tokens: int, output_tokens: int
    ) -> float:
        cost = (
            (input_tokens - cached_input_tokens) * PRICE_INPUT_PER_M
            + cached_input_tokens * PRICE_CACHED_INPUT_PER_M
            + output_tokens * PRICE_OUTPUT_PER_M
        ) / 1_000_000
        self.charged += cost
        return cost

    def keep(self, reservation: Reservation) -> float:
        self.charged += reservation.reserved_usd
        return reservation.reserved_usd

    def release(self, reservation: Reservation) -> None:
        self.released.append(reservation.id)

    def ai_status(self, principal: Principal | None) -> AiStatus:
        return AiStatus(True)

    def record_compute(self, **_: Any) -> float:
        return 0.0

    def summary(self) -> dict[str, Any]:
        if self.summary_fails:
            raise StoreUnavailable("down")
        return self._summary
