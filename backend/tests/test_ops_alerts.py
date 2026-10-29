"""alerts.py: every threshold, delivery to healthchecks.io + FCM, 6-hour de-duplication, test fire."""

from __future__ import annotations

import json
from collections.abc import Callable
from datetime import UTC, datetime, timedelta

import httpx
import pytest
import respx

from ridemate_api import alerts
from ridemate_api.alerts import (
    CATALOG,
    FCM_SCOPE,
    FCM_URL,
    Alert,
    AlertDelivery,
    AlertInputs,
    build_delivery,
    evaluate,
    gather_inputs,
    owner_tokens,
    render_text,
    run_alerts,
)
from ridemate_api.clock import FakeClock
from ridemate_api.metrics import DictMetrics, MetricsView, record_request, record_session_event
from ridemate_api.store import MemoryStore
from tests.test_ops_fakes import (
    HC_ALERT,
    OWNER_TOKEN,
    PROJECT,
    TOKEN_URI,
    FakeRouter,
    FakeSpend,
    FakeTokens,
    assertion_claims,
    make_deps,
    make_settings,
    token_response,
)

T0 = datetime(2026, 10, 28, 4, 30, 5, tzinfo=UTC)
FCM = FCM_URL.format(project=PROJECT)


def view_of(record: Callable[[DictMetrics], None], *, settle: bool) -> MetricsView:
    store, clock = MemoryStore(), FakeClock(T0)
    m = DictMetrics(store, clock, shard="s", background_flush=False)
    record(m)
    m.flush()
    if not settle:
        return m.collect(clock.now_utc())
    clock.advance(600)  # windows close and roll up into the hour
    return m.snapshot_to_firestore(None, clock.now_utc())


def names(inputs: AlertInputs) -> list[str]:
    return [a.name for a in evaluate(inputs)]


def requests(ok: int, errors: int, route: str = "/v1/read-screen", latency: float = 900) -> MetricsView:
    def record(m: DictMetrics) -> None:
        for _ in range(ok):
            record_request(m, route, 200, latency)
        for _ in range(errors):
            record_request(m, route, 503, latency)

    return view_of(record, settle=False)


# ---- thresholds --------------------------------------------------------------------------------------------


def test_5xx_rate_over_10_minutes():
    assert "http_5xx_rate" in names(AlertInputs(T0, view=requests(27, 3)))  # 10%
    assert "http_5xx_rate" not in names(AlertInputs(T0, view=requests(57, 3)))  # exactly 5% is not over
    # At the paced traffic (≈ 5 requests per 10 minutes) a failing server must still alert...
    assert "http_5xx_rate" in names(AlertInputs(T0, view=requests(2, 3)))
    # ...but one or two stray errors are noise.
    assert "http_5xx_rate" not in names(AlertInputs(T0, view=requests(0, 2)))
    assert "http_5xx_rate" not in names(AlertInputs(T0, view=requests(0, 30, route="/healthz")))


def test_read_p95_over_15_seconds():
    assert "read_p95" in names(AlertInputs(T0, view=requests(6, 0, latency=16_000)))
    assert "read_p95" not in names(AlertInputs(T0, view=requests(6, 0, latency=15_000)))
    assert "read_p95" not in names(AlertInputs(T0, view=requests(4, 0, latency=30_000)))  # too few samples

    def model_latency(m: DictMetrics) -> None:
        for _ in range(5):
            m.observe_ms("ai_model_latency_ms", 18_000, task="read")

    assert "read_p95" in names(AlertInputs(T0, view=view_of(model_latency, settle=False)))


def test_read_p95_looks_back_an_hour_for_enough_samples():
    store, clock = MemoryStore(), FakeClock(T0)
    m = DictMetrics(store, clock, shard="s", background_flush=False)
    for minutes in (0, 15, 30):  # reads spread over half an hour: never 5 in one 10-minute window
        for _ in range(2):
            record_request(m, "/v1/read-screen", 200, 17_000)
        m.flush()
        clock.advance(15 * 60)
    assert "read_p95" in names(AlertInputs(clock.now_utc(), view=m.collect(clock.now_utc())))
    later = clock.now_utc() + timedelta(minutes=60)
    assert "read_p95" not in names(AlertInputs(later, view=m.collect(later)))


