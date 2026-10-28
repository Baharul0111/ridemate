"""Validation and application of the model's proposals. Nothing the model proposes reaches the staged config
unless it compiles under RE2 (with the RE2/J portability rules), matches every intended sample and no negative
sample, and keeps the regression gate green.
"""

from __future__ import annotations

import copy
import re
from collections.abc import Sequence
from dataclasses import dataclass, field
from typing import Any

from ridemate_config.matching import pick_entry
from ridemate_config.regex import PatternError, check_pattern

from .samples import EVENTS, PostSample
from .templates import Classification, classify, compiled, conditions_hold, pattern_matches

MAX_PATTERN_CHARS = 400
# A mask token written literally in a pattern (but not the `(?P<name>…)` group syntax).
LITERAL_MASK_RE = re.compile(r"(?<!\(\?P)<(?:m|name|phone|otp|pin|plate)>", re.IGNORECASE)
TEMPLATE_GROUPS = frozenset({"eta", "dist", "plate", "name"})
TEMPLATE_ID_RE = re.compile(r"^[a-z0-9_]+(\.[a-z0-9_]+)+$")
PACKAGE_SHORT = {
    "com.ubercab": "uber",
    "com.olacabs.customer": "ola",
    "com.rapido.passenger": "rapido",
    "demo.kestrel": "kestrel",
    "demo.pepper": "pepper",
    "demo.tamarind": "tamarind",
}
VEHICLE_CLASSES = ("bike", "auto", "cab_economy", "cab_premium", "cab_xl", "other")
SCREEN_TYPES = ("fare_options", "searching", "driver_assigned", "cancel_flow", "cancelled", "home", "other")
# Parser fields the model may change, with the RE2 rules their schema enforces.
PARSER_PATTERN_FIELDS: dict[str, tuple[frozenset[str], int]] = {
    "price_token_pattern": (frozenset(), 0),
    "eta_pattern": (frozenset(), 1),
    "driver_distance_pattern": (frozenset(), 2),
}
PARSER_FIELDS = (
    "vehicle_label",
    *PARSER_PATTERN_FIELDS,
    "strike_height_ratio",
    "row_gap_ratio",
    "screen_keywords",
    "driver_eta_anchors",
)


class ProposalError(ValueError):
    """The proposal is rejected; the message is returned to the model so it can try again."""


@dataclass
class ProposalRecord:
    kind: str  # template | parser
    package: str
    min_version_code: int
    summary: dict[str, Any]
    rationale: str
    accepted: bool
    errors: list[str] = field(default_factory=list)
    fixes: list[str] = field(default_factory=list)  # sample ids this proposal fixes

    def as_dict(self) -> dict[str, Any]:
        return {
            "kind": self.kind,
            "package": self.package,
            "min_version_code": self.min_version_code,
            "proposal": self.summary,
            "rationale": self.rationale,
            "accepted": self.accepted,
            "errors": self.errors,
            "fixes": self.fixes,
        }


def check_regex(pattern: Any, *, allowed_groups: frozenset[str], min_groups: int = 0) -> None:
    if not isinstance(pattern, str) or not pattern.strip():
        raise ProposalError("pattern must be a non-empty string")
    if len(pattern) > MAX_PATTERN_CHARS:
        raise ProposalError(f"pattern is longer than {MAX_PATTERN_CHARS} characters")
    if LITERAL_MASK_RE.search(pattern):
        raise ProposalError(
            "pattern contains a mask token such as <m> or <name>; on the phone the real text is there, "
            "so match it with a tolerant group instead"
        )
    try:
        check_pattern(pattern, allowed_groups=allowed_groups, min_groups=min_groups)
    except PatternError as e:
        raise ProposalError(str(e)) from None
    if compiled(pattern).search("") is not None:
        raise ProposalError("pattern matches the empty string, so it would match every post")


def _entry_for_edit(payload: dict[str, Any], package: str, min_version_code: int) -> dict[str, Any]:
    """The entry with exactly `min_version_code`, created as a copy of the entry serving that version if absent
    (new ride-app versions get their own entry; older versions keep theirs)."""
    entries = payload.get("apps", {}).get(package)
    if not entries:
        raise ProposalError(f"package {package!r} has no entries in this config")
    if isinstance(min_version_code, bool) or not isinstance(min_version_code, int) or min_version_code < 0:
        raise ProposalError("min_version_code must be a non-negative integer")
    for entry in entries:
        if entry["min_version_code"] == min_version_code:
            return entry
    entry = copy.deepcopy(pick_entry(entries, min_version_code))
    entry["min_version_code"] = min_version_code
    entry.pop("max_known_version_code", None)
    if "templates" in entry:
        # Template ids are unique across the whole payload (config/validate.py), so the copies get new ids.
        taken = _all_template_ids(payload)
        for tpl in entry["templates"]:
            tpl["id"] = _versioned_id(tpl["id"], min_version_code, taken)
            taken.add(tpl["id"])
    entries.append(entry)
    entries.sort(key=lambda e: e["min_version_code"])
    return entry


