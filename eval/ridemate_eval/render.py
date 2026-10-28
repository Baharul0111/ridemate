"""A drawing surface that records ideal OCR as it draws: one token per word, exact ink boxes, line ids.

Each `text()` call is one OCR line. Words are drawn one at a time so every token box is exactly where its
glyphs landed, which is what a perfect ML Kit would report.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Literal

from PIL import Image, ImageDraw

from .fonts import Weight, font, font_for

RGB = tuple[int, int, int]
Box = tuple[int, int, int, int]
Align = Literal["left", "right", "center"]

FRAME_W = 1080
FRAME_H = 2400


@dataclass(frozen=True)
class Theme:
    name: str
    bg: RGB
    surface: RGB
    text: RGB
    muted: RGB
    divider: RGB
    map_bg: RGB
    map_road: RGB
    map_block: RGB
    danger: RGB = (179, 38, 30)
    on_danger: RGB = (255, 255, 255)


LIGHT = Theme(
    name="light",
    bg=(255, 255, 255),
    surface=(255, 255, 255),
    text=(17, 17, 17),
    muted=(80, 80, 80),
    divider=(222, 222, 222),
    map_bg=(236, 238, 232),
    map_road=(255, 255, 255),
    map_block=(214, 224, 206),
)
DARK = Theme(
    name="dark",
    bg=(18, 18, 18),
    surface=(30, 30, 30),
    text=(245, 245, 245),
    muted=(190, 190, 190),
    divider=(66, 66, 66),
    map_bg=(40, 44, 48),
    map_road=(70, 74, 80),
    map_block=(52, 60, 56),
    danger=(242, 108, 98),
    on_danger=(20, 20, 20),
)


@dataclass
class Token:
    text: str
    box: Box
    line: int
    block: int

    def to_json(self) -> dict[str, object]:
        return {"text": self.text, "box": list(self.box), "line": self.line, "block": self.block}

    @property
    def center(self) -> tuple[float, float]:
        return ((self.box[0] + self.box[2]) / 2, (self.box[1] + self.box[3]) / 2)


def union(boxes: list[Box]) -> Box:
    return (
        min(b[0] for b in boxes),
        min(b[1] for b in boxes),
        max(b[2] for b in boxes),
        max(b[3] for b in boxes),
    )


@dataclass
class Canvas:
    theme: Theme
    width: int = FRAME_W
    height: int = FRAME_H
    tokens: list[Token] = field(default_factory=list)

    def __post_init__(self) -> None:
        self.img = Image.new("RGB", (self.width, self.height), self.theme.bg)
        self.draw = ImageDraw.Draw(self.img)
        self._line = 0
        self._block = 0

    def new_block(self) -> int:
        self._block += 1
        return self._block

    def text_width(self, text: str, size: int, weight: Weight = "regular") -> float:
        words = text.split(" ")
        space = font("latin", weight, size).getlength(" ")
        return sum(font_for(w, weight, size).getlength(w) for w in words) + space * (len(words) - 1)

    def text(
        self,
        x: float,
        baseline: float,
        text: str,
        size: int,
        *,
        weight: Weight = "regular",
        color: RGB | None = None,
        align: Align = "left",
        block: int | None = None,
        strike: bool = False,
        record: bool = True,
    ) -> Box:
        """Draw one line of text with its baseline at `baseline`; returns the union ink box."""
        color = color or self.theme.text
        width = self.text_width(text, size, weight)
        start = x - width if align == "right" else x - width / 2 if align == "center" else x
        line = self._next_line() if record else -1
        block = block if block is not None else self.new_block()
        space = font("latin", weight, size).getlength(" ")
        cursor = start
        boxes: list[Box] = []
        for word in text.split(" "):
            f = font_for(word, weight, size)
            self.draw.text((cursor, baseline), word, font=f, fill=color, anchor="ls")
            box = tuple(int(v) for v in self.draw.textbbox((cursor, baseline), word, font=f, anchor="ls"))
            boxes.append(box)
            if record:
                self.tokens.append(Token(word, box, line, block))
            cursor += f.getlength(word) + space
        run = union(boxes)
        if strike:
            mid = (run[1] + run[3]) // 2 + 1
            self.draw.line([(run[0] - 4, mid), (run[2] + 4, mid)], fill=color, width=max(2, size // 14))
        return run

    def _next_line(self) -> int:
        self._line += 1
        return self._line

    def rect(
        self, box: Box, fill: RGB | None = None, outline: RGB | None = None, width: int = 1, radius: int = 0
    ) -> None:
        if radius:
            self.draw.rounded_rectangle(box, radius=radius, fill=fill, outline=outline, width=width)
        else:
            self.draw.rectangle(box, fill=fill, outline=outline, width=width)

    def circle(
        self,
        cx: float,
        cy: float,
        r: float,
        fill: RGB | None = None,
        outline: RGB | None = None,
        width: int = 1,
    ) -> None:
        self.draw.ellipse((cx - r, cy - r, cx + r, cy + r), fill=fill, outline=outline, width=width)

    def line(self, points: list[tuple[float, float]], fill: RGB, width: int = 1) -> None:
        self.draw.line(points, fill=fill, width=width, joint="curve")

    def polygon(self, points: list[tuple[float, float]], fill: RGB) -> None:
        self.draw.polygon(points, fill=fill)
