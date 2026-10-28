"""limits.IpLimiter (right-most hop, /64, spoofing) and limits.QuotaManager (fair share per IST day)."""

from __future__ import annotations

import threading
from datetime import UTC, datetime, timedelta

import pytest
from starlette.datastructures import Headers
from starlette.requests import Request

from ridemate_api.clock import FakeClock, next_ist_midnight
from ridemate_api.crypto import HmacKeyring
from ridemate_api.interfaces import ApiError, Principal
from ridemate_api.limits import IpLimiter, QuotaManager, allow_daily, client_bucket
from ridemate_api.store import MemoryStore, StoreUnavailable
from ridemate_api.testing import FakeFirestore

REAL_CLIENT = "49.36.120.7"


def request_with(*xff: str) -> Request:
    headers = [(b"x-forwarded-for", v.encode()) for v in xff]
    return Request(
        {"type": "http", "method": "GET", "path": "/v1/x", "headers": headers, "query_string": b""}
    )


def limiter(store: MemoryStore | None = None, clock: FakeClock | None = None) -> IpLimiter:
    clock = clock or FakeClock()
    return IpLimiter(store or MemoryStore(), clock, HmacKeyring(["k"]))


# ---- client IP -------------------------------------------------------------------------------------------


def test_bucket_is_the_right_most_hop() -> None:
    assert client_bucket(Headers({"x-forwarded-for": f"1.2.3.4, 10.0.0.1, {REAL_CLIENT}"})) == REAL_CLIENT
    assert client_bucket(Headers({"x-forwarded-for": REAL_CLIENT})) == REAL_CLIENT


def test_repeated_headers_are_joined_and_right_most_wins() -> None:
    req = request_with("6.6.6.6", f"7.7.7.7, {REAL_CLIENT}")
    assert client_bucket(req.headers) == REAL_CLIENT


def test_ipv6_is_bucketed_by_64() -> None:
    a = client_bucket(Headers({"x-forwarded-for": "2401:4900:1c2a:1234:aaaa::1"}))
    b = client_bucket(Headers({"x-forwarded-for": "2401:4900:1c2a:1234:ffff:1:2:3"}))
    c = client_bucket(Headers({"x-forwarded-for": "2401:4900:1c2a:9999::1"}))
    assert a == b == "2401:4900:1c2a:1234::/64"
    assert c != a


def test_hop_forms_with_ports_and_mapped_v4() -> None:
    assert client_bucket(Headers({"x-forwarded-for": "49.36.1.1:5555"})) == "49.36.1.1"
    assert client_bucket(Headers({"x-forwarded-for": "[2401:4900::1]:443"})) == "2401:4900::/64"
    assert client_bucket(Headers({"x-forwarded-for": "::ffff:49.36.1.2"})) == "49.36.1.2"


@pytest.mark.parametrize("value", ["", " , ", "not-an-ip", "0.0.0.0", "1.2.3.4, garbage"])
def test_untrustworthy_header_means_no_bucket(value: str) -> None:
    assert client_bucket(Headers({"x-forwarded-for": value})) is None


@pytest.mark.parametrize(
    "proxy_hop",
    ["10.0.0.7", "172.16.4.2", "192.168.1.1", "127.0.0.1", "100.64.0.9", "169.254.1.1", "fd00::1", "::1"],
)
def test_a_private_right_most_hop_is_a_proxy_not_the_client(proxy_hop: str) -> None:
    """If the right-most hop is an internal proxy address, bucketing on it would rate-limit every user together."""
    assert client_bucket(Headers({"x-forwarded-for": f"{REAL_CLIENT}, {proxy_hop}"})) is None
    lim = limiter()
    for _ in range(200):
        lim.check(request_with(f"{REAL_CLIENT}, {proxy_hop}"))


def test_missing_header_skips_ip_limiting() -> None:
    lim = limiter()
    for _ in range(200):
        lim.check(Request({"type": "http", "headers": [], "path": "/v1/x", "query_string": b""}))


def test_61st_request_in_a_minute_is_rate_limited_then_refills() -> None:
    store, clock = MemoryStore(), FakeClock()
    lim = limiter(store, clock)
    for _ in range(60):
        lim.check(request_with(REAL_CLIENT))
    with pytest.raises(ApiError) as e:
        lim.check(request_with(REAL_CLIENT))
    assert (e.value.status, e.value.code) == (429, "rate_limited")
    assert e.value.retry_after_s == 1
    clock.advance(1.0)
    lim.check(request_with(REAL_CLIENT))
    with pytest.raises(ApiError):
        lim.check(request_with(REAL_CLIENT))


