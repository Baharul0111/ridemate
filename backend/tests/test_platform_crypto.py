"""crypto.HmacKeyring: versioned HMAC of the Google sub, 30-day dual read after rotation (§4.18 H5)."""

from __future__ import annotations

import pytest

from ridemate_api.clock import FakeClock
from ridemate_api.crypto import HmacKeyring, key_id
from ridemate_api.store import MemoryStore


def test_digest_is_stable_keyed_and_domain_separated() -> None:
    ring = HmacKeyring(["k1"])
    assert ring.digest("sub-1") == ring.digest("sub-1")
    assert len(ring.digest("sub-1")) == 64
    assert ring.digest("sub-1") != HmacKeyring(["k2"]).digest("sub-1")
    assert ring.digest("sub-1", "sub") != ring.digest("sub-1", "uid") != ring.digest("sub-1", "ip")
    assert "sub-1" not in ring.digest("sub-1")


def test_requires_a_key_and_at_most_two() -> None:
    with pytest.raises(ValueError):
        HmacKeyring([])
    with pytest.raises(ValueError):
        HmacKeyring(["", ""])
    with pytest.raises(ValueError):
        HmacKeyring(["a", "b", "c"])


def test_single_key_has_no_previous_digest() -> None:
    ring = HmacKeyring(["k1"])
    assert ring.previous_digest("s") is None
    assert ring.digests("s") == [ring.digest("s")]
    assert ring.previous_kid is None
    assert ring.current_kid == key_id("k1")


def test_identical_previous_key_is_ignored() -> None:
    ring = HmacKeyring(["k1", "k1"])
    assert ring.previous_digest("s") is None


def test_previous_key_valid_for_30_days_after_rotation_is_first_seen() -> None:
    store, clock = MemoryStore(), FakeClock()
    ring = HmacKeyring(["new", "old"], store=store, clock=clock)
    old = HmacKeyring(["old"])
    assert ring.previous_digest("sub-9") == old.digest("sub-9")
    assert ring.digests("sub-9") == [HmacKeyring(["new"]).digest("sub-9"), old.digest("sub-9")]

    clock.advance(29 * 86400)
    # A fresh process reads the same rotation record, so the window is shared across containers.
    assert HmacKeyring(["new", "old"], store=store, clock=clock).previous_active()
    clock.advance(2 * 86400)
    assert not ring.previous_active()
    assert ring.previous_digest("sub-9") is None
    assert not HmacKeyring(["new", "old"], store=store, clock=clock).previous_active()


def test_unreadable_rotation_record_keeps_previous_key_active() -> None:
    store, clock = MemoryStore(), FakeClock()
    store.fail = True
    ring = HmacKeyring(["new", "old"], store=store, clock=clock)
    assert ring.previous_active()
    assert ring.previous_digest("x") == HmacKeyring(["old"]).digest("x")