def _all_template_ids(payload: dict[str, Any]) -> set[str]:
    return {t["id"] for es in payload.get("apps", {}).values() for e in es for t in e.get("templates", [])}


def _versioned_id(template_id: str, min_version_code: int, taken: set[str]) -> str:
    """`rapido.assigned.v1` copied into the entry for versionCode 1234 → `rapido.assigned.v1_vc1234`."""
    stem = f"{template_id}_vc{min_version_code}"
    candidate, n = stem, 1
    while candidate in taken:
        n += 1
        candidate = f"{stem}_{n}"
    return candidate


def _new_template_id(payload: dict[str, Any], package: str, event: str, min_version_code: int) -> str:
    existing = _all_template_ids(payload)
    stem = f"{PACKAGE_SHORT.get(package, 'app')}.{event.lower()}.doctor_v{min_version_code}"
    n = 1
    while f"{stem}_{n}" in existing:
        n += 1
    return f"{stem}_{n}"


@dataclass(frozen=True)
class TemplateProposal:
    package: str
    min_version_code: int
    event: str
    pattern: str
    channel_id: str | None = None
    ongoing: bool | None = None
    before_template_id: str | None = None


def apply_template(payload: dict[str, Any], proposal: TemplateProposal) -> tuple[dict[str, Any], str]:
    """A deep copy of `payload` with the template inserted; returns (payload, new template id)."""
    staged = copy.deepcopy(payload)
    entry = _entry_for_edit(staged, proposal.package, proposal.min_version_code)
    tpl_id = _new_template_id(staged, proposal.package, proposal.event, proposal.min_version_code)
    template = {
        "id": tpl_id,
        "event": proposal.event,
        "pattern": proposal.pattern,
        "channel_id": proposal.channel_id,
        "ongoing": proposal.ongoing,
    }
    templates = entry["templates"]
    if proposal.before_template_id is None:
        templates.append(template)
    else:
        ids = [t["id"] for t in templates]
        # In an entry the Doctor just copied for a new versionCode, the old id names its renamed copy.
        copied = _versioned_id(proposal.before_template_id, proposal.min_version_code, set())
        anchor = next((i for i in (proposal.before_template_id, copied) if i in ids), None)
        if anchor is None:
            raise ProposalError(f"before_template_id {proposal.before_template_id!r} is not in that entry")
        templates.insert(ids.index(anchor), template)
    return staged, tpl_id


def validate_template_proposal(
    proposal: TemplateProposal,
    *,
    staged: dict[str, Any],
    baseline: dict[str, Any],
    posts_by_id: dict[str, PostSample],
    positive_ids: Sequence[str],
    negative_ids: Sequence[str],
) -> tuple[dict[str, Any], list[str]]:
    """Returns (new staged payload, fixed sample ids) or raises ProposalError listing every problem."""
    if proposal.event not in EVENTS:
        raise ProposalError(f"event must be one of {', '.join(EVENTS)}")
    if proposal.channel_id is not None and (
        not isinstance(proposal.channel_id, str) or not proposal.channel_id
    ):
        raise ProposalError("channel_id must be null or a non-empty string")
    if proposal.ongoing is not None and not isinstance(proposal.ongoing, bool):
        raise ProposalError("ongoing must be null or true/false")
    check_regex(proposal.pattern, allowed_groups=TEMPLATE_GROUPS)
    if not positive_ids:
        raise ProposalError("positive_sample_ids must name at least one sample the template is for")

    problems: list[str] = []
    positives = _samples(posts_by_id, positive_ids, proposal.package, problems)
    explicit_negatives = _samples(posts_by_id, negative_ids, proposal.package, problems)
    if problems:
        raise ProposalError("; ".join(problems))

    tpl = {"pattern": proposal.pattern, "channel_id": proposal.channel_id, "ongoing": proposal.ongoing}
    for sample in positives:
        if sample.is_empty:
            problems.append(f"{sample.id} is redacted/empty; regex templates never see it")
        elif sample.label not in (None, proposal.event):
            problems.append(f"{sample.id} is labelled {sample.label}, not {proposal.event}")
        elif not template_matches_sample(tpl, sample):
            problems.append(f"does not match positive sample {sample.id}")

    positive_set = {p.id for p in positives}
    negatives: dict[str, tuple[PostSample, str]] = {
        s.id: (s, "explicit negative") for s in explicit_negatives
    }
    for sample in posts_by_id.values():
        if sample.package != proposal.package or sample.is_empty or sample.id in negatives:
            continue
        expected = _expected(sample, classify(baseline, sample))
        if expected is not None and expected != proposal.event:
            negatives[sample.id] = (sample, f"expected {expected}")
    for sample, why in negatives.values():
        if sample.id in positive_set:
            problems.append(f"{sample.id} is both positive and negative")
        elif template_matches_sample(tpl, sample):
            problems.append(f"matches negative sample {sample.id} ({why})")
    if problems:
        raise ProposalError("; ".join(problems))

    new_staged, _ = apply_template(staged, proposal)
    fixed: list[str] = []
    for sample in positives:
        got = classify(new_staged, sample)
        if got is None or got.event != proposal.event:
            what = "nothing" if got is None else f"{got.template_id} ({got.event})"
            problems.append(
                f"{sample.id} is served by {what} after the insert (an earlier template wins, or its version "
                "entry is a different one); use before_template_id or the sample's version"
            )
        else:
            fixed.append(sample.id)
    if problems:
        raise ProposalError("; ".join(problems))
    return new_staged, fixed


