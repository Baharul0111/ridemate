"""cron.py: the single dispatcher — job isolation, time limits, daily/weekly gating, probe, cleanup,
billing reconciliation, drift run, heartbeat and compute metering."""

from __future__ import annotations

import asyncio
import json
import sys
import types
from dataclasses import replace
from datetime import UTC, datetime, timedelta
from pathlib import Path
from types import SimpleNamespace

import httpx
import pytest
import respx

from ridemate_api import cron
from ridemate_api.alerts import DeliveryResult
from ridemate_api.clock import IST, FakeClock
from ridemate_api.cron import (
    BillingUnavailable,
    DispatcherHooks,
    JobSpec,
    ProbeResult,
    billing_window,
    cleanup_firestore,
    cleanup_hot_store,
    compare_read,
    default_hooks,
    is_ridemate_cost,
    make_golden_probe,
    meter_day_total_usd,
    reconcile,
    run_dispatcher,
    template_doctor_drift,
)
from ridemate_api.interfaces import ApiError, Purpose
from ridemate_api.settings import CRON_CPU_CORES, CRON_MEMORY_MIB, SUB_BUDGET_EVAL_RUN_USD
from ridemate_api.store import MemoryStore
from tests.test_ops_fakes import HC_HEARTBEAT, FakeFirestore, FakeRouter, FakeSpend, make_deps

NOW = datetime(2026, 10, 28, 5, 0, tzinfo=UTC)  # Wednesday 10:30 IST
NANO = 1_000_000_000


class FakeDelivery:
    def __init__(self) -> None:
        self.sent: list[list[str]] = []
        self.ok_pings = 0

    def send(self, alerts, *, test: bool = False) -> DeliveryResult:
        self.sent.append([a.name for a in alerts])
        return DeliveryResult(healthchecks=True, pushes=1)

    def ping_ok(self) -> bool:
        self.ok_pings += 1
        return True


class Replayable(FakeFirestore):
    def __init__(self) -> None:
        super().__init__()
        self.replays = 0

    def replay_buffer(self, *, max_items: int = 500, stop=None) -> int:
        self.replays += 1
        self.stop = stop
        return 0


def hooks(**overrides) -> DispatcherHooks:
    base = DispatcherHooks(delivery=FakeDelivery(), http_client=httpx.Client())
    for key, value in overrides.items():
        setattr(base, key, value)
    return base


def statuses(report) -> dict[str, str]:
    return {o.name: o.status for o in report.outcomes}


@pytest.fixture
def heartbeat():
    with respx.mock(assert_all_called=False) as mock:
        yield mock.post(HC_HEARTBEAT).mock(return_value=httpx.Response(200, text="OK"))


# ---- the run -----------------------------------------------------------------------------------------------


async def test_a_full_run_executes_every_job_meters_compute_and_pings_the_heartbeat(heartbeat):
    spend, fs = FakeSpend(), Replayable()
    deps = make_deps(clock=FakeClock(NOW), spend=spend, firestore=fs)
    h = hooks(billing_report=lambda start, end: [], drift=None)
    report = await run_dispatcher(deps, NOW, hooks=h)
    assert statuses(report) == {
        "metrics": "ok",
        "spend_snapshot": "ok",
        "replay_buffer": "ok",
        "pending_erasures": "ok",
        "cleanup": "ok",
        "probe": "skipped",  # no router in this deployment
        "reconcile": "ok",
        "drift": "skipped",
        "alerts": "ok",
    }
    assert report.heartbeat and heartbeat.call_count == 1
    assert spend.compacted == 1 and spend.snapshots == [fs]
    assert fs.replays == 1
    assert spend.compute == [
        {
            "cpu_cores": CRON_CPU_CORES,
            "memory_mib": CRON_MEMORY_MIB,
            "seconds": cron.IDLE_AFTER_RUN_S,
            "source": "cron",
        }
    ]
    assert h.delivery.ok_pings == 1  # nothing firing
    assert json.loads(json.dumps(report.as_dict()))["outcomes"][0]["name"] == "metrics"


