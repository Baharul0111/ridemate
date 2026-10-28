"""ONDC auth header signing/verification and the on_subscribe challenge crypto."""

from __future__ import annotations

import base64
import hashlib
import re

import pytest
from nacl.signing import SigningKey

from ridemate_api.ondc import crypto_box
from ridemate_api.ondc.signing import (
    SIGNED_HEADERS,
    SignatureError,
    StaticKeyRegistry,
    SubscriberKey,
    authorization_header,
    body_digest,
    parse_header,
    signing_string,
    verify,
)

NOW = 1_761_640_000
BODY = b'{"context":{"action":"on_search"},"message":{}}'


@pytest.fixture
def key() -> SubscriberKey:
    return SubscriberKey.generate("bpp.example.test", "k1")


@pytest.fixture
def registry(key: SubscriberKey) -> StaticKeyRegistry:
    reg = StaticKeyRegistry()
    reg.register(key)
    return reg


def test_header_has_the_ondc_shape(key: SubscriberKey) -> None:
    header = authorization_header(BODY, key, created=NOW)
    pattern = (
        r'^Signature keyId="bpp\.example\.test\|k1\|ed25519",algorithm="ed25519",created="(\d+)",'
        r'expires="(\d+)",headers="\(created\) \(expires\) digest",signature="([A-Za-z0-9+/=]+)"$'
    )
    match = re.match(pattern, header)
    assert match is not None
    assert int(match.group(1)) == NOW and int(match.group(2)) > NOW


def test_digest_is_blake2b_512_and_signing_string_is_exact() -> None:
    expected = base64.b64encode(hashlib.blake2b(BODY, digest_size=64).digest()).decode()
    assert body_digest(BODY) == expected
    assert signing_string(1, 2, "abc") == "(created): 1\n(expires): 2\ndigest: BLAKE-512=abc"


def test_signature_verifies_with_the_raw_public_key(key: SubscriberKey) -> None:
    header = authorization_header(BODY, key, created=NOW)
    parsed = parse_header(header)
    message = signing_string(parsed.created, parsed.expires, body_digest(BODY)).encode()
    from nacl.signing import VerifyKey

    VerifyKey(base64.b64decode(key.public_key_b64)).verify(message, base64.b64decode(parsed.signature))


def test_round_trip(key: SubscriberKey, registry: StaticKeyRegistry) -> None:
    header = authorization_header(BODY, key, created=NOW)
    parsed = verify(header, BODY, registry.lookup, now=NOW + 10)
    assert (parsed.subscriber_id, parsed.unique_key_id, parsed.headers) == (
        "bpp.example.test",
        "k1",
        SIGNED_HEADERS,
    )


def test_tampered_body_is_detected(key: SubscriberKey, registry: StaticKeyRegistry) -> None:
    header = authorization_header(BODY, key, created=NOW)
    with pytest.raises(SignatureError):
        verify(header, BODY.replace(b"on_search", b"on_cancel"), registry.lookup, now=NOW)


def test_tampered_header_fields_are_detected(key: SubscriberKey, registry: StaticKeyRegistry) -> None:
    header = authorization_header(BODY, key, created=NOW)
    later = header.replace(f'created="{NOW}"', f'created="{NOW + 1}"')
    with pytest.raises(SignatureError):
        verify(later, BODY, registry.lookup, now=NOW + 1)
    other_headers = header.replace('headers="(created) (expires) digest"', 'headers="digest"')
    with pytest.raises(SignatureError):
        verify(other_headers, BODY, registry.lookup, now=NOW)


def test_signature_from_another_key_is_rejected(registry: StaticKeyRegistry) -> None:
    impostor = SubscriberKey.generate("bpp.example.test", "k1")  # same ids, different key
    header = authorization_header(BODY, impostor, created=NOW)
    with pytest.raises(SignatureError):
        verify(header, BODY, registry.lookup, now=NOW)


def test_unknown_signer_is_rejected(registry: StaticKeyRegistry) -> None:
    stranger = SubscriberKey.generate("stranger.example.test", "k9")
    with pytest.raises(SignatureError, match="unknown"):
        verify(authorization_header(BODY, stranger, created=NOW), BODY, registry.lookup, now=NOW)


