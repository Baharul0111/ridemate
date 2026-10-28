"""firestore.py: REST client, value encoding, deadlines, error mapping, write-budget shedding and buffering."""

from __future__ import annotations

import json
from datetime import UTC, datetime, timedelta

import httpx
import pytest
import respx

from ridemate_api.clock import IST, FakeClock
from ridemate_api.firestore import (
    BUFFER_PREFIX,
    DATASTORE_SCOPE,
    FirestoreRest,
    OpKind,
    ServiceAccountTokens,
    build_firestore,
    decode_fields,
    encode_fields,
    encode_value,
    quota_day,
    quota_reset_utc,
    quote_field_path,
)
from ridemate_api.interfaces import FirestoreQuotaExhausted, FirestoreUnavailable
from ridemate_api.store import MemoryStore
from tests.test_ops_fakes import (
    PROJECT,
    TOKEN_URI,
    FakeTokens,
    assertion_claims,
    make_settings,
    sa_json,
    token_response,
)

DOCS = f"https://firestore.googleapis.com/v1/projects/{PROJECT}/databases/(default)/documents"
NOW = datetime(2026, 10, 28, 6, 0, tzinfo=UTC)  # 11:30 IST, quota day 2026-10-27 (PDT)


def client(
    store: MemoryStore | None = None,
    clock: FakeClock | None = None,
    events: list[str] | None = None,
    writer: str = "w1",
) -> FirestoreRest:
    return FirestoreRest(
        project_id=PROJECT,
        tokens=FakeTokens(),
        clock=clock or FakeClock(NOW),
        store=store,
        writer_id=writer,
        on_event=(events.append if events is not None else None),
    )


def doc_response(fields: dict) -> httpx.Response:
    return httpx.Response(200, json={"name": "x", "fields": encode_fields(fields)})


# ---- encoding ----------------------------------------------------------------------------------------------


def test_values_round_trip_through_firestore_encoding():
    data = {
        "s": "₹110",
        "i": 42,
        "big": 2**53 + 1,
        "f": 0.125,
        "b": True,
        "n": None,
        "t": datetime(2026, 10, 28, 10, 0, 0, 123456, tzinfo=UTC),
        "raw": b"\x00\x01",
        "list": [1, "two", {"three": 3}],
        "empty": [],
        "map": {"nested": {"deep": False}},
    }
    encoded = encode_fields(data)
    assert encoded["i"] == {"integerValue": "42"}
    assert encoded["b"] == {"booleanValue": True}
    assert encoded["t"] == {"timestampValue": "2026-10-28T10:00:00.123456Z"}
    assert decode_fields(json.loads(json.dumps(encoded))) == data


def test_timestamps_are_utc_rfc3339_and_naive_means_utc():
    ist = datetime(2026, 10, 28, 16, 0, tzinfo=IST)
    assert encode_value(ist) == {"timestampValue": "2026-10-28T10:30:00.000000Z"}
    assert encode_value(datetime(2026, 1, 1, 0, 0)) == {"timestampValue": "2026-01-01T00:00:00.000000Z"}
    decoded = decode_fields({"t": {"timestampValue": "2026-10-28T10:00:00.123456789Z"}})["t"]
    assert decoded == datetime(2026, 10, 28, 10, 0, 0, 123456, tzinfo=UTC)


def test_unsupported_values_are_rejected():
    with pytest.raises(TypeError):
        encode_value(object())


def test_field_paths_are_backtick_quoted_when_not_simple():
    assert quote_field_path("created_at") == "created_at"
    assert quote_field_path("http_requests{route=/v1}") == "`http_requests{route=/v1}`"
    assert quote_field_path("a`b") == "`a\\`b`"


# ---- requests ----------------------------------------------------------------------------------------------


@respx.mock
def test_get_decodes_fields_and_returns_none_when_missing():
    respx.get(f"{DOCS}/ondc_txn/t1").mock(return_value=doc_response({"uid": "u1", "state": "CONFIRMED"}))
    respx.get(f"{DOCS}/ondc_txn/missing").mock(
        return_value=httpx.Response(404, json={"error": {"code": 404}})
    )
    fs = client()
    assert fs.get("ondc_txn/t1") == {"uid": "u1", "state": "CONFIRMED"}
    assert fs.get("ondc_txn/missing") is None
    request = respx.calls[0].request
    assert request.headers["Authorization"] == "Bearer fake-access-token"


@respx.mock
def test_every_call_has_a_3_second_deadline():
    route = respx.get(f"{DOCS}/blocked_uids/list").mock(return_value=doc_response({"uids": []}))
    client().get("blocked_uids/list")
    timeout = route.calls[0].request.extensions["timeout"]
    assert set(timeout.values()) == {3.0}


