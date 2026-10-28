"""spend.SpendMeter: the all-spend credit meter (§4.2 "Credit budget", §4.18 L1)."""

from __future__ import annotations

import random
import threading
from datetime import UTC, date, datetime, timedelta

import pytest

from ridemate_api.clock import FakeClock, ist_day, next_ist_midnight
from ridemate_api.interfaces import ApiError, Principal, Purpose
from ridemate_api.spend import (
    NANO,
    ROLLUP_KEY,
    SpendMeter,
    compute_cost_usd,
    eval_run_id,
    token_cost_usd,
    usd_to_nano,
)
from ridemate_api.store import MemoryStore
from ridemate_api.testing import FakeFirestore, FakeRemoteConfig, make_settings

DAY1 = "2026-10-28"  # FakeClock starts 2026-10-28 06:00 UTC = 11:30 IST


class FakeTiers:
    def __init__(self) -> None:
        self.young: set[str] = set()
        self.heavy: set[str] = set()

    def is_young(self, principal: Principal) -> bool:
        return principal.uid in self.young

    def is_heavy(self, principal: Principal) -> bool:
        return principal.uid in self.heavy


def user(uid: str = "u1", *, judge: bool = False) -> Principal:
    return Principal(uid=uid, provider="google.com", sub_hmac=f"h-{uid}", is_judge=judge)


def make(
    store: MemoryStore | None = None,
    clock: FakeClock | None = None,
    *,
    rc: dict | None = None,
    shard: str = "s1",
    tiers: FakeTiers | None = None,
    firestore: FakeFirestore | None = None,
    rc_obj: FakeRemoteConfig | None = None,
) -> SpendMeter:
    return SpendMeter(
        store=store if store is not None else MemoryStore(),
        clock=clock or FakeClock(),
        settings=make_settings(),
        remote_config=rc_obj or FakeRemoteConfig(rc),
        tiers=tiers,
        firestore=firestore,
        shard=shard,
        index_ttl_s=0.0,
        peer_ttl_s=0.0,
    )


def preload(store: MemoryStore, shard: str, budget: str, usd: float, day: str = DAY1) -> None:
    """Spend recorded by another (e.g. finished) writer."""
    for period in ("lifetime", day):
        key = f"spend:{shard}:{budget}:{period}"
        store.put(key, int(store.get(key, 0)) + usd_to_nano(usd))


def spend_app(m: SpendMeter, usd: float, principal: Principal | None = None, rid: str = "r") -> None:
    m.keep(m.reserve(purpose=Purpose.READ, max_usd=usd, principal=principal or user(), request_id=rid))


# ---- prices ----------------------------------------------------------------------------------------------


def test_token_cost_uses_the_three_rates_with_cached_as_a_subset() -> None:
    assert token_cost_usd(1_000_000, 0, 0) == pytest.approx(1.25)
    assert token_cost_usd(0, 0, 1_000_000) == pytest.approx(4.25)
    assert token_cost_usd(1_000_000, 400_000, 0) == pytest.approx(0.6 * 1.25 + 0.4 * 0.15)
    assert token_cost_usd(1_500, 0, 300) == pytest.approx((1_500 * 1.25 + 300 * 4.25) / 1e6)
    assert token_cost_usd(100, 500, 0) == token_cost_usd(100, 100, 0)  # cached can't exceed input
    with pytest.raises(ValueError):
        token_cost_usd(-1, 0, 0)


def test_compute_cost_matches_modal_rates() -> None:
    assert compute_cost_usd(1, 1024, 1) == pytest.approx(0.0000131 + 0.00000222)
    assert compute_cost_usd(0.25, 512, 3600) == pytest.approx(3600 * (0.25 * 0.0000131 + 0.5 * 0.00000222))


def test_eval_run_id_from_request_id_prefix() -> None:
    assert eval_run_id("eval-run:abc123:read:1") == "abc123"
    assert eval_run_id("eval-run:abc123") == "abc123"
    assert eval_run_id("something-else") == "unlabelled"
    assert len(eval_run_id("eval-run:has spaces!")) == 16


