"""Firebase ID token verification without firebase-admin (architecture §4.2 "Auth middleware").

Tokens are RS256 JWTs signed by Google's `securetoken` service. The public certificates are fetched from Google
and cached for the Cache-Control max-age; a token whose key id isn't cached triggers one early refetch (key
rotation). Claims are checked as Firebase specifies: aud = project id, iss = securetoken URL, exp / iat /
auth_time sane, a non-empty sub.

Quotas are keyed on HMAC of the Google account's own `sub` (firebase.identities["google.com"][0]), not on the
Firebase uid, so erasing and recreating the Firebase account doesn't reset them. Owner = a verified email in
OWNER_EMAILS; judge = SHA-256 of the lower-cased verified email in Remote Config `judge_allowlist`.

`check_not_revoked` asks Identity Toolkit `accounts:lookup` with the token itself and the web API key, so the
server needs no Auth-admin role. Every network call has a 3 s deadline; blocking calls run off the event loop.

The blocklist (`blocked_uids/list`) is re-read from Firestore every minute. The last list read is also kept in the
hot store, so a container that starts while Firestore is unreadable (or a deployment whose Firestore key is
missing, which /readyz reports) still enforces it instead of starting with nobody blocked.
"""

from __future__ import annotations

import asyncio
import base64
import hashlib
import json
import logging
import re
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Any

import httpx
from cryptography import x509
from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import padding, rsa
from fastapi import Request

from .clock import Clock
from .crypto import HmacKeyring
from .deps import get_deps
from .interfaces import ApiError, Firestore, Principal, RemoteConfig
from .settings import Settings
from .store import HotStore, StoreUnavailable

log = logging.getLogger(__name__)

CERTS_URL = "https://www.googleapis.com/robot/v1/metadata/x509/securetoken@system.gserviceaccount.com"
LOOKUP_URL = "https://identitytoolkit.googleapis.com/v1/accounts:lookup"
ISSUER_PREFIX = "https://securetoken.google.com/"
NETWORK_TIMEOUT_S = 3.0
CLOCK_SKEW_S = 30
MAX_TOKEN_CHARS = 4096
DEFAULT_CERT_MAX_AGE_S = 3600
MIN_CERT_MAX_AGE_S = 60
MAX_CERT_MAX_AGE_S = 24 * 3600
UNKNOWN_KID_REFETCH_S = 60.0
BLOCKED_TTL_S = 60.0
BLOCKED_DOC = "blocked_uids/list"
BLOCKED_CACHE_KEY = "auth:blocked_uids"  # the last list read from Firestore: {"uids": [...], "at": iso}
GOOGLE = "google.com"
REVOKED_MESSAGES = ("USER_DISABLED", "TOKEN_EXPIRED", "USER_NOT_FOUND", "INVALID_ID_TOKEN")
_MAX_AGE = re.compile(r"max-age=(\d+)")


class TokenInvalid(Exception):
    """A token that fails verification. The message is a short reason for logs, never token content."""


def _unavailable() -> ApiError:
    return ApiError(503, "auth_unavailable")


def _b64url(segment: str) -> bytes:
    return base64.urlsafe_b64decode(segment + "=" * (-len(segment) % 4))


def _cert_max_age(cache_control: str) -> float:
    match = _MAX_AGE.search(cache_control or "")
    age = int(match.group(1)) if match else DEFAULT_CERT_MAX_AGE_S
    return float(min(max(age, MIN_CERT_MAX_AGE_S), MAX_CERT_MAX_AGE_S))


def _number(claims: dict[str, Any], name: str) -> float:
    value = claims.get(name)
    if isinstance(value, bool) or not isinstance(value, int | float):
        raise TokenInvalid(f"{name} missing")
    return float(value)


