"""Eval harness scripts. No test makes a network call or spends money: readers, meters, urlopen are fakes."""

from __future__ import annotations

import hashlib
import io
import json
import sys
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import pytest
from ridemate_eval.paths import DEMO_LABELS, EVAL_DIR, REPO_ROOT

sys.path.insert(0, str(EVAL_DIR / "harness"))

import gateway  # noqa: E402
import record_channel_a as rec  # noqa: E402
import summarize  # noqa: E402
import sync_golden_to_volume as sync  # noqa: E402


@pytest.fixture(autouse=True)
def no_network(monkeypatch):
    def refuse(*a: Any, **k: Any) -> None:
        raise AssertionError("network call attempted")

    monkeypatch.setattr(urllib.request, "urlopen", refuse)


READ = {
    "app": "demo_kestrel",
    "screen_type": "searching",
    "options": [],
    "fee_text": None,
    "driver_eta_min": None,
    "driver_distance_text": None,
}


def fake_result(cost: float | None = 0.004, source: str = "live") -> rec.ReadResult:
    usage = {"input_tokens": 1500, "cached_input_tokens": 0, "output_tokens": 200}
    return rec.ReadResult(
        dict(READ), "meta-models/Muse-Spark-Open-816B-A42B", source, "rid", 1200, usage, cost
    )


class FakeReader:
    def __init__(
        self, cost: float | None = 0.004, source: str = "live", stop_after: int | None = None
    ) -> None:
        self.calls = 0
        self.cost, self.source, self.stop_after = cost, source, stop_after

    def read(self, job: rec.Job, image: bytes) -> rec.ReadResult:
        self.calls += 1
        if self.stop_after is not None and self.calls > self.stop_after:
            raise rec.StopRun("read-screen refused: ai_paused")
        return fake_result(self.cost, self.source)


def test_dry_run_lists_demo_screens_without_network_or_token(capsys, tmp_path) -> None:
    assert rec.main(["--dry-run", "--out-dir", str(tmp_path)]) == 0
    out = capsys.readouterr().out
    assert "would record 16" in out and "worst-case" in out
    assert not list(tmp_path.iterdir())


def test_jobs_resolve_demo_images_and_metadata() -> None:
    jobs = rec.load_jobs(DEMO_LABELS)
    assert len(jobs) == 16
    assert all(j.image.is_file() and j.meta["demo"] and j.media_type == "image/png" for j in jobs)


def test_budget_guard_stops_before_exceeding_cap(tmp_path) -> None:
    jobs = rec.load_jobs(DEMO_LABELS)
    reader = FakeReader(cost=0.004)
    report = rec.run(jobs, reader, out_dir=tmp_path, max_usd=0.05, worst_case_usd=0.0115, log=lambda *_: None)
    assert report.stopped_reason and "budget guard" in report.stopped_reason
    assert report.spent_usd + 0.0115 > 0.05 and report.spent_usd <= 0.05
    assert reader.calls == len(list(tmp_path.glob("*.json")))


def test_unknown_cost_is_counted_at_worst_case(tmp_path) -> None:
    jobs = rec.load_jobs(DEMO_LABELS)[:3]
    report = rec.run(
        jobs, FakeReader(cost=None), out_dir=tmp_path, max_usd=1.0, worst_case_usd=0.01, log=lambda *_: None
    )
    assert report.unknown_cost_calls == 3 and report.spent_usd == pytest.approx(0.03)


def test_recordings_have_the_contract_shape_and_existing_ones_are_skipped(tmp_path, schema_validator) -> None:
    jobs = rec.load_jobs(DEMO_LABELS)[:2]
    rec.run(jobs, FakeReader(), out_dir=tmp_path, max_usd=1.0, log=lambda *_: None)
    for j in jobs:
        doc = json.loads((tmp_path / f"{j.sha256}.json").read_text())
        assert doc["image_sha256"] == j.sha256 and doc["cost_usd"] == 0.004
        assert schema_validator("channel_a", doc) == []
    reader = FakeReader()
    report = rec.run(jobs, reader, out_dir=tmp_path, max_usd=1.0, log=lambda *_: None)
    assert reader.calls == 0 and len(report.skipped) == 2


