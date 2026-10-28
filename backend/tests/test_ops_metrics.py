"""metrics.py: per-window aggregation in the hot store (sharded per container), snapshots, rollups, helpers."""

from __future__ import annotations

from datetime import UTC, datetime, timedelta

from ridemate_api.clock import FakeClock
from ridemate_api.metrics import (
    DROPPED,
    EXCEPTIONS,
    HTTP_LATENCY_MS,
    HTTP_REQUESTS,
    MAX_SERIES_PER_WINDOW,
    SNAPSTATE_KEY,
    DictMetrics,
    Histogram,
    firestore_event_hook,
    parse_series,
    record_exception,
    record_request,
    record_session_event,
    series_key,
    window_id,
)
from ridemate_api.store import MemoryStore
from tests.test_ops_fakes import FakeFirestore

T0 = datetime(2026, 10, 28, 4, 30, 5, tzinfo=UTC)  # 10:00:05 IST, window 202610280430


def setup(shard: str = "s1", store: MemoryStore | None = None, clock: FakeClock | None = None):
    store = store or MemoryStore()
    clock = clock or FakeClock(T0)
    return DictMetrics(store, clock, shard=shard, background_flush=False), store, clock


def test_window_ids_are_five_minute_utc_buckets():
    assert window_id(T0) == "202610280430"
    assert window_id(T0 + timedelta(minutes=4, seconds=54)) == "202610280430"
    assert window_id(T0 + timedelta(minutes=5)) == "202610280435"


def test_series_keys_round_trip_and_sanitise_labels():
    key = series_key("http_requests", {"status_class": "5xx", "route": "/v1/read-screen"})
    assert key == "http_requests{route=/v1/read-screen,status_class=5xx}"
    assert parse_series(key) == ("http_requests", {"route": "/v1/read-screen", "status_class": "5xx"})
    assert parse_series(series_key("x", {"bad": "a,b=c{d}"})) == ("x", {"bad": "a_b_c_d_"})


def test_counts_are_flushed_per_window_and_shard_after_the_interval():
    m, store, clock = setup()
    m.incr("ai_call", task="read", outcome="ok")
    assert store.keys_with_prefix("metrics:") == []  # not flushed yet
    clock.advance(10)
    m.incr("ai_call", task="read", outcome="ok")
    m.observe_ms(HTTP_LATENCY_MS, 1200, route="/v1/read-screen")
    data = store.get("metrics:202610280430:s1")
    assert data["c"]["ai_call{outcome=ok,task=read}"] == 2
    assert m.flush()
    assert store.get("metrics:202610280430:s1")["h"]["http_latency_ms{route=/v1/read-screen}"]["n"] == 1


def test_histogram_quantiles_use_bucket_upper_bounds():
    h = Histogram()
    for value in [100] * 94 + [16_000] * 6:
        h.observe(value)
    assert h.quantile(0.5) == 100
    assert h.quantile(0.95) == 20_000
    overflow = Histogram()
    overflow.observe(75_000)
    assert overflow.quantile(0.95) == 75_000
    assert Histogram().quantile(0.95) is None


def test_shards_are_merged_and_finished_windows_are_snapshotted_to_firestore():
    store, clock = MemoryStore(), FakeClock(T0)
    a, _, _ = setup("a", store, clock)
    b, _, _ = setup("b", store, clock)
    record_request(a, "/v1/read-screen", 200, 900)
    record_request(b, "/v1/read-screen", 503, 15_500)
    record_exception(b, TimeoutError("user text ₹110 must not be stored"))
    a.flush()
    b.flush()
    fs = FakeFirestore()

    view = a.snapshot_to_firestore(fs, T0 + timedelta(minutes=2))
    assert fs.writes == []  # window still open
    assert view.recent(10).counter(HTTP_REQUESTS) == 2

    clock.advance(8 * 60)
    view = a.snapshot_to_firestore(fs, clock.now_utc())
    paths = [w[0] for w in fs.writes]
    assert paths == ["metrics/2026-10-28", "metrics/2026-10-28/shards/202610280430"]
    doc = fs.docs["metrics/2026-10-28/shards/202610280430"]
    assert doc["counters"]["http_requests{route=/v1/read-screen,status_class=5xx}"] == 1
    assert doc["counters"]["http_requests{route=/v1/read-screen,status_class=2xx}"] == 1
    assert doc["exceptions"] == 1
    assert doc["counters"]["exceptions{type=TimeoutError}"] == 1
    assert doc["latency"]["http_latency_ms{route=/v1/read-screen}"]["p95_ms"] == 20_000
    assert "110" not in str(doc)
    assert view.hours["2026102804"].counter(HTTP_REQUESTS) == 2
    assert view.last_hours(1).counter(HTTP_REQUESTS, status_class="5xx") == 1