def verify_firebase_token(
    token: str, keys: dict[str, rsa.RSAPublicKey], *, project_id: str, now: datetime
) -> dict[str, Any]:
    """Verify signature and claims of a Firebase ID token against `keys` (kid → public key)."""
    if not project_id:
        raise TokenInvalid("project id not configured")
    if len(token) > MAX_TOKEN_CHARS or token.count(".") != 2:
        raise TokenInvalid("malformed")
    head_b64, body_b64, sig_b64 = token.split(".")
    try:
        header = json.loads(_b64url(head_b64))
        claims = json.loads(_b64url(body_b64))
        signature = _b64url(sig_b64)
    except (ValueError, TypeError):
        raise TokenInvalid("malformed") from None
    if not isinstance(header, dict) or not isinstance(claims, dict):
        raise TokenInvalid("malformed")
    if header.get("alg") != "RS256":
        raise TokenInvalid("alg")
    key = keys.get(str(header.get("kid", "")))
    if key is None:
        raise TokenInvalid("kid")
    try:
        key.verify(signature, f"{head_b64}.{body_b64}".encode(), padding.PKCS1v15(), hashes.SHA256())
    except InvalidSignature:
        raise TokenInvalid("signature") from None

    if claims.get("aud") != project_id:
        raise TokenInvalid("aud")
    if claims.get("iss") != ISSUER_PREFIX + project_id:
        raise TokenInvalid("iss")
    sub = claims.get("sub")
    if not isinstance(sub, str) or not sub or len(sub) > 128:
        raise TokenInvalid("sub")
    t = now.timestamp()
    if _number(claims, "exp") <= t - CLOCK_SKEW_S:
        raise TokenInvalid("expired")
    if _number(claims, "iat") > t + CLOCK_SKEW_S:
        raise TokenInvalid("iat in future")
    if _number(claims, "auth_time") > t + CLOCK_SKEW_S:
        raise TokenInvalid("auth_time in future")
    firebase = claims.get("firebase")
    if not isinstance(firebase, dict) or not isinstance(firebase.get("sign_in_provider"), str):
        raise TokenInvalid("provider")
    return claims


def google_sub(claims: dict[str, Any]) -> str:
    """The Google account id behind a google.com sign-in (stable across Firebase account re-creation)."""
    identities = claims.get("firebase", {}).get("identities", {})
    subs = identities.get(GOOGLE) if isinstance(identities, dict) else None
    if not isinstance(subs, list) or not subs or not isinstance(subs[0], str) or not subs[0]:
        raise TokenInvalid("google identity missing")
    return subs[0]


def email_sha256(email: str) -> str:
    return hashlib.sha256(email.strip().lower().encode()).hexdigest()


class GoogleCerts:
    """Google's securetoken signing certificates, cached for their Cache-Control max-age."""

    def __init__(self, client: httpx.AsyncClient, clock: Clock, url: str = CERTS_URL) -> None:
        self._client = client
        self._clock = clock
        self._url = url
        self._keys: dict[str, rsa.RSAPublicKey] = {}
        self._expires_at = float("-inf")
        self._fetched_at = float("-inf")
        self._lock: asyncio.Lock | None = None
        self._lock_loop: asyncio.AbstractEventLoop | None = None

    async def keys_for(self, kid: str) -> dict[str, rsa.RSAPublicKey]:
        now = self._clock.monotonic()
        fresh = now < self._expires_at
        if fresh and (kid in self._keys or now - self._fetched_at < UNKNOWN_KID_REFETCH_S):
            return self._keys
        async with self._get_lock():
            now = self._clock.monotonic()
            if now < self._expires_at and (
                kid in self._keys or now - self._fetched_at < UNKNOWN_KID_REFETCH_S
            ):
                return self._keys
            try:
                await self._fetch()
            except (httpx.HTTPError, ValueError) as e:
                if self._keys and kid in self._keys:
                    log.warning("auth: cert refresh failed (%s); using cached certs", type(e).__name__)
                    return self._keys
                log.error("auth: cert fetch failed: %s", type(e).__name__)
                raise _unavailable() from None
        return self._keys

    def _get_lock(self) -> asyncio.Lock:
        loop = asyncio.get_running_loop()
        if self._lock is None or self._lock_loop is not loop:
            self._lock, self._lock_loop = asyncio.Lock(), loop
        return self._lock

    async def _fetch(self) -> None:
        response = await self._client.get(self._url, timeout=NETWORK_TIMEOUT_S)
        response.raise_for_status()
        payload = response.json()
        if not isinstance(payload, dict) or not payload:
            raise ValueError("certificate payload is empty")
        keys: dict[str, rsa.RSAPublicKey] = {}
        for kid, pem in payload.items():
            key = x509.load_pem_x509_certificate(str(pem).encode()).public_key()
            if isinstance(key, rsa.RSAPublicKey):
                keys[str(kid)] = key
        if not keys:
            raise ValueError("no RSA certificates")
        now = self._clock.monotonic()
        self._keys = keys
        self._fetched_at = now
        self._expires_at = now + _cert_max_age(response.headers.get("cache-control", ""))


