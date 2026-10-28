"""Abuse limits (architecture §4.2 "Abuse limits").

`IpLimiter`: a token bucket of 60 requests/min per client IP. The client IP is the RIGHT-MOST hop of
X-Forwarded-For, because Modal's proxy appends the address it saw; hops to its left are whatever the client sent
and are never trusted. IPv6 clients are bucketed by /64. Without a usable header there is no trustworthy client
address, so IP limiting is skipped and only the per-user quotas apply. Bucket keys are HMACs, not raw addresses.

`QuotaManager`: the per-user fair share per IST day, keyed on HMAC(Google sub). Modal Dict has no atomic
increment, so each unit is a slot claimed with put_if_absent ('quota:{hmac}:{day}:{kind}:{n}', n < limit): two
concurrent requests can never take the same slot, so the limit holds exactly. A request id is claimed first
('…:rid:{request_id}'), which makes counting idempotent: a retried id is not counted twice.

`allow_daily`: per-user daily caps for the free endpoints that write or read Firestore (/v1/event, /v1/erase).
"""

from __future__ import annotations

import hashlib
import ipaddress
import logging
import math
import re
from collections import OrderedDict
from datetime import datetime, timedelta
from typing import Any

from fastapi import Request

from .clock import Clock, ist_day, next_ist_midnight
from .crypto import HmacKeyring
from .interfaces import ApiError, Firestore, FirestoreUnavailable, Principal, QuotaKind
from .settings import (
    IP_BUCKET_PER_MIN,
    QUOTA_PARSES_JUDGE,
    QUOTA_PARSES_PER_DAY,
    QUOTA_PARSES_YOUNG,
    QUOTA_READS_JUDGE,
    QUOTA_READS_PER_DAY,
    QUOTA_READS_YOUNG,
    YOUNG_ACCOUNT_S,
)
from .store import HotStore, StoreUnavailable

log = logging.getLogger(__name__)

HEAVY_FRACTION = 2 / 3
KINDS: tuple[QuotaKind, ...] = ("read", "parse")
SYNCED_PREFIX = "first_seen_synced:"
_RID_RE = re.compile(r"^[A-Za-z0-9_-]{1,64}$")


def _meter_down() -> ApiError:
    return ApiError(503, "meter_unavailable", reason="meter")


# ---- client IP ---------------------------------------------------------------------------------------------


def _parse_hop(raw: str) -> ipaddress.IPv4Address | ipaddress.IPv6Address | None:
    hop = raw.strip().strip('"')
    if hop.startswith("["):  # "[2001:db8::1]:443"
        hop = hop[1 : hop.find("]")] if "]" in hop else ""
    elif hop.count(":") == 1:  # "198.51.100.7:5123"
        hop = hop.split(":", 1)[0]
    try:
        ip = ipaddress.ip_address(hop)
    except ValueError:
        return None
    if isinstance(ip, ipaddress.IPv6Address) and ip.ipv4_mapped is not None:
        return ip.ipv4_mapped
    return ip


def client_bucket(headers: Any, header: str = "x-forwarded-for") -> str | None:
    """The rate-limit bucket for a request: the right-most forwarded hop (an IPv4 address or an IPv6 /64),
    or None when there is no trustworthy client address."""
    values = headers.getlist(header) if hasattr(headers, "getlist") else [headers.get(header, "")]
    hops = [h for v in values if v for h in v.split(",") if h.strip()]
    if not hops:
        return None
    ip = _parse_hop(hops[-1])
    # A private, loopback or reserved right-most hop is a proxy, not the client: bucketing on it would put every
    # user behind that proxy into one 60/min bucket.
    if ip is None or not ip.is_global:
        return None
    if isinstance(ip, ipaddress.IPv6Address):
        return str(ipaddress.ip_network(f"{ip}/64", strict=False))
    return str(ip)


