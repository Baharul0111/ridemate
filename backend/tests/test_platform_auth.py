"""auth.FirebaseAuth: Firebase ID tokens verified with Google's certs (no firebase-admin), revocation lookups."""

from __future__ import annotations

from datetime import timedelta
from typing import Any

import httpx
import pytest
from starlette.requests import Request

from ridemate_api.auth import FirebaseAuth, bearer_token
from ridemate_api.clock import FakeClock
from ridemate_api.crypto import HmacKeyring
from ridemate_api.interfaces import ApiError, Principal
from ridemate_api.store import MemoryStore
from ridemate_api.testing import (
    TEST_OWNER_EMAIL,
    FakeFirestore,
    FakeRemoteConfig,
    FirebaseTokenMinter,
    _rsa_key,
    judge_allowlist,
    make_settings,
)


def request_for(token: str | None, scheme: str = "Bearer") -> Request:
    headers = [(b"authorization", f"{scheme} {token}".encode())] if token is not None else []
    return Request(
        {"type": "http", "method": "GET", "path": "/v1/x", "headers": headers, "query_string": b""}
    )


class Env:
    def __init__(self, keys: tuple[str, ...] = ("current",), **settings: Any) -> None:
        self.clock = FakeClock()
        self.minter = FirebaseTokenMinter(clock=self.clock)
        self.rc = FakeRemoteConfig()
        self.firestore = FakeFirestore()
        self.store = MemoryStore()
        self.keyring = HmacKeyring(keys, store=self.store, clock=self.clock)
        self.auth = FirebaseAuth(
            settings=make_settings(**settings),
            keyring=self.keyring,
            clock=self.clock,
            remote_config=self.rc,
            firestore=self.firestore,
            http_client=self.minter.client(),
        )

    async def verify(self, token: str, *, require_google: bool = True) -> Principal:
        return await self.auth.verify(request_for(token), require_google=require_google)

    async def rejects(self, token: str, status: int = 401, code: str = "auth_invalid", **kw: Any) -> None:
        with pytest.raises(ApiError) as e:
            await self.verify(token, **kw)
        assert (e.value.status, e.value.code) == (status, code)


@pytest.fixture
def env() -> Env:
    return Env()


async def test_valid_google_token_yields_principal_keyed_on_google_sub(env: Env) -> None:
    p = await env.verify(
        env.minter.mint(uid="firebase-uid-1", google_sub="google-sub-42", email="Rider@Example.com")
    )
    assert p.uid == "firebase-uid-1" and p.provider == "google.com" and p.is_google
    assert p.sub_hmac == env.keyring.digest("google-sub-42", "sub")
    assert p.email == "rider@example.com" and p.email_verified
    assert not p.is_owner and not p.is_judge
    assert p.issued_at is not None and p.auth_time is not None
    assert p.raw_token


async def test_recreated_firebase_account_keeps_the_same_quota_key(env: Env) -> None:
    a = await env.verify(env.minter.mint(uid="uid-before-erase", google_sub="same-google-account"))
    b = await env.verify(env.minter.mint(uid="uid-after-erase", google_sub="same-google-account"))
    assert a.uid != b.uid and a.sub_hmac == b.sub_hmac


async def test_anonymous_token_allowed_only_without_require_google(env: Env) -> None:
    token = env.minter.mint(uid="anon-1", provider="anonymous", email=None)
    p = await env.verify(token, require_google=False)
    assert p.provider == "anonymous" and not p.is_google
    assert p.sub_hmac == env.keyring.digest("anon-1", "uid")
    await env.rejects(token, 403, "google_required", require_google=True)


async def test_missing_and_malformed_authorization(env: Env) -> None:
    with pytest.raises(ApiError) as e:
        await env.auth.verify(request_for(None), require_google=False)
    assert e.value.code == "auth_required"
    with pytest.raises(ApiError) as e:
        await env.auth.verify(request_for("abc", scheme="Basic"), require_google=False)
    assert e.value.code == "auth_invalid"
    for junk in ("abc", "a.b", "a.b.c", "x" * 5000):
        await env.rejects(junk)


