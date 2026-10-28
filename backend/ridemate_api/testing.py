"""Shared test doubles. Never imported by production code.

- `FakeAuth` issues opaque tokens for Principals it builds (HMACs from the real keyring, so quota keys match).
- `FakeFirestore`, `FakeRemoteConfig`, `FakeMetrics`, `FakeRouter`: in-memory versions of the interfaces.
- `make_test_deps()` builds a Deps with MemoryStore + FakeClock + the REAL limiter, quota manager, spend meter and
  (with Firestore) audit trail.
- `FirebaseTokenMinter` signs real RS256 Firebase-shaped tokens and serves its certificate (and an optional
  Identity Toolkit lookup) through an httpx.MockTransport, for testing auth.FirebaseAuth without the network.
"""

from __future__ import annotations

import base64
import copy
import functools
import hashlib
import itertools
import json
from collections import Counter
from collections.abc import Callable
from datetime import UTC, datetime, timedelta
from typing import Any

import httpx
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa
from cryptography.x509.oid import NameOID
from fastapi import Request

from .audit import AuditTrail
from .clock import FakeClock
from .crypto import HmacKeyring
from .deps import Deps
from .interfaces import ApiError, FirestoreQuotaExhausted, FirestoreUnavailable, Principal
from .limits import IpLimiter, QuotaManager
from .settings import Settings
from .spend import SpendMeter
from .store import MemoryStore

TEST_PROJECT = "ridemate-test"
TEST_HMAC_KEY = "test-hmac-key-current"
TEST_OWNER_EMAIL = "owner@example.com"
# Smallest valid-looking images: the magic bytes are what the server checks.
PNG_BYTES = b"\x89PNG\r\n\x1a\n" + b"\x00" * 64
JPEG_BYTES = b"\xff\xd8\xff\xe0" + b"\x00" * 64

_UNSET: Any = object()


def make_settings(**overrides: Any) -> Settings:
    values: dict[str, Any] = {
        "firebase_project_id": TEST_PROJECT,
        "firebase_web_api_key": "test-web-api-key",
        "hmac_keys": (TEST_HMAC_KEY,),
        "owner_emails": (TEST_OWNER_EMAIL,),
    }
    values.update(overrides)
    return Settings(**values)


