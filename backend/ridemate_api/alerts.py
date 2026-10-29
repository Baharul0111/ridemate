"""Owner alerts (architecture §4.2 "Observability", §4.12, §4.18 m6).

The dispatcher evaluates the thresholds below every 15 minutes over the metrics view, the spend summary, the
phone's session counters and the dispatcher's own records (probe, reconciliation, key age). Firing alerts are
delivered two ways, so one broken path doesn't silence them:
- a healthchecks.io "fail" ping (`<HEALTHCHECKS_ALERT_URL>/fail`) whose body lists alert names and numbers only,
  which emails the owner; a plain ping when nothing fires brings the check back up;
- an FCM HTTP v1 push to the owner's phone(s), sent with the ops service account.
The same alert is delivered at most once per 6 hours. `test_fire` sends every alert once, labelled TEST.
"""

from __future__ import annotations

import logging
from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta
from typing import Any

import httpx

from .firestore import OpKind, ServiceAccountTokens, TokenError, TokenSource
from .limits import IP_BUCKET_METRIC
from .metrics import (
    EVENT_PREFIX,
    HTTP_LATENCY_MS,
    HTTP_REQUESTS,
    READ_ROUTE,
    Aggregate,
    MetricsView,
    parse_series,
)
from .settings import BUDGET_MARGIN_USD, Settings
from .store import HotStore, StoreUnavailable

log = logging.getLogger(__name__)

FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging"
FCM_URL = "https://fcm.googleapis.com/v1/projects/{project}/messages:send"
DELIVERY_TIMEOUT_S = 5.0
DEDUPE = timedelta(hours=6)
MAX_OWNER_TOKENS = 10  # api/misc.py keeps at most 10 registrations
MAX_TEXT_CHARS = 900

# Thresholds (architecture §4.2 "Observability").
HTTP_WINDOW_MIN = 10
HTTP_5XX_MAX = 0.05
# A lone 5xx is noise, but a request floor would silence this rule at the paced traffic (≈ 5 requests per
# 10 minutes), so the floor is on errors instead.
HTTP_MIN_ERRORS = 3
READ_P95_MAX_MS = 15_000.0
READ_P95_WINDOW_MIN = 60  # ~4 reads per session: 10 minutes rarely holds enough samples for a p95
READ_MIN_SAMPLES = 5
RATE_WINDOW_H = 24
VERIFIED_MIN = 0.70
VERIFIED_MIN_READS = 20
RULE_MISS_MAX = 0.20
RULE_MIN_POSTS = 20
SPIKE_MIN_COUNT = 10  # "rule miss, no classifier" in the last 2 hours
SPIKE_FACTOR = 3.0  # versus the average 2-hour count of the previous 22 hours
DAY_CAP_MAX = 0.70
BUDGET_MAX = 0.80
DRIFT_MAX = 0.10
FIRESTORE_WRITES_MAX = 0.70
# Per-network limits silently switch off for requests without a usable X-Forwarded-For hop (limits.py).
IP_UNBUCKETED_MAX = 0.20
IP_UNBUCKETED_WINDOW_MIN = 60
IP_UNBUCKETED_MIN_REQUESTS = 20
SA_KEY_MAX_AGE_DAYS = 80
RECORD_FRESH = timedelta(hours=30)  # probe/drift records older than this are ignored

# Hot-store keys read here (written by the dispatcher, deploy.sh and /v1/owner/fcm-token).
DRIFT_KEY = "billing:drift"
PROBE_KEY = "cron:probe:last"
SA_KEYS_KEY = "ops:sa_keys"
OWNER_TOKENS_KEY = "owner:fcm_tokens"
SENT_PREFIX = "alert:last:"


@dataclass(frozen=True)
class Alert:
    name: str
    value: float | None = None
    threshold: float | None = None
    unit: str = ""  # "%" (value is a fraction), "ms", "usd", "d" or ""

    def render(self) -> str:
        if self.value is None:
            return self.name
        text = f"{self.name}={_fmt(self.value, self.unit)}"
        if self.threshold is not None:
            text += f" (limit {_fmt(self.threshold, self.unit)})"
        return text