# ---- reserve / settle / keep / release -------------------------------------------------------------------


def test_reserve_then_settle_charges_actual_cost_only() -> None:
    m = make()
    res = m.reserve(purpose=Purpose.READ, max_usd=0.01, principal=user(), request_id="r1")
    assert res.reserved_usd == pytest.approx(0.01) and res.budgets == ("ai", "app") and res.day == DAY1
    assert m.summary()["ai_reserved_usd"] == pytest.approx(0.01)
    cost = m.settle(res, input_tokens=2000, cached_input_tokens=500, output_tokens=300)
    expected = (1500 * 1.25 + 500 * 0.15 + 300 * 4.25) / 1e6
    assert cost == pytest.approx(expected)
    s = m.summary()
    assert s["ai_spent_usd"] == pytest.approx(expected)
    assert s["ai_reserved_usd"] == 0
    assert s["sub_budgets"]["app"]["spent_usd"] == pytest.approx(expected)
    assert s["day_spent_usd"] == pytest.approx(expected)


def test_timeout_keeps_the_whole_reservation() -> None:
    m = make()
    res = m.reserve(purpose=Purpose.PARSE, max_usd=0.004, principal=user(), request_id="r1")
    assert m.keep(res) == pytest.approx(0.004)
    assert m.summary()["ai_spent_usd"] == pytest.approx(0.004)


def test_release_charges_nothing_and_finishing_twice_is_a_no_op() -> None:
    m = make()
    res = m.reserve(purpose=Purpose.READ, max_usd=0.01, principal=user(), request_id="r1")
    m.release(res)
    assert m.keep(res) == 0.0
    assert m.settle(res, input_tokens=10, cached_input_tokens=0, output_tokens=10) == 0.0
    s = m.summary()
    assert s["ai_spent_usd"] == 0 and s["ai_reserved_usd"] == 0


def test_actual_cost_above_the_reservation_is_still_recorded_truthfully() -> None:
    m = make()
    res = m.reserve(purpose=Purpose.READ, max_usd=0.001, principal=user(), request_id="r1")
    m.settle(res, input_tokens=0, cached_input_tokens=0, output_tokens=1000)  # $0.00425
    assert m.summary()["ai_spent_usd"] == pytest.approx(0.00425)


def test_negative_or_nan_reservation_is_rejected() -> None:
    m = make()
    for bad in (-0.01, float("nan"), float("inf")):
        with pytest.raises(ValueError):
            m.reserve(purpose=Purpose.READ, max_usd=bad, principal=user(), request_id="r")


# ---- fail closed -----------------------------------------------------------------------------------------


def test_meter_unreadable_fails_closed() -> None:
    store = MemoryStore()
    m = make(store)
    store.fail = True
    with pytest.raises(ApiError) as e:
        m.reserve(purpose=Purpose.READ, max_usd=0.01, principal=user(), request_id="r")
    assert (e.value.status, e.value.code, e.value.reason) == (503, "meter_unavailable", "meter")
    status = m.ai_status(user())
    assert (status.enabled, status.reason) == (False, "meter")


def test_charges_while_the_store_is_down_are_deferred_not_lost() -> None:
    store = MemoryStore()
    m = make(store)
    res = m.reserve(purpose=Purpose.READ, max_usd=0.01, principal=user(), request_id="r")
    store.fail = True
    assert m.keep(res) == pytest.approx(0.01)  # never fails the request that already got its answer
    assert m.record_compute(cpu_cores=1, memory_mib=1024, seconds=100, source="web") > 0
    store.fail = False
    assert m.summary()["ai_reserved_usd"] == pytest.approx(0.01)  # still counted while pending
    spend_app(m, 0.001)  # the next write flushes the queue first
    s = m.summary()
    assert s["ai_spent_usd"] == pytest.approx(0.011)
    assert s["ai_reserved_usd"] == 0
    assert s["compute_spent_usd"] == pytest.approx(compute_cost_usd(1, 1024, 100), rel=1e-6)