@respx.mock
def test_set_replaces_the_document_and_merge_sends_an_update_mask():
    route = respx.patch(url__startswith=f"{DOCS}/ondc_txn/t1").mock(return_value=doc_response({}))
    fs = client()
    fs.set("ondc_txn/t1", {"uid": "u1", "fee": {"amount": 0}})
    fs.set("ondc_txn/t1", {"state": "ENDED", "odd-key": 1}, merge=True)
    replace, merge = (c.request for c in route.calls)
    assert replace.url.params.get_list("updateMask.fieldPaths") == []
    assert json.loads(replace.content)["fields"]["fee"] == {
        "mapValue": {"fields": {"amount": {"integerValue": "0"}}}
    }
    assert merge.url.params.get_list("updateMask.fieldPaths") == ["state", "`odd-key`"]


@respx.mock
def test_delete_and_missing_delete_both_succeed():
    respx.delete(f"{DOCS}/events/e1").mock(return_value=httpx.Response(200, json={}))
    respx.delete(f"{DOCS}/events/gone").mock(return_value=httpx.Response(404, json={}))
    fs = client()
    fs.delete("events/e1")
    fs.delete("events/gone")


@respx.mock
def test_increment_commits_a_field_transform_that_creates_the_document():
    route = respx.post(f"{DOCS}:commit").mock(return_value=httpx.Response(200, json={"writeResults": [{}]}))
    fs = client()
    fs.increment("spend_shards/2026-10-28_s1", "usd", 0.25)
    fs.increment("spend_shards/2026-10-28_s1", "count", 2)
    first, second = (json.loads(c.request.content)["writes"][0] for c in route.calls)
    assert first["update"] == {
        "name": f"projects/{PROJECT}/databases/(default)/documents/spend_shards/2026-10-28_s1",
        "fields": {},
    }
    assert first["updateMask"] == {"fieldPaths": []}
    assert first["updateTransforms"] == [{"fieldPath": "usd", "increment": {"doubleValue": 0.25}}]
    assert second["updateTransforms"] == [{"fieldPath": "count", "increment": {"integerValue": "2"}}]


@respx.mock
def test_query_runs_a_structured_query_and_returns_relative_paths():
    prefix = f"projects/{PROJECT}/databases/(default)/documents"
    route = respx.post(f"{DOCS}/metrics/2026-10-28:runQuery").mock(
        return_value=httpx.Response(
            200,
            json=[
                {"readTime": "x"},
                {
                    "document": {
                        "name": f"{prefix}/metrics/2026-10-28/shards/202610281000",
                        "fields": encode_fields({"day": "2026-10-28"}),
                    }
                },
            ],
        )
    )
    rows = client().query("metrics/2026-10-28/shards", "day", "==", "2026-10-28", limit=5)
    assert rows == [("metrics/2026-10-28/shards/202610281000", {"day": "2026-10-28"})]
    body = json.loads(route.calls[0].request.content)["structuredQuery"]
    assert body["from"] == [{"collectionId": "shards"}]
    assert body["where"]["fieldFilter"] == {
        "field": {"fieldPath": "day"},
        "op": "EQUAL",
        "value": {"stringValue": "2026-10-28"},
    }
    assert body["limit"] == 5


@respx.mock
def test_top_level_query_and_timestamp_filters():
    route = respx.post(f"{DOCS}:runQuery").mock(return_value=httpx.Response(200, json=[]))
    cutoff = datetime(2025, 10, 28, tzinfo=UTC)
    assert client().query("events", "created_at", "<", cutoff) == []
    fil = json.loads(route.calls[0].request.content)["structuredQuery"]["where"]["fieldFilter"]
    assert fil["op"] == "LESS_THAN"
    assert fil["value"] == {"timestampValue": "2025-10-28T00:00:00.000000Z"}


def test_bad_paths_and_operators_are_rejected():
    fs = client()
    with pytest.raises(ValueError):
        fs.query("events", "x", "~=", 1)
    with pytest.raises(ValueError):
        fs.query("events/e1", "x", "==", 1)
    with pytest.raises(ValueError):
        fs.get("events")


# ---- failures ----------------------------------------------------------------------------------------------


@respx.mock
@pytest.mark.parametrize(
    "response",
    [
        httpx.Response(429, json={"error": {"code": 429, "status": "RESOURCE_EXHAUSTED"}}),
        httpx.Response(400, json={"error": {"code": 400, "status": "RESOURCE_EXHAUSTED"}}),
    ],
)
def test_quota_errors_raise_quota_exhausted(response):
    respx.get(f"{DOCS}/ondc_txn/t1").mock(return_value=response)
    with pytest.raises(FirestoreQuotaExhausted):
        client().get("ondc_txn/t1")


