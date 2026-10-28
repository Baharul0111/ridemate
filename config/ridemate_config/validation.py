"""Validates each Remote Config default against its JSON Schema (draft 2020-12) and what a schema cannot say.

Schemas mark RE2 patterns with the custom keyword `"x-re2"`: `true`, or an object with
`"groups"` (allowed named groups) and/or `"minGroups"`. Every marked string is compiled with RE2 during
validation, so a default that validates is also a default the phone's RE2/J loader accepts.
"""

from __future__ import annotations

import json
import re
from collections.abc import Iterator
from pathlib import Path
from typing import Any

from jsonschema import Draft202012Validator, ValidationError
from jsonschema.validators import extend

from .keys import (
    DEMO_PACKAGES,
    KEYS,
    KEYS_BY_NAME,
    LAUNCH_MODES,
    LAUNCH_PLACEHOLDERS,
    REAL_PACKAGES,
    UNVERIFIED_NOTE,
    KeySpec,
)
from .regex import PatternError, check_pattern

# A real-app entry stops being "unverified" only once rewritten from a Recorder export (architecture §4.19).
VERIFIED_NOTE_PREFIX = "verified against Recorder export"

EVENTS = (
    "SEARCHING",
    "ASSIGNED",
    "ARRIVING",
    "DRIVER_CANCELLED",
    "NO_DRIVERS",
    "CANCELLED",
    "ENDED",
    "TRIP_STARTED",
)


def _x_re2(validator: Any, spec: Any, instance: Any, schema: dict[str, Any]) -> Iterator[ValidationError]:
    if not isinstance(instance, str) or spec is False:
        return
    groups = frozenset(spec["groups"]) if isinstance(spec, dict) and "groups" in spec else None
    min_groups = int(spec.get("minGroups", 0)) if isinstance(spec, dict) else 0
    try:
        check_pattern(instance, allowed_groups=groups, min_groups=min_groups)
    except PatternError as e:
        yield ValidationError(f"{instance!r}: {e}")


ConfigValidator = extend(Draft202012Validator, {"x-re2": _x_re2})


def load_json(path: Path) -> Any:
    with path.open(encoding="utf-8") as f:
        return json.load(f)


def load_default(key: KeySpec) -> Any:
    return load_json(key.default_path)


def load_schema(key: KeySpec) -> dict[str, Any]:
    return load_json(key.schema_path)


def schema_errors(key: KeySpec, payload: Any) -> list[str]:
    schema = load_schema(key)
    ConfigValidator.check_schema(schema)
    validator = ConfigValidator(schema)
    return [
        f"{key.name}: /{'/'.join(str(p) for p in err.absolute_path)}: {err.message}"
        for err in sorted(validator.iter_errors(payload), key=lambda e: list(map(str, e.absolute_path)))
    ]


def _semantic_errors(key: KeySpec, payload: Any) -> list[str]:
    errors: list[str] = []
    if key.per_app:
        apps = payload.get("apps", {})
        missing = [p for p in key.required_packages if p not in apps]
        if missing:
            errors.append(f"{key.name}: apps missing {missing}")
        notes = payload.get("notes", {})
        for pkg in REAL_PACKAGES:
            note = str(notes.get(pkg, ""))
            if pkg in apps and UNVERIFIED_NOTE not in note and not note.startswith(VERIFIED_NOTE_PREFIX):
                wanted = f"'{UNVERIFIED_NOTE}' (or start '{VERIFIED_NOTE_PREFIX}')"
                errors.append(f"{key.name}: notes[{pkg}] must say {wanted}")
    if key.name == "launch_templates":
        errors += _launch_errors(payload)
    if key.name in ("notification_templates", "fare_parsers"):
        for pkg, entries in payload.get("apps", {}).items():
            codes = [e["min_version_code"] for e in entries]
            if len(codes) != len(set(codes)):
                errors.append(f"{key.name}: {pkg} has duplicate min_version_code")
    if key.name == "notification_templates":
        seen: set[str] = set()
        for pkg, entries in payload.get("apps", {}).items():
            for entry in entries:
                for tpl in entry["templates"]:
                    if tpl["id"] in seen:
                        errors.append(f"{key.name}: duplicate template id {tpl['id']}")
                    seen.add(tpl["id"])
                if pkg in DEMO_PACKAGES:
                    covered = {t["event"] for t in entry["templates"]}
                    lacking = [e for e in EVENTS if e not in covered]
                    if lacking:
                        errors.append(f"{key.name}: demo {pkg} lacks events {lacking}")
    return errors


def _launch_errors(payload: dict[str, Any]) -> list[str]:
    """What the phone's LaunchTemplate.validate rejects beyond the schema: unknown modes and placeholders."""
    errors: list[str] = []
    for pkg, tpl in payload.get("apps", {}).items():
        for mode in (tpl.get("mode"), tpl.get("fallback")):
            if mode is not None and mode not in LAUNCH_MODES:
                errors.append(f"launch_templates: {pkg} mode {mode!r} is not one of {list(LAUNCH_MODES)}")
        unknown = sorted(set(re.findall(r"\{([a-z]+)\}", tpl.get("uri") or "")) - LAUNCH_PLACEHOLDERS)
        if unknown:
            errors.append(f"launch_templates: {pkg} uri has unknown placeholders {unknown}")
    return errors


def validate_key(key: KeySpec, payload: Any | None = None) -> list[str]:
    """All problems with one key's default (or with `payload` if given). Empty list = valid."""
    if payload is None:
        if not key.default_path.exists():
            return [f"{key.name}: missing default {key.default_path.name}"]
        if not key.schema_path.exists():
            return [f"{key.name}: missing schema {key.schema_path.name}"]
        payload = load_default(key)
    errors = schema_errors(key, payload)
    return errors or _semantic_errors(key, payload)


def validate_all() -> dict[str, list[str]]:
    return {key.name: validate_key(key) for key in KEYS}


def default_value(name: str) -> Any:
    """The default as the phone sees it: the payload for JSON keys, the bare scalar otherwise."""
    key = KEYS_BY_NAME[name]
    data = load_default(key)
    return data if key.is_json else data["value"]