async def test_daily_and_weekly_jobs_run_once_per_period(heartbeat):
    deps = make_deps(clock=FakeClock(NOW))
    h = hooks(billing_report=lambda s, e: [])
    await run_dispatcher(deps, NOW, hooks=h)
    second = statuses(await run_dispatcher(deps, NOW + timedelta(minutes=15), hooks=h))
    assert second["cleanup"] == second["reconcile"] == second["probe"] == second["drift"] == "not_due"
    assert second["metrics"] == second["alerts"] == "ok"
    next_day = statuses(await run_dispatcher(deps, NOW + timedelta(days=1), hooks=h))
    assert next_day["cleanup"] == next_day["reconcile"] == "ok" and next_day["drift"] == "not_due"
    assert deps.store.get("cron:last:cleanup") == "2026-10-29"


async def test_daily_jobs_wait_for_their_hour_in_ist(heartbeat):
    early = datetime(2026, 10, 28, 2, 0, tzinfo=IST).astimezone(UTC)
    deps = make_deps(clock=FakeClock(early))
    result = statuses(await run_dispatcher(deps, early, hooks=hooks(billing_report=lambda s, e: [])))
    assert result["cleanup"] == result["probe"] == result["reconcile"] == "not_due"
    at_three = datetime(2026, 10, 28, 3, 5, tzinfo=IST).astimezone(UTC)
    result = statuses(await run_dispatcher(deps, at_three, hooks=hooks(billing_report=lambda s, e: [])))
    assert result["cleanup"] == "ok" and result["reconcile"] == "not_due"


def test_weekly_drift_waits_for_monday_morning():
    spec = next(s for s in cron.JOBS if s.name == "drift")
    store = MemoryStore()
    monday_early = datetime(2026, 11, 2, 3, 59, tzinfo=IST)
    monday = datetime(2026, 11, 2, 4, 0, tzinfo=IST)
    assert cron.period_id(spec, monday) == "2026-W45"
    assert not cron._due(store, spec, "2026-W45", monday_early)
    assert cron._due(store, spec, "2026-W45", monday)
    store.put("cron:last:drift", "2026-W45")
    assert not cron._due(store, spec, "2026-W45", monday + timedelta(days=3))


def test_an_unreadable_hot_store_never_lets_a_daily_job_run():
    store = MemoryStore()
    store.fail = True
    spec = next(s for s in cron.JOBS if s.name == "probe")
    assert not cron._due(store, spec, "2026-10-28", NOW)


async def test_one_failing_job_never_stops_the_others_and_raises_an_alert(heartbeat):
    spend = FakeSpend()
    spend.compact = lambda: (_ for _ in ()).throw(RuntimeError("boom"))
    deps = make_deps(clock=FakeClock(NOW), spend=spend, firestore=FakeFirestore())
    h = hooks(billing_report=lambda s, e: [])
    report = await run_dispatcher(deps, NOW, hooks=h)
    result = statuses(report)
    assert result["spend_snapshot"] == "failed"
    assert report.outcome("spend_snapshot").detail == {"error_type": "RuntimeError"}
    assert result["cleanup"] == result["alerts"] == "ok"
    assert h.delivery.sent == [["cron_job_failed_spend_snapshot"]]
    assert report.heartbeat


async def test_a_hanging_job_is_cut_off_and_the_run_continues(heartbeat, monkeypatch):
    async def slow_probe(deps):
        await asyncio.sleep(5)
        return ProbeResult("pass")

    jobs = tuple(replace(s, timeout_s=0.05) if s.name == "probe" else s for s in cron.JOBS)
    monkeypatch.setattr(cron, "JOBS", jobs)
    deps = make_deps(clock=FakeClock(NOW), router=FakeRouter())
    h = hooks(probe=slow_probe, billing_report=lambda s, e: [])
    report = await run_dispatcher(deps, NOW, hooks=h)
    result = statuses(report)
    assert result["probe"] == "timeout"
    assert result["reconcile"] == result["alerts"] == "ok"
    assert h.delivery.sent == [["cron_job_failed_probe"]]
    assert deps.store.get("cron:fail:probe") == {"period": "2026-10-28", "count": 1}
    assert deps.store.get("cron:last:probe") is None  # retried on the next run