def test_lost_dict_with_firestore_history_unreachable_fails_closed() -> None:
    fs = FakeFirestore()
    fs.set(
        "spend_shards/rollup", {"period": "rollup", "frozen": [], "totals_nusd": {"ai:lifetime": 10 * NANO}}
    )
    fs.fail = True
    m = make(MemoryStore(), firestore=fs)
    with pytest.raises(ApiError) as e:
        m.reserve(purpose=Purpose.READ, max_usd=0.01, principal=user(), request_id="r")
    assert e.value.code == "meter_unavailable"


# ---- budgets ---------------------------------------------------------------------------------------------


def test_ai_budget_exhausted_pauses_everyone_without_resume_time() -> None:
    store = MemoryStore()
    preload(store, "old", "ai", 79.995, day="2026-10-01")
    preload(store, "old", "judging", 79.995, day="2026-10-01")
    m = make(store, rc={"credit_expiry_date": "2026-10-29"})
    with pytest.raises(ApiError) as e:
        m.reserve(purpose=Purpose.PROBE, max_usd=0.01, principal=None, request_id="p")
    assert (e.value.status, e.value.code, e.value.reason, e.value.resume_at) == (
        429,
        "ai_paused",
        "budget",
        None,
    )
    m.reserve(purpose=Purpose.PROBE, max_usd=0.005, principal=None, request_id="p")  # exactly fills it
    assert m.ai_status(user()).reason == "budget"


def test_probe_sub_budget_is_2_dollars_and_does_not_touch_app_traffic() -> None:
    m = make()
    for i in range(4):
        m.keep(m.reserve(purpose=Purpose.PROBE, max_usd=0.5, principal=None, request_id=f"p{i}"))
    with pytest.raises(ApiError) as e:
        m.reserve(purpose=Purpose.PROBE, max_usd=0.01, principal=None, request_id="p")
    assert e.value.reason == "budget"
    spend_app(m, 0.01)
    assert m.summary()["sub_budgets"]["probes"]["spent_usd"] == pytest.approx(2.0)


def test_demo_misses_have_a_3_dollar_sub_budget() -> None:
    store = MemoryStore()
    preload(store, "old", "demo", 2.995, day="2026-10-01")
    preload(store, "old", "ai", 2.995, day="2026-10-01")
    m = make(store)
    with pytest.raises(ApiError):
        m.reserve(purpose=Purpose.DEMO, max_usd=0.01, principal=None, request_id="d")
    m.reserve(purpose=Purpose.DEMO, max_usd=0.005, principal=None, request_id="d")


def test_eval_runs_are_capped_at_50_cents_each_and_5_dollars_total() -> None:
    m = make()
    m.keep(m.reserve(purpose=Purpose.EVAL, max_usd=0.3, principal=None, request_id="eval-run:a:read:1"))
    with pytest.raises(ApiError) as e:
        m.reserve(purpose=Purpose.EVAL, max_usd=0.3, principal=None, request_id="eval-run:a:read:2")
    assert e.value.reason == "budget"
    m.keep(m.reserve(purpose=Purpose.DOCTOR, max_usd=0.2, principal=None, request_id="eval-run:a:doctor:1"))
    for i in range(9):  # nine more runs of $0.50 → $5 total
        run = f"eval-run:run{i}:x"
        m.keep(m.reserve(purpose=Purpose.EVAL, max_usd=0.5, principal=None, request_id=run))
    with pytest.raises(ApiError):
        m.reserve(purpose=Purpose.EVAL, max_usd=0.01, principal=None, request_id="eval-run:fresh:x")
    assert m.summary()["sub_budgets"]["evals"]["spent_usd"] == pytest.approx(5.0)


def test_unlabelled_eval_calls_share_one_run_cap() -> None:
    m = make()
    m.keep(m.reserve(purpose=Purpose.EVAL, max_usd=0.4, principal=None, request_id="no-prefix-1"))
    with pytest.raises(ApiError):
        m.reserve(purpose=Purpose.EVAL, max_usd=0.2, principal=None, request_id="no-prefix-2")


