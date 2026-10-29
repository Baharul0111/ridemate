"""Python port of the phone's Channel B FareParser rules (architecture §4.2 "FareParser", contracts §2.2), used to
find golden screens where the current `fare_parsers` entry disagrees with the owner's labels.

Per row: the vehicle label is the first `vehicle_labels` pattern (list order) found on a line; the row spans the
lines whose vertical centre lies between half a line above the label and `row_gap_ratio` label-heights below it
(never past the next label). Payable price = the tallest ₹ token in the row (rightmost on a tie); ₹ tokens under
`strike_height_ratio` × the tallest are struck through. ETA = `eta_pattern` group 1. ₹ OCR confusions (Z, R or %
directly before a digit) are normalised to ₹ before `price_token_pattern` runs (M5). All patterns run under RE2.

The Kotlin FareParser and its accuracy test stay authoritative; this port only decides which screens to show the
Template Doctor and checks that a proposed change fixes them without breaking screens that agreed before.
"""

from __future__ import annotations

import re
import statistics
from dataclasses import dataclass
from typing import Any

from ridemate_config.matching import pick_entry

from .samples import OcrFrame, OcrToken
from .templates import compiled

_DEVANAGARI = str.maketrans("०१२३४५६७८९", "0123456789")
_CONFUSED_RUPEE = re.compile(r"^[ZR%](?=[0-9०-९])")
_CURRENCY = re.compile(r"(?i)₹|\brs\.?|\binr|रु\.?|रू\.?")
_SPACES = re.compile(r"[\s  ]+")
_RANGE_SPLIT = re.compile(r"[-–—]")


@dataclass(frozen=True)
class ParsedRow:
    vehicle_class: str
    label_text: str
    price_min: int | None
    price_max: int | None
    has_struck: bool
    eta_min: int | None
    top: int


@dataclass(frozen=True)
class ParsedScreen:
    screen_type: str
    rows: tuple[ParsedRow, ...]


@dataclass
class _Line:
    tokens: list[OcrToken]
    text: str
    spans: list[tuple[int, int, OcrToken]]

    @property
    def top(self) -> int:
        return min(t.box[1] for t in self.tokens)

    @property
    def bottom(self) -> int:
        return max(t.box[3] for t in self.tokens)

    @property
    def center(self) -> float:
        return (self.top + self.bottom) / 2

    @property
    def height(self) -> float:
        return float(statistics.median(t.height for t in self.tokens)) or 1.0


def normalize_token_text(text: str) -> str:
    return _CONFUSED_RUPEE.sub("₹", text.strip())


def parse_price_text(text: str) -> tuple[int, int] | None:
    """'₹1,234' → (1234, 1234); 'Rs 110–130' → (110, 130); Devanagari digits by value; None if unparseable."""
    cleaned = _SPACES.sub("", _CURRENCY.sub("", text.translate(_DEVANAGARI)))
    parts = [p for p in _RANGE_SPLIT.split(cleaned) if p]
    if not 1 <= len(parts) <= 2:
        return None
    values: list[int] = []
    for part in parts:
        digits = part.replace(",", "")
        if not re.fullmatch(r"\d+(?:\.\d{1,2})?", digits):
            return None
        values.append(round(float(digits)))
    low, high = values[0], values[-1]
    return (low, high) if low <= high else None


def _lines(frame: OcrFrame) -> list[_Line]:
    grouped: dict[int, list[OcrToken]] = {}
    for tok in frame.tokens:
        grouped.setdefault(tok.line, []).append(tok)
    lines: list[_Line] = []
    for toks in grouped.values():
        toks = sorted(toks, key=lambda t: t.box[0])
        parts: list[str] = []
        spans: list[tuple[int, int, OcrToken]] = []
        pos = 0
        for tok in toks:
            text = normalize_token_text(tok.text)
            if parts:
                pos += 1
            spans.append((pos, pos + len(text), tok))
            parts.append(text)
            pos += len(text)
        lines.append(_Line(tokens=toks, text=" ".join(parts), spans=spans))
    return sorted(lines, key=lambda line: (line.top, line.tokens[0].box[0]))


