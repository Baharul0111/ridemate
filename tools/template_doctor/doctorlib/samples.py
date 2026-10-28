"""Inputs: owner Recorder exports of masked notification posts (architecture §4.19(a)) and masked golden captures
with labels + OCR token files (contracts §6). Only these owner-made files are ever read; nothing here touches
user data.

Recorder export (one JSON file per export, from the phone's share sheet):
    {"schema": "1.0", "kind": "recorder_posts", "posts": [
      {"package": "com.rapido.passenger", "version_code": 1234, "channel_id": "ride_status", "category": "status",
       "is_ongoing": true, "id": 7, "tag": null, "title": "<masked>", "text": "<masked>", "redacted": false,
       "removal_reason": null, "posted_at": "2026-10-28T10:00:00Z", "label": "ASSIGNED"}]}
A bare list of posts is accepted too, and camelCase keys (`packageName`, `versionCode`, `channelId`, `isOngoing`,
`removalReason`, `postedAt`/`timestamp`). `label` is the owner's optional ground truth: one of the template
events, or `OTHER` for a post that must match no template.

Text is masked on the phone before export. As defence in depth, every title/text is scrubbed again here
(phone numbers, OTP/PIN codes, number plates) before it is matched or shown to the model.
"""

from __future__ import annotations

import hashlib
import json
import re
from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

EVENTS: tuple[str, ...] = (
    "SEARCHING",
    "ASSIGNED",
    "ARRIVING",
    "DRIVER_CANCELLED",
    "NO_DRIVERS",
    "CANCELLED",
    "ENDED",
    "TRIP_STARTED",
)
OTHER = "OTHER"
_LABEL_ALIASES = {
    "other": OTHER,
    "none": OTHER,
    "not_ride": OTHER,
    "unknown": None,
    "": None,
}

APP_TO_PACKAGE: Mapping[str, str] = {
    "uber": "com.ubercab",
    "ola": "com.olacabs.customer",
    "rapido": "com.rapido.passenger",
    "demo_kestrel": "demo.kestrel",
    "demo_pepper": "demo.pepper",
    "demo_tamarind": "demo.tamarind",
}
ALLOWED_PACKAGES = frozenset(APP_TO_PACKAGE.values())
MAX_FIELD_CHARS = 600

# Mask tokens a Recorder export may contain. Typed ones are replaced by a neutral surrogate for matching, so a
# template written for the raw text the phone sees (e.g. a name group) is evaluated fairly on masked samples.
MASK_SURROGATES: Mapping[str, str] = {
    "<name>": "Asha",
    "<phone>": "9000000000",
    "<otp>": "4821",
    "<pin>": "4821",
    "<plate>": "AS01AB1234",
}
MASK_TOKEN_RE = re.compile(r"<(?:m|name|phone|otp|pin|plate)>", re.IGNORECASE)

_PHONE_RE = re.compile(r"(?<!\d)(?:\+?91[\s-]?)?[6-9]\d{4}[\s-]?\d{5}(?!\d)")
_OTP_RE = re.compile(r"(?i)\b(otp|pin|code)\b(\W{0,6})(\d{4,6})(?!\d)")
_PLATE_RE = re.compile(r"\b[A-Z]{2}[\s-]?\d{1,2}[\s-]?[A-Z]{1,3}[\s-]?\d{4}\b")


def scrub(text: str) -> str:
    """Re-mask anything that still looks like a phone number, OTP/PIN or number plate; cap the length."""
    out = _PHONE_RE.sub("<phone>", text)
    out = _OTP_RE.sub(lambda m: f"{m.group(1)}{m.group(2)}<otp>", out)
    out = _PLATE_RE.sub("<plate>", out)
    return out[:MAX_FIELD_CHARS]


def surrogate(text: str) -> str:
    """The text templates are matched against: typed mask tokens replaced by neutral surrogates."""
    out = text
    for token, value in MASK_SURROGATES.items():
        out = re.sub(re.escape(token), value, out, flags=re.IGNORECASE)
    return out