def test_spoofed_left_hops_do_not_create_new_buckets() -> None:
    """A client rotating fake left-most hops still lands in its real (right-most) bucket."""
    lim = limiter()
    for i in range(60):
        lim.check(request_with(f"203.0.113.{i}, 198.51.100.{i}, {REAL_CLIENT}"))
    with pytest.raises(ApiError):
        lim.check(request_with(f"1.1.1.1, {REAL_CLIENT}"))
    lim.check(request_with(f"{REAL_CLIENT}, 49.36.120.8"))  # a different real client is unaffected


def test_spoofing_a_victims_ip_on_the_left_does_not_drain_the_victim() -> None:
    lim = limiter()
    for _ in range(60):
        lim.check(request_with(f"{REAL_CLIENT}, 49.36.200.200"))
    lim.check(request_with(REAL_CLIENT))


def test_check_is_idempotent_per_request() -> None:
    lim = limiter()
    req = request_with(REAL_CLIENT)
    for _ in range(100):
        lim.check(req)  # middleware and endpoint may both call it: one token per request


def test_bucket_keys_never_hold_the_raw_address() -> None:
    store = MemoryStore()
    limiter(store).check(request_with(REAL_CLIENT))
    keys = store.keys_with_prefix("ip:")
    assert len(keys) == 1 and REAL_CLIENT not in keys[0]


def test_store_failure_skips_ip_limit() -> None:
    store = MemoryStore()
    store.fail = True
    limiter(store).check(request_with(REAL_CLIENT))


# ---- quotas ----------------------------------------------------------------------------------------------

RING = HmacKeyring(["current-key"])


def google(uid: str = "u1", sub: str = "g-1", *, judge: bool = False, ring: HmacKeyring = RING) -> Principal:
    return Principal(
        uid=uid,
        provider="google.com",
        sub_hmac=ring.digest(sub),
        sub_hmac_previous=ring.previous_digest(sub),
        is_judge=judge,
    )


def seasoned(qm: QuotaManager, clock: FakeClock, p: Principal) -> None:
    """Make the account older than 24 h."""
    qm.first_seen(p)
    clock.advance(25 * 3600)


def setup(firestore: FakeFirestore | None = None) -> tuple[QuotaManager, MemoryStore, FakeClock]:
    store, clock = MemoryStore(), FakeClock()
    return QuotaManager(store, clock, firestore=firestore), store, clock


def test_default_quota_is_6_reads_and_10_parses_per_ist_day() -> None:
    qm, _, clock = setup()
    p = google()
    seasoned(qm, clock, p)
    for i in range(6):
        qm.consume(p, "read", f"read-{i:04d}")
    with pytest.raises(ApiError) as e:
        qm.consume(p, "read", "read-9999")
    assert (e.value.status, e.value.code) == (429, "quota_exhausted")
    assert e.value.resume_at == next_ist_midnight(clock.now_utc())
    for i in range(10):
        qm.consume(p, "parse", f"parse-{i:04d}")
    with pytest.raises(ApiError):
        qm.consume(p, "parse", "parse-9999")
    assert qm.remaining(p) == {"reads_left": 0, "parses_left": 0}


def test_young_account_gets_3_reads_and_5_parses() -> None:
    qm, _, clock = setup()
    p = google()
    assert qm.is_young(p)
    assert qm.limits(p) == {"read": 3, "parse": 5}
    for i in range(3):
        qm.consume(p, "read", f"r-{i:06d}")
    with pytest.raises(ApiError):
        qm.consume(p, "read", "r-999999")
    clock.advance(24 * 3600 + 1)
    assert not qm.is_young(p)
    qm.consume(p, "read", "r-000003")  # 6 a day now (day changed too, but the limit is what matters)


def test_judge_allowlist_gets_20_reads_and_40_parses_even_when_young() -> None:
    qm, _, _ = setup()
    p = google(judge=True)
    assert qm.limits(p) == {"read": 20, "parse": 40}
    for i in range(20):
        qm.consume(p, "read", f"j-{i:06d}")
    with pytest.raises(ApiError):
        qm.consume(p, "read", "j-999999")


