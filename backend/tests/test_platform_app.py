"""app.create_app + middleware: error envelope, body limits before reading, request ids, IP limit, optional routers."""

from __future__ import annotations

import sys
import types
from collections.abc import Iterator
from datetime import UTC, datetime

import pytest
from fastapi import APIRouter, FastAPI, Request
from fastapi.testclient import TestClient
from pydantic import BaseModel
from starlette.datastructures import UploadFile

from ridemate_api import app as app_module
from ridemate_api.app import create_app
from ridemate_api.clock import FakeClock, next_ist_midnight
from ridemate_api.interfaces import ApiError, FirestoreUnavailable
from ridemate_api.middleware import (
    MAX_MULTIPART_BYTES,
    BodyLimitMiddleware,
    header_uint,
    image_mime,
    normalize_id,
    read_image,
)
from ridemate_api.settings import MAX_IMAGE_BYTES, MAX_JSON_BYTES
from ridemate_api.store import StoreUnavailable
from ridemate_api.testing import JPEG_BYTES, PNG_BYTES, FakeMetrics, make_test_deps

RID = "0f8fad5b-d9cb-469f-a165-70867728950e"


class Body(BaseModel):
    text: str


def build(**deps_kw: object) -> tuple[FastAPI, TestClient, dict[str, int]]:
    deps = make_test_deps(**deps_kw)  # type: ignore[arg-type]
    app = create_app(deps)
    calls = {"json": 0, "upload": 0}

    @app.post("/v1/test/json")
    async def json_endpoint(body: Body) -> dict[str, int]:
        calls["json"] += 1
        return {"len": len(body.text)}

    @app.post("/v1/test/upload")
    async def upload_endpoint(request: Request) -> dict[str, int]:
        calls["upload"] += 1
        form = await request.form()
        upload = form["image"]
        assert isinstance(upload, UploadFile)
        return {"len": len(await read_image(upload))}

    @app.get("/v1/test/paused")
    async def paused() -> None:
        now = deps.clock.now_utc()
        raise ApiError(429, "ai_paused", resume_at=next_ist_midnight(now), reason="day_cap")

    @app.get("/v1/test/boom")
    async def boom() -> None:
        raise RuntimeError("secret OCR text ₹123 from the user's screen")

    @app.get("/v1/test/store-down")
    async def store_down() -> None:
        raise StoreUnavailable("x")

    @app.get("/v1/test/firestore-down")
    async def firestore_down() -> None:
        raise FirestoreUnavailable("x")

    return app, TestClient(app, raise_server_exceptions=False), calls


# ---- envelope --------------------------------------------------------------------------------------------


def test_api_error_renders_the_contract_envelope_with_ist_resume_time() -> None:
    clock = FakeClock(datetime(2026, 10, 28, 8, 0, tzinfo=UTC))  # 13:30 IST
    _, client, _ = build(clock=clock)
    r = client.get("/v1/test/paused", headers={"X-Request-Id": RID})
    assert r.status_code == 429
    err = r.json()["error"]
    assert err == {
        "code": "ai_paused",
        "resume_at_ist": "00:00",
        "retry_after_s": 10 * 3600 + 30 * 60,
        "reason": "day_cap",
        "request_id": RID,
    }
    assert r.headers["retry-after"] == str(10 * 3600 + 30 * 60)
    assert r.headers["x-request-id"] == RID


def test_unhandled_exception_is_a_500_envelope_without_its_text() -> None:
    _, client, _ = build()
    r = client.get("/v1/test/boom", headers={"X-Request-Id": RID})
    assert r.status_code == 500
    assert r.json() == {
        "error": {
            "code": "internal",
            "resume_at_ist": None,
            "retry_after_s": None,
            "reason": None,
            "request_id": RID,
        }
    }
    assert "secret" not in r.text and "₹" not in r.text


def test_unhandled_exception_is_counted_by_class_only() -> None:
    metrics = FakeMetrics()
    _, client, _ = build(metrics=metrics)
    client.get("/v1/test/boom")
    assert metrics.count("exceptions", type="RuntimeError") == 1
    assert metrics.count("http_requests", route="/v1/test/boom", status_class="5xx") == 1