class InputError(ValueError):
    """An input file is missing or not in the documented format."""


def normalize_label(raw: Any) -> str | None:
    if raw is None:
        return None
    if not isinstance(raw, str):
        raise InputError(f"label must be a string, got {type(raw).__name__}")
    key = raw.strip()
    if key.lower() in _LABEL_ALIASES:
        return _LABEL_ALIASES[key.lower()]
    upper = key.upper()
    if upper in EVENTS or upper == OTHER:
        return upper
    raise InputError(f"unknown label {raw!r} (expected one of {', '.join(EVENTS)} or OTHER)")


@dataclass(frozen=True)
class PostSample:
    id: str
    package: str
    version_code: int | None
    channel_id: str | None
    category: str | None
    is_ongoing: bool | None
    title: str
    text: str
    redacted: bool
    label: str | None
    occurrences: int = 1
    source: str = ""

    @property
    def haystack(self) -> str:
        """`title + "\\n" + text` with mask surrogates, exactly what a template is matched against."""
        return surrogate(f"{self.title}\n{self.text}")

    @property
    def is_empty(self) -> bool:
        return self.redacted or not (self.title.strip() or self.text.strip())

    def for_model(self) -> dict[str, Any]:
        return {
            "sample_id": self.id,
            "package": self.package,
            "version_code": self.version_code,
            "channel_id": self.channel_id,
            "category": self.category,
            "is_ongoing": self.is_ongoing,
            "redacted": self.redacted,
            "title": self.title,
            "text": self.text,
            "owner_label": self.label,
            "occurrences": self.occurrences,
        }


def _pick(post: Mapping[str, Any], *names: str) -> Any:
    for name in names:
        if name in post:
            return post[name]
    return None


def _opt_str(value: Any, what: str) -> str | None:
    if value is None:
        return None
    if not isinstance(value, str):
        raise InputError(f"{what} must be a string")
    return value or None


def _opt_int(value: Any, what: str) -> int | None:
    if value is None:
        return None
    if isinstance(value, bool) or not isinstance(value, int):
        if isinstance(value, str) and value.strip().isdigit():
            return int(value)
        raise InputError(f"{what} must be an integer")
    return value


def _opt_bool(value: Any, what: str) -> bool | None:
    if value is None or isinstance(value, bool):
        return value
    raise InputError(f"{what} must be true/false")


def _sample_id(prefix: str, *parts: Any) -> str:
    digest = hashlib.sha256(json.dumps(parts, ensure_ascii=False, default=str).encode()).hexdigest()
    return f"{prefix}{digest[:10]}"


def parse_posts(doc: Any, source: str = "") -> list[PostSample]:
    """Recorder posts from one export document, de-duplicated (a countdown post repeats many times)."""
    if isinstance(doc, Mapping):
        posts = _pick(doc, "posts", "entries", "log")
        if not isinstance(posts, list):
            raise InputError(f"{source or 'export'}: no 'posts' list")
    elif isinstance(doc, list):
        posts = doc
    else:
        raise InputError(f"{source or 'export'}: expected an object with 'posts' or a list")

    merged: dict[str, PostSample] = {}
    for i, post in enumerate(posts):
        if not isinstance(post, Mapping):
            raise InputError(f"{source}: post {i} is not an object")
        where = f"{source}: post {i}"
        package = _opt_str(_pick(post, "package", "packageName", "pkg"), f"{where} package")
        if package not in ALLOWED_PACKAGES:
            continue  # the Recorder logs only the allowlisted packages; anything else is ignored unread
        title = scrub(_opt_str(_pick(post, "title"), f"{where} title") or "")
        text = scrub(_opt_str(_pick(post, "text", "body"), f"{where} text") or "")
        version_code = _opt_int(_pick(post, "version_code", "versionCode"), f"{where} version_code")
        channel_id = _opt_str(_pick(post, "channel_id", "channelId"), f"{where} channel_id")
        category = _opt_str(_pick(post, "category"), f"{where} category")
        is_ongoing = _opt_bool(_pick(post, "is_ongoing", "isOngoing", "ongoing"), f"{where} is_ongoing")
        redacted = bool(_opt_bool(_pick(post, "redacted", "is_redacted", "redaction"), f"{where} redacted"))
        label = normalize_label(_pick(post, "label", "event", "owner_label"))
        sid = _sample_id(
            "n", package, version_code, channel_id, category, is_ongoing, title, text, redacted, label
        )
        if sid in merged:
            prev = merged[sid]
            merged[sid] = PostSample(**{**prev.__dict__, "occurrences": prev.occurrences + 1})
            continue
        merged[sid] = PostSample(
            id=sid,
            package=package,
            version_code=version_code,
            channel_id=channel_id,
            category=category,
            is_ongoing=is_ongoing,
            title=title,
            text=text,
            redacted=redacted,
            label=label,
            source=source,
        )
    return list(merged.values())