def template_matches_sample(tpl: dict[str, Any], sample: PostSample) -> bool:
    return conditions_hold(tpl, sample.channel_id, sample.is_ongoing) and pattern_matches(
        tpl["pattern"], sample.haystack
    )


def _expected(sample: PostSample, baseline: Classification | None) -> str | None:
    if sample.label is not None:
        return sample.label
    return baseline.event if baseline is not None else None


def _samples(by_id: dict[str, Any], ids: Sequence[str], package: str, problems: list[str]) -> list[Any]:
    out = []
    for sid in ids:
        sample = by_id.get(sid) if isinstance(sid, str) else None
        if sample is None:
            problems.append(f"unknown sample id {sid!r}")
        elif sample.package != package:
            problems.append(f"{sid} belongs to {sample.package}, not {package}")
        else:
            out.append(sample)
    return out


# ---- parser -----------------------------------------------------------------------------------------------


def apply_parser_change(
    payload: dict[str, Any], package: str, min_version_code: int, field_name: str, value: Any
) -> tuple[dict[str, Any], dict[str, Any]]:
    """A deep copy of `payload` with the change applied; returns (payload, summary of the change)."""
    if field_name not in PARSER_FIELDS:
        raise ProposalError(f"field must be one of {', '.join(PARSER_FIELDS)}")
    staged = copy.deepcopy(payload)
    entry = _entry_for_edit(staged, package, min_version_code)
    if field_name == "vehicle_label":
        if not isinstance(value, dict):
            raise ProposalError("vehicle_label value must be {pattern, vehicle_class, position?}")
        if value.get("vehicle_class") not in VEHICLE_CLASSES:
            raise ProposalError(f"vehicle_class must be one of {', '.join(VEHICLE_CLASSES)}")
        check_regex(value.get("pattern"), allowed_groups=frozenset())
        position = value.get("position", "first")
        rule = {"pattern": value["pattern"], "vehicle_class": value["vehicle_class"]}
        if position == "first":
            entry["vehicle_labels"].insert(0, rule)
        elif position == "last":
            entry["vehicle_labels"].append(rule)
        else:
            raise ProposalError("position must be 'first' or 'last'")
        return staged, {"field": field_name, "value": {**rule, "position": position}}
    if field_name in PARSER_PATTERN_FIELDS:
        groups, min_groups = PARSER_PATTERN_FIELDS[field_name]
        check_regex(value, allowed_groups=groups, min_groups=min_groups)
        entry[field_name] = value
        return staged, {"field": field_name, "value": value}
    if field_name in ("strike_height_ratio", "row_gap_ratio"):
        if isinstance(value, bool) or not isinstance(value, int | float):
            raise ProposalError(f"{field_name} must be a number")
        upper_ok = value < 1 if field_name == "strike_height_ratio" else value <= 10
        if not (value > 0 and upper_ok):
            raise ProposalError(f"{field_name} is out of range")
        entry[field_name] = float(value)
        return staged, {"field": field_name, "value": float(value)}
    if field_name == "screen_keywords":
        if not isinstance(value, dict) or not value:
            raise ProposalError("screen_keywords value must be {screen_type: [keywords to add]}")
        for screen_type, words in value.items():
            if screen_type not in SCREEN_TYPES:
                raise ProposalError(f"unknown screen type {screen_type!r}")
            if (
                not isinstance(words, list)
                or not words
                or not all(isinstance(w, str) and w.strip() for w in words)
            ):
                raise ProposalError("keywords must be a non-empty list of non-empty strings")
            current = entry["screen_keywords"].setdefault(screen_type, [])
            current.extend(w for w in words if w not in current)
        return staged, {"field": field_name, "value": value}
    # driver_eta_anchors
    if not isinstance(value, list) or not value or not all(isinstance(w, str) and w.strip() for w in value):
        raise ProposalError("driver_eta_anchors value must be a non-empty list of words to add")
    entry["driver_eta_anchors"].extend(w for w in value if w not in entry["driver_eta_anchors"])
    return staged, {"field": field_name, "value": value}