def test_unknown_route_and_validation_errors_use_the_envelope_without_echoing_input() -> None:
    _, client, _ = build()
    r = client.get("/nope")
    assert r.status_code == 404 and r.json()["error"]["code"] == "not_found"
    r = client.post("/v1/test/json", json={"text": 5, "private": "my phone 9876543210"})
    assert r.status_code == 422 and r.json()["error"]["code"] == "invalid"
    assert "9876543210" not in r.text
    r = client.post("/v1/test/json", content=b"{not json", headers={"content-type": "application/json"})
    assert r.status_code == 422


def test_store_and_firestore_outages_map_to_503() -> None:
    _, client, _ = build()
    r = client.get("/v1/test/store-down")
    assert (r.status_code, r.json()["error"]["code"], r.json()["error"]["reason"]) == (
        503,
        "meter_unavailable",
        "meter",
    )
    r = client.get("/v1/test/firestore-down")
    assert (r.status_code, r.json()["error"]["code"]) == (503, "storage_unavailable")


def test_request_ids_are_echoed_or_generated() -> None:
    _, client, _ = build()
    assert client.get("/healthz", headers={"X-Request-Id": RID}).headers["x-request-id"] == RID
    generated = client.get("/healthz", headers={"X-Request-Id": "bad id; drop table"}).headers["x-request-id"]
    assert generated != "bad id; drop table" and normalize_id(generated) == generated


@pytest.mark.parametrize("version", ["1" * 5000, "\u00b2", "-3", "12abc", ""])
def test_malformed_app_version_header_never_breaks_a_request(version: str) -> None:
    _, client, _ = build()
    r = client.get("/healthz", headers={"X-App-Version": version.encode("latin-1")})
    assert r.status_code == 200 and r.json() == {"ok": True}


def test_header_uint_accepts_only_bounded_ascii_digits() -> None:
    assert header_uint("1234") == 1234 and header_uint(" 7 ") == 7
    for bad in (None, "", "-1", "\u00b2", "1" * 19, "1e3", "0x10"):
        assert header_uint(bad) is None


async def test_bogus_content_length_falls_back_to_the_streaming_cap() -> None:
    """A Content-Length the middleware can't parse (here 5000 digits, past int()'s limit) must not crash it;
    the streamed cap still applies."""
    chunks = [{"type": "http.request", "body": b"a" * 50_000, "more_body": True}] * 2
    sent: list[dict] = []

    async def receive() -> dict:
        return chunks.pop(0) if chunks else {"type": "http.request", "body": b"", "more_body": False}

    async def send(message: dict) -> None:
        sent.append(message)

    async def reading_app(scope: dict, receive_: object, send_: object) -> None:
        while True:
            message = await receive_()  # type: ignore[operator]
            if message["type"] != "http.request" or not message.get("more_body"):
                return

    scope = {
        "type": "http",
        "method": "POST",
        "path": "/v1/x",
        "headers": [(b"content-type", b"application/json"), (b"content-length", b"9" * 5000)],
        "state": {},
    }
    await BodyLimitMiddleware(reading_app)(scope, receive, send)
    assert sent[0]["type"] == "http.response.start" and sent[0]["status"] == 413


def test_a_broken_logging_module_never_breaks_requests(monkeypatch: pytest.MonkeyPatch) -> None:
    from ridemate_api import logging_setup

    def broken(**kwargs: object) -> object:
        raise NameError("half-edited module")

    monkeypatch.setattr(logging_setup, "bind_request_context", broken)
    _, client, _ = build()
    assert client.get("/healthz").status_code == 200


def test_request_metrics_use_the_route_template() -> None:
    metrics = FakeMetrics()
    _, client, _ = build(metrics=metrics)
    client.get("/healthz")
    client.get("/nope")
    assert metrics.count("http_requests", route="/healthz", status_class="2xx") == 1
    assert metrics.count("http_requests", route="unmatched", status_class="4xx") == 1


# ---- body limits -----------------------------------------------------------------------------------------


