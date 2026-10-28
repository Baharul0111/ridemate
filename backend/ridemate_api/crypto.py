"""Versioned HMAC of user identifiers (architecture §4.2 "Abuse limits", §4.18 H5).

Quotas and account ages are keyed on HMAC-SHA256 of the Google `sub`, so the raw id never reaches the hot store or
Firestore, and erasing and recreating a Firebase account (new uid, same Google `sub`) doesn't reset them.

The key lives in a Modal Secret and is versioned by its fingerprint. After a rotation the previous key stays valid
for 30 days: lookups try the current digest first and then the previous one, and callers copy what they find under
the previous digest forward (a dual-read migration), so quotas and account ages survive the rotation. The start of
a rotation is recorded once in the hot store; when that record can't be read the previous key is treated as still
valid, which only costs an extra lookup.
"""

from __future__ import annotations

import hashlib
import hmac
import logging
from collections.abc import Sequence
from datetime import datetime, timedelta

from .clock import Clock
from .store import HotStore, StoreUnavailable

log = logging.getLogger(__name__)

ROTATION_GRACE = timedelta(days=30)
_ROTATION_KEY = "hmac:rotated_at:{kid}"


def key_id(key: str) -> str:
    """Non-secret fingerprint of a key, used as its version id."""
    return hashlib.sha256(key.encode()).hexdigest()[:8]


def _digest(key: bytes, value: str, purpose: str) -> str:
    # The purpose prefix separates domains: a uid, a Google sub and an IP bucket never share a digest.
    return hmac.new(key, f"ridemate:{purpose}:{value}".encode(), hashlib.sha256).hexdigest()


class HmacKeyring:
    def __init__(
        self,
        keys: Sequence[str],
        *,
        store: HotStore | None = None,
        clock: Clock | None = None,
        grace: timedelta = ROTATION_GRACE,
    ) -> None:
        keys = [k for k in keys if k]
        if not keys:
            raise ValueError("RIDEMATE_HMAC_KEY is not configured")
        if len(keys) > 2:
            raise ValueError("at most two HMAC keys (current, previous) are supported")
        self._current = keys[0].encode()
        self._previous = keys[1].encode() if len(keys) == 2 and keys[1] != keys[0] else None
        self.current_kid = key_id(keys[0])
        self.previous_kid = key_id(keys[1]) if self._previous is not None else None
        self._store = store
        self._clock = clock
        self._grace = grace
        self._rotated_at: datetime | None = None

    def digest(self, value: str, purpose: str = "sub") -> str:
        return _digest(self._current, value, purpose)

    def previous_digest(self, value: str, purpose: str = "sub") -> str | None:
        """The digest under the previous key while it is inside its 30-day grace window, else None."""
        if self._previous is None or not self.previous_active():
            return None
        return _digest(self._previous, value, purpose)

    def digests(self, value: str, purpose: str = "sub") -> list[str]:
        """Lookup order for a dual read: current first, then previous (if still valid)."""
        out = [self.digest(value, purpose)]
        prev = self.previous_digest(value, purpose)
        if prev is not None:
            out.append(prev)
        return out

    def previous_active(self) -> bool:
        if self._previous is None:
            return False
        if self._store is None or self._clock is None:
            return True
        now = self._clock.now_utc()
        rotated_at = self._rotated_at or self._load_rotation(now)
        if rotated_at is None:
            return True
        return now < rotated_at + self._grace

    def _load_rotation(self, now: datetime) -> datetime | None:
        store = self._store
        if store is None:
            return None
        key = _ROTATION_KEY.format(kid=self.current_kid)
        try:
            store.put_if_absent(key, now.isoformat())
            raw = store.get(key)
        except StoreUnavailable:
            log.warning("hmac rotation record unreadable; keeping the previous key active")
            return None
        try:
            self._rotated_at = datetime.fromisoformat(str(raw))
        except ValueError:
            return None
        return self._rotated_at