@respx.mock
@pytest.mark.parametrize(
    "outcome",
    [
        httpx.Response(500, json={"error": {"code": 500, "status": "INTERNAL"}}),
        httpx.Response(403, json={"error": {"code": 403, "status": "PERMISSION_DENIED"}}),
        httpx.ReadTimeout("slow"),
        httpx.ConnectError("down"),
    ],
)
def test_other_failures_raise_unavailable(outcome):
    route = respx.get(f"{DOCS}/ondc_txn/t1")
    if isinstance(outcome, Exception):
        route.mock(side_effect=outcome)
    else:
        route.mock(return_value=outcome)
    with pytest.raises(FirestoreUnavailable) as info:
        client().get("ondc_txn/t1")
    assert not isinstance(info.value, FirestoreQuotaExhausted)


# ---- service-account tokens --------------------------------------------------------------------------------


@respx.mock
def test_service_account_token_is_fetched_once_with_the_datastore_scope():
    token_route = respx.post(TOKEN_URI).mock(return_value=token_response("tok-A"))
    get_route = respx.get(f"{DOCS}/blocked_uids/list").mock(return_value=doc_response({"uids": ["x"]}))
    fs = build_firestore(make_settings(web_sa_json=sa_json()), store=None, clock=FakeClock(NOW))
    assert fs is not None
    fs.get("blocked_uids/list")
    fs.get("blocked_uids/list")
    assert token_route.call_count == 1
    assert assertion_claims(token_route.calls[0].request)["scope"] == DATASTORE_SCOPE
    assert get_route.calls[1].request.headers["Authorization"] == "Bearer tok-A"


@respx.mock
def test_a_token_failure_is_firestore_unavailable():
    respx.post(TOKEN_URI).mock(return_value=httpx.Response(400, json={"error": "invalid_grant"}))
    fs = FirestoreRest(
        project_id=PROJECT, tokens=ServiceAccountTokens(sa_json(), [DATASTORE_SCOPE]), clock=FakeClock(NOW)
    )
    with pytest.raises(FirestoreUnavailable):
        fs.get("ondc_txn/t1")


def test_no_client_without_a_key_or_project_and_bad_keys_never_echo():
    assert build_firestore(make_settings(web_sa_json=""), store=None, clock=FakeClock(NOW)) is None
    assert (
        build_firestore(
            make_settings(web_sa_json=sa_json(), firebase_project_id=""), store=None, clock=FakeClock(NOW)
        )
        is None
    )
    assert (
        build_firestore(make_settings(web_sa_json="{not json SECRET"), store=None, clock=FakeClock(NOW))
        is None
    )
    with pytest.raises(ValueError) as info:
        ServiceAccountTokens('{"private_key": "SECRET-MATERIAL"}', [DATASTORE_SCOPE])
    assert "SECRET" not in str(info.value)


def test_ops_role_uses_the_ops_key():
    settings = make_settings(web_sa_json="", ops_sa_json=sa_json())
    assert build_firestore(settings, store=None, clock=FakeClock(NOW), role="web") is None
    assert build_firestore(settings, store=None, clock=FakeClock(NOW), role="ops") is not None


# ---- quota day and write budget ----------------------------------------------------------------------------


def test_quota_day_rolls_over_at_us_pacific_midnight():
    # Summer time (until 1 Nov 2026): midnight PDT = 07:00 UTC = 12:30 IST.
    assert quota_day(datetime(2026, 10, 28, 6, 59, tzinfo=UTC)) == "2026-10-27"
    assert quota_day(datetime(2026, 10, 28, 7, 0, tzinfo=UTC)) == "2026-10-28"
    assert quota_reset_utc(datetime(2026, 10, 28, 6, 0, tzinfo=UTC)) == datetime(
        2026, 10, 28, 7, 0, tzinfo=UTC
    )
    # Winter: midnight PST = 08:00 UTC = 13:30 IST.
    assert quota_day(datetime(2026, 12, 1, 7, 59, tzinfo=UTC)) == "2026-11-30"
    assert quota_day(datetime(2026, 12, 1, 8, 0, tzinfo=UTC)) == "2026-12-01"
    reset = quota_reset_utc(datetime(2026, 12, 1, 9, 0, tzinfo=UTC))
    assert reset.astimezone(IST).strftime("%H:%M") == "13:30"


