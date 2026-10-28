"""ONDC (Beckn) request authentication: BLAKE2b-512 digest of the raw body, Ed25519 signature over
"(created): …\\n(expires): …\\ndigest: BLAKE-512=…", carried in the Authorization header:

    Signature keyId="<subscriber_id>|<unique_key_id>|ed25519",algorithm="ed25519",created="<ts>",
    expires="<ts>",headers="(created) (expires) digest",signature="<base64>"

Outbound requests are signed with our key; every inbound callback is verified before it is stored or acted on.
"""

from __future__ import annotations

import base64
import binascii
import hashlib
import re
from collections.abc import Callable
from dataclasses import dataclass, field

from nacl.exceptions import BadSignatureError
from nacl.signing import SigningKey, VerifyKey

ALGORITHM = "ed25519"
SIGNED_HEADERS = "(created) (expires) digest"
DEFAULT_TTL_S = 300
MAX_CLOCK_SKEW_S = 60
_PARAM_RE = re.compile(r'([A-Za-z]+)="([^"]*)"')


class SignatureError(Exception):
    """The Authorization header is missing, malformed, expired, from an unknown key, or doesn't verify."""


@dataclass(frozen=True)
class SubscriberKey:
    """An Ed25519 signing key registered for a subscriber (ONDC registry: subscriber_id + unique_key_id)."""

    subscriber_id: str
    unique_key_id: str
    seed: bytes = field(repr=False)

    @classmethod
    def generate(cls, subscriber_id: str, unique_key_id: str) -> SubscriberKey:
        return cls(subscriber_id, unique_key_id, bytes(SigningKey.generate()))

    @classmethod
    def from_private_b64(cls, subscriber_id: str, unique_key_id: str, private_b64: str) -> SubscriberKey:
        """Accepts the 32-byte seed or libsodium's 64-byte secret key (seed ‖ public key), base64."""
        raw = base64.b64decode(private_b64, validate=True)
        if len(raw) not in (32, 64):
            raise ValueError("Ed25519 private key must be 32 or 64 bytes")
        return cls(subscriber_id, unique_key_id, raw[:32])

    @property
    def key_id(self) -> str:
        return f"{self.subscriber_id}|{self.unique_key_id}|{ALGORITHM}"

    @property
    def public_key_b64(self) -> str:
        return base64.b64encode(bytes(SigningKey(self.seed).verify_key)).decode("ascii")

    def sign(self, message: bytes) -> bytes:
        return SigningKey(self.seed).sign(message).signature


@dataclass(frozen=True)
class ParsedSignature:
    subscriber_id: str
    unique_key_id: str
    algorithm: str
    created: int
    expires: int
    headers: str
    signature: str


# (subscriber_id, unique_key_id) -> base64 Ed25519 public key, or None when unknown.
KeyLookup = Callable[[str, str], str | None]


def body_digest(body: bytes) -> str:
    return base64.b64encode(hashlib.blake2b(body, digest_size=64).digest()).decode("ascii")


def signing_string(created: int, expires: int, digest_b64: str) -> str:
    return f"(created): {created}\n(expires): {expires}\ndigest: BLAKE-512={digest_b64}"


def authorization_header(body: bytes, key: SubscriberKey, *, created: int, ttl_s: int = DEFAULT_TTL_S) -> str:
    expires = created + ttl_s
    signature = key.sign(signing_string(created, expires, body_digest(body)).encode("utf-8"))
    return (
        f'Signature keyId="{key.key_id}",algorithm="{ALGORITHM}",created="{created}",expires="{expires}",'
        f'headers="{SIGNED_HEADERS}",signature="{base64.b64encode(signature).decode("ascii")}"'
    )


def parse_header(header: str) -> ParsedSignature:
    value = header.strip()
    value = value.removeprefix("Signature ")
    params = dict(_PARAM_RE.findall(value))
    try:
        subscriber_id, unique_key_id, key_alg = params["keyId"].split("|")
        created = int(params["created"])
        expires = int(params["expires"])
        parsed = ParsedSignature(
            subscriber_id=subscriber_id,
            unique_key_id=unique_key_id,
            algorithm=params["algorithm"],
            created=created,
            expires=expires,
            headers=params["headers"],
            signature=params["signature"],
        )
    except (KeyError, ValueError) as e:
        raise SignatureError("malformed Authorization header") from e
    if key_alg != ALGORITHM or parsed.algorithm != ALGORITHM:
        raise SignatureError("unsupported algorithm")
    if parsed.headers != SIGNED_HEADERS:
        raise SignatureError("unexpected signed headers")
    return parsed


def verify(header: str | None, body: bytes, lookup: KeyLookup, *, now: int) -> ParsedSignature:
    """Checks the header against the exact raw body. Raises SignatureError; returns the parsed header."""
    if not header:
        raise SignatureError("missing Authorization header")
    parsed = parse_header(header)
    if parsed.expires <= parsed.created:
        raise SignatureError("expires before created")
    if parsed.created > now + MAX_CLOCK_SKEW_S:
        raise SignatureError("created in the future")
    if parsed.expires < now - MAX_CLOCK_SKEW_S:
        raise SignatureError("signature expired")
    public_b64 = lookup(parsed.subscriber_id, parsed.unique_key_id)
    if not public_b64:
        raise SignatureError("unknown signing key")
    try:
        verify_key = VerifyKey(base64.b64decode(public_b64, validate=True))
        signature = base64.b64decode(parsed.signature, validate=True)
        message = signing_string(parsed.created, parsed.expires, body_digest(body)).encode("utf-8")
        verify_key.verify(message, signature)
    except (BadSignatureError, binascii.Error, ValueError) as e:
        raise SignatureError("signature does not verify") from e
    return parsed


class StaticKeyRegistry:
    """Known public keys. The mock seller and our BAP register here; Pre-Prod uses the ONDC registry (P2)."""

    def __init__(self) -> None:
        self._keys: dict[tuple[str, str], str] = {}

    def register(self, key: SubscriberKey) -> None:
        self._keys[(key.subscriber_id, key.unique_key_id)] = key.public_key_b64

    def lookup(self, subscriber_id: str, unique_key_id: str) -> str | None:
        return self._keys.get((subscriber_id, unique_key_id))