def test_judges_draw_on_the_judging_sub_budget_and_skip_the_day_cap() -> None:
    store = MemoryStore()
    m = make(store, rc={"cap_profile": "judging"})
    judge = user("judge", judge=True)
    for i in range(10):  # $5 in one day, far above the $3 judging-profile day cap
        m.keep(m.reserve(purpose=Purpose.READ, max_usd=0.5, principal=judge, request_id=f"j{i}"))
    s = m.summary()
    assert s["sub_budgets"]["judging"]["spent_usd"] == pytest.approx(5.0)
    assert s["sub_budgets"]["app"]["spent_usd"] == 0
    assert s["day_spent_usd"] == 0 and s["ai_day_spent_usd"] == pytest.approx(5.0)
    spend_app(m, 0.01)  # the judges' spend never eats the public's daily share
    assert m.ai_status(judge).enabled
    preload(store, "old", "judging", 10.0)
    preload(store, "old", "ai", 10.0)
    with pytest.raises(ApiError) as e:
        m.reserve(purpose=Purpose.READ, max_usd=0.01, principal=judge, request_id="jx")
    assert e.value.reason == "budget"


# ---- pacing ----------------------------------------------------------------------------------------------


def test_day_cap_is_remaining_over_days_left_times_1_5() -> None:
    store, clock = MemoryStore(), FakeClock()
    m = make(store, clock)
    s = m.summary()
    assert s["horizon_end"] == "2027-01-26"  # 2026-10-28 + 90 days
    assert s["day_cap_usd"] == pytest.approx(80 / 90 * 1.5, abs=1e-6)
    preload(store, "old", "ai", 10.0, day=DAY1)
    clock.advance(86400)
    assert m.summary()["day_cap_usd"] == pytest.approx(70 / 89 * 1.5, abs=1e-6)


def test_todays_spend_does_not_shrink_todays_cap() -> None:
    store = MemoryStore()
    m = make(store)
    cap = m.summary()["day_cap_usd"]
    spend_app(m, 1.0)
    assert m.summary()["day_cap_usd"] == pytest.approx(cap)


def test_horizon_uses_credit_expiry_date_when_known() -> None:
    m = make(rc={"credit_expiry_date": "2026-12-27"})
    assert m.horizon_end() == date(2026, 12, 27)
    assert m.summary()["day_cap_usd"] == pytest.approx(80 / 60 * 1.5, abs=1e-6)
    assert make(rc={"credit_expiry_date": "unknown", "budget_horizon_days": 30}).horizon_end() == date(
        2026, 11, 27
    )


def test_past_horizon_counts_as_one_day() -> None:
    clock = FakeClock(datetime(2027, 3, 1, 6, 0, tzinfo=UTC))
    m = make(clock=clock)
    assert m.summary()["day_cap_usd"] == pytest.approx(80 * 1.5 / 1, abs=1e-6)


def test_day_cap_pauses_paced_traffic_until_ist_midnight() -> None:
    clock = FakeClock()
    m = make(clock=clock)
    cap = m.summary()["day_cap_usd"]
    spent = 0.0
    with pytest.raises(ApiError) as e:
        while True:
            spend_app(m, 0.05)
            spent += 0.05
    assert e.value.reason == "day_cap" and e.value.status == 429
    assert e.value.resume_at == next_ist_midnight(clock.now_utc())
    assert spent <= cap + 1e-9
    assert m.ai_status(user()).reason in ("day_cap", None)
    m.keep(m.reserve(purpose=Purpose.PROBE, max_usd=0.1, principal=None, request_id="p"))  # not paced
    clock.advance(86400)
    spend_app(m, 0.05)


def test_judging_profile_caps_the_day_at_3_dollars() -> None:
    m = make(rc={"cap_profile": "judging", "credit_expiry_date": "2026-11-02"})  # computed cap 80/5*1.5 = 24
    assert m.summary()["day_cap_usd"] == pytest.approx(3.0)
    assert m.summary()["cap_profile"] == "judging"
    m2 = make(rc={"cap_profile": "judging"})  # computed 1.33 < 3 stays
    assert m2.summary()["day_cap_usd"] == pytest.approx(80 / 90 * 1.5, abs=1e-6)