def sessions(**counts: int) -> MetricsView:
    return view_of(lambda m: record_session_event(m, counts), settle=True)


def test_verified_rate_below_70_percent_with_at_least_20_reads():
    low = sessions(reads=20, verified_rows=13, unreadable_rows=7)
    assert "verified_rate" in names(AlertInputs(T0, view=low))
    assert "verified_rate" not in names(
        AlertInputs(T0, view=sessions(reads=19, verified_rows=1, unreadable_rows=9))
    )
    assert "verified_rate" not in names(
        AlertInputs(T0, view=sessions(reads=40, verified_rows=14, unreadable_rows=6))
    )


def test_rule_miss_rate_over_20_percent():
    assert "rule_miss_rate" in names(AlertInputs(T0, view=sessions(rule_hits=15, rule_misses=5)))
    assert "rule_miss_rate" not in names(AlertInputs(T0, view=sessions(rule_hits=16, rule_misses=4)))
    assert "rule_miss_rate" not in names(AlertInputs(T0, view=sessions(rule_hits=5, rule_misses=5)))


def test_rule_miss_without_classifier_spike():
    def spike(baseline_per_hour: int, recent: int) -> MetricsView:
        store, clock = MemoryStore(), FakeClock(T0 - timedelta(hours=22))
        m = DictMetrics(store, clock, shard="s", background_flush=False)
        for _ in range(22):  # the dispatcher snapshots every 15 minutes, so every hour gets its rollup
            if baseline_per_hour:
                m.incr("event_misses_unclassified", baseline_per_hour)
            m.flush()
            clock.advance(3600)
            m.snapshot_to_firestore(None, clock.now_utc())
        m.incr("event_misses_unclassified", recent)
        m.flush()
        clock.advance(600)
        m.snapshot_to_firestore(None, clock.now_utc())
        return m.collect(clock.now_utc())

    assert "rule_miss_unclassified_spike" in names(AlertInputs(T0, view=spike(0, 12)))
    assert "rule_miss_unclassified_spike" not in names(AlertInputs(T0, view=spike(0, 9)))
    assert "rule_miss_unclassified_spike" not in names(
        AlertInputs(T0, view=spike(3, 12))
    )  # normal for this app


def test_spend_thresholds():
    spend = {
        "day_spent_usd": 1.0,
        "day_cap_usd": 1.3,  # 77% of the day's cap
        "ai_spent_usd": 65.0,
        "ai_budget_usd": 80.0,  # 81%
        "compute_spent_usd": 1.0,
        "compute_budget_usd": 12.0,
        "sub_budgets": {
            "evals": {"spent_usd": 4.5, "budget_usd": 5.0},
            "probes": {"spent_usd": 0.1, "budget_usd": 2.0},
        },
    }
    fired = names(AlertInputs(T0, spend_summary=spend))
    assert {"spend_day_cap", "spend_budget_ai", "spend_budget_evals"} <= set(fired)
    assert "spend_budget_compute" not in fired and "spend_budget_probes" not in fired
    calm = {"day_spent_usd": 0.9, "day_cap_usd": 1.3, "ai_spent_usd": 64.0, "ai_budget_usd": 80.0}
    assert names(AlertInputs(T0, spend_summary=calm)) == []
    assert names(AlertInputs(T0, spend_unreadable=True)) == ["spend_meter_unreadable"]