def _fmt(value: float, unit: str) -> str:
    if unit == "%":
        return f"{value * 100:.1f}%"
    if unit == "usd":
        return f"${value:.2f}"
    if unit == "ms":
        return f"{value / 1000:.1f}s"
    if unit == "d":
        return f"{value:.0f}d"
    return f"{value:g}"


# Every alert this module can raise, with a sample value for `test_fire`.
CATALOG: tuple[Alert, ...] = (
    Alert("http_5xx_rate", 0.06, HTTP_5XX_MAX, "%"),
    Alert("read_p95", 16_000, READ_P95_MAX_MS, "ms"),
    Alert("verified_rate", 0.65, VERIFIED_MIN, "%"),
    Alert("rule_miss_rate", 0.25, RULE_MISS_MAX, "%"),
    Alert("rule_miss_unclassified_spike", 12, SPIKE_MIN_COUNT),
    Alert("spend_day_cap", 0.75, DAY_CAP_MAX, "%"),
    Alert("spend_budget_ai", 0.85, BUDGET_MAX, "%"),
    Alert("spend_meter_unreadable"),
    Alert("credit_nearly_exhausted", 92.5, 92.0, "usd"),
    Alert("billing_drift", 0.12, DRIFT_MAX, "%"),
    Alert("reconciliation_unavailable"),
    Alert("firestore_writes", 0.72, FIRESTORE_WRITES_MAX, "%"),
    Alert("probe_failed"),
    Alert("sa_key_age_ops", 81, SA_KEY_MAX_AGE_DAYS, "d"),
    Alert("model_missing"),
    Alert("cron_job_failed_cleanup"),
    Alert("ip_unbucketed_rate", 0.4, IP_UNBUCKETED_MAX, "%"),
    Alert("judging_allowlist_empty"),
)


@dataclass
class AlertInputs:
    now: datetime
    view: MetricsView | None = None
    spend_summary: Mapping[str, Any] | None = None
    spend_unreadable: bool = False
    firestore_writes_fraction: float | None = None
    drift: Mapping[str, Any] | None = None
    probe: Mapping[str, Any] | None = None
    sa_keys_created: Mapping[str, datetime] = field(default_factory=dict)
    model_missing: bool = False
    judging_without_judges: bool = False  # cap_profile=judging while judge_allowlist lists no one
    extra: Sequence[Alert] = ()


# ---- evaluation --------------------------------------------------------------------------------------------


def evaluate(inputs: AlertInputs) -> list[Alert]:
    alerts: list[Alert] = []
    if inputs.view is not None:
        alerts += _http_rules(inputs.view.recent(HTTP_WINDOW_MIN))
        alerts += _ip_rules(inputs.view.recent(IP_UNBUCKETED_WINDOW_MIN))
        alerts += _latency_rules(inputs.view.recent(READ_P95_WINDOW_MIN))
        alerts += _session_rules(inputs.view)
    alerts += _spend_rules(inputs)
    if inputs.drift and _fresh(inputs.drift, inputs.now):
        drift = _number(inputs.drift.get("drift"))
        if drift is not None and drift > DRIFT_MAX:
            alerts.append(Alert("billing_drift", drift, DRIFT_MAX, "%"))
    fraction = inputs.firestore_writes_fraction
    if fraction is not None and fraction > FIRESTORE_WRITES_MAX:
        alerts.append(Alert("firestore_writes", fraction, FIRESTORE_WRITES_MAX, "%"))
    if inputs.probe and _fresh(inputs.probe, inputs.now) and inputs.probe.get("status") == "fail":
        alerts.append(Alert("probe_failed"))
    for role, created in sorted(inputs.sa_keys_created.items()):
        age_days = (inputs.now - created).total_seconds() / 86400
        if age_days > SA_KEY_MAX_AGE_DAYS:
            alerts.append(Alert(f"sa_key_age_{role}", age_days, SA_KEY_MAX_AGE_DAYS, "d"))
    if inputs.model_missing:
        alerts.append(Alert("model_missing"))
    if inputs.judging_without_judges:
        # The judging profile only caps the public day at ≤ $3; judges are protected by the allowlist (and Try the
        # demo). With nobody listed, judges sign in as brand-new accounts: 3 reads a day, paused first.
        alerts.append(Alert("judging_allowlist_empty"))
    alerts += list(inputs.extra)
    return _unique(alerts)