@pytest.mark.parametrize(
    "change",
    [
        {"claims": {"aud": "other-project"}},
        {"claims": {"iss": "https://securetoken.google.com/other-project"}},
        {"claims": {"sub": ""}},
        {"claims": {"sub": "x" * 129}},
        {"claims": {"firebase": {"identities": {}}}},
        {"claims": {"exp": "soon"}},
        {"header": {"alg": "none"}},
        {"header": {"alg": "HS256"}},
        {"header": {"kid": "unknown-kid"}},
    ],
)
async def test_bad_claims_and_headers_are_rejected(env: Env, change: dict[str, Any]) -> None:
    await env.rejects(env.minter.mint(**change))


async def test_google_sign_in_without_google_identity_is_rejected(env: Env) -> None:
    token = env.minter.mint(claims={"firebase": {"sign_in_provider": "google.com", "identities": {}}})
    await env.rejects(token)


async def test_signature_from_another_key_is_rejected(env: Env) -> None:
    await env.rejects(env.minter.mint(key=_rsa_key(1)))


async def test_tampered_payload_is_rejected(env: Env) -> None:
    head, _, sig = env.minter.mint(uid="a").split(".")
    other = env.minter.mint(uid="b").split(".")[1]
    await env.rejects(f"{head}.{other}.{sig}")


async def test_time_claims(env: Env) -> None:
    now = env.clock.now_utc()
    await env.rejects(env.minter.mint(iat=now - timedelta(hours=2), exp=now - timedelta(minutes=5)))
    await env.rejects(env.minter.mint(iat=now + timedelta(minutes=10)))
    await env.rejects(env.minter.mint(auth_time=now + timedelta(minutes=10)))
    await env.verify(env.minter.mint(iat=now + timedelta(seconds=10)))  # small clock skew is tolerated


async def test_certs_are_cached_for_max_age_then_refetched(env: Env) -> None:
    env.minter.max_age_s = 600
    for _ in range(5):
        await env.verify(env.minter.mint())
    assert env.minter.cert_fetches == 1
    env.clock.advance(601)
    await env.verify(env.minter.mint())
    assert env.minter.cert_fetches == 2


async def test_unknown_kid_triggers_one_early_refetch_for_key_rotation(env: Env) -> None:
    await env.verify(env.minter.mint())
    rotated = _rsa_key(2)
    env.minter.certs["kid-2"] = env.minter.cert_pem(rotated)
    env.clock.advance(61)
    await env.verify(env.minter.mint(header={"kid": "kid-2"}, key=rotated))
    assert env.minter.cert_fetches == 2
    await env.rejects(env.minter.mint(header={"kid": "kid-3"}))  # unknown again: no refetch storm
    assert env.minter.cert_fetches == 2


async def test_cert_outage_uses_cached_certs_or_answers_unavailable(env: Env) -> None:
    await env.verify(env.minter.mint())
    env.minter.cert_failure = True
    env.clock.advance(7200)
    await env.verify(env.minter.mint())  # stale but known key
    fresh = Env()
    fresh.minter.cert_failure = True
    await fresh.rejects(fresh.minter.mint(), 503, "auth_unavailable")


async def test_owner_is_a_verified_email_in_owner_emails(env: Env) -> None:
    assert (await env.verify(env.minter.mint(email=TEST_OWNER_EMAIL.upper()))).is_owner
    assert not (await env.verify(env.minter.mint(email=TEST_OWNER_EMAIL, email_verified=False))).is_owner


async def test_judge_is_sha256_of_email_in_the_server_only_firestore_allowlist(env: Env) -> None:
    env.firestore.set("server_config/judge_allowlist", judge_allowlist("Judge@Hackathon.dev"))
    assert (await env.verify(env.minter.mint(email="judge@hackathon.dev"))).is_judge
    assert not (await env.verify(env.minter.mint(email="someone@else.dev"))).is_judge
    assert not (await env.verify(env.minter.mint(email="judge@hackathon.dev", email_verified=False))).is_judge


async def test_remote_config_never_makes_a_judge(env: Env) -> None:
    """The client template is readable by every install: a judge list there must have no effect."""
    env.rc.set("judge_allowlist", judge_allowlist("judge@hackathon.dev"))
    assert not (await env.verify(env.minter.mint(email="judge@hackathon.dev"))).is_judge


