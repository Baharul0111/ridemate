"""Indian number formatting as ride apps print it: ₹1,23,456 grouping, ranges, Devanagari digits."""

from __future__ import annotations

from typing import Literal

Digits = Literal["latin", "deva"]

_TO_DEVA = str.maketrans("0123456789", "०१२३४५६७८९")


def group_indian(n: int) -> str:
    """1234 → '1,234'; 123456 → '1,23,456' (last three digits, then pairs)."""
    s = str(n)
    if len(s) <= 3:
        return s
    head, tail = s[:-3], s[-3:]
    pairs = []
    while len(head) > 2:
        pairs.insert(0, head[-2:])
        head = head[:-2]
    return ",".join([head, *pairs, tail])


def digits(text: str, system: Digits) -> str:
    return text.translate(_TO_DEVA) if system == "deva" else text


def amount(n: int, system: Digits = "latin", *, grouped: bool = True) -> str:
    return digits(group_indian(n) if grouped else str(n), system)


def price_text(
    price_min: int,
    price_max: int,
    system: Digits = "latin",
    *,
    currency: str = "₹",
    range_sep: str = "–",
    grouped: bool = True,
) -> str:
    """'₹110', '₹110–130', 'Rs 1,040', '₹१७२'. A range repeats the currency only when range_sep has spaces."""
    low = f"{currency}{amount(price_min, system, grouped=grouped)}"
    if price_max == price_min:
        return low
    high = amount(price_max, system, grouped=grouped)
    if range_sep.strip() != range_sep:
        high = f"{currency}{high}"
    return f"{low}{range_sep}{high}"