def load_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as e:
        raise InputError(f"{path}: not found") from e
    except json.JSONDecodeError as e:
        raise InputError(f"{path}: not valid JSON ({e.msg} at line {e.lineno})") from e


def load_recorder_exports(paths: Iterable[Path]) -> list[PostSample]:
    merged: dict[str, PostSample] = {}
    for path in paths:
        for sample in parse_posts(load_json(path), path.name):
            prev = merged.get(sample.id)
            if prev is not None:
                sample = PostSample(**{**prev.__dict__, "occurrences": prev.occurrences + sample.occurrences})
            merged[sample.id] = sample
    return sorted(merged.values(), key=lambda s: (s.package, s.id))


# ---- golden captures --------------------------------------------------------------------------------------


@dataclass(frozen=True)
class OcrToken:
    text: str
    box: tuple[int, int, int, int]  # l, t, r, b in image pixels
    line: int
    block: int = 0

    @property
    def height(self) -> int:
        return self.box[3] - self.box[1]


@dataclass(frozen=True)
class OcrFrame:
    width: int
    height: int
    tokens: tuple[OcrToken, ...]
    engine: str = ""


@dataclass(frozen=True)
class LabelRow:
    vehicle_class: str
    label: str | None
    price_min: int | None
    price_max: int | None
    eta_min: int | None
    struck_price: int | None = None


@dataclass(frozen=True)
class ScreenSample:
    id: str
    sha256: str
    app: str
    package: str
    screen_type: str | None
    version_code: int | None
    rows: tuple[LabelRow, ...]
    frame: OcrFrame | None
    tags: tuple[str, ...] = field(default_factory=tuple)

    def for_model(self, *, include_tokens: bool = True) -> dict[str, Any]:
        """The owner's masked capture as the model sees it: the labels' structure and the OCR lines (price tokens
        included, as the parser reads them). `read_sample` adds the labelled values the parser must reproduce.
        None of this is ever written to report.md or proposal.json, which carry sample ids and reason codes only."""
        out: dict[str, Any] = {
            "sample_id": self.id,
            "app": self.app,
            "package": self.package,
            "version_code": self.version_code,
            "labelled_screen_type": self.screen_type,
            "labelled_rows": [
                {
                    "vehicle_class": r.vehicle_class,
                    "label": r.label,
                    "has_struck_price": r.struck_price is not None,
                }
                for r in self.rows
            ],
            "tags": list(self.tags),
        }
        if include_tokens and self.frame is not None:
            lines: dict[int, list[OcrToken]] = {}
            for tok in self.frame.tokens:
                lines.setdefault(tok.line, []).append(tok)
            out["frame"] = {"width": self.frame.width, "height": self.frame.height}
            out["ocr_lines"] = [
                {
                    "line": line,
                    "text": " ".join(t.text for t in sorted(toks, key=lambda t: t.box[0])),
                    "top": min(t.box[1] for t in toks),
                    "token_heights": [t.height for t in sorted(toks, key=lambda t: t.box[0])],
                }
                for line, toks in sorted(lines.items(), key=lambda kv: min(t.box[1] for t in kv[1]))
            ]
        return out


def _int_or_none(value: Any) -> int | None:
    return value if isinstance(value, int) and not isinstance(value, bool) else None