def test_json_body_over_64kb_is_refused_from_content_length_before_reading() -> None:
    _, client, calls = build()
    big = b'{"text": "' + b"a" * MAX_JSON_BYTES + b'"}'
    r = client.post("/v1/test/json", content=big, headers={"content-type": "application/json"})
    assert r.status_code == 413 and r.json()["error"]["code"] == "too_large"
    assert calls["json"] == 0


def test_streamed_body_without_content_length_is_cut_off_at_the_limit() -> None:
    _, client, calls = build()

    def chunks() -> Iterator[bytes]:
        yield b'{"text": "'
        for _ in range(20):
            yield b"a" * 8192
        yield b'"}'

    r = client.post("/v1/test/json", content=chunks(), headers={"content-type": "application/json"})
    assert r.status_code == 413 and r.json()["error"]["code"] == "too_large"
    assert calls["json"] == 0


def test_json_under_the_limit_passes() -> None:
    _, client, calls = build()
    r = client.post("/v1/test/json", json={"text": "a" * 60_000})
    assert r.status_code == 200 and calls["json"] == 1


def test_multipart_allows_a_1mb_image_plus_overhead() -> None:
    _, client, calls = build()
    image = PNG_BYTES + b"\x00" * (MAX_IMAGE_BYTES - len(PNG_BYTES))
    r = client.post("/v1/test/upload", files={"image": ("s.png", image, "image/png")}, data={"meta": "{}"})
    assert r.status_code == 200 and r.json() == {"len": MAX_IMAGE_BYTES}
    assert calls["upload"] == 1


def test_multipart_over_the_limit_is_refused() -> None:
    _, client, calls = build()
    image = PNG_BYTES + b"\x00" * MAX_MULTIPART_BYTES
    r = client.post("/v1/test/upload", files={"image": ("s.png", image, "image/png")})
    assert r.status_code == 413 and calls["upload"] == 0


def test_upload_with_bad_magic_bytes_is_415() -> None:
    _, client, _ = build()
    r = client.post("/v1/test/upload", files={"image": ("s.gif", b"GIF89a" + b"\x00" * 10, "image/gif")})
    assert r.status_code == 415 and r.json()["error"]["code"] == "bad_media"


async def test_read_image_checks_size_and_magic_bytes() -> None:
    assert await read_image(PNG_BYTES) == PNG_BYTES
    assert await read_image(JPEG_BYTES) == JPEG_BYTES
    assert image_mime(PNG_BYTES) == "image/png" and image_mime(JPEG_BYTES) == "image/jpeg"
    for bad, code in (
        (b"", "bad_media"),
        (b"\x89PNX", "bad_media"),
        (b"\xff\xd8", "bad_media"),
        (JPEG_BYTES + b"\x00" * MAX_IMAGE_BYTES, "too_large"),
    ):
        with pytest.raises(ApiError) as e:
            await read_image(bad)
        assert e.value.code == code
    with pytest.raises(ApiError) as e:
        await read_image(object())
    assert e.value.code == "invalid"


async def test_read_image_reads_at_most_the_limit_from_an_upload() -> None:
    class Upload:
        def __init__(self) -> None:
            self.asked: list[int] = []

        async def read(self, n: int) -> bytes:
            self.asked.append(n)
            return JPEG_BYTES[:n]

    upload = Upload()
    assert await read_image(upload) == JPEG_BYTES
    assert upload.asked == [MAX_IMAGE_BYTES + 1]


# ---- IP limit --------------------------------------------------------------------------------------------


def test_ip_limit_applies_to_every_route_health_checks_included() -> None:
    # An unauthenticated /healthz a minute keeps a container warm: compute is spent whether or not AI is.
    _, client, _ = build()
    headers = {"X-Forwarded-For": "1.2.3.4, 49.36.10.10"}
    for _ in range(60):
        assert client.get("/v1/config-check", headers=headers).status_code == 200
    r = client.get("/v1/config-check", headers=headers)
    assert r.status_code == 429 and r.json()["error"]["code"] == "rate_limited"
    assert r.json()["error"]["retry_after_s"] >= 1
    for path in ("/healthz", "/readyz"):
        limited = client.get(path, headers=headers)
        assert limited.status_code == 429 and limited.json()["error"]["code"] == "rate_limited"
    assert client.get("/v1/config-check", headers={"X-Forwarded-For": "49.36.10.11"}).status_code == 200
    other = {"X-Forwarded-For": "49.36.10.12"}
    for _ in range(60):
        assert client.get("/healthz", headers=other).status_code == 200
    assert client.get("/healthz", headers=other).status_code == 429