@dataclass
class _Blocked:
    ids: frozenset[str] = frozenset()
    loaded_at: float = float("-inf")


class FirebaseAuth:
    def __init__(
        self,
        *,
        settings: Settings,
        keyring: HmacKeyring,
        clock: Clock,
        remote_config: RemoteConfig,
        firestore: Firestore | None,
        http_client: httpx.AsyncClient | None = None,
        store: HotStore | None = None,
    ) -> None:
        self._settings = settings
        self._keyring = keyring
        self._clock = clock
        self._rc = remote_config
        self._firestore = firestore
        self._store = store
        self._client = http_client or httpx.AsyncClient(timeout=NETWORK_TIMEOUT_S)
        self._certs = GoogleCerts(self._client, clock)
        self._blocked = _Blocked()
        self._owner_emails = frozenset(e.strip().lower() for e in settings.owner_emails if e.strip())

    # ---- interfaces.Auth -----------------------------------------------------------------------------------

    async def verify(self, request: Request, *, require_google: bool) -> Principal:
        principal = getattr(request.state, "principal", None)
        if not isinstance(principal, Principal):
            principal = await self.verify_token(bearer_token(request))
            request.state.principal = principal
        if require_google and not principal.is_google:
            raise ApiError(403, "google_required")
        return principal

    async def check_not_revoked(self, principal: Principal) -> None:
        if not self._settings.firebase_web_api_key:
            log.error("auth: FIREBASE_WEB_API_KEY not configured; cannot check revocation")
            raise _unavailable()
        try:
            response = await self._client.post(
                LOOKUP_URL,
                json={"idToken": principal.raw_token},
                headers={"X-Goog-Api-Key": self._settings.firebase_web_api_key},
                timeout=NETWORK_TIMEOUT_S,
            )
        except httpx.HTTPError as e:
            log.warning("auth: revocation lookup failed: %s", type(e).__name__)
            raise _unavailable() from None
        try:
            body = response.json()
        except ValueError:
            body = {}
        if response.status_code != 200:
            message = str((body.get("error") or {}).get("message", "")) if isinstance(body, dict) else ""
            if any(message.startswith(m) for m in REVOKED_MESSAGES):
                raise ApiError(401, "auth_invalid")
            log.warning("auth: revocation lookup answered HTTP %d", response.status_code)
            raise _unavailable()
        users = body.get("users") if isinstance(body, dict) else None
        user = users[0] if isinstance(users, list) and users and isinstance(users[0], dict) else None
        if user is None or user.get("localId") != principal.uid or user.get("disabled"):
            raise ApiError(401, "auth_invalid")
        try:
            valid_since = int(user.get("validSince", 0))
        except (TypeError, ValueError):
            valid_since = 0
        issued = principal.issued_at.timestamp() if principal.issued_at else 0
        if valid_since > issued:
            raise ApiError(401, "auth_invalid")

    # ---- verification --------------------------------------------------------------------------------------

    async def verify_token(self, token: str) -> Principal:
        try:
            kid = _peek_kid(token)
            keys = await self._certs.keys_for(kid)
            claims = verify_firebase_token(
                token, keys, project_id=self._settings.firebase_project_id, now=self._clock.now_utc()
            )
            principal = await self._principal(claims, token)
        except TokenInvalid as e:
            log.info("auth: token rejected: %s", e)
            raise ApiError(401, "auth_invalid") from None
        if await self._is_blocked(principal):
            raise ApiError(403, "blocked")
        return principal

    async def _principal(self, claims: dict[str, Any], token: str) -> Principal:
        provider = str(claims["firebase"]["sign_in_provider"])
        uid = str(claims["sub"])
        if provider == GOOGLE:
            sub, purpose = google_sub(claims), "sub"
        else:
            sub, purpose = uid, "uid"
        email = claims.get("email")
        email = email.strip().lower() if isinstance(email, str) and email.strip() else None
        verified = bool(claims.get("email_verified")) and email is not None
        # The previous key's grace window is recorded in the hot store and the judge list is Remote Config:
        # both may block, so they are looked up off the event loop.
        sub_hmac_previous, is_judge = await asyncio.to_thread(
            self._lookups, sub, purpose, email if verified else None
        )
        return Principal(
            uid=uid,
            provider=provider,
            sub_hmac=self._keyring.digest(sub, purpose),
            sub_hmac_previous=sub_hmac_previous,
            email=email,
            email_verified=verified,
            auth_time=datetime.fromtimestamp(float(claims["auth_time"]), UTC),
            issued_at=datetime.fromtimestamp(float(claims["iat"]), UTC),
            is_owner=verified and email in self._owner_emails,
            is_judge=is_judge,
            raw_token=token,
        )

    def _lookups(self, sub: str, purpose: str, verified_email: str | None) -> tuple[str | None, bool]:
        previous = self._keyring.previous_digest(sub, purpose)
        return previous, verified_email is not None and self._is_judge(verified_email)

    def _is_judge(self, email: str) -> bool:
        allowlist = self._rc.get_json("judge_allowlist", {})
        hashes = allowlist.get("sha256_emails", []) if isinstance(allowlist, dict) else []
        return isinstance(hashes, list) and email_sha256(email) in {str(h).lower() for h in hashes}

    async def _is_blocked(self, principal: Principal) -> bool:
        if self._firestore is None and self._store is None:
            return False
        now = self._clock.monotonic()
        if now - self._blocked.loaded_at >= BLOCKED_TTL_S:
            ids = await asyncio.to_thread(self._load_blocked)
            self._blocked = _Blocked(ids, now)
        # Blocking by HMAC(sub) as well as by uid survives the user recreating the Firebase account, and the
        # previous key's HMAC keeps matching through a key rotation (dual read, §4.18 H5).
        ids = self._blocked.ids
        return any(i in ids for i in (principal.uid, principal.sub_hmac, principal.sub_hmac_previous) if i)

    def _load_blocked(self) -> frozenset[str]:
        """Firestore's list (copied to the hot store when it changed), else the last list any container read,
        else the list this container already had."""
        if self._firestore is not None:
            try:
                doc = self._firestore.get(BLOCKED_DOC)
            except Exception as e:  # noqa: BLE001 - Firestore down: keep the last list, don't fail everyone
                log.warning("auth: blocked_uids unreadable: %s", type(e).__name__)
            else:
                raw = doc.get("uids", []) if isinstance(doc, dict) else []
                ids = frozenset(str(u) for u in raw) if isinstance(raw, list) else frozenset()
                self._remember_blocked(ids)
                return ids
        cached = self._cached_blocked()
        return cached if cached is not None else self._blocked.ids

    def _cached_blocked(self) -> frozenset[str] | None:
        if self._store is None:
            return None
        try:
            value = self._store.get(BLOCKED_CACHE_KEY)
        except StoreUnavailable:
            return None
        raw = value.get("uids") if isinstance(value, dict) else None
        return frozenset(str(u) for u in raw) if isinstance(raw, list) else None

    def _remember_blocked(self, ids: frozenset[str]) -> None:
        if self._store is None:
            return
        try:
            if self._cached_blocked() != ids:
                self._store.put(
                    BLOCKED_CACHE_KEY, {"uids": sorted(ids), "at": self._clock.now_utc().isoformat()}
                )
        except StoreUnavailable:
            log.warning("auth: blocklist copy not kept: store unavailable")