def test_unchanged_windows_are_not_rewritten_but_late_flushes_are():
    m, store, clock = setup()
    m.incr("x")
    m.flush()
    clock.advance(10 * 60)
    fs = FakeFirestore()
    m.snapshot_to_firestore(fs, clock.now_utc())
    count = len(fs.writes)
    m.snapshot_to_firestore(fs, clock.now_utc())
    assert len(fs.writes) == count
    # An idle container flushes late into the old window: the merged doc is rewritten in full.
    late, _, _ = setup("late", store, FakeClock(T0))
    late.incr("x", 4)
    late.flush()
    m.snapshot_to_firestore(fs, clock.now_utc())
    assert fs.docs["metrics/2026-10-28/shards/202610280430"]["counters"]["x"] == 5
    assert len(fs.writes) == count + 1


def test_a_firestore_outage_is_retried_on_the_next_snapshot():
    m, _, clock = setup()
    m.incr("x")
    m.flush()
    clock.advance(10 * 60)
    fs = FakeFirestore()
    fs.fail = True
    view = m.snapshot_to_firestore(fs, clock.now_utc())
    assert view.hours["2026102804"].counter("x") == 1  # rollups don't wait for Firestore
    fs.fail = False
    m.snapshot_to_firestore(fs, clock.now_utc())
    assert "metrics/2026-10-28/shards/202610280430" in fs.docs


def test_old_hot_store_keys_are_pruned():
    m, store, clock = setup()
    m.incr("x")
    m.flush()
    clock.advance(27 * 3600)
    m.snapshot_to_firestore(None, clock.now_utc())
    assert "metrics:202610280430:s1" not in store.keys_with_prefix("metrics:")
    clock.advance(23 * 3600)
    m.snapshot_to_firestore(None, clock.now_utc())
    assert not [k for k in store.keys_with_prefix("metrics:hour:")]
    assert store.get(SNAPSTATE_KEY) is not None


def test_recording_never_raises_and_unflushed_data_is_kept():
    m, store, clock = setup()
    store.fail = True
    m.incr("x")
    clock.advance(11)
    m.observe_ms("y", 5)  # flush attempt fails inside
    assert m.flush() is False
    store.fail = False
    assert m.flush() is True
    data = store.get("metrics:202610280430:s1")
    assert data["c"]["x"] == 1 and data["h"]["y"]["n"] == 1


def test_label_cardinality_is_capped_per_window():
    m, store, _ = setup()
    for i in range(MAX_SERIES_PER_WINDOW + 25):
        m.incr("route_hits", route=f"/r{i}")
    m.flush()
    counters = store.get("metrics:202610280430:s1")["c"]
    assert len(counters) == MAX_SERIES_PER_WINDOW + 1
    assert counters[DROPPED] == 25


def test_session_events_and_exceptions_feed_the_alert_counters():
    m, _, clock = setup()
    record_session_event(
        m,
        {
            "reads": 4,
            "verified_rows": 9,
            "unreadable_rows": 1,
            "rule_hits": 6,
            "rule_misses": 1,
            "misses_unclassified": 0,
            "outcome": "confirmed_all",
            "android_version": 34,
        },
    )
    record_session_event(m, {"reads": True, "verified_rows": -3, "rule_hits": 10**9})
    record_exception(m, KeyError("free text"))
    firestore_event_hook(m)("shed")
    assert m.flush()
    agg = m.collect(clock.now_utc()).recent(5)
    assert agg.counter("event_reads") == 4
    assert agg.counter("event_verified_rows") == 9
    assert agg.counter("event_rule_hits") == 6
    assert agg.counter("event_misses_unclassified") == 0
    assert agg.counter("event_android_version") == 0
    assert agg.counter(EXCEPTIONS, type="KeyError") == 1
    assert agg.counter("firestore_events", kind="shed") == 1


def test_hourly_series_is_oldest_first():
    m, _, clock = setup()
    m.incr("event_misses_unclassified", 2)
    m.flush()
    clock.advance(3600)
    m.incr("event_misses_unclassified", 5)
    m.flush()
    clock.advance(600)
    view = m.snapshot_to_firestore(None, clock.now_utc())
    assert view.hourly(3, "event_misses_unclassified") == [0, 2, 5]  # 03:00, 04:00, 05:00 (current) UTC


def test_periodic_flushes_never_block_the_recording_caller():
    import threading

    release = threading.Event()

    class SlowStore(MemoryStore):
        def put(self, key, value):
            release.wait(5)
            super().put(key, value)

    store, clock = SlowStore(), FakeClock(T0)
    m = DictMetrics(store, clock, shard="bg")
    m.incr("x")
    clock.advance(10)
    m.incr("x")  # due: flushes on another thread while this call returns at once
    m.incr("x")  # a flush is already in flight: no second thread
    assert store.keys_with_prefix("metrics:") == []
    release.set()
    for thread in [t for t in threading.enumerate() if t.name == "metrics-flush"]:
        thread.join(5)
    assert store.get("metrics:202610280430:bg")["c"]["x"] >= 2
    assert m.flush()
    assert store.get("metrics:202610280430:bg")["c"]["x"] == 3