def test_tier_pause_at_70_percent_for_young_and_heavy_accounts_only() -> None:
    tiers = FakeTiers()
    tiers.young.add("newbie")
    tiers.heavy.add("heavy")
    m = make(rc={"credit_expiry_date": "2026-11-02", "cap_profile": "judging"}, tiers=tiers)  # cap $3
    newbie, heavy, regular, judge = user("newbie"), user("heavy"), user("regular"), user("j", judge=True)
    spend_app(m, 2.0, regular)  # 67%: everyone still reads
    spend_app(m, 0.01, newbie)
    spend_app(m, 0.1, regular)  # ≈ 70.3%
    for p in (newbie, heavy):
        with pytest.raises(ApiError) as e:
            m.reserve(purpose=Purpose.READ, max_usd=0.01, principal=p, request_id="t")
        assert e.value.reason == "tier_pause"
        assert m.ai_status(p).reason == "tier_pause"
    spend_app(m, 0.01, regular)
    spend_app(m, 0.01, judge)
    assert m.ai_status(regular).enabled


# ---- compute and drift -----------------------------------------------------------------------------------


def test_compute_over_budget_reports_but_does_not_pause_ai() -> None:
    store = MemoryStore()
    m = make(store)
    m.record_compute(cpu_cores=0.25, memory_mib=512, seconds=60, source="web")
    assert m.summary()["compute_spent_usd"] == pytest.approx(compute_cost_usd(0.25, 512, 60), rel=1e-6)
    preload(store, "old", "compute", 15.0)
    status = m.ai_status(user())
    assert status.enabled and status.compute_over_budget
    assert m.summary()["compute_over_budget"] is True
    assert m.summary()["ai_limit_usd"] == pytest.approx(80.0)


def test_compute_past_the_margin_shrinks_the_ai_headroom_so_the_credit_holds() -> None:
    store = MemoryStore()
    preload(store, "old", "compute", 25.0)
    preload(store, "old", "ai", 74.99, day="2026-10-01")
    preload(store, "old", "judging", 14.99, day="2026-10-01")
    m = make(store, rc={"credit_expiry_date": "2026-10-29"})
    assert m.summary()["ai_limit_usd"] == pytest.approx(75.0)
    with pytest.raises(ApiError) as e:
        m.reserve(purpose=Purpose.PROBE, max_usd=0.02, principal=None, request_id="p")
    assert e.value.reason == "budget"


def test_drift_factor_tightens_every_reservation_and_charge() -> None:
    store = MemoryStore()
    m = make(store)
    assert m.set_drift_factor(1.2) == 1.2
    res = m.reserve(purpose=Purpose.READ, max_usd=0.01, principal=user(), request_id="r")
    assert res.reserved_usd == pytest.approx(0.012)
    cost = m.settle(res, input_tokens=0, cached_input_tokens=0, output_tokens=1000)
    assert cost == pytest.approx(0.00425 * 1.2)
    assert m.record_compute(cpu_cores=1, memory_mib=0, seconds=100, source="web") == pytest.approx(
        100 * 0.0000131 * 1.2
    )
    assert m.summary()["drift_factor"] == 1.2


def test_drift_factor_never_loosens_below_one() -> None:
    store = MemoryStore()
    m = make(store)
    assert m.set_drift_factor(0.5) == 1.0
    store.put("spend:drift_factor", "garbage")
    res = m.reserve(purpose=Purpose.READ, max_usd=0.01, principal=user(), request_id="r")
    assert res.reserved_usd == pytest.approx(0.01)


def test_kill_switch_status() -> None:
    m = make(rc={"ai_reader_enabled": False})
    assert m.ai_status(user()).reason == "kill_switch"