def test_recorded_fallback_is_not_saved_as_a_live_read(tmp_path) -> None:
    jobs = rec.load_jobs(DEMO_LABELS)[:1]
    rec.run(jobs, FakeReader(source="recorded"), out_dir=tmp_path, max_usd=1.0, log=lambda *_: None)
    assert not list(tmp_path.glob("*.json"))


def test_service_refusal_stops_the_run(tmp_path) -> None:
    report = rec.run(
        rec.load_jobs(DEMO_LABELS),
        FakeReader(stop_after=2),
        out_dir=tmp_path,
        max_usd=1.0,
        log=lambda *_: None,
    )
    assert report.stopped_reason == "read-screen refused: ai_paused" and len(report.recorded) == 2


def test_stale_label_sha_is_rejected(tmp_path) -> None:
    job = rec.load_jobs(DEMO_LABELS)[0]
    bad = rec.Job("0" * 64, job.image, job.app, job.frame_w, job.frame_h)
    with pytest.raises(SystemExit):
        rec.run([bad], FakeReader(), out_dir=tmp_path, max_usd=1.0, log=lambda *_: None)


def test_cli_guards(monkeypatch, tmp_path) -> None:
    monkeypatch.delenv(rec.TOKEN_ENV, raising=False)
    with pytest.raises(SystemExit):
        rec.main(["--mode", "http", "--api-base-url", "https://x.example", "--out-dir", str(tmp_path)])
    with pytest.raises(SystemExit):
        rec.main(["--dry-run", "--max-usd", "50"])
    # Above the architecture's $0.50 per-run eval cap, even though the evals sub-budget is $5.
    with pytest.raises(SystemExit):
        rec.main(["--dry-run", "--max-usd", "0.51", "--out-dir", str(tmp_path)])
    with pytest.raises(SystemExit):
        rec.main(["--dry-run", "--max-usd", "0", "--out-dir", str(tmp_path)])
    assert rec.main(["--dry-run", "--max-usd", "0.5", "--out-dir", str(tmp_path)]) == 0
    with pytest.raises(SystemExit):
        rec.HttpReader("http://insecure.example", "t")


def _private_labels(root: Path) -> Path:
    """A golden-style labels file outside the public repo (as the private ridemate-golden clone would be)."""
    demo = rec.load_jobs(DEMO_LABELS)[0]
    (root / "images").mkdir(parents=True)
    data = demo.image.read_bytes()
    (root / "images/g0001.png").write_bytes(data)
    screen = {
        "file": "images/g0001.png",
        "sha256": demo.sha256,
        "app": demo.app,
        "frame_w": 1080,
        "frame_h": 2400,
    }
    (root / "labels.json").write_text(json.dumps({"schema": "1.0", "screens": [screen]}))
    return root / "labels.json"


def test_private_screens_default_to_recording_next_to_their_labels(tmp_path) -> None:
    labels = _private_labels(tmp_path / "golden")
    assert rec.default_out_dir(DEMO_LABELS) == rec.DEFAULT_OUT
    assert rec.default_out_dir(labels) == (tmp_path / "golden/recorded/channel_a").resolve()


def test_private_screens_are_never_recorded_into_the_public_repo(tmp_path, capsys) -> None:
    """L6: a golden run must not drop real reads (fares included) where git would commit them."""
    labels = _private_labels(tmp_path / "golden")
    jobs = rec.load_jobs(labels)
    with pytest.raises(SystemExit, match="L6"):
        rec.check_out_dir(jobs, rec.DEFAULT_OUT)
    with pytest.raises(SystemExit, match="L6"):
        rec.main(["--labels", str(labels), "--dry-run", "--out-dir", str(rec.DEFAULT_OUT)])
    rec.check_out_dir(jobs, tmp_path / "out")
    rec.check_out_dir(jobs, EVAL_DIR / "private/recorded")  # gitignored
    rec.check_out_dir(rec.load_jobs(DEMO_LABELS), rec.DEFAULT_OUT)  # public demo screens may go there
    assert rec.main(["--labels", str(labels), "--dry-run"]) == 0
    assert "would record 1" in capsys.readouterr().out


def test_worst_case_covers_the_routers_one_retry() -> None:
    one_attempt = rec.WORST_CASE_INPUT_TOKENS * 1.25e-6 + rec.WORST_CASE_OUTPUT_TOKENS * 4.25e-6
    assert rec.WORST_CASE_USD == pytest.approx(2 * one_attempt, abs=1e-4)