def _label_for(line: _Line, labels: list[dict[str, Any]]) -> str | None:
    for rule in labels:
        if compiled(rule["pattern"]).search(line.text):
            return str(rule["vehicle_class"])
    return None


@dataclass(frozen=True)
class _Price:
    low: int
    high: int
    height: int
    right: int


def _prices_in(line: _Line, pattern: str) -> list[_Price]:
    out: list[_Price] = []
    for m in compiled(pattern).finditer(line.text):
        parsed = parse_price_text(m.group(0))
        toks = [tok for start, end, tok in line.spans if start < m.end() and end > m.start()]
        if parsed is None or not toks:
            continue
        out.append(_Price(parsed[0], parsed[1], max(t.height for t in toks), max(t.box[2] for t in toks)))
    return out


def _label_text(line: _Line, price_pattern: str, eta_pattern: str) -> str:
    covered: list[tuple[int, int]] = [
        (m.start(), m.end())
        for pattern in (price_pattern, eta_pattern)
        for m in compiled(pattern).finditer(line.text)
    ]
    keep = [
        tok.text
        for start, end, tok in line.spans
        if not any(start < c_end and end > c_start for c_start, c_end in covered)
    ]
    return " ".join(keep).strip()


def _screen_type(lines: list[_Line], keywords: dict[str, list[str]], has_priced_rows: bool) -> str:
    full = "\n".join(line.text for line in lines).lower()
    best, best_hits = None, 0
    for screen_type, words in keywords.items():
        hits = sum(1 for w in words if w.lower() in full)
        if hits > best_hits:
            best, best_hits = screen_type, hits
    if best is not None:
        return best
    return "fare_options" if has_priced_rows else "other"


def parse_screen(frame: OcrFrame, entry: dict[str, Any]) -> ParsedScreen:
    lines = _lines(frame)
    anchors = [(i, cls) for i, line in enumerate(lines) if (cls := _label_for(line, entry["vehicle_labels"]))]
    gap = float(entry["row_gap_ratio"])
    strike = float(entry["strike_height_ratio"])
    rows: list[ParsedRow] = []
    taken: set[int] = set()
    for n, (idx, cls) in enumerate(anchors):
        anchor = lines[idx]
        h = anchor.height
        upper = anchor.top - 0.5 * h
        lower = anchor.bottom + gap * h
        if n + 1 < len(anchors):
            lower = min(lower, lines[anchors[n + 1][0]].top - 0.25 * lines[anchors[n + 1][0]].height)
        members = [
            j for j, line in enumerate(lines) if j not in taken and (j == idx or upper <= line.center < lower)
        ]
        taken.update(members)
        prices = [p for j in members for p in _prices_in(lines[j], entry["price_token_pattern"])]
        low = high = None
        has_struck = False
        if prices:
            tallest = max(p.height for p in prices)
            payable = [p for p in prices if p.height >= strike * tallest]
            has_struck = len(payable) < len(prices)
            best = max(payable, key=lambda p: (p.height, p.right))
            low, high = best.low, best.high
        eta = None
        row_text = "\n".join(lines[j].text for j in members)
        m = compiled(entry["eta_pattern"]).search(row_text)
        if m and m.group(1):
            eta = int(m.group(1).translate(_DEVANAGARI))
        rows.append(
            ParsedRow(
                vehicle_class=cls,
                label_text=_label_text(anchor, entry["price_token_pattern"], entry["eta_pattern"]),
                price_min=low,
                price_max=high,
                has_struck=has_struck,
                eta_min=eta,
                top=anchor.top,
            )
        )
    screen_type = _screen_type(lines, entry["screen_keywords"], any(r.price_min is not None for r in rows))
    return ParsedScreen(screen_type=screen_type, rows=tuple(rows))


def parser_entry(payload: dict[str, Any], package: str, version_code: int | None) -> dict[str, Any] | None:
    entries = payload.get("apps", {}).get(package)
    return pick_entry(entries, version_code) if entries else None
