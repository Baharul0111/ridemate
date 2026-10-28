"""Notification template matching exactly as the phone does it (contracts §2.1, config/README "Matching rules").

- The newest entry whose `min_version_code` ≤ the post's versionCode serves it; older than every entry → the
  oldest entry; unknown versionCode → the newest entry (`ridemate_config.matching.pick_entry`).
- Templates are tried in list order; the first whose RE2 pattern finds a match in `title + "\\n" + text` and
  whose `channel_id` / `ongoing` conditions hold decides the event.
- Redacted or empty posts are matched structurally against the entry's `redacted` rules.

Patterns are compiled with `ridemate_config.regex.compile_re2` (google-re2 plus the RE2/J portability checks), so
anything that matches here matches under RE2/J on the phone.
"""

from __future__ import annotations

from dataclasses import dataclass
from functools import lru_cache
from typing import Any

from ridemate_config.matching import pick_entry
from ridemate_config.regex import PatternError, compile_re2

from .samples import PostSample


@lru_cache(maxsize=4096)
def compiled(pattern: str) -> Any:
    return compile_re2(pattern)


@dataclass(frozen=True)
class Classification:
    template_id: str | None  # None for a structural (redacted) match
    event: str
    structural: bool = False


def entry_for(payload: dict[str, Any], package: str, version_code: int | None) -> dict[str, Any] | None:
    entries = payload.get("apps", {}).get(package)
    if not entries:
        return None
    return pick_entry(entries, version_code)


def conditions_hold(tpl: dict[str, Any], channel_id: str | None, ongoing: bool | None) -> bool:
    channel_ok = tpl.get("channel_id") is None or tpl["channel_id"] == channel_id
    return channel_ok and (tpl.get("ongoing") is None or tpl["ongoing"] == ongoing)


def pattern_matches(pattern: str, haystack: str) -> bool:
    try:
        return compiled(pattern).search(haystack) is not None
    except PatternError:
        return False


def template_matches(tpl: dict[str, Any], sample: PostSample) -> bool:
    return conditions_hold(tpl, sample.channel_id, sample.is_ongoing) and pattern_matches(
        tpl["pattern"], sample.haystack
    )


def _redacted_rule_holds(rule: dict[str, Any], sample: PostSample) -> bool:
    for key, value in (
        ("channel_id", sample.channel_id),
        ("ongoing", sample.is_ongoing),
        ("category", sample.category),
    ):
        if rule.get(key) is not None and rule[key] != value:
            return False
    return True


def classify(payload: dict[str, Any], sample: PostSample) -> Classification | None:
    entry = entry_for(payload, sample.package, sample.version_code)
    if entry is None:
        return None
    if sample.is_empty:
        for rule in entry.get("redacted", []):
            if _redacted_rule_holds(rule, sample):
                return Classification(None, rule["event"], structural=True)
        return None
    for tpl in entry.get("templates", []):
        if template_matches(tpl, sample):
            return Classification(tpl["id"], tpl["event"])
    return None


def all_template_ids(payload: dict[str, Any]) -> set[str]:
    return {
        tpl["id"]
        for entries in payload.get("apps", {}).values()
        for entry in entries
        for tpl in entry.get("templates", [])
    }