def _http_rules(recent: Aggregate) -> list[Alert]:
    alerts: list[Alert] = []
    total = errors = 0
    for key, n in recent.counters.items():
        name, labels = parse_series(key)
        if name == HTTP_REQUESTS and labels.get("route", "").startswith("/v1/"):
            total += n
            if labels.get("status_class") == "5xx":
                errors += n
    if errors >= HTTP_MIN_ERRORS and errors / total > HTTP_5XX_MAX:
        alerts.append(Alert("http_5xx_rate", errors / total, HTTP_5XX_MAX, "%"))
    return alerts


def _ip_rules(recent: Aggregate) -> list[Alert]:
    """Too many /v1 requests without a usable right-most X-Forwarded-For hop: the per-network bucket is off for
    them, which is what a change in Modal's proxy would look like."""
    total = unbucketed = 0
    for key, n in recent.counters.items():
        name, labels = parse_series(key)
        if name == IP_BUCKET_METRIC and labels.get("scope") == "v1":
            total += n
            if labels.get("outcome") == "unbucketed":
                unbucketed += n
    if total >= IP_UNBUCKETED_MIN_REQUESTS and unbucketed / total > IP_UNBUCKETED_MAX:
        return [Alert("ip_unbucketed_rate", unbucketed / total, IP_UNBUCKETED_MAX, "%")]
    return []


def _latency_rules(recent: Aggregate) -> list[Alert]:
    alerts: list[Alert] = []
    p95s = []
    for hist in (
        recent.histogram(HTTP_LATENCY_MS, route=READ_ROUTE),
        recent.histogram("ai_model_latency_ms", task="read"),
    ):
        if hist.count >= READ_MIN_SAMPLES and (q := hist.quantile(0.95)) is not None:
            p95s.append(q)
    if p95s and max(p95s) > READ_P95_MAX_MS:
        alerts.append(Alert("read_p95", max(p95s), READ_P95_MAX_MS, "ms"))
    return alerts


def _session_rules(view: MetricsView) -> list[Alert]:
    alerts: list[Alert] = []
    day = view.last_hours(RATE_WINDOW_H)
    reads = day.counter(EVENT_PREFIX + "reads")
    verified = day.counter(EVENT_PREFIX + "verified_rows")
    unreadable = day.counter(EVENT_PREFIX + "unreadable_rows")
    if reads >= VERIFIED_MIN_READS and verified + unreadable > 0:
        rate = verified / (verified + unreadable)
        if rate < VERIFIED_MIN:
            alerts.append(Alert("verified_rate", rate, VERIFIED_MIN, "%"))
    hits = day.counter(EVENT_PREFIX + "rule_hits")
    misses = day.counter(EVENT_PREFIX + "rule_misses")
    if hits + misses >= RULE_MIN_POSTS and misses / (hits + misses) > RULE_MISS_MAX:
        alerts.append(Alert("rule_miss_rate", misses / (hits + misses), RULE_MISS_MAX, "%"))
    series = view.hourly(RATE_WINDOW_H, EVENT_PREFIX + "misses_unclassified")
    recent, earlier = sum(series[-2:]), series[:-2]
    baseline = sum(earlier) / (len(earlier) / 2) if earlier else 0.0
    if recent >= SPIKE_MIN_COUNT and recent > SPIKE_FACTOR * baseline:
        alerts.append(Alert("rule_miss_unclassified_spike", recent, SPIKE_MIN_COUNT))
    return alerts


