"""logging_setup.py: JSON lines, request context, and the scrubber (allowlisted keys, no secrets or free text)."""

from __future__ import annotations

import io
import json
import logging

import pytest

from ridemate_api.logging_setup import (
    ALLOWED_FIELDS,
    JsonFormatter,
    bind_request_context,
    configure_logging,
    request_context,
    reset_request_context,
    scrub_fields,
    scrub_text,
)

REQUEST_ID = "0b7f5c1e-3c1d-4b8a-9a43-2f1e8f0c9d11"
SESSION_ID = "6f1d2c3b-4a5e-4f60-8172-93a4b5c6d7e8"


def generated_pem(kind: str) -> tuple[str, str]:
    """A PEM private key generated when the tests are collected, so no key material is ever committed
    (tools/ci/check_no_secrets.sh): the log text that carries it, and a random run of its base64 body that the
    scrubber must remove."""
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric import ec, ed25519

    if kind == "pkcs8":
        key, fmt = ed25519.Ed25519PrivateKey.generate(), serialization.PrivateFormat.PKCS8
    else:  # the traditional OpenSSL "EC PRIVATE KEY" PEM format
        key, fmt = ec.generate_private_key(ec.SECP256R1()), serialization.PrivateFormat.TraditionalOpenSSL
    pem = key.private_bytes(serialization.Encoding.PEM, fmt, serialization.NoEncryption()).decode()
    body = pem.splitlines()[1]
    return f"loaded {pem} from the secret", body[-24:]


@pytest.fixture
def lines():
    buf = io.StringIO()
    configure_logging(stream=buf, env="main")
    yield lambda: [json.loads(line) for line in buf.getvalue().splitlines()]
    configure_logging()


def test_lines_are_json_with_the_request_fields(lines):
    log = logging.getLogger("ridemate.test")
    with request_context(
        session_id=SESSION_ID,
        request_id=REQUEST_ID,
        uid_hash="ab12cd34ef56ab78",
        app="uber",
        version_code=1234,
    ):
        log.info("read done", extra={"latency_ms": 2310, "outcome": "ok", "route": "/v1/read-screen"})
    (line,) = lines()
    assert line["msg"] == "read done"
    assert line["level"] == "info"
    assert line["logger"] == "ridemate.test"
    assert line["env"] == "main"
    for key, value in {
        "session_id": SESSION_ID,
        "request_id": REQUEST_ID,
        "uid_hash": "ab12cd34ef56ab78",
        "app": "uber",
        "version_code": 1234,
        "latency_ms": 2310,
        "outcome": "ok",
    }.items():
        assert line[key] == value
    assert "T" in line["ts"]


def test_context_is_scoped_to_the_request(lines):
    log = logging.getLogger("ridemate.test")
    token = bind_request_context(request_id=REQUEST_ID, session_id=None)
    log.info("inside")
    reset_request_context(token)
    log.info("outside")
    inside, outside = lines()
    assert inside["request_id"] == REQUEST_ID and "session_id" not in inside
    assert "request_id" not in outside


def test_unlisted_fields_and_free_text_values_are_dropped(lines):
    log = logging.getLogger("ridemate.test")
    log.info(
        "notification parsed",
        extra={
            "title": "Captain Ravi is arriving",  # free text, not allowlisted
            "authorization": "Bearer abc",
            "reason": "captain ravi is arriving",  # allowlisted key, free-text value
            "outcome": "eyJhbGciOiJSUzI1NiJ9AbCdEf0123456789AbCdEf0123456789AbCd",
            "alerts": ["http_5xx_rate", "two words"],
            "count": 3,
        },
    )
    (line,) = lines()
    assert "title" not in line and "authorization" not in line
    assert line["reason"] == "[dropped]"
    assert line["outcome"] == "[dropped]"
    assert line["alerts"] == ["http_5xx_rate"]
    assert line["count"] == 3