def parse_ocr(doc: Any, source: str = "") -> OcrFrame:
    if not isinstance(doc, Mapping) or not isinstance(doc.get("tokens"), list):
        raise InputError(f"{source}: OCR file needs a 'tokens' list")
    width, height = _int_or_none(doc.get("width")), _int_or_none(doc.get("height"))
    if not width or not height:
        raise InputError(f"{source}: OCR file needs integer width and height")
    tokens: list[OcrToken] = []
    for i, tok in enumerate(doc["tokens"]):
        box = tok.get("box") if isinstance(tok, Mapping) else None
        text = tok.get("text") if isinstance(tok, Mapping) else None
        if not isinstance(text, str) or not isinstance(box, list) or len(box) != 4:
            raise InputError(f"{source}: token {i} needs text and a 4-number box")
        if not all(isinstance(v, int | float) and not isinstance(v, bool) for v in box):
            raise InputError(f"{source}: token {i} box must be numbers")
        line = _int_or_none(tok.get("line"))
        tokens.append(
            OcrToken(
                text=scrub(text),
                box=(round(box[0]), round(box[1]), round(box[2]), round(box[3])),
                line=line if line is not None else -1 - i,
                block=_int_or_none(tok.get("block")) or 0,
            )
        )
    return OcrFrame(width=width, height=height, tokens=tuple(tokens), engine=str(doc.get("engine", "")))


def _label_row(raw: Any, where: str) -> LabelRow:
    if not isinstance(raw, Mapping) or not isinstance(raw.get("vehicle_class"), str):
        raise InputError(f"{where}: each row needs a vehicle_class")
    return LabelRow(
        vehicle_class=raw["vehicle_class"],
        label=raw.get("label") if isinstance(raw.get("label"), str) else None,
        price_min=_int_or_none(raw.get("price_min")),
        price_max=_int_or_none(raw.get("price_max")),
        eta_min=_int_or_none(raw.get("eta_min")),
        struck_price=_int_or_none(raw.get("struck_price")),
    )


def load_golden(labels_path: Path, ocr_dirs: Sequence[Path]) -> tuple[list[ScreenSample], list[str]]:
    """Labelled screens with the first OCR token file found for each sha256 (dirs in priority order).
    Returns (screens, notes) where notes list screens skipped for lack of OCR."""
    doc = load_json(labels_path)
    screens_raw = doc.get("screens") if isinstance(doc, Mapping) else None
    if not isinstance(screens_raw, list):
        raise InputError(f"{labels_path}: no 'screens' list")
    screens: list[ScreenSample] = []
    notes: list[str] = []
    for i, raw in enumerate(screens_raw):
        where = f"{labels_path.name}: screen {i}"
        if not isinstance(raw, Mapping):
            raise InputError(f"{where}: not an object")
        sha = raw.get("sha256")
        app = raw.get("app")
        if not isinstance(sha, str) or not re.fullmatch(r"[0-9a-f]{64}", sha):
            raise InputError(f"{where}: sha256 must be 64 lower-case hex characters")
        if app not in APP_TO_PACKAGE:
            raise InputError(f"{where}: unknown app {app!r}")
        rows = tuple(_label_row(r, where) for r in raw.get("rows") or [])
        frame = None
        for directory in ocr_dirs:
            candidate = directory / f"{sha}.json"
            if candidate.exists():
                frame = parse_ocr(load_json(candidate), candidate.name)
                break
        sample = ScreenSample(
            id=f"s{sha[:10]}",
            sha256=sha,
            app=app,
            package=APP_TO_PACKAGE[app],
            screen_type=raw.get("screen_type") if isinstance(raw.get("screen_type"), str) else None,
            version_code=_int_or_none(raw.get("version_code")),
            rows=rows,
            frame=frame,
            tags=tuple(t for t in raw.get("tags") or [] if isinstance(t, str)),
        )
        if frame is None:
            notes.append(f"{sample.id}: no OCR tokens found; parser not checked")
        screens.append(sample)
    return screens, notes