def test_retried_request_id_is_not_counted_twice() -> None:
    qm, _, clock = setup()
    p = google()
    seasoned(qm, clock, p)
    for _ in range(10):
        qm.consume(p, "read", "same-request-id")
    assert qm.remaining(p)["reads_left"] == 5


def test_consume_says_whether_it_counted_the_unit() -> None:
    qm, _, clock = setup()
    p = google()
    seasoned(qm, clock, p)
    assert qm.consume(p, "read", "req-00000001") is True
    assert qm.consume(p, "read", "req-00000001") is False  # a retry runs on the unit already counted
    qm.refund(p, "read", "req-00000001")
    assert qm.consume(p, "read", "req-00000001") is True  # given back, so the next run counts it again


def test_a_request_id_still_being_counted_elsewhere_is_refused_not_run_for_free() -> None:
    # Two simultaneous requests with one id: the second must not run on a unit the first may never get
    # (an exhausted quota would otherwise be bypassed by sending every request twice at once).
    qm, store, clock = setup()
    p = google()
    seasoned(qm, clock, p)
    for i in range(6):
        qm.consume(p, "read", f"x-{i:06d}")
    rid_key = next(k for k in store.keys_with_prefix("quota:") if k.endswith(":read:rid:x-000000"))
    in_progress = rid_key.replace("x-000000", "x-000099")
    store.put(in_progress, -1)  # the first request is between its claim and its slot
    with pytest.raises(ApiError) as err:
        qm.consume(p, "read", "x-000099")
    assert (err.value.status, err.value.code) == (422, "invalid")
    assert qm.remaining(p)["reads_left"] == 0


def test_refund_returns_the_unit_and_is_safe_to_repeat() -> None:
    qm, _, clock = setup()
    p = google()
    seasoned(qm, clock, p)
    qm.consume(p, "read", "req-00000001")
    qm.refund(p, "read", "req-00000001")
    qm.refund(p, "read", "req-00000001")
    qm.refund(p, "read", "never-consumed")
    assert qm.remaining(p)["reads_left"] == 6
    for i in range(6):
        qm.consume(p, "read", f"x-{i:06d}")
    with pytest.raises(ApiError):
        qm.consume(p, "read", "x-999999")


def test_a_refunded_slot_is_reused() -> None:
    qm, _, clock = setup()
    p = google()
    seasoned(qm, clock, p)
    for i in range(6):
        qm.consume(p, "read", f"x-{i:06d}")
    qm.refund(p, "read", "x-000002")
    qm.consume(p, "read", "x-000100")
    with pytest.raises(ApiError):
        qm.consume(p, "read", "x-000101")


def test_quota_resets_at_ist_midnight() -> None:
    store, clock = MemoryStore(), FakeClock(datetime(2026, 10, 28, 18, 0, tzinfo=UTC))  # 23:30 IST
    p = google()
    store.put(f"first_seen:{p.sub_hmac}", (clock.now_utc() - timedelta(days=2)).isoformat())
    qm = QuotaManager(store, clock)
    for i in range(6):
        qm.consume(p, "read", f"d1-{i:05d}")
    with pytest.raises(ApiError):
        qm.consume(p, "read", "d1-99999")
    clock.advance(31 * 60)  # 00:01 IST
    qm.consume(p, "read", "d2-00000")