@respx.mock
def test_writes_are_counted_per_quota_day_across_containers():
    respx.patch(url__startswith=DOCS).mock(return_value=doc_response({}))
    respx.delete(url__startswith=DOCS).mock(return_value=httpx.Response(200, json={}))
    store, clock = MemoryStore(), FakeClock(NOW)
    a, b = client(store, clock, writer="a"), client(store, clock, writer="b")
    a.set("ondc_txn/t1", {"x": 1})
    a.set("ondc_txn/t2", {"x": 1})
    b.set("ondc_txn/t3", {"x": 1})
    b.delete("events/e1")
    day = quota_day(NOW)
    assert store.get(f"fs:writes:{day}:a") == 2
    assert store.get(f"fs:writes:{day}:b") == 1
    assert store.get(f"fs:deletes:{day}:b") == 1
    clock.advance(31)  # totals across writers refresh every 30 s
    assert a.budget.used(OpKind.WRITES) == 3
    assert b.budget.fraction(OpKind.WRITES) == pytest.approx(3 / 20_000)


@respx.mock
def test_metrics_detail_is_shed_above_80_percent_but_essential_writes_go_through():
    route = respx.patch(url__startswith=DOCS).mock(return_value=doc_response({}))
    store, events = MemoryStore(), []
    store.put(f"fs:writes:{quota_day(NOW)}:other", 16_000)
    fs = client(store, events=events)
    fs.set("metrics/2026-10-28/shards/202610281000", {"counters": {}})
    assert route.call_count == 0
    assert events == ["firestore_shed"]
    fs.set("events/e1", {"outcome": "demo"})
    fs.set("ondc_txn/t1", {"uid": "u1"})
    assert route.call_count == 2


@respx.mock
def test_audit_and_events_are_parked_when_the_quota_is_used_up_and_replayed_after_the_reset():
    route = respx.patch(url__startswith=DOCS).mock(return_value=doc_response({}))
    store, clock, events = MemoryStore(), FakeClock(NOW), []
    store.put(f"fs:writes:{quota_day(NOW)}:other", 20_000)
    fs = client(store, clock, events=events)

    fs.set("audit/a1", {"endpoint": "/v1/read-screen", "outcome": "ok"})
    fs.set("events/e1", {"outcome": "confirmed_all"})
    with pytest.raises(FirestoreQuotaExhausted):
        fs.set("ondc_txn/t1", {"uid": "u1"})
    assert route.call_count == 0
    parked = sorted(store.keys_with_prefix(BUFFER_PREFIX))
    assert len(parked) == 2 and fs.buffered_count() == 2
    assert store.get(parked[0])["data"]["created_at"] == NOW  # stamped when parked, for the cleanup job
    assert fs.replay_buffer() == 0  # still the same quota day

    clock.advance((quota_reset_utc(NOW) - NOW).total_seconds() + 60)
    assert fs.replay_buffer() == 2
    assert route.call_count == 2
    assert store.keys_with_prefix(BUFFER_PREFIX) == []
    replayed = json.loads(route.calls[0].request.content)["fields"]
    assert replayed["endpoint"] == {"stringValue": "/v1/read-screen"}
    assert "firestore_buffered" in events


@respx.mock
def test_the_metrics_day_doc_is_never_shed_so_its_shards_stay_findable_by_cleanup():
    route = respx.patch(url__startswith=DOCS).mock(return_value=doc_response({}))
    store, events = MemoryStore(), []
    store.put(f"fs:writes:{quota_day(NOW)}:other", 16_500)
    fs = client(store, events=events)
    fs.set("metrics/2026-10-28", {"day": "2026-10-28"}, merge=True)
    fs.set("metrics/2026-10-28/shards/202610281000", {"day": "2026-10-28"})
    assert route.call_count == 1
    assert route.calls[0].request.url.path.endswith("/metrics/2026-10-28")
    assert events == ["firestore_shed"]


@respx.mock
def test_parking_is_capped_per_quota_day_not_per_container_lifetime(monkeypatch):
    from ridemate_api import firestore as firestore_mod

    monkeypatch.setattr(firestore_mod, "MAX_BUFFERED_PER_WRITER", 2)
    respx.patch(url__startswith=DOCS).mock(return_value=doc_response({}))
    store, clock, events = MemoryStore(), FakeClock(NOW), []
    store.put(f"fs:writes:{quota_day(NOW)}:other", 20_000)
    fs = client(store, clock, events=events)
    fs.set("events/e1", {"outcome": "demo"})
    fs.set("events/e2", {"outcome": "demo"})
    with pytest.raises(FirestoreQuotaExhausted):
        fs.set("events/e3", {"outcome": "demo"})
    assert "firestore_buffer_full" in events
    clock.advance((quota_reset_utc(NOW) - NOW).total_seconds() + 60)
    assert fs.replay_buffer() == 2
    store.put(f"fs:exhausted:writes:{quota_day(clock.now_utc())}", True)  # the new day's quota is gone too
    fs.set("events/e4", {"outcome": "demo"})  # a new quota day: parking works again
    assert fs.buffered_count() == 1