@pytest.mark.parametrize(
    ("text", "secret"),
    [
        ("Authorization: Bearer ya29.a0AfB_byC", "ya29"),
        ("got bearer eyJhbGci.eyJzdWIi.sig", "eyJhbGci"),
        ("token=abc123secret and more", "abc123secret"),
        ("proxy wk-AbC123xyz / ws-Zz9876secret", "AbC123xyz"),
        ("key AIzaSyA1234567890abcdefghijklmnopqrstu", "AIzaSy"),
        ("img data:image/png;base64,iVBORw0KGgoAAAANSUhEUg==", "iVBORw0K"),
        ("ping https://hc-ping.com/5f6e7d8c-uuid failed", "5f6e7d8c"),
        pytest.param(*generated_pem("pkcs8"), id="pem-pkcs8-private-key"),
        pytest.param(*generated_pem("traditional"), id="pem-ec-private-key"),
        ("blob QWxhZGRpbjpvcGVuIHNlc2FtZQ9Xyz0123456789AbCdEfGh", "QWxhZGRpbjpv"),
        ("hmac 5f1d3c0a9b8e7d6c5f1d3c0a9b8e7d6c5f1d3c0a9b8e7d6c5f1d3c0a9b8e7d6c", "5f1d3c0a9b8e"),
        ("user baharul@example.com wrote", "baharul@example.com"),
        ("call +91 98765 43210 or 9876543210", "98765"),
        ("api_key: sk_live_123", "sk_live_123"),
    ],
)
def test_messages_are_redacted(text, secret):
    assert secret not in scrub_text(text)


def test_harmless_text_survives_and_long_messages_are_truncated():
    assert scrub_text("cron job cleanup_firestore_and_hot_store_finished_in_time ok") == (
        "cron job cleanup_firestore_and_hot_store_finished_in_time ok"
    )
    assert scrub_text("latency 12345 ms for /v1/read-screen") == "latency 12345 ms for /v1/read-screen"
    long = scrub_text("x " * 500)
    assert len(long) <= 300 and long.endswith("…")


def test_exceptions_log_type_and_code_location_but_never_the_message(lines):
    log = logging.getLogger("ridemate.test")
    try:
        raise ValueError("OCR said ₹110 for Captain Ravi 9876543210")
    except ValueError:
        log.exception("parse failed")
    (line,) = lines()
    assert line["error_type"] == "ValueError"
    assert line["stack"] and all(":" in frame for frame in line["stack"])
    assert "Ravi" not in json.dumps(line) and "110" not in json.dumps(line)


def test_noisy_libraries_are_quieted_and_handlers_replaced(lines):
    configure_logging(stream=io.StringIO())
    assert len(logging.getLogger().handlers) == 1
    assert logging.getLogger("httpx").level == logging.WARNING  # INFO lines carry ping URLs
    assert logging.getLogger("uvicorn.access").level == logging.WARNING
    assert logging.getLogger("uvicorn").propagate


def test_scrub_fields_never_keeps_sensitive_key_names():
    assert "authorization" not in ALLOWED_FIELDS
    assert scrub_fields({"route": "/v1/event", "secret_value": "x", "status": 503}) == {
        "route": "/v1/event",
        "status": 503,
    }


def test_format_arguments_pass_the_same_check_as_fields(lines):
    log = logging.getLogger("ridemate.test")
    log.warning("parse failed for %s", "Captain Ravi is arriving at Ganeshguri, OTP 4821")
    log.warning(
        "model %s on %s: status %d in %.1f s", "meta-models/Muse-Spark-Open-816B-A42B", "modal", 503, 2.5
    )
    log.warning("token rejected: %s", ValueError("Token used too early, 1730000000 < 1729999990: eyJhbGci"))
    log.warning("mapping %(app)s %(text)s", {"app": "uber", "text": "drop at Fancy Bazar please"})
    log.error(ValueError("OCR text Captain Ravi"))
    free, ok, exc, mapping, bare = (line["msg"] for line in lines())
    assert free == "parse failed for [dropped]"
    assert ok == "model meta-models/Muse-Spark-Open-816B-A42B on modal: status 503 in 2.5 s"
    assert exc == "token rejected: ValueError"
    assert mapping == "mapping uber [dropped]"
    assert bare == "ValueError"


def test_emails_and_phone_numbers_are_dropped_from_field_values(lines):
    log = logging.getLogger("ridemate.test")
    log.info("x", extra={"reason": "someone@gmail.com", "outcome": "9876543210", "code": "ai_paused"})
    (line,) = lines()
    assert line["reason"] == line["outcome"] == "[dropped]"
    assert line["code"] == "ai_paused"
    assert "gmail" not in json.dumps(line) and "98765" not in json.dumps(line)


def test_a_bad_format_string_still_produces_a_line():
    record = logging.LogRecord(
        "ridemate.test", logging.INFO, __file__, 1, "value %d", ("not-a-number",), None
    )
    assert json.loads(JsonFormatter().format(record))["msg"] == "value %d"
