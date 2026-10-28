"""Reference notification matcher used by the Python tooling and tests (the phone's TemplateEngine is Kotlin).

Semantics mirror contracts §2.1: pick the newest entry whose min_version_code <= versionCode (else the
oldest), then the first template in list order whose RE2 pattern finds a match in `title + "\\n" + text`
and whose optional channel_id / ongoing conditions hold.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from .regex import compile_re2


@dataclass(frozen=True)
class TemplateHit:
    template_id: str
    event: str
    groups: dict[str, str]


def pick_entry(entries: list[dict[str, Any]], version_code: int | None) -> dict[str, Any]:
    ordered = sorted(entries, key=lambda e: e["min_version_code"], reverse=True)
    if version_code is None:
        return ordered[0]
    return next((e for e in ordered if e["min_version_code"] <= version_code), ordered[-1])


def _conditions_hold(tpl: dict[str, Any], channel_id: str | None, ongoing: bool | None) -> bool:
    if tpl.get("channel_id") is not None and tpl["channel_id"] != channel_id:
        return False
    if tpl.get("ongoing") is not None and tpl["ongoing"] != ongoing:
        return False
    return True


def all_hits(
    payload: dict[str, Any],
    package: str,
    title: str,
    text: str,
    *,
    channel_id: str | None = None,
    ongoing: bool | None = None,
    version_code: int | None = 0,
) -> list[TemplateHit]:
    """Every template that matches, in precedence order (the first one is what the phone uses)."""
    entries = payload["apps"].get(package)
    if not entries:
        return []
    haystack = f"{title}\n{text}"
    hits: list[TemplateHit] = []
    for tpl in pick_entry(entries, version_code)["templates"]:
        if not _conditions_hold(tpl, channel_id, ongoing):
            continue
        m = compile_re2(tpl["pattern"]).search(haystack)
        if m:
            groups = {k: v for k, v in m.groupdict().items() if v is not None}
            hits.append(TemplateHit(tpl["id"], tpl["event"], groups))
    return hits


def match(payload: dict[str, Any], package: str, title: str, text: str, **kw: Any) -> TemplateHit | None:
    hits = all_hits(payload, package, title, text, **kw)
    return hits[0] if hits else None