# ---- optional routers ------------------------------------------------------------------------------------


def test_app_serves_platform_routes_when_optional_modules_are_missing_or_broken(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    missing, broken = "ridemate_api.api._missing_for_test", "ridemate_api.api._broken_for_test"
    real_import = app_module.importlib.import_module

    def fake_import(name: str) -> types.ModuleType:
        if name == broken:
            raise SyntaxError("bad module")
        return real_import(name)

    monkeypatch.setattr(app_module, "OPTIONAL_ROUTERS", (missing, broken))
    monkeypatch.setattr(app_module.importlib, "import_module", fake_import)
    app = create_app(make_test_deps())
    assert app.state.optional_routes == {"_missing_for_test": False, "_broken_for_test": False}
    client = TestClient(app)
    assert client.get("/healthz").json() == {"ok": True}
    assert client.get("/v1/config-check").status_code == 200


def test_optional_router_and_mount_hooks_are_used(monkeypatch: pytest.MonkeyPatch) -> None:
    with_router = types.ModuleType("rm_test_with_router")
    with_router.router = APIRouter()  # type: ignore[attr-defined]

    @with_router.router.get("/v1/test/from-router")  # type: ignore[attr-defined]
    async def from_router() -> dict[str, bool]:
        return {"ok": True}

    with_mount = types.ModuleType("rm_test_with_mount")
    mounted: list[str] = []
    with_mount.mount = lambda app, settings: mounted.append(settings.ondc_mode)  # type: ignore[attr-defined]
    monkeypatch.setitem(sys.modules, "rm_test_with_router", with_router)
    monkeypatch.setitem(sys.modules, "rm_test_with_mount", with_mount)
    monkeypatch.setattr(app_module, "OPTIONAL_ROUTERS", ("rm_test_with_router", "rm_test_with_mount"))
    app = create_app(make_test_deps())
    assert app.state.optional_routes == {"rm_test_with_router": True, "rm_test_with_mount": True}
    assert mounted == ["off"]
    assert TestClient(app).get("/v1/test/from-router").json() == {"ok": True}


def test_a_module_whose_mount_fails_is_contained(monkeypatch: pytest.MonkeyPatch) -> None:
    failing = types.ModuleType("rm_test_failing_mount")

    def mount(app: FastAPI, settings: object) -> None:
        raise RuntimeError("bad route table")

    failing.mount = mount  # type: ignore[attr-defined]
    monkeypatch.setitem(sys.modules, "rm_test_failing_mount", failing)
    monkeypatch.setattr(app_module, "OPTIONAL_ROUTERS", ("rm_test_failing_mount",))
    app = create_app(make_test_deps())
    assert app.state.optional_routes == {"rm_test_failing_mount": False}
    client = TestClient(app)
    assert client.get("/healthz").json() == {"ok": True}
    assert client.get("/readyz").json()["checks"]["routes_rm_test_failing_mount"] is False


def test_real_ai_and_ondc_modules_mount_when_present() -> None:
    app = create_app(make_test_deps())
    assert app.state.optional_routes == {"ai": True, "ondc": True}
    client = TestClient(app)
    for method, path in (
        ("POST", "/v1/event"),
        ("POST", "/v1/erase"),
        ("GET", "/v1/config-check"),
        ("POST", "/v1/owner/fcm-token"),
        ("GET", "/healthz"),
        ("GET", "/readyz"),
    ):
        assert client.request(method, path).status_code != 404, path
    if app.state.optional_routes.get("ai"):
        assert client.post("/v1/read-screen").status_code != 404
    if app.state.optional_routes.get("ondc"):
        assert client.post("/v1/ondc/search").status_code != 404