def test_drift_firestore_probe_key_age_and_model():
    fresh = T0.isoformat()
    stale = (T0 - timedelta(days=3)).isoformat()
    fired = names(
        AlertInputs(
            T0,
            drift={"drift": 0.12, "at": fresh},
            firestore_writes_fraction=0.71,
            probe={"status": "fail", "at": fresh},
            sa_keys_created={"ops": T0 - timedelta(days=81), "web": T0 - timedelta(days=10)},
            model_missing=True,
        )
    )
    assert fired == ["billing_drift", "firestore_writes", "probe_failed", "sa_key_age_ops", "model_missing"]
    quiet = names(
        AlertInputs(
            T0,
            drift={"drift": 0.12, "at": stale},
            firestore_writes_fraction=0.70,
            probe={"status": "pass", "at": fresh},
            sa_keys_created={"ops": T0 - timedelta(days=80)},
        )
    )
    assert quiet == []
    assert (
        names(AlertInputs(T0, drift={"drift": 0.10, "at": fresh}, probe={"status": "fail", "at": stale}))
        == []
    )


def test_inputs_are_gathered_defensively():
    store = MemoryStore()
    store.put(alerts.DRIFT_KEY, {"drift": 0.2, "at": T0.isoformat()})
    store.put(alerts.PROBE_KEY, {"status": "fail", "at": T0.isoformat()})
    store.put(alerts.SA_KEYS_KEY, {"web": (T0 - timedelta(days=90)).isoformat(), "ops": "not a date"})
    spend = FakeSpend()
    spend.summary_error = RuntimeError("meter unreadable")
    deps = make_deps(store=store, spend=spend, router=FakeRouter(reason="model_missing"), clock=FakeClock(T0))
    fired = names(gather_inputs(deps, T0, None, extra=[Alert("cron_job_failed_cleanup")]))
    assert fired == [
        "spend_meter_unreadable",
        "billing_drift",
        "probe_failed",
        "sa_key_age_web",
        "model_missing",
        "cron_job_failed_cleanup",
    ]


def test_owner_tokens_come_from_the_secret_and_the_registrations():
    store = MemoryStore()
    store.put(alerts.OWNER_TOKENS_KEY, {"device-2": {"uid_hash": "h"}, OWNER_TOKEN: {"uid_hash": "h"}})
    assert owner_tokens(make_settings(), store) == [OWNER_TOKEN, "device-2"]
    store.put(alerts.OWNER_TOKENS_KEY, ["device-3"])
    assert owner_tokens(make_settings(owner_fcm_token=""), store) == ["device-3"]


def test_alert_text_carries_names_and_numbers_only():
    text = render_text(
        [
            Alert("http_5xx_rate", 0.071, 0.05, "%"),
            Alert("read_p95", 16_000, 15_000, "ms"),
            Alert("probe_failed"),
        ],
        env="main",
    )
    assert (
        text == "RideMate main: http_5xx_rate=7.1% (limit 5.0%); read_p95=16.0s (limit 15.0s); probe_failed"
    )
    assert render_text([Alert("x")], env="dev", test=True).startswith("TEST RideMate dev")


# ---- delivery ----------------------------------------------------------------------------------------------


def mock_channels():
    hc_fail = respx.post(f"{HC_ALERT}/fail").mock(return_value=httpx.Response(200, text="OK"))
    hc_ok = respx.post(HC_ALERT).mock(return_value=httpx.Response(200, text="OK"))
    token = respx.post(TOKEN_URI).mock(return_value=token_response("fcm-access"))
    fcm = respx.post(FCM).mock(return_value=httpx.Response(200, json={"name": "projects/x/messages/1"}))
    return hc_fail, hc_ok, token, fcm


def firing_deps(store: MemoryStore | None = None):
    store = store or MemoryStore()
    store.put(alerts.OWNER_TOKENS_KEY, {"device-2": {"uid_hash": "h"}})
    store.put(alerts.PROBE_KEY, {"status": "fail", "at": T0.isoformat()})
    return make_deps(store=store, clock=FakeClock(T0))