class SlowFirestore(Replayable):
    """Every delete takes 10 s of (fake) time, as a congested Firestore might."""

    def __init__(self, clock: FakeClock) -> None:
        super().__init__()
        self.clock = clock

    def delete(self, path: str) -> None:
        super().delete(path)
        self.clock.advance(10)


async def test_threaded_jobs_stop_at_their_time_limit_instead_of_running_on(heartbeat):
    clock = FakeClock(NOW)
    fs = SlowFirestore(clock)
    for i in range(100):
        fs.docs[f"events/e{i:03d}"] = {"created_at": NOW - timedelta(days=400)}
    deps = make_deps(clock=clock, firestore=fs)
    report = await run_dispatcher(deps, NOW, hooks=hooks(billing_report=lambda s, e: []))
    cleanup = report.outcome("cleanup")
    assert cleanup.status == "ok"
    limit = next(s.timeout_s for s in cron.JOBS if s.name == "cleanup")
    # Deletes stop once the job's time (minus one Firestore deadline) is used up, not after the 2,000 cap.
    assert len(fs.deleted) == int((limit - cron.STOP_MARGIN_S) // 10) + 1
    assert cleanup.detail["firestore"] == {"events": len(fs.deleted)}
    assert callable(fs.stop)  # the replay job got the same stop check


def test_cleanup_stops_when_told():
    fs = FakeFirestore()
    for i in range(30):
        fs.docs[f"events/e{i:02d}"] = {"created_at": NOW - timedelta(days=400)}
    stop_after = iter([False] * 5 + [True] * 100)
    assert cleanup_firestore(fs, NOW, stop=lambda: next(stop_after)) == {"events": 4}


async def test_the_alert_run_asks_the_endpoint_whether_the_pinned_model_is_still_served(heartbeat):
    router = FakeRouter()

    async def model_available(task=None):
        router.reason = "model_missing"  # what the real router records after GET /models
        return False

    router.model_available = model_available
    deps = make_deps(clock=FakeClock(NOW), router=router)
    h = hooks(billing_report=lambda s, e: [])
    report = await run_dispatcher(deps, NOW, hooks=h)
    assert report.outcome("alerts").status == "ok"
    assert ["model_missing"] in h.delivery.sent


async def test_an_unanswered_model_check_is_not_an_alert(heartbeat, monkeypatch):
    monkeypatch.setattr(cron, "MODEL_CHECK_TIMEOUT_S", 0.01)
    router = FakeRouter()

    async def hangs(task=None):
        await asyncio.sleep(5)

    router.model_available = hangs
    deps = make_deps(clock=FakeClock(NOW), router=router)
    h = hooks(billing_report=lambda s, e: [])
    report = await run_dispatcher(deps, NOW, hooks=h)
    assert report.outcome("alerts").status == "ok"
    assert h.delivery.sent == [] and h.delivery.ok_pings == 1


def test_a_failing_daily_job_is_retried_at_most_three_times():
    store = MemoryStore()
    store.put("cron:last:reconcile", "2026-10-28")
    spec = JobSpec("reconcile", "daily", 30, cron.time(10, 0))
    for attempt in range(1, 4):
        assert cron._due(store, spec, "2026-10-29", NOW + timedelta(days=1))
        cron._settle_period(store, spec, "2026-10-29", "failed")
        assert store.get("cron:fail:reconcile") == {"period": "2026-10-29", "count": attempt}
    assert store.get("cron:last:reconcile") == "2026-10-29"
    assert not cron._due(store, spec, "2026-10-29", NOW + timedelta(days=1))
    cron._settle_period(store, spec, "2026-10-30", "ok")
    assert store.get("cron:fail:reconcile") is None


async def test_the_heartbeat_is_skipped_without_a_url_and_failures_are_tolerated():
    deps = make_deps(clock=FakeClock(NOW))
    deps.settings = replace(deps.settings, healthchecks_heartbeat_url="")
    report = await run_dispatcher(deps, NOW, hooks=hooks())
    assert report.heartbeat is False
    with respx.mock:
        respx.post(HC_HEARTBEAT).mock(side_effect=httpx.ConnectError("down"))
        deps.settings = replace(deps.settings, healthchecks_heartbeat_url=HC_HEARTBEAT)
        report = await run_dispatcher(make_deps(clock=FakeClock(NOW)), NOW, hooks=hooks())
    assert report.heartbeat is False


async def test_compute_is_still_reported_when_the_meter_fails(heartbeat):
    spend = FakeSpend()
    spend.record_compute = lambda **kw: (_ for _ in ()).throw(RuntimeError("meter down"))
    report = await run_dispatcher(make_deps(clock=FakeClock(NOW), spend=spend), NOW, hooks=hooks())
    assert report.compute_usd is None and report.heartbeat


async def test_pending_erasures_are_finished_by_the_dispatcher(heartbeat, monkeypatch):
    from ridemate_api.api import misc

    calls = []
    monkeypatch.setattr(misc, "complete_pending_erasures", lambda deps: calls.append(deps) or 2)
    deps = make_deps(clock=FakeClock(NOW), firestore=FakeFirestore())
    report = await run_dispatcher(deps, NOW, hooks=hooks())
    assert report.outcome("pending_erasures").detail == {"count": 2}
    assert calls == [deps]


# ---- golden probe ------------------------------------------------------------------------------------------

LABEL = {
    "file": "demo/kestrel/fare_options.png",
    "sha256": "x",
    "app": "demo_kestrel",
    "screen_type": "fare_options",
    "rows": [{"vehicle_class": "auto", "label": "Auto", "price_min": 110, "price_max": 110, "eta_min": 3}],
}
GOOD_READ = {
    "app": "demo_kestrel",
    "screen_type": "fare_options",
    "options": [
        {
            "vehicle_class": "auto",
            "label_text": "Auto",
            "price_min": 110,
            "price_max": 110,
            "price_struck_text": None,
            "eta_min": 3,
            "bbox_0_1000": [10, 10, 900, 100],
        }
    ],
    "fee_text": None,
    "driver_eta_min": None,
    "driver_distance_text": None,
}


def golden(tmp_path: Path) -> Path:
    image = tmp_path / LABEL["file"]
    image.parent.mkdir(parents=True)
    image.write_bytes(b"\x89PNG\r\n\x1a\n" + b"\x00" * 32)
    labels = tmp_path / "demo_labels.json"
    labels.write_text(json.dumps({"schema": "1.0", "screens": [LABEL, {**LABEL, "file": "missing.png"}]}))
    return labels


async def test_the_probe_reads_a_demo_screen_through_the_router_as_a_probe(tmp_path):
    router = FakeRouter()
    router.result = SimpleNamespace(data=GOOD_READ, latency_ms=2100, cost_usd=0.003)
    probe = make_golden_probe([(tmp_path / "nope.json", tmp_path), (golden(tmp_path), tmp_path)])
    result = await probe(make_deps(clock=FakeClock(NOW), router=router))
    assert result == ProbeResult("pass", "", latency_ms=2100, cost_usd=0.003)
    (call,) = router.calls
    assert call["purpose"] is Purpose.PROBE and call["principal"] is None
    assert call["request_id"] == "probe-2026-10-28"
    assert call["request"].image_count == 1


async def test_a_wrong_read_fails_the_probe_and_alerts(tmp_path, heartbeat):
    router = FakeRouter()
    router.result = SimpleNamespace(data={**GOOD_READ, "options": []}, latency_ms=1, cost_usd=0.0)
    deps = make_deps(clock=FakeClock(NOW), router=router)
    h = hooks(probe=make_golden_probe([(golden(tmp_path), tmp_path)]))
    report = await run_dispatcher(deps, NOW, hooks=h)
    assert report.outcome("probe").detail == {"probe": "fail", "reason": "price"}
    assert deps.store.get("cron:probe:last")["status"] == "fail"
    assert "probe_failed" in h.delivery.sent[0]


@pytest.mark.parametrize(
    ("error", "status", "reason"),
    [
        (ApiError(429, "ai_paused", reason="budget"), "skipped", "ai_paused"),
        (ApiError(503, "meter_unavailable"), "skipped", "meter_unavailable"),
        (ApiError(503, "ai_unavailable", reason="model_missing"), "fail", "model_missing"),
        (ApiError(504, "model_timeout"), "fail", "model_timeout"),
    ],
)
async def test_probe_outcomes_for_router_errors(tmp_path, error, status, reason):
    router = FakeRouter()
    router.error = error
    result = await make_golden_probe([(golden(tmp_path), tmp_path)])(make_deps(router=router))
    assert (result.status, result.reason) == (status, reason)


async def test_probe_skips_cleanly_without_labels_or_router(tmp_path, heartbeat):
    result = await make_golden_probe([(tmp_path / "none.json", tmp_path)])(make_deps(router=FakeRouter()))
    assert result.status == "skipped"
    report = await run_dispatcher(
        make_deps(clock=FakeClock(NOW)), NOW, hooks=hooks(probe=make_golden_probe([]))
    )
    assert report.outcome("probe").detail == {"reason": "no_router"}


def test_compare_read_checks_app_screen_and_every_labelled_price():
    assert compare_read(GOOD_READ, LABEL) is None
    assert compare_read({**GOOD_READ, "app": "uber"}, LABEL) == "app"
    assert compare_read({**GOOD_READ, "app": None}, LABEL) is None
    assert compare_read({**GOOD_READ, "screen_type": "home"}, LABEL) == "screen_type"
    wrong = {**GOOD_READ, "options": [{**GOOD_READ["options"][0], "price_min": 111, "price_max": 111}]}
    assert compare_read(wrong, LABEL) == "price"


# ---- cleanup -----------------------------------------------------------------------------------------------


def test_cleanup_deletes_expired_records_only():
    fs = FakeFirestore()
    old, new = NOW - timedelta(days=400), NOW - timedelta(days=10)
    fs.docs.update(
        {
            "events/old": {"created_at": old},
            "events/new": {"created_at": new},
            "events/old_iso": {"created_at": old.isoformat()},
            "audit/old": {"created_at": old},
            "audit/new": {"created_at": new},
            "ondc_txn/old": {"created_at": NOW - timedelta(days=181)},
            "ondc_txn/new": {"created_at": NOW - timedelta(days=179)},
            "ondc_event/old": {"received_at": NOW - timedelta(days=200)},
            "ondc_event/new": {"received_at": new},
            "usage_snapshot/erased_old": {"erased_at": NOW - timedelta(hours=49), "first_seen": old},
            "usage_snapshot/erased_new": {"erased_at": NOW - timedelta(hours=47)},
            "usage_snapshot/active": {"first_seen": old},
            "spend_shards/lifetime_s1": {"period": "lifetime"},
            "spend_shards/rollup": {"period": "rollup"},
            "spend_shards/2026-08-01_s1": {"period": "2026-08-01"},
            "spend_shards/2026-10-01_s1": {"period": "2026-10-01"},
            "metrics/2026-07-01": {"day": "2026-07-01"},
            "metrics/2026-07-01/shards/202607010000": {"day": "2026-07-01"},
            "metrics/2026-07-01/shards/202607010005": {"day": "2026-07-01"},
            "metrics/2026-10-01": {"day": "2026-10-01"},
            "metrics/2026-10-01/shards/202610010000": {"day": "2026-10-01"},
        }
    )
    deleted = cleanup_firestore(fs, NOW)
    assert sorted(fs.deleted) == sorted(
        [
            "events/old",
            "events/old_iso",
            "audit/old",
            "ondc_txn/old",
            "ondc_event/old",
            "usage_snapshot/erased_old",
            "spend_shards/2026-08-01_s1",
            "metrics/2026-07-01/shards/202607010000",
            "metrics/2026-07-01/shards/202607010005",
            "metrics/2026-07-01",
        ]
    )
    assert deleted["events"] == 2 and deleted["metrics"] == 2 and deleted["metrics_days"] == 1


def test_cleanup_is_bounded_per_run():
    fs = FakeFirestore()
    for i in range(30):
        fs.docs[f"events/e{i:02d}"] = {"created_at": NOW - timedelta(days=400)}
    assert cleanup_firestore(fs, NOW, max_deletes=12) == {"events": 12}
    assert len(fs.docs) == 18


def test_hot_store_cleanup_removes_old_quota_slots_and_budget_counters():
    store = MemoryStore()
    for key in [
        "quota:h1:2026-10-25:read:0",
        "quota:h1:2026-10-25:rid:abc",
        "quota:h1:2026-10-27:read:0",
        "quota:h1:2026-10-28",
        "fs:writes:2026-10-20:w1",
        "fs:exhausted:writes:2026-10-20",
        "fs:writes:2026-10-27:w1",
        "spend:s1:ai:2026-01-01",
    ]:
        store.put(key, 1)
    assert cleanup_hot_store(store, NOW) == {"quota": 2, "fs": 2}
    assert sorted(store.keys_with_prefix("")) == [
        "fs:writes:2026-10-27:w1",
        "quota:h1:2026-10-27:read:0",
        "quota:h1:2026-10-28",
        "spend:s1:ai:2026-01-01",
    ]


# ---- billing reconciliation --------------------------------------------------------------------------------


def meter_store(ai_usd: float, compute_usd: float = 0.0) -> MemoryStore:
    store = MemoryStore()
    store.put("spend:live1:ai:2026-10-27", int(ai_usd / 2 * NANO))
    store.put("spend:live1:compute:2026-10-27", int(compute_usd * NANO))
    store.put("spend:live1:ai:2026-10-28", 99 * NANO)  # today: not yesterday
    store.put("spend:live1:ai:lifetime", 50 * NANO)
    store.put("spend:old:ai:2026-10-27", 77 * NANO)  # frozen: counted through the rollup only
    store.put("spend:rollup", {"frozen": ["old"], "totals": {"ai:2026-10-27": int(ai_usd / 2 * NANO)}})
    return store


def rows(*costs: tuple[str, float]) -> list[dict]:
    return [
        {"description": d, "object_id": "ap-1", "environment_name": "main", "cost": c, "tags": {}}
        for d, c in costs
    ]


def test_billing_window_covers_the_ist_day_in_whole_utc_hours():
    start, end = billing_window("2026-10-27")
    assert start == datetime(2026, 10, 26, 18, 0, tzinfo=UTC)
    assert end - start == timedelta(hours=24)


def test_billing_rows_for_ridemate_and_the_shared_endpoint_are_counted():
    assert is_ridemate_cost({"description": "ridemate-api"})
    assert is_ridemate_cost({"description": "Endpoint meta-models/Muse-Spark-Open-816B-A42B"})
    assert is_ridemate_cost({"description": "x", "tags": {"app": "ridemate"}})
    assert not is_ridemate_cost({"description": "someone-elses-notebook"})


def test_the_meter_total_for_yesterday_reads_live_shards_and_the_rollup():
    assert meter_day_total_usd(meter_store(2.0, 0.5), "2026-10-27") == pytest.approx(2.5)


def test_reconciliation_within_10_percent_changes_nothing():
    store, spend = meter_store(2.0, 0.5), FakeSpend()
    record = reconcile(
        store,
        spend,
        NOW,
        lambda s, e: rows(("ridemate-api", 0.6), ("muse-spark-open", 2.1), ("other-app", 5.0)),
    )
    assert record["billing_usd"] == pytest.approx(2.7)
    assert record["meter_usd"] == pytest.approx(2.5)
    assert record["drift"] == pytest.approx(0.2 / 2.7, abs=1e-4)
    assert spend.drift_factors == []
    assert store.get(cron.DRIFT_KEY)["day"] == "2026-10-27"


def test_drift_over_10_percent_tightens_the_meter():
    store, spend = meter_store(2.0, 0.0), FakeSpend()
    store.put("spend:drift_factor", 1.1)
    record = reconcile(store, spend, NOW, lambda s, e: rows(("ridemate-api", 3.0)))
    assert record["drift"] == pytest.approx(1 / 3, abs=1e-4)
    assert spend.drift_factors == [pytest.approx(1.1 * 1.5)]
    assert record["factor"] == pytest.approx(1.65)


def test_a_low_bill_alerts_but_never_loosens_the_meter():
    store, spend = meter_store(2.0, 0.0), FakeSpend()
    store.put("spend:drift_factor", 1.5)
    # The matcher may have missed the endpoint's rows: a bill under the meter must not lower the factor.
    record = reconcile(store, spend, NOW, lambda s, e: rows(("ridemate-api", 1.0)))
    assert record["drift"] == pytest.approx(0.5)
    assert record["factor"] == 1.5 and spend.drift_factors == []
    assert store.get("spend:drift_factor") == 1.5


def test_a_factor_set_above_the_cap_by_hand_is_never_lowered():
    store, spend = meter_store(2.0, 0.0), FakeSpend()
    store.put("spend:drift_factor", 5.0)
    record = reconcile(store, spend, NOW, lambda s, e: rows(("ridemate-api", 4.0)))
    assert record["factor"] == 5.0 and spend.drift_factors == []


def test_drift_factor_falls_back_to_the_store_and_is_capped():
    store = meter_store(0.0)
    record = reconcile(store, object(), NOW, lambda s, e: rows(("ridemate-api", 4.0)))
    assert record["factor"] == cron.MAX_DRIFT_FACTOR
    assert store.get("spend:drift_factor") == cron.MAX_DRIFT_FACTOR


async def test_unavailable_billing_alerts_once_and_the_run_continues(heartbeat):
    def unavailable(start, end):
        raise BillingUnavailable("NotFoundError")

    deps = make_deps(clock=FakeClock(NOW))
    h = hooks(billing_report=unavailable)
    report = await run_dispatcher(deps, NOW, hooks=h)
    assert report.outcome("reconcile").status == "skipped"
    assert h.delivery.sent == [["reconciliation_unavailable"]]
    h2 = hooks(billing_report=unavailable)
    await run_dispatcher(deps, NOW + timedelta(days=1), hooks=h2)
    assert h2.delivery.sent == []  # once, not daily
    h3 = hooks(billing_report=lambda s, e: [])
    await run_dispatcher(deps, NOW + timedelta(days=2), hooks=h3)
    assert deps.store.get(cron.RECON_UNAVAILABLE_FLAG) is None  # re-armed after a success


def test_modal_billing_errors_become_billing_unavailable(monkeypatch):
    fake_modal = types.SimpleNamespace(
        Workspace=types.SimpleNamespace(from_context=lambda: (_ for _ in ()).throw(PermissionError("plan")))
    )
    monkeypatch.setitem(sys.modules, "modal", fake_modal)
    with pytest.raises(BillingUnavailable):
        cron.modal_billing_report(NOW - timedelta(days=1), NOW)


def test_modal_billing_rows_are_normalised(monkeypatch):
    item = types.SimpleNamespace(
        description="ridemate-api", object_id="ap-1", environment_name="main", cost="0.42", tags={"a": "b"}
    )
    report = types.SimpleNamespace(report=lambda **kw: [item])
    fake_modal = types.SimpleNamespace(
        Workspace=types.SimpleNamespace(from_context=lambda: types.SimpleNamespace(billing=report))
    )
    monkeypatch.setitem(sys.modules, "modal", fake_modal)
    assert cron.modal_billing_report(NOW - timedelta(days=1), NOW) == [
        {
            "description": "ridemate-api",
            "object_id": "ap-1",
            "environment_name": "main",
            "cost": 0.42,
            "tags": {"a": "b"},
        }
    ]


# ---- weekly drift run --------------------------------------------------------------------------------------


async def test_drift_run_skips_cleanly_without_template_doctor(monkeypatch):
    monkeypatch.setitem(sys.modules, "tools.template_doctor", None)
    monkeypatch.setitem(sys.modules, "template_doctor", None)
    assert await template_doctor_drift(make_deps(router=FakeRouter())) == {
        "status": "skipped",
        "reason": "template_doctor_absent",
    }


async def test_drift_run_calls_template_doctor_in_report_mode_within_the_evals_budget(monkeypatch):
    calls = []

    async def run_report(**kwargs):
        calls.append(kwargs)
        return {"proposals": 2, "regressions": 0, "notes": "free text is not returned"}

    module = types.ModuleType("template_doctor")
    module.run_report = run_report
    monkeypatch.setitem(sys.modules, "tools.template_doctor", None)
    monkeypatch.setitem(sys.modules, "template_doctor", module)
    router = FakeRouter()
    result = await template_doctor_drift(make_deps(router=router))
    assert result == {"proposals": 2, "regressions": 0}
    assert calls[0]["max_usd"] == SUB_BUDGET_EVAL_RUN_USD and calls[0]["purpose"] is Purpose.DOCTOR
    assert calls[0]["router"] is router


async def test_drift_run_passes_deps_when_the_doctor_accepts_them(monkeypatch):
    calls = []

    async def run_report(deps=None, budget_usd=None, *, spend=None, max_usd=None, purpose=None, router=None):
        calls.append({"deps": deps, "max_usd": max_usd})
        return {"status": "skipped", "reason": "no golden set at /golden (free text)", "model_calls": 0}

    module = types.ModuleType("template_doctor")
    module.run_report = run_report
    monkeypatch.setitem(sys.modules, "tools.template_doctor", None)
    monkeypatch.setitem(sys.modules, "template_doctor", module)
    deps = make_deps(router=FakeRouter())
    result = await template_doctor_drift(deps)
    assert calls == [{"deps": deps, "max_usd": SUB_BUDGET_EVAL_RUN_USD}]
    assert result == {"status": "skipped", "reason": "template_doctor_skipped", "model_calls": 0}


async def test_a_doctor_that_is_present_but_cannot_import_fails_loudly(tmp_path, monkeypatch, heartbeat):
    package = tmp_path / "template_doctor"
    package.mkdir()
    (package / "__init__.py").write_text("import re2_dependency_missing_from_the_image\n")
    monkeypatch.syspath_prepend(str(tmp_path))
    monkeypatch.setitem(sys.modules, "tools.template_doctor", None)
    monkeypatch.delitem(sys.modules, "template_doctor", raising=False)
    monday = datetime(2026, 11, 2, 4, 30, tzinfo=IST).astimezone(UTC)  # the weekly drift run is due
    deps = make_deps(clock=FakeClock(monday), router=FakeRouter())
    h = hooks(billing_report=lambda s, e: [], drift=template_doctor_drift)
    report = await run_dispatcher(deps, monday, hooks=h)
    assert report.outcome("drift").status == "failed"
    assert report.outcome("drift").detail == {"error_type": "ModuleNotFoundError"}
    assert any("cron_job_failed_drift" in sent for sent in h.delivery.sent)


def test_default_hooks_prefer_the_private_golden_set(tmp_path):
    h = default_hooks(root=tmp_path, golden_dir=tmp_path / "golden")
    assert h.probe is not None and h.billing_report is cron.modal_billing_report
    assert h.drift is template_doctor_drift
    no_golden = default_hooks(root=tmp_path, golden_dir=None)
    assert no_golden.probe is not None


async def test_extra_alerts_reach_delivery_with_metrics(heartbeat):
    deps = make_deps(clock=FakeClock(NOW))
    deps.store.put("billing:drift", {"drift": 0.5, "at": NOW.isoformat()})
    h = hooks()
    await run_dispatcher(deps, NOW, hooks=h)
    assert h.delivery.sent == [["billing_drift"]]