async def test_judge_allowlist_is_cached_for_60_seconds_and_survives_a_firestore_outage(env: Env) -> None:
    assert not (await env.verify(env.minter.mint(email="judge@hackathon.dev"))).is_judge
    env.firestore.set("server_config/judge_allowlist", judge_allowlist("judge@hackathon.dev"))
    assert not (await env.verify(env.minter.mint(email="judge@hackathon.dev"))).is_judge  # cached empty list
    env.clock.advance(61)
    assert (await env.verify(env.minter.mint(email="judge@hackathon.dev"))).is_judge
    env.firestore.fail = True
    env.clock.advance(61)
    assert (await env.verify(env.minter.mint(email="judge@hackathon.dev"))).is_judge  # last good list kept


async def test_blocked_uids_are_refused_and_cached_for_60_seconds(env: Env) -> None:
    token = env.minter.mint(uid="bad-actor")
    await env.verify(token)
    env.firestore.set("blocked_uids/list", {"uids": ["bad-actor"]})
    await env.verify(env.minter.mint(uid="bad-actor"))  # cached list from the first call
    env.clock.advance(61)
    await env.rejects(env.minter.mint(uid="bad-actor"), 403, "blocked")
    await env.verify(env.minter.mint(uid="someone-else", google_sub="other"))


async def test_blocking_by_hmac_survives_account_recreation(env: Env) -> None:
    p = await env.verify(env.minter.mint(uid="v1", google_sub="abuser"))
    env.firestore.set("blocked_uids/list", {"uids": [p.sub_hmac]})
    env.clock.advance(61)
    await env.rejects(env.minter.mint(uid="v2-recreated", google_sub="abuser"), 403, "blocked")


async def test_hmac_block_survives_a_key_rotation() -> None:
    """An abuser blocked by HMAC(sub) under the old key stays blocked during the 30-day dual read (H5)."""
    blocked_under_old_key = HmacKeyring(["old"]).digest("abuser", "sub")
    env = Env(keys=("new", "old"))
    env.firestore.set("blocked_uids/list", {"uids": [blocked_under_old_key]})
    await env.rejects(env.minter.mint(uid="v3", google_sub="abuser"), 403, "blocked")
    await env.verify(env.minter.mint(uid="fine", google_sub="someone-else"))


async def test_blocked_list_unreadable_keeps_serving(env: Env) -> None:
    env.firestore.fail = True
    await env.verify(env.minter.mint())


async def test_principal_is_cached_on_the_request() -> None:
    env = Env()
    req = request_for(env.minter.mint())
    first = await env.auth.verify(req, require_google=True)
    assert await env.auth.verify(req, require_google=False) is first
    assert env.minter.cert_fetches == 1


async def test_bearer_token_helper() -> None:
    assert bearer_token(request_for("tok")) == "tok"
    with pytest.raises(ApiError):
        bearer_token(request_for(None))


# ---- revocation ------------------------------------------------------------------------------------------


async def test_check_not_revoked_passes_for_live_account_and_sends_the_token(env: Env) -> None:
    token = env.minter.mint(uid="live")
    p = await env.verify(token)
    await env.auth.check_not_revoked(p)
    assert env.minter.lookups[-1] == {"body": {"idToken": token}, "api_key": "test-web-api-key"}


@pytest.mark.parametrize(
    "message", ["USER_DISABLED", "TOKEN_EXPIRED", "USER_NOT_FOUND", "INVALID_ID_TOKEN : x"]
)
async def test_revoked_or_disabled_accounts_are_401(env: Env, message: str) -> None:
    p = await env.verify(env.minter.mint())
    env.minter.lookup_handler = lambda body, req: httpx.Response(400, json={"error": {"message": message}})
    with pytest.raises(ApiError) as e:
        await env.auth.check_not_revoked(p)
    assert (e.value.status, e.value.code) == (401, "auth_invalid")


async def test_tokens_issued_before_valid_since_are_revoked(env: Env) -> None:
    p = await env.verify(env.minter.mint(uid="u"))
    assert p.issued_at is not None
    later = int(p.issued_at.timestamp()) + 5
    env.minter.lookup_handler = lambda body, req: httpx.Response(
        200, json={"users": [{"localId": "u", "validSince": str(later)}]}
    )
    with pytest.raises(ApiError) as e:
        await env.auth.check_not_revoked(p)
    assert e.value.code == "auth_invalid"