class FakeResponse(io.BytesIO):
    status = 200

    def __enter__(self) -> FakeResponse:
        return self

    def __exit__(self, *a: Any) -> None:
        return None


def test_http_reader_posts_multipart_and_costs_from_owner_budget(monkeypatch) -> None:
    seen: list[urllib.request.Request] = []
    spent = iter([1.000, 1.0035])

    def fake_urlopen(req: urllib.request.Request, timeout: float) -> FakeResponse:
        seen.append(req)
        if req.full_url.endswith("/v1/config-check"):
            return FakeResponse(json.dumps({"budget": {"ai_spent_usd": next(spent)}}).encode())
        body = {
            "read": {"app": "demo_pepper", "options": []},
            "source": "live",
            "model": "m",
            "latency_ms": 900,
            "request_id": "r",
        }
        return FakeResponse(json.dumps(body).encode())

    monkeypatch.setattr(urllib.request, "urlopen", fake_urlopen)
    job = rec.load_jobs(DEMO_LABELS)[5]
    reader = rec.HttpReader("https://api.example", "secret-token")
    result = reader.read(job, job.image.read_bytes())
    post = seen[1]
    assert post.get_method() == "POST" and post.full_url == "https://api.example/v1/read-screen"
    assert post.get_header("Authorization") == "Bearer secret-token"
    assert b'name="meta"' in post.data and b'"app": "demo_pepper"' in post.data and b"\x89PNG" in post.data
    assert result.cost_usd == pytest.approx(0.0035) and result.usage is None


def test_http_reader_turns_error_envelopes_into_stop(monkeypatch) -> None:
    import urllib.error

    def fake_urlopen(req: urllib.request.Request, timeout: float) -> FakeResponse:
        if req.full_url.endswith("/v1/config-check"):
            return FakeResponse(b'{"budget": null}')
        raise urllib.error.HTTPError(
            req.full_url, 429, "x", {}, io.BytesIO(b'{"error": {"code": "ai_paused"}}')
        )

    monkeypatch.setattr(urllib.request, "urlopen", fake_urlopen)
    job = rec.load_jobs(DEMO_LABELS)[0]
    with pytest.raises(rec.StopRun, match="ai_paused"):
        rec.HttpReader("https://api.example", "t").read(job, job.image.read_bytes())


@dataclass
class FakeMeter:
    spent: float = 0.0
    enabled: bool = True
    records: bool = True
    readable: bool = True

    def ai_status(self, principal: Any) -> Any:
        return SimpleNamespace(enabled=self.enabled, reason=None if self.enabled else "budget")

    def summary(self) -> dict[str, Any]:
        if not self.readable:
            raise RuntimeError("store unavailable")
        return {"ai_spent_usd": self.spent}


class FakeRouter:
    """The backend ProviderRouter.call shape: async, returns a RouterResult-like object."""

    def __init__(self, meter: FakeMeter, cost: float = 0.003, extra_metered: float = 0.0) -> None:
        self.meter, self.cost, self.extra_metered = meter, cost, extra_metered
        self.request: Any = None
        self.kwargs: dict[str, Any] = {}
        self.fail: Exception | None = None

    async def call(self, request: Any, **kwargs: Any) -> Any:
        self.request, self.kwargs = request, kwargs
        if self.fail is not None:
            raise self.fail
        if self.meter.records:
            self.meter.spent += self.cost + self.extra_metered
        return SimpleNamespace(
            data={"app": "demo_kestrel", "options": []}, model="m", cost_usd=self.cost, latency_ms=6900
        )


CALLS = gateway.BackendCalls(
    build_request=lambda data, media_type: {"image": data, "media_type": media_type},
    validate=lambda raw: {**raw, "validated": True},
    task="read",
    purpose="eval",
)


def test_gateway_reads_through_router_call_with_eval_purpose_and_run_id() -> None:
    meter = FakeMeter()
    router = FakeRouter(meter)
    job = rec.load_jobs(DEMO_LABELS)[0]
    reader = gateway.GatewayReader(router, meter, CALLS, run_id="run42")
    result = reader.read(job, b"img")
    reader.close()
    assert router.request == {"image": b"img", "media_type": "image/png"}
    assert router.kwargs["task"] == "read" and router.kwargs["purpose"] == "eval"
    assert router.kwargs["principal"] is None
    # The meter's per-run eval cap keys on this prefix (backend spend.eval_run_id).
    assert router.kwargs["request_id"].startswith("eval-run:run42:") and result.request_id.startswith(
        "eval-run:"
    )
    assert result.read["validated"] is True and result.source == "live"
    assert result.cost_usd == 0.003 and result.run_charge == 0.003 and result.usage is None