def test_summary_has_the_contract_fields() -> None:
    s = make().summary()
    for key in (
        "ai_spent_usd",
        "ai_budget_usd",
        "compute_spent_usd",
        "compute_budget_usd",
        "day_cap_usd",
        "day_spent_usd",
        "horizon_end",
    ):
        assert key in s
    assert s["ai_budget_usd"] == 80 and s["compute_budget_usd"] == 12
    assert sum(b["budget_usd"] for b in s["sub_budgets"].values()) == pytest.approx(80)


# ---- properties ------------------------------------------------------------------------------------------


def _assert_within_budgets(m: SpendMeter, store: MemoryStore) -> None:
    s = m.summary()
    assert s["ai_spent_usd"] + s["ai_reserved_usd"] <= s["ai_limit_usd"] + 1e-9
    for name, b in s["sub_budgets"].items():
        assert b["spent_usd"] <= b["budget_usd"] + 1e-9, name
    runs: dict[str, int] = {}
    for key in store.keys_with_prefix("spend:"):
        parts = key.split(":")
        if len(parts) == 4 and parts[2].startswith("evalrun-") and parts[3] == "lifetime":
            runs[parts[2]] = runs.get(parts[2], 0) + int(store.get(key))
    assert all(n <= usd_to_nano(0.5) for n in runs.values())


@pytest.mark.parametrize("seed", [1, 2, 3])
def test_property_sequential_charges_never_exceed_any_budget(seed: int) -> None:
    rng = random.Random(seed)
    store, clock = MemoryStore(), FakeClock()
    m = make(store, clock)
    principals = [user("a"), user("b"), user("judge", judge=True), None]
    purposes = [Purpose.READ, Purpose.PARSE, Purpose.DEMO, Purpose.PROBE, Purpose.EVAL, Purpose.DOCTOR]
    accepted = 0
    for i in range(1500):
        clock.advance(rng.uniform(0, 4 * 3600))
        purpose = rng.choice(purposes)
        principal = rng.choice(principals) if purpose in (Purpose.READ, Purpose.PARSE) else None
        max_usd = rng.uniform(0.001, 0.8)
        try:
            res = m.reserve(
                purpose=purpose, max_usd=max_usd, principal=principal, request_id=f"eval-run:{i % 7}:{i}"
            )
        except ApiError as e:
            assert e.code == "ai_paused"
            continue
        accepted += 1
        action = rng.random()
        if action < 0.5:
            out_tokens = int(res.reserved_nusd * rng.random() / 4250)
            m.settle(res, input_tokens=0, cached_input_tokens=0, output_tokens=out_tokens)
        elif action < 0.85:
            m.keep(res)
        else:
            m.release(res)
        if i % 50 == 0:
            _assert_within_budgets(m, store)
    _assert_within_budgets(m, store)
    assert accepted > 100


def test_property_paced_spend_per_day_never_exceeds_that_days_cap() -> None:
    rng = random.Random(7)
    store, clock = MemoryStore(), FakeClock()
    m = make(store, clock)
    caps: dict[str, float] = {}
    for i in range(3000):
        clock.advance(rng.uniform(0, 1800))
        day = ist_day(clock.now_utc())
        caps.setdefault(day, m.summary()["day_cap_usd"])
        try:
            spend_app(m, rng.uniform(0.001, 0.2), user(rng.choice("abc")), rid=str(i))
        except ApiError:
            pass
    per_day: dict[str, int] = {}
    for key in store.keys_with_prefix("spend:s1:ai:"):
        period = key.rsplit(":", 1)[1]
        if period != "lifetime":
            per_day[period] = int(store.get(key))
    assert len(per_day) > 30
    for day, n in per_day.items():
        assert n / NANO <= caps[day] + 1e-9, day


# ---- concurrency -----------------------------------------------------------------------------------------


def _hammer(meters: list[SpendMeter], threads_per_meter: int, ops: int, max_usd: float) -> None:
    def worker(m: SpendMeter, seed: int) -> None:
        rng = random.Random(seed)
        for i in range(ops):
            try:
                res = m.reserve(
                    purpose=Purpose.PROBE,
                    max_usd=rng.uniform(0.001, max_usd),
                    principal=None,
                    request_id=f"{i}",
                )
            except ApiError:
                continue
            m.keep(res)

    threads = [
        threading.Thread(target=worker, args=(m, k * 100 + j))
        for k, m in enumerate(meters)
        for j in range(threads_per_meter)
    ]
    for t in threads:
        t.start()
    for t in threads:
        t.join()