@respx.mock
def test_unwritable_records_are_refused_up_front_and_never_block_the_replay():
    route = respx.patch(url__startswith=DOCS).mock(return_value=doc_response({}))
    store, clock, events = MemoryStore(), FakeClock(NOW), []
    store.put(f"fs:writes:{quota_day(NOW)}:other", 20_000)
    fs = client(store, clock, events=events)
    with pytest.raises(ValueError):
        fs.set("events", {"outcome": "demo"})  # a collection, not a document
    with pytest.raises(TypeError):
        fs.set("events/e0", {"outcome": {1, 2}})
    assert fs.buffered_count() == 0
    store.put(
        f"{BUFFER_PREFIX}old:00000001", {"path": "audit", "data": {}, "merge": False}
    )  # parked by old code
    fs.set("audit/a1", {"outcome": "ok"})
    clock.advance((quota_reset_utc(NOW) - NOW).total_seconds() + 60)
    assert fs.replay_buffer() == 1
    assert route.call_count == 1 and fs.buffered_count() == 0
    assert "firestore_buffer_dropped" in events


@respx.mock
def test_replay_stops_when_the_dispatcher_runs_out_of_time():
    route = respx.patch(url__startswith=DOCS).mock(return_value=doc_response({}))
    store, clock = MemoryStore(), FakeClock(NOW)
    store.put(f"fs:writes:{quota_day(NOW)}:other", 20_000)
    fs = client(store, clock)
    for i in range(5):
        fs.set(f"audit/a{i}", {"outcome": "ok"})
    clock.advance((quota_reset_utc(NOW) - NOW).total_seconds() + 60)
    checks = iter([False, False, True])
    assert fs.replay_buffer(stop=lambda: next(checks)) == 2
    assert route.call_count == 2 and fs.buffered_count() == 3


@respx.mock
def test_a_firestore_429_marks_the_day_exhausted_for_every_container():
    route = respx.patch(url__startswith=DOCS).mock(
        return_value=httpx.Response(429, json={"error": {"status": "RESOURCE_EXHAUSTED"}})
    )
    store, events = MemoryStore(), []
    fs = client(store, events=events)
    fs.set("events/e1", {"outcome": "demo"})  # parked, not lost
    assert fs.buffered_count() == 1
    assert "firestore_writes_exhausted" in events
    other = client(store, writer="other")
    with pytest.raises(FirestoreQuotaExhausted):
        other.set("ondc_txn/t1", {"uid": "u1"})
    with pytest.raises(FirestoreQuotaExhausted):
        other.increment("spend_shards/x", "usd", 1.0)
    assert route.call_count == 1


@respx.mock
def test_retention_field_is_stamped_on_new_audit_and_event_records_only():
    route = respx.patch(url__startswith=DOCS).mock(return_value=doc_response({}))
    fs = client()
    fs.set("events/e1", {"outcome": "demo"})
    fs.set("events/e2", {"outcome": "demo", "created_at": NOW - timedelta(days=1)})
    fs.set("events/e3", {"outcome": "demo"}, merge=True)
    fs.set("ondc_txn/t1", {"uid": "u1"})
    bodies = [json.loads(c.request.content)["fields"] for c in route.calls]
    assert bodies[0]["created_at"] == {"timestampValue": "2026-10-28T06:00:00.000000Z"}
    assert bodies[1]["created_at"] == {"timestampValue": "2026-10-27T06:00:00.000000Z"}
    assert "created_at" not in bodies[2]
    assert "created_at" not in bodies[3]


@respx.mock
def test_hot_store_outage_never_fails_a_firestore_write():
    route = respx.patch(url__startswith=DOCS).mock(return_value=doc_response({}))
    store = MemoryStore()
    fs = client(store)
    store.fail = True
    fs.set("ondc_txn/t1", {"uid": "u1"})
    assert route.call_count == 1


@respx.mock
def test_deletes_stop_when_the_delete_quota_is_gone():
    route = respx.delete(url__startswith=DOCS).mock(return_value=httpx.Response(200, json={}))
    store = MemoryStore()
    store.put(f"fs:deletes:{quota_day(NOW)}:other", 20_000)
    with pytest.raises(FirestoreQuotaExhausted):
        client(store).delete("events/e1")
    assert route.call_count == 0
