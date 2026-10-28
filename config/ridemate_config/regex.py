"""RE2 compilation with the same rules the phone applies under RE2/J.

google-re2 wraps the C++ library, whose syntax RE2/J ports. A few constructs differ between the two, so on top
of compiling we reject what RE2/J 1.8 may not accept: `(?<name>…)` (use `(?P<name>…)`) and `\\C` (any byte).
"""

from __future__ import annotations

import re2

_OPTIONS = re2.Options()
_OPTIONS.log_errors = False

_RE2J_UNSUPPORTED = ("(?<", "\\C")


class PatternError(ValueError):
    pass


def compile_re2(pattern: str) -> re2._Regexp:
    """Compile `pattern` with RE2; raise PatternError saying why if RE2 or RE2/J would reject it."""
    for token in _RE2J_UNSUPPORTED:
        if token in pattern:
            raise PatternError(f"construct {token!r} is not portable to RE2/J")
    try:
        return re2.compile(pattern, _OPTIONS)
    except re2.error as e:
        reason = e.args[0].decode() if e.args and isinstance(e.args[0], bytes) else str(e)
        raise PatternError(f"RE2 rejects pattern: {reason}") from e


def check_pattern(pattern: str, *, allowed_groups: frozenset[str] | None = None, min_groups: int = 0) -> None:
    """Compile and enforce named-group and group-count rules. Raises PatternError."""
    rx = compile_re2(pattern)
    if allowed_groups is not None:
        unknown = set(rx.groupindex) - allowed_groups
        if unknown:
            raise PatternError(
                f"named groups {sorted(unknown)} not allowed (allowed: {sorted(allowed_groups)})"
            )
    if rx.groups < min_groups:
        raise PatternError(f"needs at least {min_groups} capture group(s), has {rx.groups}")