@respx.mock
def test_firing_alerts_reach_healthchecks_and_every_owner_device():
    hc_fail, hc_ok, token, fcm = mock_channels()
    deps = firing_deps()
    run = run_alerts(deps, T0, None, build_delivery(deps.settings, deps.store))
    assert run.sent == ["probe_failed"]
    assert hc_fail.call_count == 1 and hc_ok.call_count == 0
    assert hc_fail.calls[0].request.content.decode() == "RideMate main: probe_failed"
    assert assertion_claims(token.calls[0].request)["scope"] == FCM_SCOPE
    assert fcm.call_count == 2
    bodies = [json.loads(c.request.content)["message"] for c in fcm.calls]
    assert {b["token"] for b in bodies} == {OWNER_TOKEN, "device-2"}
    assert bodies[0]["data"] == {"type": "owner_alert", "test": "0", "names": "probe_failed"}
    assert bodies[0]["android"] == {"priority": "high"}
    assert fcm.calls[0].request.headers["Authorization"] == "Bearer fcm-access"


@respx.mock
def test_the_same_alert_is_delivered_at_most_once_per_6_hours():
    hc_fail, _, _, fcm = mock_channels()
    deps = firing_deps()
    delivery = build_delivery(deps.settings, deps.store)
    run_alerts(deps, T0, None, delivery)
    again = run_alerts(deps, T0 + timedelta(hours=5, minutes=59), None, delivery)
    assert again.sent == [] and again.suppressed == ["probe_failed"]
    assert hc_fail.call_count == 1
    later = run_alerts(deps, T0 + timedelta(hours=6), None, delivery)
    assert later.sent == ["probe_failed"]
    assert hc_fail.call_count == 2 and fcm.call_count == 4


@respx.mock
def test_a_failed_delivery_is_retried_next_round():
    respx.post(f"{HC_ALERT}/fail").mock(return_value=httpx.Response(500))
    deps = firing_deps()
    delivery = AlertDelivery(deps.settings, deps.store, fcm_tokens=None)
    assert run_alerts(deps, T0, None, delivery).sent == []
    respx.post(f"{HC_ALERT}/fail").mock(return_value=httpx.Response(200))
    assert run_alerts(deps, T0 + timedelta(minutes=15), None, delivery).sent == ["probe_failed"]


@respx.mock
def test_a_quiet_round_pings_the_alert_check_up_again():
    hc_fail, hc_ok, _, fcm = mock_channels()
    deps = make_deps(clock=FakeClock(T0))
    run = run_alerts(deps, T0, None, build_delivery(deps.settings, deps.store))
    assert run.firing == []
    assert hc_ok.call_count == 1 and hc_fail.call_count == 0 and fcm.call_count == 0


class ReadCountingStore(MemoryStore):
    def __init__(self) -> None:
        super().__init__()
        self.reads: list[str] = []

    def get(self, key, default=None):
        self.reads.append(key)
        return super().get(key, default)


@respx.mock
def test_quiet_rounds_still_read_the_owner_registrations_so_the_dict_keeps_them():
    mock_channels()
    store = ReadCountingStore()
    store.put(alerts.OWNER_TOKENS_KEY, {"registered-device": {"uid_hash": "h"}})
    deps = make_deps(clock=FakeClock(T0), store=store)
    for minutes in (0, 15, 30):
        assert (
            run_alerts(
                deps, T0 + timedelta(minutes=minutes), None, build_delivery(deps.settings, store)
            ).firing
            == []
        )
    assert store.reads.count(alerts.OWNER_TOKENS_KEY) == 3


@respx.mock
def test_fcm_errors_do_not_stop_healthchecks_and_missing_config_sends_nothing():
    hc_fail = respx.post(f"{HC_ALERT}/fail").mock(return_value=httpx.Response(200))
    respx.post(FCM).mock(return_value=httpx.Response(404, json={"error": {"status": "NOT_FOUND"}}))
    deps = firing_deps()
    result = AlertDelivery(deps.settings, deps.store, fcm_tokens=FakeTokens()).send([Alert("probe_failed")])
    assert result.healthchecks and result.pushes == 0
    assert hc_fail.call_count == 1
    bare = make_settings(healthchecks_alert_url="", firebase_project_id="")
    assert not AlertDelivery(bare, MemoryStore(), fcm_tokens=FakeTokens()).send([Alert("x")]).delivered


