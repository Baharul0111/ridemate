"""Screen builders for the fictional providers: fare options, searching, driver, cancel flow, cancelled.

Every builder returns a `Screen`: the image, the ideal OCR tokens, the labelled rows with their pixel boxes,
the "Show me" target boxes and the facts a perfect reader would report. The look is invented (plain shapes,
invented colours) and every screen carries the DEMO banner, so nothing resembles a real ride app.
"""

from __future__ import annotations

import random
import zlib
from dataclasses import dataclass, field
from typing import Literal

from .numbers import amount, digits, price_text
from .providers import DEMO_BANNER, DEMO_BANNER_COLOR, FAKE_PHONE, DemoProvider, Driver, FareRow, Trip
from .render import FRAME_H, FRAME_W, LIGHT, RGB, Box, Canvas, Theme, Token

StatusTime = "10:24"
SCREEN_TYPES = ("fare_options", "searching", "driver_assigned", "cancel_flow", "cancelled")


@dataclass
class LabelledRow:
    row: FareRow
    label_text: str  # exactly as displayed (long labels may be truncated with "…")
    box: Box  # the row's container in pixels
    price_token: str
    struck_token: str | None
    eta_token: str


@dataclass
class Screen:
    provider: DemoProvider
    screen_type: str
    canvas: Canvas
    rows: list[LabelledRow] = field(default_factory=list)
    targets: dict[str, Box] = field(default_factory=dict)
    driver: Driver | None = None
    fee_text: str | None = None
    extra_tags: list[str] = field(default_factory=list)

    @property
    def tokens(self) -> list[Token]:
        return self.canvas.tokens


@dataclass(frozen=True)
class FareStyle:
    """Layout knobs the golden generator varies; the defaults are the demo look."""

    theme: Theme = LIGHT
    struck_position: Literal["below", "left"] = "below"
    eta_position: Literal["sub", "right"] = "sub"
    currency: str = "₹"
    range_sep: str = "–"
    compact: bool = False  # many-option layout (up to 12 rows)
    injection_banner: str | None = None
    book_label: str = "Book now"


DEFAULT_STYLE = FareStyle()


def _seed(*parts: str) -> int:
    return zlib.crc32("|".join(parts).encode())


def _mix(a: RGB, b: RGB, t: float) -> RGB:
    return (round(a[0] + (b[0] - a[0]) * t), round(a[1] + (b[1] - a[1]) * t), round(a[2] + (b[2] - a[2]) * t))


def _chrome(c: Canvas, p: DemoProvider) -> None:
    """Fake status bar, DEMO banner and app bar: the top 268 px of every screen."""
    t = c.theme
    block = c.new_block()
    c.text(48, 58, StatusTime, 32, weight="bold", block=block)
    for i in range(4):
        h = 10 + i * 7
        c.rect((850 + i * 14, 62 - h, 860 + i * 14, 62), fill=t.text)
    c.text(926, 60, "5G", 28, weight="bold", block=block)
    c.rect((972, 34, 1020, 60), outline=t.text, width=3, radius=4)
    c.rect((976, 38, 1006, 56), fill=t.text)
    c.rect((1021, 42, 1026, 52), fill=t.text)
    c.rect((0, 84, FRAME_W, 148), fill=DEMO_BANNER_COLOR)
    c.text(FRAME_W / 2, 128, DEMO_BANNER, 32, weight="bold", color=(255, 255, 255), align="center")
    c.circle(92, 208, 40, fill=p.brand.primary)
    c.text(92, 224, p.logo, 44, weight="bold", color=p.brand.on_primary, align="center")
    c.text(152, 224, p.name, 44, weight="bold")
    for i in range(3):
        c.rect((968, 190 + i * 14, 1020, 196 + i * 14), fill=t.text)


