"""A small Python reference of the §4.3 verification gate. It is used only to check that the fixtures'
`expect` blocks are self-consistent. The phone's VerificationGate (Kotlin) is the real implementation.

Channel B here is deliberately simple. For each Channel A option it looks at the OCR tokens inside A's box
(± 4 % of the frame) and then:
- normalises ₹ misreads (Z/R/% before a digit),
- picks the payable token (the tallest price token, with tokens under `strike_height_ratio` × tallest treated
  as struck, and the rightmost winning ties),
- takes the label from the top text line left of the price,
- takes the pickup ETA from the first `eta_pattern` match in reading order.

A row is VERIFIED only if rules (a), (b), (c) and (e) all hold. Rule (d) needs two frames; fixtures have one.
"""

from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass
from typing import Any

from .paths import use_config_package

use_config_package()

from ridemate_config.keys import KEYS_BY_NAME  # noqa: E402
from ridemate_config.regex import compile_re2  # noqa: E402
from ridemate_config.validation import load_default  # noqa: E402

TOLERANCE = 0.04
_DEVA_DIGITS = str.maketrans("०१२३४५६७८९", "0123456789")
_RUPEE_MISREAD = re.compile(r"^[ZR%](?=[0-9०-९])")
PACKAGE_OF = {
    "uber": "com.ubercab",
    "ola": "com.olacabs.customer",
    "rapido": "com.rapido.passenger",
    "demo_kestrel": "demo.kestrel",
    "demo_pepper": "demo.pepper",
    "demo_tamarind": "demo.tamarind",
}


@dataclass(frozen=True)
class Tok:
    text: str
    box: tuple[int, int, int, int]
    line: int

    @property
    def cx(self) -> float:
        return (self.box[0] + self.box[2]) / 2

    @property
    def cy(self) -> float:
        return (self.box[1] + self.box[3]) / 2

    @property
    def height(self) -> int:
        return self.box[3] - self.box[1]


def _tokens(ocr: dict[str, Any]) -> list[Tok]:
    return [Tok(_RUPEE_MISREAD.sub("₹", t["text"]), tuple(t["box"]), t["line"]) for t in ocr["tokens"]]


def _digits(text: str) -> list[int]:
    return [int(n.replace(",", "")) for n in re.findall(r"[0-9][0-9,]*", text.translate(_DEVA_DIGITS))]


def _norm_label(text: str | None) -> str:
    return " ".join(unicodedata.normalize("NFC", text or "").lower().replace("…", "").split())


def _sanity_ok(cls: str, price: int, trip_km: float | None, bands: dict[str, Any]) -> bool:
    """contracts §2.3 as core SanityBands applies it: `trip_km` is straight-line km, d = km × road_factor."""
    if not bands["price_min_inr"] <= price <= bands["price_max_inr"]:
        return False
    if trip_km is None or trip_km <= 0:
        return True
    d = trip_km * bands["road_factor"]
    lo, hi = bands["per_km_inr"].get(cls, bands["per_km_inr"]["other"])
    allowance = bands["base_allowance_inr"]
    return lo * d - allowance <= price <= hi * d + allowance