def test_concurrent_threads_on_one_shard_never_overshoot() -> None:
    store = MemoryStore()
    m = make(store)
    _hammer([m], threads_per_meter=12, ops=60, max_usd=0.05)
    spent = m.summary()["sub_budgets"]["probes"]["spent_usd"]
    assert 1.9 < spent <= 2.0 + 1e-9


def test_concurrent_writers_on_separate_shards_overshoot_at_most_one_reservation_each() -> None:
    store, clock = MemoryStore(), FakeClock()
    meters = [make(store, clock, shard=f"w{k}") for k in range(4)]
    max_usd = 0.05
    _hammer(meters, threads_per_meter=4, ops=60, max_usd=max_usd)
    spent = meters[0].summary()["sub_budgets"]["probes"]["spent_usd"]
    assert spent <= 2.0 + (len(meters) - 1) * max_usd + 1e-9
    assert spent > 1.8


# ---- shards, compaction, snapshots -----------------------------------------------------------------------


def test_totals_sum_over_every_shard() -> None:
    store, clock = MemoryStore(), FakeClock()
    a, b = make(store, clock, shard="a"), make(store, clock, shard="b")
    spend_app(a, 0.1)
    spend_app(b, 0.2)
    assert a.summary()["ai_spent_usd"] == pytest.approx(0.3)
    assert b.summary()["ai_spent_usd"] == pytest.approx(0.3)
    assert {k.split(":")[1] for k in store.keys_with_prefix("spend:")} >= {"a", "b"}