def _spend_rules(inputs: AlertInputs) -> list[Alert]:
    if inputs.spend_unreadable:
        return [Alert("spend_meter_unreadable")]
    summary = inputs.spend_summary or {}
    alerts: list[Alert] = []
    day_spent, day_cap = _number(summary.get("day_spent_usd")), _number(summary.get("day_cap_usd"))
    if day_spent is not None and day_cap and day_spent / day_cap > DAY_CAP_MAX:
        alerts.append(Alert("spend_day_cap", day_spent / day_cap, DAY_CAP_MAX, "%"))
    for name, spent, budget in _budgets(summary):
        if budget and spent / budget > BUDGET_MAX:
            alerts.append(Alert(f"spend_budget_{name}", spent / budget, BUDGET_MAX, "%"))
    # AI stops itself at its budget, but compute is only metered: once AI + compute reach the credit minus the
    # margin, only the owner can stop the spend (runbooks/modal-down-or-credit-used-up.md: `modal app stop`).
    used, credit = _number(summary.get("credit_used_usd")), _number(summary.get("credit_usd"))
    if used is not None and credit and used >= credit - BUDGET_MARGIN_USD:
        alerts.append(Alert("credit_nearly_exhausted", used, credit - BUDGET_MARGIN_USD, "usd"))
    return alerts


def _budgets(summary: Mapping[str, Any]) -> Iterable[tuple[str, float, float]]:
    """(name, spent, budget) from `{x}_spent_usd`/`{x}_budget_usd` pairs and an optional `sub_budgets` (or
    `budgets`) mapping of name → {spent_usd, budget_usd}."""
    for key, value in summary.items():
        if key.endswith("_spent_usd") and key != "day_spent_usd":
            name = key.removesuffix("_spent_usd")
            spent, budget = _number(value), _number(summary.get(f"{name}_budget_usd"))
            if spent is not None and budget is not None:
                yield name, spent, budget
    for group in ("sub_budgets", "budgets"):
        entries = summary.get(group)
        if isinstance(entries, Mapping):
            for name, entry in entries.items():
                if isinstance(entry, Mapping):
                    spent, budget = _number(entry.get("spent_usd")), _number(entry.get("budget_usd"))
                    if spent is not None and budget is not None:
                        yield str(name), spent, budget


def _number(value: Any) -> float | None:
    if isinstance(value, bool) or not isinstance(value, int | float):
        return None
    return float(value)


def _fresh(record: Mapping[str, Any], now: datetime) -> bool:
    at = _parse_time(record.get("at"))
    return at is not None and now - at <= RECORD_FRESH


def _parse_time(value: Any) -> datetime | None:
    if isinstance(value, datetime):
        return value if value.tzinfo else value.replace(tzinfo=UTC)
    if isinstance(value, str):
        try:
            parsed = datetime.fromisoformat(value)
        except ValueError:
            return None
        return parsed if parsed.tzinfo else parsed.replace(tzinfo=UTC)
    return None


def _unique(alerts: Iterable[Alert]) -> list[Alert]:
    seen: dict[str, Alert] = {}
    for alert in alerts:
        seen.setdefault(alert.name, alert)
    return list(seen.values())


# ---- inputs ------------------------------------------------------------------------------------------------


def gather_inputs(
    deps: Any, now: datetime, view: MetricsView | None, extra: Sequence[Alert] = ()
) -> AlertInputs:
    """Everything `evaluate` needs, read defensively: a source that can't be read only skips its rules."""
    inputs = AlertInputs(now=now, view=view, extra=extra)
    try:
        inputs.spend_summary = deps.spend.summary()
    except Exception:  # noqa: BLE001 - an unreadable meter is itself an alert
        inputs.spend_unreadable = True
    budget = getattr(deps.firestore, "budget", None)
    if budget is not None:
        try:
            inputs.firestore_writes_fraction = budget.fraction(OpKind.WRITES)
        except Exception:  # noqa: BLE001
            log.warning("alerts: Firestore write budget unreadable")
    store: HotStore = deps.store
    inputs.drift = _safe_get(store, DRIFT_KEY)
    inputs.probe = _safe_get(store, PROBE_KEY)
    created = _safe_get(store, SA_KEYS_KEY) or {}
    if isinstance(created, Mapping):
        inputs.sa_keys_created = {str(k): t for k, v in created.items() if (t := _parse_time(v)) is not None}
    router = deps.router
    reason = None
    if router is not None and hasattr(router, "unavailable_reason"):
        try:
            reason = router.unavailable_reason()
        except Exception:  # noqa: BLE001
            reason = None
    missing_counted = view is not None and view.recent(60).counter("ai_model_missing") > 0
    inputs.model_missing = reason == "model_missing" or missing_counted
    inputs.judging_without_judges = _judging_without_judges(deps)
    return inputs