class ReferenceGate:
    def __init__(self) -> None:
        self.fare = load_default(KEYS_BY_NAME["fare_parsers"])
        self.bands = load_default(KEYS_BY_NAME["sanity_bands"])

    def _parser(self, app: str) -> dict[str, Any]:
        return self.fare["apps"][PACKAGE_OF[app]][0]

    def _classify(self, cfg: dict[str, Any], label: str) -> str | None:
        for rule in cfg["vehicle_labels"]:
            if compile_re2(rule["pattern"]).search(label):
                return rule["vehicle_class"]
        return None

    def channel_b_row(
        self, cfg: dict[str, Any], toks: list[Tok], bbox: list[int], w: int, h: int
    ) -> dict[str, Any]:
        """Parser row = the tokens whose centres lie in A's box; the payable token must sit in the box ± 4 %
        (it always does here, so a box that misses the row simply finds no price → UNREADABLE)."""
        x0, y0, x1, y1 = bbox[0] / 1000 * w, bbox[1] / 1000 * h, bbox[2] / 1000 * w, bbox[3] / 1000 * h
        inside = [t for t in toks if x0 <= t.cx <= x1 and y0 <= t.cy <= y1]
        price_rx = compile_re2(cfg["price_token_pattern"])
        prices = [t for t in inside if price_rx.search(t.text)]
        if not prices:
            return {"price": None}
        tallest = max(t.height for t in prices)
        payable_candidates = [t for t in prices if t.height >= cfg["strike_height_ratio"] * tallest]
        payable = max(payable_candidates, key=lambda t: (t.height, t.box[2]))
        tol_x, tol_y = TOLERANCE * w, TOLERANCE * h
        if not (x0 - tol_x <= payable.cx <= x1 + tol_x and y0 - tol_y <= payable.cy <= y1 + tol_y):
            return {"price": None}
        rest = [t for t in inside if t not in prices and t.box[2] <= payable.box[0]]
        lines: dict[int, list[Tok]] = {}
        for t in rest:
            lines.setdefault(t.line, []).append(t)
        label = ""
        if lines:
            top_line = min(lines.values(), key=lambda ts: min(t.box[1] for t in ts))
            label = " ".join(t.text for t in sorted(top_line, key=lambda t: t.box[0]))
        reading = " ".join(
            t.text for t in sorted((t for t in inside if t not in prices), key=lambda t: (t.line, t.box[0]))
        )
        eta_m = compile_re2(cfg["eta_pattern"]).search(reading)
        return {
            "price": _digits(price_rx.search(payable.text).group(0)),
            "label": label,
            "eta": int(eta_m.group(1).translate(_DEVA_DIGITS)) if eta_m else None,
        }

    def driver_facts(self, cfg: dict[str, Any], toks: list[Tok]) -> tuple[int | None, str | None]:
        ordered = sorted(toks, key=lambda t: (t.line, t.box[0]))
        text_by_line: dict[int, str] = {}
        for t in ordered:
            text_by_line[t.line] = (text_by_line.get(t.line, "") + " " + t.text).strip()
        eta = None
        eta_rx = compile_re2(cfg["eta_pattern"])
        for line in text_by_line.values():
            if any(a in line.lower() for a in cfg["driver_eta_anchors"]):
                m = eta_rx.search(line)
                if m:
                    eta = int(m.group(1).translate(_DEVA_DIGITS))
                    break
        dist_m = compile_re2(cfg["driver_distance_pattern"]).search("\n".join(text_by_line.values()))
        return eta, (f"{dist_m.group(1)} {dist_m.group(2)}" if dist_m else None)

    def evaluate(self, label: dict[str, Any], ocr: dict[str, Any], read: dict[str, Any]) -> dict[str, Any]:
        """Outcome per labelled row (matched to A's options by index) and for the driver facts."""
        cfg = self._parser(label["app"])
        toks = _tokens(ocr)
        app_ok = read.get("app") == label["app"]
        rows: list[str] = []
        for i in range(len(label["rows"])):
            option = read["options"][i] if i < len(read["options"]) else None
            if not app_ok or option is None or option.get("bbox_0_1000") is None:
                rows.append("UNREADABLE")
                continue
            b = self.channel_b_row(cfg, toks, option["bbox_0_1000"], ocr["width"], ocr["height"])
            a_price = (
                [option["price_min"]]
                if option["price_min"] == option["price_max"]
                else [option["price_min"], option["price_max"]]
            )
            ok_b = b["price"] == a_price
            a_label = _norm_label(option.get("label_text"))
            ok_c = bool(b.get("label")) and _norm_label(b["label"]) == a_label
            ok_c = ok_c and self._classify(cfg, b["label"]) == option.get("vehicle_class")
            ok_e = all(
                _sanity_ok(option["vehicle_class"], p, label.get("trip_km"), self.bands) for p in a_price
            )
            rows.append("VERIFIED" if ok_b and ok_c and ok_e else "UNREADABLE")
        out: dict[str, Any] = {"rows": rows}
        if label["driver_eta_min"] is not None:
            eta, dist = self.driver_facts(cfg, toks)
            out["driver_eta"] = (
                "VERIFIED"
                if app_ok and eta is not None and read.get("driver_eta_min") == eta
                else "SEE_IN_APP"
            )
            out["driver_distance"] = (
                "VERIFIED"
                if app_ok and dist is not None and read.get("driver_distance_text") == dist
                else "SEE_IN_APP"
            )
        return out

    @staticmethod
    def satisfies(outcome: dict[str, Any], expect: dict[str, Any]) -> bool:
        """Does an outcome meet an `expect` block? NOT_WRONG accepts UNREADABLE or a VERIFIED true value
        (here VERIFIED implies A and B agreed on the labelled value, so it can't be a wrong number)."""
        got_rows, want_rows = outcome.get("rows", []), expect.get("rows", [])
        if len(got_rows) != len(want_rows):
            return False
        if any(want != "NOT_WRONG" and got != want for got, want in zip(got_rows, want_rows, strict=True)):
            return False
        return all(outcome.get(k) == expect[k] for k in ("driver_eta", "driver_distance") if k in expect)
