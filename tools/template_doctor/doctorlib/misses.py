"""Rule-miss detection.

Notification misses (per recorded post, against `notification_templates`):
- `unmatched`           a non-empty post no template matches (and the owner did not label it OTHER)
- `wrong_event`         the owner labelled the post with event E but the first matching template says F ≠ E
- `false_match`         the owner labelled the post OTHER but a template matches it
- `redacted_unmatched`  a redacted/empty post no structural `redacted` rule recognises (reported, not regex-fixable)

Parser disagreements (per golden screen with OCR tokens, against `fare_parsers`): issue codes
`row_missing`, `price_mismatch`, `eta_mismatch`, `extra_row` (each suffixed with `:<vehicle_class>`) and
`screen_type`. Issue codes carry no fare values, so reports built from them never print a price.
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass, field
from typing import Any

from .fare_rules import ParsedScreen, parse_screen, parser_entry
from .samples import OTHER, PostSample, ScreenSample
from .templates import Classification, classify


@dataclass(frozen=True)
class Miss:
    sample_id: str
    kind: str
    package: str
    version_code: int | None
    expected: str | None = None  # expected event (notifications) or screen type
    got: str | None = None  # template id / event that matched, or parsed screen type
    issues: tuple[str, ...] = field(default_factory=tuple)

    def as_dict(self) -> dict[str, Any]:
        return {
            "sample_id": self.sample_id,
            "kind": self.kind,
            "package": self.package,
            "version_code": self.version_code,
            "expected": self.expected,
            "got": self.got,
            "issues": list(self.issues),
        }


def expected_event(sample: PostSample, baseline: Classification | None) -> str | None:
    """What the post should classify as: the owner's label, else whatever the baseline config says."""
    if sample.label is not None:
        return sample.label
    return baseline.event if baseline is not None else None


def notification_miss(sample: PostSample, cls: Classification | None) -> Miss | None:
    got = None if cls is None else (cls.template_id or f"redacted:{cls.event}")
    base = {"sample_id": sample.id, "package": sample.package, "version_code": sample.version_code}
    if sample.is_empty:
        if cls is None and sample.label != OTHER:
            return Miss(kind="redacted_unmatched", expected=sample.label, **base)
        return None
    if sample.label == OTHER:
        return Miss(kind="false_match", expected=OTHER, got=got, **base) if cls is not None else None
    if cls is None:
        return Miss(kind="unmatched", expected=sample.label, **base)
    if sample.label is not None and cls.event != sample.label:
        return Miss(kind="wrong_event", expected=sample.label, got=got, **base)
    return None


def notification_misses(payload: dict[str, Any], samples: Sequence[PostSample]) -> list[Miss]:
    out: list[Miss] = []
    for sample in samples:
        miss = notification_miss(sample, classify(payload, sample))
        if miss is not None:
            out.append(miss)
    return out


# ---- parser -----------------------------------------------------------------------------------------------


def _norm(text: str | None) -> str:
    return " ".join((text or "").lower().split())


def screen_issues(screen: ScreenSample, parsed: ParsedScreen) -> tuple[str, ...]:
    issues: list[str] = []
    if (
        screen.screen_type
        and screen.screen_type not in ("unreadable",)
        and parsed.screen_type != screen.screen_type
    ):
        issues.append("screen_type")
    remaining = list(parsed.rows)
    for row in screen.rows:
        same_class = [r for r in remaining if r.vehicle_class == row.vehicle_class]
        if not same_class:
            issues.append(f"row_missing:{row.vehicle_class}")
            continue
        named = [r for r in same_class if row.label and _norm(r.label_text) == _norm(row.label)]
        match = (named or same_class)[0]
        remaining.remove(match)
        if (match.price_min, match.price_max) != (row.price_min, row.price_max):
            issues.append(f"price_mismatch:{row.vehicle_class}")
        if row.eta_min is not None and match.eta_min != row.eta_min:
            issues.append(f"eta_mismatch:{row.vehicle_class}")
    if screen.rows:
        issues.extend(f"extra_row:{r.vehicle_class}" for r in remaining if r.price_min is not None)
    return tuple(issues)


def parse_golden(payload: dict[str, Any], screen: ScreenSample) -> ParsedScreen | None:
    if screen.frame is None:
        return None
    entry = parser_entry(payload, screen.package, screen.version_code)
    if entry is None:
        return None
    return parse_screen(screen.frame, entry)


def screen_miss(payload: dict[str, Any], screen: ScreenSample) -> Miss | None:
    parsed = parse_golden(payload, screen)
    if parsed is None:
        return None
    issues = screen_issues(screen, parsed)
    if not issues:
        return None
    return Miss(
        sample_id=screen.id,
        kind="parser_disagrees",
        package=screen.package,
        version_code=screen.version_code,
        expected=screen.screen_type,
        got=parsed.screen_type,
        issues=issues,
    )


def parser_misses(payload: dict[str, Any], screens: Sequence[ScreenSample]) -> list[Miss]:
    return [m for s in screens if (m := screen_miss(payload, s)) is not None]