def _map(
    c: Canvas, p: DemoProvider, top: int, bottom: int, *, dim: bool = False, seed: str = ""
) -> tuple[tuple[int, int], tuple[int, int]]:
    """An invented street map with a route; returns (pickup, drop) pixel positions."""
    t = c.theme
    rng = random.Random(_seed(p.key, seed, str(top), str(bottom)))
    c.rect((0, top, FRAME_W, bottom), fill=t.map_bg)
    for _ in range(7):
        x0, y0 = rng.randrange(0, FRAME_W - 200), rng.randrange(top, bottom - 120)
        c.rect(
            (x0, y0, x0 + rng.randrange(120, 260), y0 + rng.randrange(60, 140)), fill=t.map_block, radius=12
        )
    for _ in range(5):
        y = rng.randrange(top + 30, bottom - 30)
        c.line(
            [(0, y), (FRAME_W, y + rng.randrange(-60, 60))], fill=t.map_road, width=rng.choice((14, 20, 26))
        )
    for _ in range(4):
        x = rng.randrange(40, FRAME_W - 40)
        c.line([(x, top), (x + rng.randrange(-80, 80), bottom)], fill=t.map_road, width=rng.choice((14, 20)))
    pickup = (rng.randrange(160, 420), rng.randrange(top + 80, bottom - 80))
    drop = (rng.randrange(660, 940), rng.randrange(top + 80, bottom - 80))
    mid = ((pickup[0] + drop[0]) // 2, rng.randrange(top + 60, bottom - 60))
    c.line([pickup, mid, drop], fill=p.brand.primary, width=10)
    c.circle(*pickup, 18, fill=(20, 140, 60), outline=(255, 255, 255), width=5)
    c.rect(
        (drop[0] - 16, drop[1] - 16, drop[0] + 16, drop[1] + 16),
        fill=t.text,
        outline=(255, 255, 255),
        width=5,
    )
    if dim:
        overlay = c.img.crop((0, top, FRAME_W, bottom))
        shade = overlay.point(lambda v: v * 45 // 100)
        c.img.paste(shade, (0, top))
    return pickup, drop


def _sheet(c: Canvas, top: int) -> None:
    c.rect((0, top, FRAME_W, FRAME_H + 60), fill=c.theme.surface, radius=48)
    c.rect((490, top + 22, 590, top + 32), fill=c.theme.divider, radius=5)


def _vehicle_icon(c: Canvas, cx: int, cy: int, r: int, vehicle_class: str, color: RGB, bg: RGB) -> None:
    c.circle(cx, cy, r, fill=bg)
    s = r / 44
    if vehicle_class == "bike":
        c.circle(cx - 16 * s, cy + 10 * s, 10 * s, outline=color, width=max(2, round(4 * s)))
        c.circle(cx + 16 * s, cy + 10 * s, 10 * s, outline=color, width=max(2, round(4 * s)))
        c.line(
            [(cx - 16 * s, cy + 10 * s), (cx, cy - 8 * s), (cx + 16 * s, cy + 10 * s)],
            fill=color,
            width=max(2, round(4 * s)),
        )
    elif vehicle_class == "auto":
        c.polygon(
            [
                (cx - 22 * s, cy + 12 * s),
                (cx - 12 * s, cy - 16 * s),
                (cx + 22 * s, cy - 16 * s),
                (cx + 22 * s, cy + 12 * s),
            ],
            fill=color,
        )
        c.circle(cx - 12 * s, cy + 14 * s, 6 * s, fill=bg)
        c.circle(cx + 14 * s, cy + 14 * s, 6 * s, fill=bg)
    else:
        width = 28 if vehicle_class in ("cab_xl", "other") else 24
        c.rect(
            (round(cx - width * s), round(cy - 10 * s), round(cx + width * s), round(cy + 10 * s)),
            fill=color,
            radius=max(2, round(6 * s)),
        )
        c.circle(cx - 14 * s, cy + 12 * s, 6 * s, fill=bg)
        c.circle(cx + 14 * s, cy + 12 * s, 6 * s, fill=bg)


def _fit(c: Canvas, text: str, size: int, max_w: float, weight: Literal["regular", "bold"] = "bold") -> str:
    """Truncate with an ellipsis the way apps do when a label is too long for its slot."""
    if c.text_width(text, size, weight) <= max_w:
        return text
    words = text.split(" ")
    while len(words) > 1:
        words.pop()
        candidate = " ".join(words) + "…"
        if c.text_width(candidate, size, weight) <= max_w:
            return candidate
    return words[0]


def _eta_text(row: FareRow) -> str:
    return f"{digits(str(row.eta_min), row.digits)} min"


def _row(
    c: Canvas, p: DemoProvider, row: FareRow, top: int, height: int, style: FareStyle, selected: bool
) -> LabelledRow:
    t = c.theme
    s = height / 190
    box = (32, top, FRAME_W - 32, top + height - 8)
    if selected:
        tint = p.brand.tint if t.name == "light" else _mix(t.surface, p.brand.primary, 0.35)
        c.rect(box, fill=tint, outline=p.brand.primary, width=3, radius=24)
    else:
        c.line([(184, box[3] + 4), (FRAME_W - 48, box[3] + 4)], fill=t.divider, width=2)
    icon_bg = p.brand.tint if t.name == "light" else _mix(t.surface, p.brand.primary, 0.25)
    _vehicle_icon(c, 112, top + height // 2 - 4, round(44 * s), row.vehicle_class, p.brand.primary, icon_bg)
    block = c.new_block()
    price = price_text(
        row.price_min, row.price_max, row.digits, currency=style.currency, range_sep=style.range_sep
    )
    price_size, label_size, sub_size, struck_size = round(52 * s), round(44 * s), round(32 * s), round(34 * s)
    right = FRAME_W - 72
    price_box = c.text(
        right, top + round(86 * s), price, price_size, weight="bold", align="right", block=block
    )
    struck = None
    struck_left = price_box[0]
    if row.struck_price is not None:
        struck = f"{style.currency}{amount(row.struck_price, row.digits)}"
        if style.struck_position == "left":
            sb = c.text(
                price_box[0] - 22,
                top + round(86 * s),
                struck,
                struck_size,
                color=t.muted,
                align="right",
                block=block,
                strike=True,
            )
            struck_left = sb[0]
        else:
            c.text(
                right,
                top + round(140 * s),
                struck,
                struck_size,
                color=t.muted,
                align="right",
                block=block,
                strike=True,
            )
    label_x = 184
    label = _fit(c, row.label, label_size, struck_left - 28 - label_x)
    c.text(label_x, top + round(82 * s), label, label_size, weight="bold", block=block)
    eta = _eta_text(row)
    sub_parts = [eta] if style.eta_position == "sub" else []
    if row.trip_time:
        sub_parts.append(row.trip_time)
    if row.tagline and not style.compact:
        sub_parts.append(row.tagline)
    if style.eta_position == "right":
        c.text(right, top + round(140 * s), eta, sub_size, color=t.muted, align="right", block=block)
    if sub_parts:
        sub = _fit(
            c,
            " · ".join(sub_parts),
            sub_size,
            (right - 260 if style.eta_position == "right" else right) - label_x,
            "regular",
        )
        c.text(label_x, top + round(138 * s), sub, sub_size, color=t.muted, block=block)
    return LabelledRow(row, label, box, price, struck, eta)


def _footer(c: Canvas, p: DemoProvider, label: str) -> None:
    c.text(60, 2196, "Pay by cash or UPI", 32, color=c.theme.muted)
    c.rect((60, 2230, FRAME_W - 60, 2350), fill=p.brand.primary, radius=60)
    c.text(FRAME_W / 2, 2306, label, 44, weight="bold", color=p.brand.on_primary, align="center")


def fare_options(
    p: DemoProvider,
    rows: tuple[FareRow, ...] | list[FareRow],
    trip: Trip,
    style: FareStyle = DEFAULT_STYLE,
    *,
    selected: int | None = 0,
) -> Screen:
    c = Canvas(style.theme)
    _chrome(c, p)
    compact = style.compact or len(rows) > 6
    map_bottom = 440 if compact else 900
    _map(c, p, 268, map_bottom, seed="fare")
    sheet_top = map_bottom - 40
    _sheet(c, sheet_top)
    c.text(60, sheet_top + 100, p.fare_heading, 48, weight="bold")
    c.text(60, sheet_top + 156, trip.route_text, 32, color=c.theme.muted)
    y = sheet_top + 200
    if style.injection_banner:
        c.rect((32, y, FRAME_W - 32, y + 96), fill=_mix(c.theme.surface, (255, 196, 0), 0.25), radius=18)
        c.text(60, y + 62, _fit(c, style.injection_banner, 30, FRAME_W - 120, "regular"), 30)
        y += 116
    available = 2160 - y
    height = min(190, available // max(len(rows), 1))
    screen = Screen(p, "fare_options", c)
    for i, row in enumerate(rows):
        screen.rows.append(_row(c, p, row, y + i * height, height, style, selected == i))
    _footer(c, p, style.book_label)
    return screen


def searching(p: DemoProvider, trip: Trip, theme: Theme = LIGHT) -> Screen:
    c = Canvas(theme)
    _chrome(c, p)
    pickup, _ = _map(c, p, 268, 1300, seed="searching")
    for r in (60, 120, 180, 240):
        c.circle(*pickup, r, outline=p.brand.primary, width=4)
    _sheet(c, 1260)
    c.text(60, 1370, p.searching_heading, 48, weight="bold")
    c.text(60, 1430, f"{p.booked.label} · {trip.route_text}", 34, color=c.theme.muted)
    c.rect((60, 1480, FRAME_W - 60, 1496), fill=c.theme.divider, radius=8)
    c.rect((60, 1480, 620, 1496), fill=p.brand.primary, radius=8)
    c.text(60, 1580, "Hang tight, this usually takes a minute", 32, color=c.theme.muted)
    return Screen(p, "searching", c)


def driver_assigned(p: DemoProvider, driver: Driver, theme: Theme = LIGHT) -> Screen:
    c = Canvas(theme)
    _chrome(c, p)
    pickup, _ = _map(c, p, 268, 1150, seed="assigned")
    car = (pickup[0] + 140, pickup[1] - 40)
    c.line([car, pickup], fill=p.brand.primary, width=8)
    _vehicle_icon(c, car[0], car[1], 34, "auto", (255, 255, 255), p.brand.primary)
    _sheet(c, 1110)
    c.text(60, 1220, p.assigned_heading, 48, weight="bold")
    c.text(
        60,
        1310,
        f"{driver.eta_min} min away",
        60,
        weight="bold",
        color=p.brand.primary if theme.name == "light" else theme.text,
    )
    c.text(60, 1372, f"{driver.distance_text} from pickup", 36, color=c.theme.muted)
    c.line([(60, 1408), (FRAME_W - 60, 1408)], fill=c.theme.divider, width=2)
    c.circle(116, 1490, 56, fill=p.brand.tint)
    c.text(116, 1508, driver.name[0], 48, weight="bold", color=p.brand.primary, align="center")
    c.text(196, 1478, driver.name, 44, weight="bold")
    c.text(196, 1528, driver.vehicle, 32, color=c.theme.muted)
    c.rect((680, 1442, FRAME_W - 60, 1534), outline=c.theme.text, width=4, radius=12)
    c.text((680 + FRAME_W - 60) / 2, 1504, driver.plate, 38, weight="bold", align="center")
    c.rect((60, 1590, 380, 1680), fill=p.brand.tint, radius=20)
    c.text(220, 1650, f"{p.code_label} {driver.code}", 40, weight="bold", color=(17, 17, 17), align="center")
    c.text(60, 1770, f"Call {FAKE_PHONE}", 34)
    for i, label in enumerate(("Share trip", "Safety")):
        x0 = 60 + i * 490
        c.rect((x0, 1830, x0 + 470, 1930), outline=c.theme.divider, width=3, radius=50)
        c.text(x0 + 235, 1894, label, 36, weight="bold", align="center")
    return Screen(p, "driver_assigned", c, driver=driver)


def cancel_flow(p: DemoProvider, theme: Theme = LIGHT) -> Screen:
    c = Canvas(theme)
    _chrome(c, p)
    _map(c, p, 268, 1000, dim=True, seed="cancel")
    _sheet(c, 960)
    c.text(60, 1080, "Cancel your ride?", 48, weight="bold")
    c.text(60, 1140, "Tell us why you're cancelling", 34, color=c.theme.muted)
    reasons = ("Booked another ride", "Driver is too far", "Changed my plans", "Other")
    for i, reason in enumerate(reasons):
        cy = 1240 + i * 108
        c.circle(92, cy, 22, outline=p.brand.primary if i == 0 else c.theme.muted, width=4)
        if i == 0:
            c.circle(92, cy, 11, fill=p.brand.primary)
        c.text(140, cy + 13, reason, 38)
    c.text(60, 1700, p.fee_text, 34, color=c.theme.muted)
    button = (60, 1780, FRAME_W - 60, 1910)
    c.rect(button, fill=c.theme.danger, radius=65)
    c.text(FRAME_W / 2, 1862, "Cancel ride", 46, weight="bold", color=c.theme.on_danger, align="center")
    c.rect((60, 1950, FRAME_W - 60, 2080), outline=c.theme.text, width=3, radius=65)
    c.text(FRAME_W / 2, 2031, "Keep my ride", 44, weight="bold", align="center")
    return Screen(p, "cancel_flow", c, targets={"cancel_button": button}, fee_text=p.fee_text)


def cancelled(p: DemoProvider, trip: Trip, theme: Theme = LIGHT) -> Screen:
    c = Canvas(theme)
    _chrome(c, p)
    c.circle(540, 760, 150, fill=p.brand.tint)
    c.line([(480, 700), (600, 820)], fill=p.brand.primary, width=22)
    c.line([(600, 700), (480, 820)], fill=p.brand.primary, width=22)
    c.text(540, 1050, p.cancelled_heading, 56, weight="bold", align="center")
    c.text(540, 1120, "No cancellation fee was charged.", 36, color=c.theme.muted, align="center")
    c.text(540, 1180, trip.route_text, 32, color=c.theme.muted, align="center")
    c.rect((60, 2200, FRAME_W - 60, 2330), fill=p.brand.primary, radius=65)
    c.text(FRAME_W / 2, 2282, "Book a new ride", 44, weight="bold", color=p.brand.on_primary, align="center")
    return Screen(p, "cancelled", c)