class IpLimiter:
    def __init__(
        self,
        store: HotStore,
        clock: Clock,
        keyring: HmacKeyring,
        *,
        per_minute: int = IP_BUCKET_PER_MIN,
        header: str = "x-forwarded-for",
    ) -> None:
        self._store = store
        self._clock = clock
        self._keyring = keyring
        self._capacity = float(per_minute)
        self._rate_per_s = per_minute / 60.0
        self._header = header

    def check(self, request: Request) -> None:
        state = request.state
        if getattr(state, "ip_checked", False):  # app middleware and an endpoint may both call this
            return
        state.ip_checked = True
        bucket = client_bucket(request.headers, self._header)
        if bucket is None:
            return
        self.take(bucket)

    def take(self, bucket: str) -> None:
        key = f"ip:{self._keyring.digest(bucket, purpose='ip')[:32]}"
        now = self._clock.now_utc().timestamp()
        try:
            state = self._store.get(key)
            tokens = self._capacity
            if isinstance(state, dict):
                elapsed = max(0.0, now - float(state.get("ts", now)))
                tokens = min(
                    self._capacity, float(state.get("tokens", self._capacity)) + elapsed * self._rate_per_s
                )
            allowed = tokens >= 1.0
            self._store.put(key, {"tokens": tokens - 1.0 if allowed else tokens, "ts": now})
        except StoreUnavailable:
            # Per-IP limits are a courtesy layer; money is guarded by quotas and the spend meter, which fail closed.
            log.warning("ip limiter store unavailable; skipping")
            return
        if not allowed:
            raise ApiError(
                429, "rate_limited", retry_after_s=max(1, math.ceil((1.0 - tokens) / self._rate_per_s))
            )


# ---- per-user quotas ---------------------------------------------------------------------------------------


def _rid(request_id: str) -> str:
    return request_id if _RID_RE.match(request_id) else hashlib.sha256(request_id.encode()).hexdigest()[:32]


def _parse_dt(raw: Any) -> datetime | None:
    if isinstance(raw, datetime):
        return raw
    try:
        return datetime.fromisoformat(str(raw))
    except ValueError:
        return None


