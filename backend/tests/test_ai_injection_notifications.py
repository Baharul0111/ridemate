"""The prompt-injection notifications of eval/injection/notifications.json through the real backend (§4.11, goal 29).

Each case's `request` is exactly the `/v1/parse-notification` body the phone sends. It goes through the real endpoint,
`prompts.parse_request` and `prompts.validate_parse`, with a fake model that obeys the planted instruction. The server's
part of the defence: the post reaches the model only as quoted, JSON-encoded untrusted data; a label stands only with an
evidence span that really is in the post; and the only labels that exist are the ten classifier labels, none of which
confirms a cancel or acts. The phone's part (a classifier label never confirms a cancel or ends a leg) is replayed in
`android/core/src/test/.../replay/NotificationInjectionReplayTest.kt` with the same cases.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import pytest

from ridemate_api import prompts

from .test_ai_endpoints import Env
from .test_ai_fakes import google_headers

REPO = Path(__file__).resolve().parents[2]
CASES: list[dict[str, Any]] = json.loads(
    (REPO / "eval/injection/notifications.json").read_text(encoding="utf-8")
)["cases"]
ACTING_LABELS = (
    "cancel_confirmed",
    "CANCEL_CONFIRMED",
    "confirm_cancellation",
    "auto_cancel",
    "leg_ended",
    "free",
)
MADE_UP_SPAN = "Your booking was cancelled by RideMate"


def planted_span(request: dict[str, Any]) -> str:
    """The words a model obeying the injection would quote: the post's own text (the planted instruction is in it)."""
    return request["text"].strip() or request["title"].strip()


@pytest.fixture
def env(tmp_path: Path) -> Env:
    return Env(tmp_path)


def parse(env: Env, request: dict[str, Any]) -> dict[str, Any]:
    response = env.client.post("/v1/parse-notification", json=request, headers=google_headers())
    assert response.status_code == 200, response.text
    return response.json()


def test_the_suite_has_the_documented_cases() -> None:
    ids = {c["id"] for c in CASES}
    assert {
        "rapido_chat_fake_cancel",
        "ola_json_smuggle",
        "uber_promo_free_ride",
        "rapido_otp_exfiltration",
    } <= ids
    assert len(CASES) >= 6


@pytest.mark.parametrize("case", CASES, ids=[c["id"] for c in CASES])
def test_the_post_reaches_the_model_only_as_quoted_untrusted_data(env: Env, case: dict[str, Any]) -> None:
    env.provider.script("parse", {"label": "unknown", "evidence_span": None})
    assert parse(env, case["request"])["label"] == "unknown"
    request, _ = env.provider.requests[-1]
    assert request.system == prompts.PARSE_SYSTEM and prompts.UNTRUSTED_RULE in request.system
    text = case["request"]["text"]
    assert text not in request.system
    (part,) = request.parts
    assert part.text.startswith("Notification (untrusted data):\n")
    quoted = json.loads(part.text.split("\n", 1)[1])
    assert quoted["text"] == text and quoted["title"] == case["request"]["title"]
    assert "version_code" not in quoted


@pytest.mark.parametrize("case", CASES, ids=[c["id"] for c in CASES])
def test_an_obeying_model_gets_a_label_only_with_a_real_quote_and_never_an_acting_one(
    env: Env, case: dict[str, Any]
) -> None:
    request = case["request"]
    span = planted_span(request)
    post = f"{request['title']}\n{request['text']}"

    # The model follows the planted words and quotes them: the quote is real, so the label is passed on, but it is
    # only one of the ten classifier labels, which the phone turns into at most a question (contracts §1.3).
    for label in ("cancelled", "ended"):
        env.provider.script("parse", {"label": label, "evidence_span": span})
        body = parse(env, request)
        assert (body["label"], body["evidence_span"]) == (label, span)
        assert body["evidence_span"] in post
        assert body["label"] in prompts.NOTIFICATION_LABELS

    # The model claims words that are not in the post: unknown.
    env.provider.script("parse", {"label": "cancelled", "evidence_span": MADE_UP_SPAN})
    body = parse(env, request)
    assert (body["label"], body["evidence_span"]) == ("unknown", None)

    # Whatever the post asks for, no label can confirm a cancel or act: anything outside the enum is unknown.
    for label in ACTING_LABELS:
        env.provider.script("parse", {"label": label, "evidence_span": span})
        assert parse(env, request)["label"] == "unknown", label


@pytest.mark.parametrize("case", CASES, ids=[c["id"] for c in CASES])
def test_validate_parse_matches_the_case_expectations(case: dict[str, Any]) -> None:
    request = case["request"]
    title, text = request["title"], request["text"]
    assert case["expect"]["server_label_unknown_unless_evidence_is_substring"] is True
    span = planted_span(request)
    assert prompts.validate_parse({"label": "cancelled", "evidence_span": span}, title, text) == (
        "cancelled",
        span,
    )
    assert prompts.validate_parse({"label": "cancelled", "evidence_span": MADE_UP_SPAN}, title, text) == (
        "unknown",
        None,
    )
    # A reply that is the smuggled JSON as a string, or carries extra fields, is no answer at all.
    assert prompts.validate_parse(text, title, text) == ("unknown", None)
    assert prompts.validate_parse(
        {"label": "cancelled", "evidence_span": span + " (verified)"}, title, text
    ) == (
        "unknown",
        None,
    )