def test_gateway_records_the_router_s_model_time_apart_from_the_harness_wall_time(tmp_path) -> None:
    """A gateway read's wall time includes this laptop's Modal Dict round trips; the model time is the router's."""
    meter = FakeMeter()
    reader = gateway.GatewayReader(FakeRouter(meter), meter, CALLS)
    job = rec.load_jobs(DEMO_LABELS)[0]
    result = reader.read(job, b"img")
    reader.close()
    assert result.latency_ms == 6900
    assert result.wall_ms is not None and result.wall_ms >= 0
    doc = rec.recorded_doc(job, result)
    assert doc["latency_ms"] == 6900 and doc["wall_ms"] == result.wall_ms
    # http mode: the API's own latency_ms is the server time; without it, only the wall time is recorded.
    no_server_time = rec.ReadResult(dict(READ), "m", "live", "rid", None, None, None, wall_ms=48_000)
    assert "latency_ms" not in rec.recorded_doc(job, no_server_time)
    assert rec.recorded_doc(job, no_server_time)["wall_ms"] == 48_000


def test_committed_recordings_never_pass_wall_time_off_as_model_latency() -> None:
    """latency_ms is bounded by the router (12 s per attempt, one retry); longer is the harness's wall time."""
    files = sorted((EVAL_DIR / "recorded/channel_a").glob("*.json"))
    assert files
    for path in files:
        doc = json.loads(path.read_text(encoding="utf-8"))
        if doc.get("source") == "live" and "latency_ms" in doc:
            assert doc["latency_ms"] <= 2 * 12_000 + 1_000, path.name


def test_gateway_counts_a_retried_attempt_against_the_run() -> None:
    meter = FakeMeter()
    reader = gateway.GatewayReader(FakeRouter(meter, cost=0.003, extra_metered=0.002), meter, CALLS)
    result = reader.read(rec.load_jobs(DEMO_LABELS)[0], b"img")
    assert result.cost_usd == 0.003 and result.run_charge == pytest.approx(0.005)


def test_gateway_refuses_without_meter_and_aborts_on_bypass() -> None:
    with pytest.raises(SystemExit):
        gateway.GatewayReader(FakeRouter(FakeMeter()), None, CALLS)
    with pytest.raises(SystemExit):
        gateway.GatewayReader(None, FakeMeter(), CALLS)
    meter = FakeMeter(records=False)
    job = rec.load_jobs(DEMO_LABELS)[0]
    with pytest.raises(gateway.MeterBypass):
        gateway.GatewayReader(FakeRouter(meter), meter, CALLS).read(job, b"img")
    paused = FakeMeter(enabled=False)
    with pytest.raises(rec.StopRun, match="paused"):
        gateway.GatewayReader(FakeRouter(paused), paused, CALLS).read(job, b"img")
    unreadable = FakeMeter(readable=False)
    with pytest.raises(gateway.MeterBypass, match="unreadable"):
        gateway.GatewayReader(FakeRouter(unreadable), unreadable, CALLS).read(job, b"img")


def test_gateway_router_failure_stops_the_run(tmp_path) -> None:
    meter = FakeMeter()
    router = FakeRouter(meter)
    router.fail = RuntimeError("ai_paused")
    reader = gateway.GatewayReader(router, meter, CALLS)
    report = rec.run(rec.load_jobs(DEMO_LABELS), reader, out_dir=tmp_path, max_usd=0.5, log=lambda *_: None)
    assert "router refused" in (report.stopped_reason or "") and not list(tmp_path.iterdir())


def test_gateway_bypass_stops_a_run(tmp_path) -> None:
    meter = FakeMeter(records=False)
    reader = gateway.GatewayReader(FakeRouter(meter), meter, CALLS)
    report = rec.run(rec.load_jobs(DEMO_LABELS), reader, out_dir=tmp_path, max_usd=0.5, log=lambda *_: None)
    assert "did not record" in (report.stopped_reason or "") and not list(tmp_path.iterdir())