async def test_disabled_flag_or_other_user_is_revoked(env: Env) -> None:
    p = await env.verify(env.minter.mint(uid="u"))
    for user in ({"localId": "u", "disabled": True}, {"localId": "someone-else"}):
        env.minter.lookup_handler = lambda body, req, user=user: httpx.Response(200, json={"users": [user]})
        with pytest.raises(ApiError):
            await env.auth.check_not_revoked(p)


async def test_lookup_outage_or_missing_key_is_unavailable(env: Env) -> None:
    p = await env.verify(env.minter.mint())
    env.minter.lookup_handler = lambda body, req: httpx.Response(500)
    with pytest.raises(ApiError) as e:
        await env.auth.check_not_revoked(p)
    assert (e.value.status, e.value.code) == (503, "auth_unavailable")

    def boom(body: Any, req: httpx.Request) -> httpx.Response:
        raise httpx.ConnectTimeout("slow", request=req)

    env.minter.lookup_handler = boom
    with pytest.raises(ApiError) as e:
        await env.auth.check_not_revoked(p)
    assert e.value.code == "auth_unavailable"
    no_key = Env(firebase_web_api_key="")
    with pytest.raises(ApiError) as e:
        await no_key.auth.check_not_revoked(p)
    assert e.value.code == "auth_unavailable"


async def test_every_network_call_has_a_3_second_deadline(env: Env) -> None:
    p = await env.verify(env.minter.mint())
    await env.auth.check_not_revoked(p)
    assert len(env.minter.timeouts) == 2  # the certificate fetch and the revocation lookup
    for timeout in env.minter.timeouts:
        assert set(timeout.values()) == {3.0}


async def test_project_id_must_be_configured() -> None:
    env = Env(firebase_project_id="")
    await env.rejects(env.minter.mint())


async def test_previous_hmac_is_attached_during_rotation() -> None:
    env = Env(keys=("new", "old"))
    p = await env.verify(env.minter.mint(google_sub="g"))
    assert p.sub_hmac_previous == HmacKeyring(["old"]).digest("g", "sub")


def _auth_sharing(env: Env, *, firestore: Any) -> FirebaseAuth:
    """Another container: same hot store and keys, its own (possibly missing or failing) Firestore."""
    return FirebaseAuth(
        settings=make_settings(),
        keyring=env.keyring,
        clock=env.clock,
        remote_config=env.rc,
        firestore=firestore,
        http_client=env.minter.client(),
        store=env.store,
    )


async def test_a_cold_start_with_firestore_down_still_enforces_the_last_blocklist() -> None:
    env = Env()
    warm = _auth_sharing(env, firestore=env.firestore)
    env.firestore.set("blocked_uids/list", {"uids": ["bad-actor"]})
    with pytest.raises(ApiError):
        await warm.verify(request_for(env.minter.mint(uid="bad-actor")), require_google=True)
    # a new container starts while Firestore is unreadable, or with its Firestore key missing
    down = FakeFirestore()
    down.fail = True
    for firestore in (down, None):
        cold = _auth_sharing(env, firestore=firestore)
        with pytest.raises(ApiError) as e:
            await cold.verify(request_for(env.minter.mint(uid="bad-actor")), require_google=True)
        assert (e.value.status, e.value.code) == (403, "blocked")
        await cold.verify(request_for(env.minter.mint(uid="fine", google_sub="fine")), require_google=True)


async def test_the_blocklist_copy_follows_firestore_and_is_written_only_when_it_changes() -> None:
    env = Env()
    auth = _auth_sharing(env, firestore=env.firestore)
    env.firestore.set("blocked_uids/list", {"uids": ["a"]})
    await auth.verify(request_for(env.minter.mint(uid="ok")), require_google=True)
    first = env.store.get("auth:blocked_uids")
    assert first["uids"] == ["a"]
    env.clock.advance(61)
    await auth.verify(request_for(env.minter.mint(uid="ok")), require_google=True)
    assert env.store.get("auth:blocked_uids") == first  # unchanged list: no write
    env.firestore.set("blocked_uids/list", {"uids": []})  # unblocked
    env.clock.advance(61)
    await auth.verify(request_for(env.minter.mint(uid="ok")), require_google=True)
    assert env.store.get("auth:blocked_uids")["uids"] == []
    cold = _auth_sharing(env, firestore=None)
    await cold.verify(request_for(env.minter.mint(uid="a")), require_google=True)  # the copy is current