class QuotaManager:
    def __init__(
        self,
        store: HotStore,
        clock: Clock,
        *,
        firestore: Firestore | None = None,
        first_seen_cache_size: int = 10_000,
    ) -> None:
        self._store = store
        self._clock = clock
        self._firestore = firestore
        self._first_seen: OrderedDict[str, datetime] = OrderedDict()
        self._cache_size = first_seen_cache_size

    # ---- limits --------------------------------------------------------------------------------------------

    def limits(self, principal: Principal) -> dict[QuotaKind, int]:
        if principal.is_judge:
            return {"read": QUOTA_READS_JUDGE, "parse": QUOTA_PARSES_JUDGE}
        if self.is_young(principal):
            return {"read": QUOTA_READS_YOUNG, "parse": QUOTA_PARSES_YOUNG}
        return {"read": QUOTA_READS_PER_DAY, "parse": QUOTA_PARSES_PER_DAY}

    # ---- the QuotaManager protocol -------------------------------------------------------------------------

    def consume(self, principal: Principal, kind: QuotaKind, request_id: str) -> None:
        if not principal.is_google:
            raise ApiError(403, "google_required")
        now = self._clock.now_utc()
        rid = _rid(request_id)
        claimed: list[str] = []
        try:
            limit = self.limits(principal)[kind]
            base = self._base(principal, ist_day(now))
            rid_key = f"{base}:{kind}:rid:{rid}"
            if not self._store.put_if_absent(rid_key, -1):
                return  # this request id was already counted (or is being counted right now)
            claimed.append(rid_key)
            summary = self._summary(base)
            hint = min(max(int(summary.get(kind, 0)), 0), limit)
            for n in [*range(hint, limit), *range(hint)]:
                slot = f"{base}:{kind}:{n}"
                if self._store.put_if_absent(slot, rid):
                    claimed.append(slot)
                    self._store.put(rid_key, n)
                    self._bump(base, kind, +1)
                    return
            self._store.delete(rid_key)
        except StoreUnavailable:
            # The request fails with 503, so undo its claims: a half-made claim would let a retry of this id
            # through without counting, and a claimed slot would charge a unit for an answer never given.
            self._unclaim(claimed)
            raise _meter_down() from None
        raise ApiError(429, "quota_exhausted", resume_at=next_ist_midnight(now))

    def _unclaim(self, keys: list[str]) -> None:
        for key in reversed(keys):
            try:
                self._store.delete(key)
            except StoreUnavailable:
                log.warning("quota claim not undone: store unavailable")
                return

    def refund(self, principal: Principal, kind: QuotaKind, request_id: str) -> None:
        if not principal.is_google:
            return
        rid = _rid(request_id)
        try:
            base = self._base(principal, ist_day(self._clock.now_utc()))
            rid_key = f"{base}:{kind}:rid:{rid}"
            n = self._store.get(rid_key)
            if not isinstance(n, int) or n < 0:
                return
            slot = f"{base}:{kind}:{n}"
            if self._store.get(slot) == rid:
                self._store.delete(slot)
                self._bump(base, kind, -1)
            self._store.delete(rid_key)
        except StoreUnavailable:
            # A refund must never turn a failed request into a different error; the unit stays used.
            log.warning("quota refund skipped: store unavailable")

    def remaining(self, principal: Principal) -> dict[str, int]:
        if not principal.is_google:
            return {"reads_left": 0, "parses_left": 0}
        try:
            limits = self.limits(principal)
            used = self._summary(self._base(principal, ist_day(self._clock.now_utc())))
        except StoreUnavailable:
            raise _meter_down() from None
        return {
            "reads_left": max(0, limits["read"] - int(used.get("read", 0))),
            "parses_left": max(0, limits["parse"] - int(used.get("parse", 0))),
        }

    def is_young(self, principal: Principal) -> bool:
        if not principal.is_google:
            return True
        return self._clock.now_utc() - self.first_seen(principal) < timedelta(seconds=YOUNG_ACCOUNT_S)

    # ---- tier inputs for the spend meter -------------------------------------------------------------------

    def usage_fraction(self, principal: Principal) -> float:
        """The larger of reads used / read limit and parses used / parse limit, today."""
        if not principal.is_google:
            return 0.0
        limits = self.limits(principal)
        used = self._summary(self._base(principal, ist_day(self._clock.now_utc())))
        return max(int(used.get(k, 0)) / limits[k] for k in KINDS)

    def is_heavy(self, principal: Principal) -> bool:
        return self.usage_fraction(principal) >= HEAVY_FRACTION - 1e-9

    # ---- account age ---------------------------------------------------------------------------------------

    def first_seen(self, principal: Principal) -> datetime:
        """When this Google account (by HMAC(sub)) was first seen. Recorded once; survives Dict expiry through
        the Firestore usage snapshot and key rotation through the dual read."""
        h = principal.sub_hmac
        cached = self._first_seen.get(h)
        if cached is not None:
            return cached
        key = f"first_seen:{h}"
        found = _parse_dt(self._store.get(key))
        durable = False  # known to be in Firestore under the current HMAC
        if found is None and principal.sub_hmac_previous:
            found = _parse_dt(self._store.get(f"first_seen:{principal.sub_hmac_previous}"))
        if found is None:
            found, durable = self._first_seen_from_firestore(principal)
        if found is not None:
            self._store.put_if_absent(key, found.isoformat())
        else:
            now = self._clock.now_utc()
            if self._store.put_if_absent(key, now.isoformat()):
                found = now
            else:
                found = _parse_dt(self._store.get(key)) or now
        self._sync_first_seen(h, found, durable=durable)
        self._remember(h, found)
        return found

    def _first_seen_from_firestore(self, principal: Principal) -> tuple[datetime | None, bool]:
        """(first_seen, whether it was stored under the current HMAC)."""
        if self._firestore is None:
            return None, False
        for h in filter(None, (principal.sub_hmac, principal.sub_hmac_previous)):
            try:
                doc = self._firestore.get(f"usage_snapshot/{h}")
            except FirestoreUnavailable:
                return None, False
            if doc and doc.get("first_seen") is not None:
                found = _parse_dt(doc["first_seen"])
                return found, found is not None and h == principal.sub_hmac
        return None, False

    def _sync_first_seen(self, h: str, at: datetime, *, durable: bool) -> None:
        """Keep the durable copy (usage_snapshot/{hmac}) under the CURRENT key. It is written once and retried on
        a later lookup if Firestore was down, and after a key rotation it is copied forward, so an account's age
        outlives both the Dict's 7-day expiry and the previous key's 30-day grace."""
        if self._firestore is None:
            return
        marker = f"{SYNCED_PREFIX}{h}"
        if durable:
            self._store.put(marker, True)
            return
        if self._store.get(marker) is not None:
            return
        try:
            self._firestore.set(
                f"usage_snapshot/{h}", {"first_seen": at, "updated_at": self._clock.now_utc()}, merge=True
            )
        except FirestoreUnavailable:
            log.warning("usage snapshot of first_seen deferred: firestore unavailable")
            return
        self._store.put(marker, True)

    def _remember(self, h: str, at: datetime) -> None:
        self._first_seen[h] = at
        while len(self._first_seen) > self._cache_size:
            self._first_seen.popitem(last=False)

    # ---- store layout --------------------------------------------------------------------------------------

    def _base(self, principal: Principal, day: str) -> str:
        """'quota:{hmac}:{day}' under the current key, migrating today's slots from the previous key once."""
        base = f"quota:{principal.sub_hmac}:{day}"
        prev = principal.sub_hmac_previous
        if prev and self._store.get(base) is None:
            old = f"quota:{prev}:{day}"
            old_summary = self._store.get(old)
            if isinstance(old_summary, dict):
                self._migrate(old, base, old_summary)
        return base

    def _migrate(self, old: str, new: str, summary: dict[str, Any]) -> None:
        caps = {"read": QUOTA_READS_JUDGE, "parse": QUOTA_PARSES_JUDGE}  # the widest limit covers every slot
        for kind in KINDS:
            for n in range(caps[kind]):
                rid = self._store.get(f"{old}:{kind}:{n}")
                if rid is None:
                    continue
                self._store.put_if_absent(f"{new}:{kind}:{n}", rid)
                self._store.put_if_absent(f"{new}:{kind}:rid:{rid}", n)
        self._store.put_if_absent(new, {k: int(summary.get(k, 0)) for k in KINDS})

    def _summary(self, base: str) -> dict[str, Any]:
        raw = self._store.get(base)
        return raw if isinstance(raw, dict) else {}

    def _bump(self, base: str, kind: QuotaKind, delta: int) -> None:
        # Not atomic, so it is only a scan hint and the display/tier count; the slots themselves enforce the limit.
        summary = dict(self._summary(base))
        summary[kind] = max(0, int(summary.get(kind, 0)) + delta)
        self._store.put(base, summary)


# ---- per-user daily caps on free endpoints -----------------------------------------------------------------


def allow_daily(store: HotStore, clock: Clock, principal: Principal, action: str, limit: int) -> bool:
    """One use of `action` for this user today, if fewer than `limit` were used. For endpoints that cost no model
    money but do spend the free Firestore quota (20k writes and 50k reads a day, shared with ONDC and every other
    user), so one account can't exhaust it. Raises StoreUnavailable; the caller decides how to fail.

    Keys sit under the user's quota prefix ('quota:{hmac}:{day}:cap:{action}:{n}'), so the hot-store cleanup drops
    them with the quota slots. Slots are claimed with put_if_absent and never released, and the hint at the base
    key is only ever written just past a claimed slot, so every slot below any hint is taken: the cap is exact
    under concurrency, and once it is reached a refusal costs one read."""
    base = f"quota:{principal.sub_hmac}:{ist_day(clock.now_utc())}:cap:{action}"
    try:
        hint = max(0, int(store.get(base) or 0))
    except (TypeError, ValueError):
        hint = 0
    for n in range(hint, limit):
        if store.put_if_absent(f"{base}:{n}", 1):
            store.put(base, n + 1)
            return True
    return False