def test_run_budget_counts_charges_above_the_settled_cost(tmp_path) -> None:
    class Charged(FakeReader):
        def read(self, job: rec.Job, image: bytes) -> rec.ReadResult:
            result = super().read(job, image)
            result.charged_usd = 0.02
            return result

    reader = Charged(cost=0.004)
    report = rec.run(
        rec.load_jobs(DEMO_LABELS),
        reader,
        out_dir=tmp_path,
        max_usd=0.05,
        worst_case_usd=0.0115,
        log=lambda *_: None,
    )
    assert reader.calls == 2 and report.spent_usd == pytest.approx(0.04)


def _results(tmp_path: Path, suite: str, rows: list[tuple[str, bool | None]], **extra: Any) -> Path:
    doc = {
        "schema": "1.0",
        "suite": suite,
        "screens": [
            {
                "sha256": "a" * 64,
                "app": "demo_pepper",
                "rows": [{"outcome": o, "correct": c, "price": 172} for o, c in rows],
                "driver_eta": {"outcome": "VERIFIED", "correct": True},
                **extra,
            }
        ],
    }
    path = tmp_path / f"{suite}.json"
    path.write_text(json.dumps(doc))
    return path


def test_summarize_counts_and_never_prints_fares(tmp_path, capsys) -> None:
    _results(
        tmp_path, "golden", [("VERIFIED", True), ("VERIFIED", True), ("UNREADABLE", None), ("VERIFIED", True)]
    )
    assert summarize.main(["--results", str(tmp_path)]) == 0
    out = capsys.readouterr().out
    assert "75.0%" in out and "172" not in out
    t = summarize.summarise([tmp_path / "golden.json"])["golden"]["all"]
    assert (t.rows, t.verified, t.unreadable, t.wrong_shown, t.driver_verified) == (4, 3, 1, 0, 1)


def test_summarize_fails_on_any_wrong_demo_value_and_on_rate_thresholds(tmp_path) -> None:
    _results(tmp_path, "demo", [("VERIFIED", False), ("VERIFIED", True)])
    assert summarize.main(["--results", str(tmp_path / "demo.json")]) == 1
    _results(tmp_path, "golden", [("VERIFIED", True)] * 3 + [("UNREADABLE", None)])
    assert summarize.main(["--results", str(tmp_path / "golden.json"), "--min-verified-rate", "0.9"]) == 1
    assert summarize.main(["--results", str(tmp_path / "golden.json"), "--min-verified-rate", "0.7"]) == 0


def test_summarize_without_results_is_a_no_op(tmp_path, capsys) -> None:
    assert summarize.main(["--results", str(tmp_path / "missing")]) == 0
    assert "no accuracy results" in capsys.readouterr().out


def _fake_golden(root: Path) -> Path:
    (root / "images").mkdir(parents=True)
    (root / "ocr").mkdir()
    data = (REPO_ROOT / rec.load_jobs(DEMO_LABELS)[0].image.relative_to(REPO_ROOT)).read_bytes()
    (root / "images/g0001.png").write_bytes(data)
    sha = hashlib.sha256(data).hexdigest()
    (root / "ocr" / f"{sha}.json").write_text("{}")
    (root / "labels.json").write_text(
        json.dumps({"schema": "1.0", "screens": [{"file": "images/g0001.png", "sha256": sha}]})
    )
    return root


def test_sync_plans_uploads_and_validates(tmp_path, capsys) -> None:
    src = _fake_golden(tmp_path / "golden")
    uploads = sync.plan(src)
    assert [u.remote for u in uploads][:2] == ["/golden/labels.json", "/golden/images/g0001.png"]
    assert sync.main(["--src", str(src), "--dry-run"]) == 0
    assert "3 files" in capsys.readouterr().out  # labels.json, the image, its OCR
    (src / "images/g0001.png").write_bytes(b"tampered")
    with pytest.raises(SystemExit, match="sha256"):
        sync.plan(src)


def test_sync_refuses_tracked_repo_paths() -> None:
    assert sync._inside_public_repo_and_tracked(EVAL_DIR / "demo")
    assert not sync._inside_public_repo_and_tracked(EVAL_DIR / "private/golden")
    assert not sync._inside_public_repo_and_tracked(Path("/tmp/elsewhere"))