def _judging_without_judges(deps: Any) -> bool:
    rc = getattr(deps, "remote_config", None)
    if rc is None:
        return False
    try:
        if rc.get_str("cap_profile", "normal") != "judging":
            return False
        allowlist = rc.get_json("judge_allowlist", {})
    except Exception:  # noqa: BLE001 - an unreadable config only skips this rule
        return False
    hashes = allowlist.get("sha256_emails") if isinstance(allowlist, Mapping) else None
    return not (isinstance(hashes, list) and any(str(h).strip() for h in hashes))


def _safe_get(store: HotStore, key: str) -> Any:
    try:
        return store.get(key)
    except StoreUnavailable:
        return None


def owner_tokens(settings: Settings, store: HotStore) -> list[str]:
    """The owner's FCM device tokens: the one in the Modal Secret plus those registered by the app."""
    tokens: list[str] = []
    if settings.owner_fcm_token:
        tokens.append(settings.owner_fcm_token)
    registered = _safe_get(store, OWNER_TOKENS_KEY)
    if isinstance(registered, str):
        tokens.append(registered)
    elif isinstance(registered, Mapping):
        tokens += [str(k) for k in registered]
    elif isinstance(registered, list | tuple | set):
        tokens += [str(t) for t in registered if isinstance(t, str)]
    unique = list(dict.fromkeys(t for t in tokens if t))
    return unique[:MAX_OWNER_TOKENS]


# ---- delivery ----------------------------------------------------------------------------------------------


@dataclass
class DeliveryResult:
    healthchecks: bool = False
    pushes: int = 0

    @property
    def delivered(self) -> bool:
        return self.healthchecks or self.pushes > 0


class AlertDelivery:
    def __init__(
        self,
        settings: Settings,
        store: HotStore,
        *,
        http_client: httpx.Client | None = None,
        fcm_tokens: TokenSource | None = None,
    ) -> None:
        self._settings = settings
        self._store = store
        self._client = http_client or httpx.Client()
        self._fcm_tokens = fcm_tokens

    def send(self, alerts: Sequence[Alert], *, test: bool = False) -> DeliveryResult:
        text = render_text(alerts, env=self._settings.environment, test=test)
        result = DeliveryResult(healthchecks=self._ping(self._settings.healthchecks_alert_url, "fail", text))
        result.pushes = self._push(alerts, text, test=test)
        return result

    def ping_ok(self) -> bool:
        return self._ping(self._settings.healthchecks_alert_url, "", None)

    def _ping(self, base_url: str, suffix: str, body: str | None) -> bool:
        if not base_url:
            return False
        url = base_url.rstrip("/") + (f"/{suffix}" if suffix else "")
        try:
            response = self._client.post(
                url,
                content=(body or "").encode("utf-8"),
                headers={"Content-Type": "text/plain; charset=utf-8"},
                timeout=DELIVERY_TIMEOUT_S,
            )
        except httpx.HTTPError as e:
            log.warning("alerts: healthchecks ping failed (%s)", type(e).__name__)
            return False
        if response.status_code >= 400:
            log.warning("alerts: healthchecks ping refused", extra={"status": response.status_code})
            return False
        return True

    def _push(self, alerts: Sequence[Alert], text: str, *, test: bool) -> int:
        tokens = owner_tokens(self._settings, self._store)
        if not tokens or self._fcm_tokens is None or not self._settings.firebase_project_id:
            return 0
        try:
            access = self._fcm_tokens.token()
        except TokenError as e:
            log.warning("alerts: no FCM access token (%s)", e)
            return 0
        title = ("TEST " if test else "") + f"RideMate alert ({len(alerts)})"
        sent = 0
        for device in tokens:
            message = {
                "message": {
                    "token": device,
                    "notification": {"title": title, "body": text},
                    "data": {
                        "type": "owner_alert",
                        "test": "1" if test else "0",
                        "names": ",".join(a.name for a in alerts)[:500],
                    },
                    "android": {"priority": "high"},
                }
            }
            try:
                response = self._client.post(
                    FCM_URL.format(project=self._settings.firebase_project_id),
                    json=message,
                    headers={"Authorization": f"Bearer {access}"},
                    timeout=DELIVERY_TIMEOUT_S,
                )
            except httpx.HTTPError as e:
                log.warning("alerts: FCM push failed (%s)", type(e).__name__)
                continue
            if response.status_code < 400:
                sent += 1
            else:
                log.warning("alerts: FCM push refused", extra={"status": response.status_code})
        return sent