def test_expired_and_future_signatures_are_rejected(key: SubscriberKey, registry: StaticKeyRegistry) -> None:
    header = authorization_header(BODY, key, created=NOW, ttl_s=30)
    with pytest.raises(SignatureError, match="expired"):
        verify(header, BODY, registry.lookup, now=NOW + 3600)
    future = authorization_header(BODY, key, created=NOW + 3600)
    with pytest.raises(SignatureError, match="future"):
        verify(future, BODY, registry.lookup, now=NOW)


@pytest.mark.parametrize(
    "header",
    [
        None,
        "",
        "Bearer abc",
        'Signature keyId="a|b|rsa",algorithm="rsa",created="1",expires="2",headers="(created) (expires) digest",signature="x"',
        'Signature keyId="a|b",algorithm="ed25519",created="1",expires="2",headers="(created) (expires) digest",signature="x"',
        'Signature keyId="a|b|ed25519",algorithm="ed25519",created="x",expires="2",headers="(created) (expires) digest",signature="x"',
    ],
)
def test_malformed_headers_are_rejected(header: str | None, registry: StaticKeyRegistry) -> None:
    with pytest.raises(SignatureError):
        verify(header, BODY, registry.lookup, now=NOW)


def test_bad_signature_encoding_is_rejected(key: SubscriberKey, registry: StaticKeyRegistry) -> None:
    header = re.sub(
        r'signature="[^"]+"', 'signature="!!notbase64!!"', authorization_header(BODY, key, created=NOW)
    )
    with pytest.raises(SignatureError):
        verify(header, BODY, registry.lookup, now=NOW)


def test_private_key_formats() -> None:
    signing = SigningKey.generate()
    seed_b64 = base64.b64encode(bytes(signing)).decode()
    libsodium_b64 = base64.b64encode(bytes(signing) + bytes(signing.verify_key)).decode()
    a = SubscriberKey.from_private_b64("s", "k", seed_b64)
    b = SubscriberKey.from_private_b64("s", "k", libsodium_b64)
    assert a.public_key_b64 == b.public_key_b64 == base64.b64encode(bytes(signing.verify_key)).decode()
    with pytest.raises(ValueError):
        SubscriberKey.from_private_b64("s", "k", base64.b64encode(b"short").decode())
    assert "seed" not in repr(a)


# ---- on_subscribe challenge -----------------------------------------------------------------------------------


def test_challenge_round_trip_between_registry_and_subscriber() -> None:
    ours = crypto_box.EncryptionKeyPair.generate()
    registry = crypto_box.EncryptionKeyPair.generate()
    challenge = crypto_box.encrypt_challenge(
        "ondc-challenge-7f3a", registry.private_der_b64, ours.public_der_b64
    )
    assert (
        crypto_box.decrypt_challenge(challenge, ours.private_der_b64, registry.public_der_b64)
        == "ondc-challenge-7f3a"
    )
    assert crypto_box.shared_key(ours.private_der_b64, registry.public_der_b64) == crypto_box.shared_key(
        registry.private_der_b64, ours.public_der_b64
    )
    assert crypto_box.on_subscribe_answer({"challenge": challenge}, ours, registry.public_der_b64) == {
        "answer": "ondc-challenge-7f3a"
    }


def test_raw_32_byte_public_keys_are_accepted() -> None:
    from cryptography.hazmat.primitives import serialization

    ours = crypto_box.EncryptionKeyPair.generate()
    registry = crypto_box.EncryptionKeyPair.generate()
    raw = serialization.load_der_public_key(base64.b64decode(registry.public_der_b64)).public_bytes(
        serialization.Encoding.Raw, serialization.PublicFormat.Raw
    )
    raw_b64 = base64.b64encode(raw).decode()
    challenge = crypto_box.encrypt_challenge("hello", registry.private_der_b64, ours.public_der_b64)
    assert crypto_box.decrypt_challenge(challenge, ours.private_der_b64, raw_b64) == "hello"


def test_wrong_key_or_missing_challenge_fails() -> None:
    ours = crypto_box.EncryptionKeyPair.generate()
    registry = crypto_box.EncryptionKeyPair.generate()
    stranger = crypto_box.EncryptionKeyPair.generate()
    challenge = crypto_box.encrypt_challenge("secret", registry.private_der_b64, ours.public_der_b64)
    with pytest.raises(crypto_box.ChallengeError):
        crypto_box.decrypt_challenge(challenge, ours.private_der_b64, stranger.public_der_b64)
    with pytest.raises(crypto_box.ChallengeError):
        crypto_box.on_subscribe_answer({}, ours, registry.public_der_b64)
    assert "private_der_b64" not in repr(ours)