def _peek_kid(token: str) -> str:
    if len(token) > MAX_TOKEN_CHARS or token.count(".") != 2:
        raise TokenInvalid("malformed")
    try:
        header = json.loads(_b64url(token.split(".", 1)[0]))
    except (ValueError, TypeError):
        raise TokenInvalid("malformed") from None
    if not isinstance(header, dict) or not isinstance(header.get("kid"), str):
        raise TokenInvalid("kid")
    return header["kid"]


def bearer_token(request: Request) -> str:
    raw = request.headers.get("authorization")
    if not raw:
        raise ApiError(401, "auth_required")
    scheme, _, token = raw.partition(" ")
    if scheme.lower() != "bearer" or not token.strip():
        raise ApiError(401, "auth_invalid")
    return token.strip()


# ---- FastAPI dependencies ----------------------------------------------------------------------------------


async def google_principal(request: Request) -> Principal:
    """A Google-signed-in caller (AI endpoints, event writes, erase)."""
    return await get_deps(request).auth.verify(request, require_google=True)


async def any_principal(request: Request) -> Principal:
    """Any signed-in caller, anonymous included."""
    return await get_deps(request).auth.verify(request, require_google=False)


async def optional_principal(request: Request) -> Principal | None:
    """None when no Authorization header was sent; a bad token is still an error."""
    if not request.headers.get("authorization"):
        return None
    return await any_principal(request)