def test_default_shard_comes_from_modal_task_id(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("MODAL_TASK_ID", "ta-01ABC")
    m = SpendMeter(
        store=MemoryStore(), clock=FakeClock(), settings=make_settings(), remote_config=FakeRemoteConfig()
    )
    assert m.shard == "ta-01ABC"
    monkeypatch.delenv("MODAL_TASK_ID")
    m = SpendMeter(
        store=MemoryStore(), clock=FakeClock(), settings=make_settings(), remote_config=FakeRemoteConfig()
    )
    assert m.shard.startswith("local-")


def test_compaction_folds_idle_shards_without_changing_totals() -> None:
    store, clock = MemoryStore(), FakeClock()
    a, b = make(store, clock, shard="a"), make(store, clock, shard="b")
    spend_app(a, 0.1)
    pending = a.reserve(purpose=Purpose.READ, max_usd=0.05, principal=user(), request_id="inflight")
    spend_app(b, 0.2)
    clock.advance(2 * 3600)
    before = b.summary()
    frozen = b.compact(idle_s=3600)
    assert set(frozen) == {"a", "b"}
    after = b.summary()
    # The in-flight reservation of a frozen shard is counted as spent (it may have been billed).
    assert after["ai_spent_usd"] + after["ai_reserved_usd"] == pytest.approx(
        before["ai_spent_usd"] + before["ai_reserved_usd"]
    )
    assert store.get(ROLLUP_KEY)["frozen"] == ["a", "b"]
    assert b.compact(idle_s=3600) == []

    # A frozen writer that wakes up moves to a fresh shard; nothing is lost (over-counting is allowed).
    spend_app(a, 0.4)
    assert a.shard == "a.g1"
    a.settle(pending, input_tokens=0, cached_input_tokens=0, output_tokens=1000)
    total = a.summary()["ai_spent_usd"]
    assert total >= 0.1 + 0.2 + 0.4 + 0.00425 - 1e-9
    assert total <= 0.1 + 0.2 + 0.4 + 0.05 + 0.00425 + 1e-9


def test_a_reused_shard_id_whose_frozen_marker_expired_still_counts() -> None:
    """A local shard id (hostname + pid) can come back weeks later, after the Dict dropped the frozen marker.
    The rollup still lists the shard as frozen, so readers ignore it: the writer must notice and move on."""
    store, clock = MemoryStore(), FakeClock()
    first = make(store, clock, shard="local-laptop-4242")
    spend_app(first, 0.2)
    clock.advance(2 * 3600)
    assert first.compact(idle_s=3600) == ["local-laptop-4242"]
    store.delete("spend:frozen:local-laptop-4242")  # expired after 7 idle days

    again = make(store, clock, shard="local-laptop-4242")
    spend_app(again, 0.3)
    assert again.shard == "local-laptop-4242.g1"
    observer = make(store, clock, shard="observer")
    assert observer.summary()["ai_spent_usd"] == pytest.approx(0.5)
    assert again.summary()["ai_spent_usd"] == pytest.approx(0.5)


def test_compaction_skips_active_shards() -> None:
    store, clock = MemoryStore(), FakeClock()
    a = make(store, clock, shard="a")
    spend_app(a, 0.1)
    clock.advance(600)
    assert a.compact(idle_s=3600) == []
    assert a.shard == "a"


def test_snapshot_is_idempotent_and_restore_refills_a_lost_dict() -> None:
    fs = FakeFirestore()
    store, clock = MemoryStore(), FakeClock()
    a, b = make(store, clock, shard="a", firestore=fs), make(store, clock, shard="b", firestore=fs)
    spend_app(a, 0.1)
    b.record_compute(cpu_cores=1, memory_mib=1024, seconds=600, source="cron")
    clock.advance(2 * 3600)
    b.compact(idle_s=3600)
    spend_app(a, 0.3)  # a.g1
    before = a.summary()
    written = a.snapshot_to_firestore()
    assert written >= 2
    assert "spend_shards/rollup" in fs.docs and "spend_shards/lifetime_a.g1" in fs.docs
    assert a.snapshot_to_firestore() == 0  # unchanged → no writes

    lost = MemoryStore()  # the Dict lost everything
    c = make(lost, clock, shard="c", firestore=fs)
    after = c.summary()  # first read finds no rollup, sees Firestore has one, restores
    assert after["ai_spent_usd"] == pytest.approx(before["ai_spent_usd"])
    assert after["compute_spent_usd"] == pytest.approx(before["compute_spent_usd"])
    assert c.restore_from_firestore() == 0  # nothing missing any more


def test_restore_counts_outstanding_reservations_as_spent_and_skips_frozen_shards() -> None:
    fs = FakeFirestore()
    store, clock = MemoryStore(), FakeClock()
    a = make(store, clock, shard="a", firestore=fs)
    spend_app(a, 0.1)
    a.reserve(purpose=Purpose.READ, max_usd=0.05, principal=user(), request_id="crashed")
    a.snapshot_to_firestore()
    lost = MemoryStore()
    c = make(lost, clock, shard="c", firestore=fs)
    assert c.summary()["ai_spent_usd"] == pytest.approx(0.15)


def test_peer_values_never_go_backwards_within_a_process() -> None:
    store = MemoryStore()
    preload(store, "old", "ai", 1.0)
    preload(store, "old", "app", 1.0)
    m = make(store)
    assert m.summary()["ai_spent_usd"] == pytest.approx(1.0)
    for key in store.keys_with_prefix("spend:old:"):
        store.delete(key)  # e.g. expired from the Dict
    assert m.summary()["ai_spent_usd"] == pytest.approx(1.0)


def test_day_charges_follow_the_reservation_day_across_midnight() -> None:
    clock = FakeClock(datetime(2026, 10, 28, 18, 29, 50, tzinfo=UTC))  # 23:59:50 IST
    store = MemoryStore()
    m = make(store, clock)
    res = m.reserve(purpose=Purpose.READ, max_usd=0.01, principal=user(), request_id="r")
    clock.advance(timedelta(seconds=20).total_seconds())
    m.keep(res)
    assert int(store.get("spend:s1:ai:2026-10-28")) == usd_to_nano(0.01)
    assert store.get("spend:s1:ai:2026-10-29") is None