def render_text(alerts: Sequence[Alert], *, env: str, test: bool = False) -> str:
    """Alert names and numbers only: no user data, no free text."""
    head = ("TEST " if test else "") + f"RideMate {env}: "
    text = head + "; ".join(a.render() for a in alerts)
    return text if len(text) <= MAX_TEXT_CHARS else text[: MAX_TEXT_CHARS - 1] + "…"


def build_delivery(
    settings: Settings, store: HotStore, *, http_client: httpx.Client | None = None
) -> AlertDelivery:
    client = http_client or httpx.Client()
    tokens: TokenSource | None = None
    if settings.ops_sa_json:
        try:
            tokens = ServiceAccountTokens(settings.ops_sa_json, [FCM_SCOPE], http_client=client)
        except ValueError:
            log.error("alerts: ops service-account key is not valid JSON; FCM pushes disabled")
    return AlertDelivery(settings, store, http_client=client, fcm_tokens=tokens)


# ---- the round ---------------------------------------------------------------------------------------------


@dataclass
class AlertRun:
    firing: list[str] = field(default_factory=list)
    sent: list[str] = field(default_factory=list)
    suppressed: list[str] = field(default_factory=list)
    delivery: DeliveryResult | None = None


def run_alerts(
    deps: Any,
    now: datetime,
    view: MetricsView | None,
    delivery: AlertDelivery,
    *,
    extra: Sequence[Alert] = (),
) -> AlertRun:
    # Modal Dict entries expire after 7 days without a read: reading the owner's device registrations every round
    # keeps them through quiet weeks, so the first alert after one still reaches the phone.
    owner_tokens(deps.settings, deps.store)
    firing = evaluate(gather_inputs(deps, now, view, extra))
    run = AlertRun(firing=[a.name for a in firing])
    if not firing:
        delivery.ping_ok()
        return run
    due = [a for a in firing if not _recently_sent(deps.store, a.name, now)]
    run.suppressed = [a.name for a in firing if a not in due]
    if not due:
        return run
    run.delivery = delivery.send(due)
    if run.delivery.delivered:
        run.sent = [a.name for a in due]
        for alert in due:
            _mark_sent(deps.store, alert.name, now)
    log.warning("alerts fired", extra={"alerts": run.sent or run.firing, "count": len(due)})
    return run


def test_fire(delivery: AlertDelivery) -> DeliveryResult:
    """Send every alert in the catalog once, labelled TEST, bypassing de-duplication (proves both paths)."""
    return delivery.send(CATALOG, test=True)


test_fire.__test__ = False  # type: ignore[attr-defined]  # not a pytest test despite the name


def _recently_sent(store: HotStore, name: str, now: datetime) -> bool:
    last = _parse_time(_safe_get(store, SENT_PREFIX + name))
    return last is not None and now - last < DEDUPE


def _mark_sent(store: HotStore, name: str, now: datetime) -> None:
    try:
        store.put(SENT_PREFIX + name, now.isoformat())
    except StoreUnavailable:
        log.warning("alerts: could not record delivery; the alert may repeat")


__all__ = [
    "CATALOG",
    "Alert",
    "AlertDelivery",
    "AlertInputs",
    "AlertRun",
    "build_delivery",
    "evaluate",
    "gather_inputs",
    "owner_tokens",
    "render_text",
    "run_alerts",
    "test_fire",
]