def test_concurrent_consumers_never_exceed_the_limit() -> None:
    qm, _, clock = setup()
    p = google()
    seasoned(qm, clock, p)
    ok: list[str] = []
    lock = threading.Lock()

    def worker(i: int) -> None:
        try:
            qm.consume(p, "parse", f"c-{i:06d}")
        except ApiError:
            return
        with lock:
            ok.append(str(i))

    threads = [threading.Thread(target=worker, args=(i,)) for i in range(50)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    assert len(ok) == 10


def test_quota_follows_the_google_sub_not_the_firebase_uid() -> None:
    """Erasing and recreating the Firebase account (new uid, same Google sub) does not reset quotas."""
    qm, _, clock = setup()
    first = google(uid="uid-old", sub="g-same")
    seasoned(qm, clock, first)
    for i in range(6):
        qm.consume(first, "read", f"a-{i:06d}")
    again = google(uid="uid-new", sub="g-same")
    with pytest.raises(ApiError):
        qm.consume(again, "read", "a-999999")
    assert not qm.is_young(again)


def test_anonymous_cannot_consume_and_has_nothing_left() -> None:
    qm, _, _ = setup()
    anon = Principal(uid="a", provider="anonymous", sub_hmac="h")
    with pytest.raises(ApiError) as e:
        qm.consume(anon, "read", "req-00000001")
    assert e.value.code == "google_required"
    assert qm.remaining(anon) == {"reads_left": 0, "parses_left": 0}
    assert qm.is_young(anon)


def test_store_failure_fails_closed() -> None:
    qm, store, _ = setup()
    store.fail = True
    with pytest.raises(ApiError) as e:
        qm.consume(google(), "read", "req-00000001")
    assert (e.value.status, e.value.code) == (503, "meter_unavailable")
    qm.refund(google(), "read", "req-00000001")  # never raises


class FlakyStore(MemoryStore):
    """Fails every put_if_absent / put after the first `ok` successful writes."""

    def __init__(self, ok: int) -> None:
        super().__init__()
        self.ok = ok

    def _write_allowed(self) -> None:
        if self.ok <= 0:
            raise StoreUnavailable("flaky")
        self.ok -= 1

    def put_if_absent(self, key: str, value: object) -> bool:
        self._write_allowed()
        return super().put_if_absent(key, value)

    def put(self, key: str, value: object) -> None:
        self._write_allowed()
        super().put(key, value)


@pytest.mark.parametrize("writes_before_failure", [1, 2, 3])
def test_a_store_failure_mid_count_undoes_the_claim(writes_before_failure: int) -> None:
    """A consume that dies half-way answers 503 and leaves nothing behind: the retry is counted normally
    (not waved through as 'already counted'), and no unit is lost for an answer never given."""
    store, clock = FlakyStore(ok=10**6), FakeClock()
    qm = QuotaManager(store, clock)
    p = google()
    seasoned(qm, clock, p)
    store.ok = writes_before_failure
    with pytest.raises(ApiError) as e:
        qm.consume(p, "read", "req-flaky-01")
    assert e.value.code == "meter_unavailable"
    assert not any(":rid:" in k or k.endswith(":read:0") for k in store.keys_with_prefix("quota:"))
    store.ok = 10**6
    assert qm.remaining(p)["reads_left"] == 6
    for i in range(6):
        qm.consume(p, "read", "req-flaky-01" if i == 0 else f"req-after-{i:02d}")
    with pytest.raises(ApiError):
        qm.consume(p, "read", "req-after-99")


def test_heavy_account_detection() -> None:
    qm, _, clock = setup()
    p = google()
    seasoned(qm, clock, p)
    for i in range(3):
        qm.consume(p, "read", f"h-{i:06d}")
    assert not qm.is_heavy(p)
    qm.consume(p, "read", "h-000003")  # 4 of 6
    assert qm.is_heavy(p)


def test_first_seen_is_snapshotted_to_firestore_and_survives_dict_loss() -> None:
    fs = FakeFirestore()
    store, clock = MemoryStore(), FakeClock()
    p = google()
    QuotaManager(store, clock, firestore=fs).first_seen(p)
    assert fs.get(f"usage_snapshot/{p.sub_hmac}")["first_seen"] == clock.now_utc()
    clock.advance(10 * 86400)
    fresh_store = MemoryStore()  # Dict entries expired
    qm = QuotaManager(fresh_store, clock, firestore=fs)
    assert not qm.is_young(p)
    assert fresh_store.get(f"first_seen:{p.sub_hmac}") is not None


def test_first_seen_without_firestore_and_with_firestore_down() -> None:
    fs = FakeFirestore()
    fs.fail = True
    qm, store, clock = setup(fs)
    p = google()
    assert qm.first_seen(p) == clock.now_utc()
    assert store.get(f"first_seen:{p.sub_hmac}") == clock.now_utc().isoformat()


def test_first_seen_snapshot_missed_while_firestore_was_down_is_written_later() -> None:
    fs = FakeFirestore()
    fs.fail = True
    store, clock = MemoryStore(), FakeClock()
    p = google()
    born = clock.now_utc()
    QuotaManager(store, clock, firestore=fs).first_seen(p)
    assert fs.docs == {}
    fs.fail = False
    clock.advance(3600)
    QuotaManager(store, clock, firestore=fs).first_seen(p)  # a new container: cache miss, Dict hit
    assert fs.get(f"usage_snapshot/{p.sub_hmac}")["first_seen"] == born
    writes = len(fs.writes)
    QuotaManager(store, clock, firestore=fs).first_seen(p)
    assert len(fs.writes) == writes  # synced once, not on every cache miss
    clock.advance(10 * 86400)
    assert not QuotaManager(MemoryStore(), clock, firestore=fs).is_young(p)  # survives the Dict expiring


def test_first_seen_is_copied_forward_to_the_new_key_after_rotation() -> None:
    """After the 30-day grace the previous key is gone: the durable age must already sit under the new key."""
    fs = FakeFirestore()
    store, clock = MemoryStore(), FakeClock()
    before = google(ring=HmacKeyring(["old-key"]))
    QuotaManager(store, clock, firestore=fs).first_seen(before)
    born = clock.now_utc()
    clock.advance(5 * 86400)
    after = google(ring=HmacKeyring(["new-key", "old-key"], store=store, clock=clock))
    QuotaManager(store, clock, firestore=fs).first_seen(after)
    assert fs.get(f"usage_snapshot/{after.sub_hmac}")["first_seen"] == born

    clock.advance(40 * 86400)
    only_new = google(ring=HmacKeyring(["new-key"]))
    assert QuotaManager(MemoryStore(), clock, firestore=fs).first_seen(only_new) == born


def test_hmac_rotation_dual_read_migrates_counters_and_age() -> None:
    store, clock = MemoryStore(), FakeClock()
    old_ring = HmacKeyring(["old-key"])
    before = google(ring=old_ring)
    qm = QuotaManager(store, clock)
    qm.first_seen(before)
    clock.advance(30 * 3600)
    for i in range(4):
        qm.consume(before, "read", f"rot-{i:05d}")

    rotated = HmacKeyring(["new-key", "old-key"], store=store, clock=clock)
    after = google(ring=rotated)
    assert after.sub_hmac != before.sub_hmac and after.sub_hmac_previous == before.sub_hmac
    qm2 = QuotaManager(store, clock)
    assert not qm2.is_young(after)
    assert qm2.remaining(after)["reads_left"] == 2
    qm2.consume(after, "read", "rot-00000")  # already counted under the old key: idempotent
    assert qm2.remaining(after)["reads_left"] == 2
    qm2.consume(after, "read", "rot-new01")
    qm2.consume(after, "read", "rot-new02")
    with pytest.raises(ApiError):
        qm2.consume(after, "read", "rot-new03")
    assert store.get(f"first_seen:{after.sub_hmac}") is not None


def test_request_ids_with_odd_characters_are_hashed_into_keys() -> None:
    qm, store, clock = setup()
    p = google()
    seasoned(qm, clock, p)
    qm.consume(p, "read", "weird:id/with spaces")
    qm.consume(p, "read", "weird:id/with spaces")
    assert qm.remaining(p)["reads_left"] == 5
    assert not any("weird" in k for k in store.keys_with_prefix("quota:"))


def test_young_threshold_is_24_hours() -> None:
    qm, _, clock = setup()
    p = google()
    qm.first_seen(p)
    clock.advance(timedelta(hours=23, minutes=59).total_seconds())
    assert qm.is_young(p)
    clock.advance(61)
    assert not qm.is_young(p)


# ---- daily caps on free endpoints ------------------------------------------------------------------------


def test_daily_cap_is_exact_per_user_and_resets_at_ist_midnight() -> None:
    store, clock = MemoryStore(), FakeClock()
    a, b = google("a", "g-a"), google("b", "g-b")
    assert all(allow_daily(store, clock, a, "event", 5) for _ in range(5))
    assert not allow_daily(store, clock, a, "event", 5)
    assert allow_daily(store, clock, a, "erase", 5)  # actions are separate
    assert allow_daily(store, clock, b, "event", 5)  # users are separate
    clock.advance(86400)
    assert allow_daily(store, clock, a, "event", 5)
    assert all(
        k.startswith((f"quota:{a.sub_hmac}:", f"quota:{b.sub_hmac}:")) for k in store.keys_with_prefix("")
    )


def test_daily_cap_holds_under_concurrency() -> None:
    store, clock = MemoryStore(), FakeClock()
    p = google()
    allowed: list[int] = []
    lock = threading.Lock()

    def worker() -> None:
        if allow_daily(store, clock, p, "event", 7):
            with lock:
                allowed.append(1)

    threads = [threading.Thread(target=worker) for _ in range(40)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    assert len(allowed) == 7