@respx.mock
def test_test_fire_sends_every_alert_once_labelled_test():
    hc_fail, _, _, fcm = mock_channels()
    deps = firing_deps()
    delivery = build_delivery(deps.settings, deps.store)
    run_alerts(deps, T0, None, delivery)  # a real alert was just sent: test fire ignores de-duplication
    result = alerts.test_fire(delivery)
    assert result.healthchecks and result.pushes == 2
    text = hc_fail.calls[-1].request.content.decode()
    assert text.startswith("TEST RideMate main: ")
    entries = text.removeprefix("TEST RideMate main: ").split("; ")
    assert [e.split("=")[0].split(" ")[0] for e in entries] == [a.name for a in CATALOG]
    assert json.loads(fcm.calls[-1].request.content)["message"]["data"]["test"] == "1"
    assert len({a.name for a in CATALOG}) == len(CATALOG)


@pytest.mark.parametrize("bad_key", ["", "not json"])
def test_delivery_without_an_ops_key_still_pings_healthchecks(bad_key):
    delivery = build_delivery(make_settings(ops_sa_json=bad_key), MemoryStore())
    assert delivery._fcm_tokens is None


# ---- credit, per-network bucketing, judging week ----------------------------------------------------------


def test_credit_nearly_exhausted_fires_once_ai_plus_compute_reach_the_credit_minus_the_margin():
    # AI stops itself at its budget; compute is only metered, so this is the owner's cue to stop the app.
    assert "credit_nearly_exhausted" in names(
        AlertInputs(T0, spend_summary={"credit_used_usd": 92.0, "credit_usd": 100.0})
    )
    assert names(AlertInputs(T0, spend_summary={"credit_used_usd": 91.9, "credit_usd": 100.0})) == []


def _ip_view(bucketed: int, unbucketed: int, other_unbucketed: int = 0) -> MetricsView:
    def record(m: DictMetrics) -> None:
        for _ in range(bucketed):
            m.incr("ip_bucket", outcome="bucketed", scope="v1")
        for _ in range(unbucketed):
            m.incr("ip_bucket", outcome="unbucketed", scope="v1")
        for _ in range(other_unbucketed):
            m.incr("ip_bucket", outcome="unbucketed", scope="other")

    return view_of(record, settle=False)


def test_ip_unbucketed_rate_fires_when_modals_forwarded_hop_goes_missing():
    assert "ip_unbucketed_rate" in names(AlertInputs(T0, view=_ip_view(15, 10)))  # 40% of /v1 unbucketed
    assert "ip_unbucketed_rate" not in names(AlertInputs(T0, view=_ip_view(40, 10)))  # exactly 20%
    assert "ip_unbucketed_rate" not in names(AlertInputs(T0, view=_ip_view(0, 10)))  # too few requests
    # requests outside /v1 (health checks from tools without a proxy hop) don't count
    assert "ip_unbucketed_rate" not in names(AlertInputs(T0, view=_ip_view(30, 0, other_unbucketed=50)))


def test_the_judging_profile_without_any_listed_judge_alerts_the_owner():
    from ridemate_api.testing import FakeRemoteConfig

    deps = make_deps(clock=FakeClock(T0))
    for rc, fires in (
        ({"cap_profile": "judging"}, True),
        ({"cap_profile": "judging", "judge_allowlist": {"sha256_emails": []}}, True),
        ({"cap_profile": "judging", "judge_allowlist": {"sha256_emails": ["ab" * 32]}}, False),
        ({"cap_profile": "normal"}, False),
    ):
        deps.remote_config = FakeRemoteConfig(rc)
        assert ("judging_allowlist_empty" in names(gather_inputs(deps, T0, None))) is fires, rc