def bearer(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


# ---- auth --------------------------------------------------------------------------------------------------


class FakeAuth:
    """`interfaces.Auth` over tokens it issued itself."""

    def __init__(self, keyring: HmacKeyring | None = None) -> None:
        self._keyring = keyring or HmacKeyring([TEST_HMAC_KEY])
        self._tokens: dict[str, Principal] = {}
        self._seq = itertools.count(1)
        self.blocked: set[str] = set()  # uids
        self.revoked: set[str] = set()  # uids
        self.revocation_checks: list[str] = []

    def issue(self, principal: Principal) -> str:
        token = f"fake-token-{next(self._seq)}"
        self._tokens[token] = principal
        return token

    def google(
        self,
        uid: str = "user-1",
        *,
        google_sub: str | None = None,
        email: str | None = None,
        owner: bool = False,
        judge: bool = False,
    ) -> str:
        sub = google_sub or f"g-{uid}"
        email = email or (TEST_OWNER_EMAIL if owner else f"{uid}@example.com")
        return self.issue(
            Principal(
                uid=uid,
                provider="google.com",
                sub_hmac=self._keyring.digest(sub, "sub"),
                sub_hmac_previous=self._keyring.previous_digest(sub, "sub"),
                email=email,
                email_verified=True,
                is_owner=owner,
                is_judge=judge,
            )
        )

    def anonymous(self, uid: str = "anon-1") -> str:
        return self.issue(Principal(uid=uid, provider="anonymous", sub_hmac=self._keyring.digest(uid, "uid")))

    def principal(self, token: str) -> Principal:
        return self._tokens[token]

    async def verify(self, request: Request, *, require_google: bool) -> Principal:
        raw = request.headers.get("authorization")
        if not raw:
            raise ApiError(401, "auth_required")
        principal = self._tokens.get(raw.removeprefix("Bearer ").strip())
        if principal is None:
            raise ApiError(401, "auth_invalid")
        if principal.uid in self.blocked:
            raise ApiError(403, "blocked")
        if require_google and not principal.is_google:
            raise ApiError(403, "google_required")
        request.state.principal = principal
        return principal

    async def check_not_revoked(self, principal: Principal) -> None:
        self.revocation_checks.append(principal.uid)
        if principal.uid in self.revoked:
            raise ApiError(401, "auth_invalid")


# ---- Firestore / Remote Config / metrics / router ----------------------------------------------------------


class FakeFirestore:
    """In-memory `interfaces.Firestore`. `query` returns (document path, data), like firestore.FirestoreRest."""

    def __init__(self) -> None:
        self.docs: dict[str, dict[str, Any]] = {}
        self.fail = False
        self.quota_exhausted = False
        self.writes: list[str] = []
        self.deletes: list[str] = []

    def _check(self) -> None:
        if self.quota_exhausted:
            raise FirestoreQuotaExhausted("fake quota exhausted")
        if self.fail:
            raise FirestoreUnavailable("fake firestore down")

    @staticmethod
    def _doc_path(path: str) -> str:
        if not path or len(path.strip("/").split("/")) % 2:
            raise ValueError(f"not a document path: {path!r}")
        return path.strip("/")

    def get(self, path: str) -> dict[str, Any] | None:
        self._check()
        doc = self.docs.get(self._doc_path(path))
        return copy.deepcopy(doc) if doc is not None else None

    def set(self, path: str, data: dict[str, Any], *, merge: bool = False) -> None:
        self._check()
        path = self._doc_path(path)
        base = self.docs.get(path, {}) if merge else {}
        self.docs[path] = {**base, **copy.deepcopy(data)}
        self.writes.append(path)

    def delete(self, path: str) -> None:
        self._check()
        path = self._doc_path(path)
        self.docs.pop(path, None)
        self.deletes.append(path)

    def increment(self, path: str, field_name: str, amount: float) -> None:
        self._check()
        path = self._doc_path(path)
        doc = self.docs.setdefault(path, {})
        doc[field_name] = doc.get(field_name, 0) + amount
        self.writes.append(path)

    def query(
        self, collection: str, field_name: str, op: str, value: Any, *, limit: int = 100
    ) -> list[tuple[str, dict[str, Any]]]:
        self._check()
        compare: dict[str, Callable[[Any], bool]] = {
            "==": lambda v: v == value,
            "!=": lambda v: v != value,
            "<": lambda v: v is not None and v < value,
            "<=": lambda v: v is not None and v <= value,
            ">": lambda v: v is not None and v > value,
            ">=": lambda v: v is not None and v >= value,
            "in": lambda v: v in value,
            "array-contains": lambda v: isinstance(v, list) and value in v,
        }
        test = compare[op]
        prefix = collection.strip("/") + "/"
        out = []
        for path, doc in sorted(self.docs.items()):
            if (
                path.startswith(prefix)
                and "/" not in path[len(prefix) :]
                and field_name in doc
                and test(doc[field_name])
            ):
                out.append((path, copy.deepcopy(doc)))
                if len(out) >= limit:
                    break
        return out

    def collection(self, name: str) -> dict[str, dict[str, Any]]:
        prefix = name.strip("/") + "/"
        return {p: d for p, d in self.docs.items() if p.startswith(prefix) and "/" not in p[len(prefix) :]}


class FakeRemoteConfig:
    def __init__(self, values: dict[str, Any] | None = None) -> None:
        self.values: dict[str, Any] = dict(values or {})

    def set(self, key: str, value: Any) -> None:
        self.values[key] = value

    def get_bool(self, key: str, default: bool) -> bool:
        value = self.values.get(key, default)
        return value if isinstance(value, bool) else str(value).lower() == "true"

    def get_int(self, key: str, default: int) -> int:
        try:
            return int(self.values.get(key, default))
        except (TypeError, ValueError):
            return default

    def get_str(self, key: str, default: str) -> str:
        value = self.values.get(key, default)
        return value if isinstance(value, str) else json.dumps(value)

    def get_json(self, key: str, default: Any) -> Any:
        value = self.values.get(key, default)
        if isinstance(value, str):
            try:
                return json.loads(value)
            except ValueError:
                return default
        return copy.deepcopy(value)


class FakeMetrics:
    def __init__(self) -> None:
        self.counters: Counter[tuple[str, frozenset[tuple[str, str]]]] = Counter()
        self.observations: list[tuple[str, float, dict[str, str]]] = []

    def incr(self, name: str, n: int = 1, **labels: str) -> None:
        self.counters[(name, frozenset(labels.items()))] += n

    def observe_ms(self, name: str, value_ms: float, **labels: str) -> None:
        self.observations.append((name, value_ms, labels))

    def count(self, name: str, **labels: str) -> int:
        want = set(labels.items())
        return sum(n for (k, lbl), n in self.counters.items() if k == name and want <= lbl)


class FakeRouter:
    """Stands in for router.ProviderRouter where only its status matters (config-check, readiness)."""

    def __init__(self, reason: str | None = None) -> None:
        self.reason = reason

    def unavailable_reason(self, task: Any = None) -> str | None:
        return self.reason


# ---- deps --------------------------------------------------------------------------------------------------


def make_test_deps(
    *,
    settings: Settings | None = None,
    clock: FakeClock | None = None,
    store: MemoryStore | None = None,
    firestore: Any = _UNSET,
    rc: dict[str, Any] | None = None,
    shard: str = "test-shard",
    **overrides: Any,
) -> Deps:
    """A Deps over in-memory state with the real IpLimiter, QuotaManager, SpendMeter and (when there is Firestore)
    AuditTrail. Pass `firestore=None` for a deployment without Firestore; any Deps field can be overridden by
    keyword."""
    settings = settings or make_settings()
    clock = clock or FakeClock()
    store = store if store is not None else MemoryStore()
    fs = FakeFirestore() if firestore is _UNSET else firestore
    remote_config = overrides.pop("remote_config", None) or FakeRemoteConfig(rc)
    keyring = HmacKeyring(settings.hmac_keys, store=store, clock=clock)
    quotas = overrides.pop("quotas", None) or QuotaManager(store, clock, firestore=fs)
    spend = overrides.pop("spend", None) or SpendMeter(
        store=store,
        clock=clock,
        settings=settings,
        remote_config=remote_config,
        tiers=quotas,
        firestore=fs,
        shard=shard,
        index_ttl_s=0.0,
        peer_ttl_s=0.0,
    )
    values: dict[str, Any] = {
        "settings": settings,
        "clock": clock,
        "store": store,
        "auth": FakeAuth(keyring),
        "ip_limiter": IpLimiter(store, clock, keyring),
        "quotas": quotas,
        "spend": spend,
        "firestore": fs,
        "remote_config": remote_config,
        "metrics": FakeMetrics(),
    }
    values.update(overrides)
    if "audit" not in values and fs is not None:
        values["audit"] = AuditTrail(store=store, clock=clock, firestore=fs, metrics=values["metrics"])
    return Deps(**values)


def make_test_app(deps: Deps | None = None) -> Any:
    from .app import create_app

    return create_app(deps or make_test_deps())


# ---- real Firebase-shaped tokens for auth tests ------------------------------------------------------------


@functools.cache
def _rsa_key(seed: int = 0) -> rsa.RSAPrivateKey:
    return rsa.generate_private_key(public_exponent=65537, key_size=2048)


def _b64(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


class FirebaseTokenMinter:
    def __init__(
        self, *, clock: FakeClock, project_id: str = TEST_PROJECT, kid: str = "kid-1", seed: int = 0
    ) -> None:
        self.clock = clock
        self.project_id = project_id
        self.kid = kid
        self.key = _rsa_key(seed)
        self.certs: dict[str, str] = {kid: self.cert_pem(self.key)}
        self.cert_fetches = 0
        self.lookups: list[dict[str, Any]] = []
        self.max_age_s = 3600
        self.lookup_handler: Callable[[dict[str, Any], httpx.Request], httpx.Response] | None = None
        self.cert_failure = False
        self.timeouts: list[dict[str, Any]] = []  # httpx timeout of every request served

    @staticmethod
    def cert_pem(key: rsa.RSAPrivateKey) -> str:
        name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "securetoken.test")])
        now = datetime(2026, 1, 1, tzinfo=UTC)
        cert = (
            x509.CertificateBuilder()
            .subject_name(name)
            .issuer_name(name)
            .public_key(key.public_key())
            .serial_number(1)
            .not_valid_before(now)
            .not_valid_after(now + timedelta(days=3650))
            .sign(key, hashes.SHA256())
        )
        return cert.public_bytes(serialization.Encoding.PEM).decode()

    def mint(
        self,
        *,
        uid: str = "user-1",
        provider: str = "google.com",
        google_sub: str = "g-sub-1",
        email: str | None = "user@example.com",
        email_verified: bool = True,
        iat: datetime | None = None,
        exp: datetime | None = None,
        auth_time: datetime | None = None,
        claims: dict[str, Any] | None = None,
        header: dict[str, Any] | None = None,
        key: rsa.RSAPrivateKey | None = None,
    ) -> str:
        now = self.clock.now_utc()
        iat = iat or now - timedelta(minutes=1)
        firebase: dict[str, Any] = {"sign_in_provider": provider, "identities": {}}
        if provider == "google.com":
            firebase["identities"] = {"google.com": [google_sub], "email": [email] if email else []}
        body: dict[str, Any] = {
            "iss": f"https://securetoken.google.com/{self.project_id}",
            "aud": self.project_id,
            "auth_time": int((auth_time or iat).timestamp()),
            "user_id": uid,
            "sub": uid,
            "iat": int(iat.timestamp()),
            "exp": int((exp or iat + timedelta(hours=1)).timestamp()),
            "firebase": firebase,
        }
        if email is not None and provider != "anonymous":
            body["email"] = email
            body["email_verified"] = email_verified
        body.update(claims or {})
        head = {"alg": "RS256", "kid": self.kid, "typ": "JWT", **(header or {})}
        signing_input = f"{_b64(json.dumps(head).encode())}.{_b64(json.dumps(body).encode())}"
        signature = (key or self.key).sign(signing_input.encode(), padding.PKCS1v15(), hashes.SHA256())
        return f"{signing_input}.{_b64(signature)}"

    def handler(self, request: httpx.Request) -> httpx.Response:
        self.timeouts.append(dict(request.extensions.get("timeout", {})))
        if request.url.host == "www.googleapis.com":
            self.cert_fetches += 1
            if self.cert_failure:
                return httpx.Response(503)
            return httpx.Response(
                200,
                json=self.certs,
                headers={"Cache-Control": f"public, max-age={self.max_age_s}, must-revalidate"},
            )
        if request.url.host == "identitytoolkit.googleapis.com":
            body = json.loads(request.content or b"{}")
            self.lookups.append({"body": body, "api_key": request.headers.get("x-goog-api-key")})
            if self.lookup_handler is not None:
                return self.lookup_handler(body, request)
            uid = _unverified_claims(body.get("idToken", "")).get("sub")
            return httpx.Response(200, json={"users": [{"localId": uid, "validSince": "0"}]})
        return httpx.Response(404)

    def client(self) -> httpx.AsyncClient:
        return httpx.AsyncClient(transport=httpx.MockTransport(self.handler))


def _unverified_claims(token: str) -> dict[str, Any]:
    try:
        part = token.split(".")[1]
        return json.loads(base64.urlsafe_b64decode(part + "=" * (-len(part) % 4)))
    except (IndexError, ValueError):
        return {}


def judge_allowlist(*emails: str) -> dict[str, Any]:
    """A Remote Config `judge_allowlist` value for these emails."""
    return {
        "schema": "1.0",
        "sha256_emails": [hashlib.sha256(e.strip().lower().encode()).hexdigest() for e in emails],
    }
