"""Bundled OFL fonts (eval/fonts/README.md), always laid out with Pillow's BASIC engine.

BASIC layout keeps PNG bytes identical whether or not libraqm/fribidi happen to be installed. The
trade-off is no complex Devanagari shaping, so rendered Hindi is limited to text that needs none: ऑटो, बाइक,
कैब, दूर and the digits ०-९. Words with a left-side matra or a conjunct (मिनट, ड्राइवर, रद्द) only ever
appear in notification text, which is never rendered.
"""

from __future__ import annotations

from functools import cache
from typing import Literal

from PIL import ImageFont

from .paths import FONTS_DIR

Weight = Literal["regular", "bold"]
Script = Literal["latin", "deva"]

_FILES: dict[tuple[Script, Weight], str] = {
    ("latin", "regular"): "NotoSans-Regular.ttf",
    ("latin", "bold"): "NotoSans-Bold.ttf",
    ("deva", "regular"): "Mukta-Regular.ttf",
    ("deva", "bold"): "Mukta-Bold.ttf",
}

DEVANAGARI = range(0x0900, 0x0980)
# Characters that must not be rendered: BASIC layout can't reorder or conjoin them.
UNSHAPED_DEVANAGARI = frozenset({"\N{DEVANAGARI VOWEL SIGN I}", "\N{DEVANAGARI SIGN VIRAMA}"})


def script_of(word: str) -> Script:
    return "deva" if any(ord(c) in DEVANAGARI for c in word) else "latin"


@cache
def font(script: Script, weight: Weight, size: int) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(
        str(FONTS_DIR / _FILES[(script, weight)]), size, layout_engine=ImageFont.Layout.BASIC
    )


def font_for(word: str, weight: Weight, size: int) -> ImageFont.FreeTypeFont:
    if UNSHAPED_DEVANAGARI.intersection(word):
        raise ValueError(f"{word!r} needs complex shaping; keep it out of rendered screens")
    return font(script_of(word), weight, size)
