"""ONDC registry challenge (`/on_subscribe`): X25519 key agreement between our encryption key and the registry's,
then AES-256-ECB with PKCS#7 padding over the base64 challenge, exactly as the ONDC reference utilities do.
Keys travel as base64 DER (PKCS#8 private, SubjectPublicKeyInfo public); a raw 32-byte public key is accepted too.

Used only when subscribing to a real registry (Pre-Prod/Prod); the in-process mock seller never needs it.
"""

from __future__ import annotations

import base64
from dataclasses import dataclass, field

from cryptography.hazmat.primitives import padding, serialization
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey, X25519PublicKey
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

_BLOCK_BITS = 128


class ChallengeError(Exception):
    pass


@dataclass(frozen=True)
class EncryptionKeyPair:
    private_der_b64: str = field(repr=False)
    public_der_b64: str

    @classmethod
    def generate(cls) -> EncryptionKeyPair:
        private = X25519PrivateKey.generate()
        private_der = private.private_bytes(
            serialization.Encoding.DER, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()
        )
        public_der = private.public_key().public_bytes(
            serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo
        )
        return cls(
            base64.b64encode(private_der).decode("ascii"), base64.b64encode(public_der).decode("ascii")
        )


def _private_key(private_der_b64: str) -> X25519PrivateKey:
    key = serialization.load_der_private_key(base64.b64decode(private_der_b64), password=None)
    if not isinstance(key, X25519PrivateKey):
        raise ChallengeError("encryption private key is not X25519")
    return key


def _public_key(public_b64: str) -> X25519PublicKey:
    raw = base64.b64decode(public_b64)
    if len(raw) == 32:
        return X25519PublicKey.from_public_bytes(raw)
    key = serialization.load_der_public_key(raw)
    if not isinstance(key, X25519PublicKey):
        raise ChallengeError("peer public key is not X25519")
    return key


def shared_key(private_der_b64: str, peer_public_b64: str) -> bytes:
    """The 32-byte X25519 shared secret, used directly as the AES-256 key."""
    return _private_key(private_der_b64).exchange(_public_key(peer_public_b64))


def _cipher(key: bytes) -> Cipher[modes.ECB]:
    # ECB is mandated by the ONDC subscription protocol for this one-block-ish challenge; not our choice.
    return Cipher(algorithms.AES(key), modes.ECB())  # nosec B305


def decrypt_challenge(challenge_b64: str, private_der_b64: str, peer_public_b64: str) -> str:
    try:
        decryptor = _cipher(shared_key(private_der_b64, peer_public_b64)).decryptor()
        padded = decryptor.update(base64.b64decode(challenge_b64)) + decryptor.finalize()
        unpadder = padding.PKCS7(_BLOCK_BITS).unpadder()
        return (unpadder.update(padded) + unpadder.finalize()).decode("utf-8")
    except (ValueError, UnicodeDecodeError) as e:
        raise ChallengeError("challenge does not decrypt") from e


def encrypt_challenge(plaintext: str, private_der_b64: str, peer_public_b64: str) -> str:
    """The registry's side of the exchange (tests and the subscription dry-run tool)."""
    padder = padding.PKCS7(_BLOCK_BITS).padder()
    padded = padder.update(plaintext.encode("utf-8")) + padder.finalize()
    encryptor = _cipher(shared_key(private_der_b64, peer_public_b64)).encryptor()
    return base64.b64encode(encryptor.update(padded) + encryptor.finalize()).decode("ascii")


def on_subscribe_answer(
    body: dict[str, object], keys: EncryptionKeyPair, registry_public_b64: str
) -> dict[str, str]:
    """The `/on_subscribe` response: {"answer": <decrypted challenge>}."""
    challenge = body.get("challenge")
    if not isinstance(challenge, str) or not challenge:
        raise ChallengeError("missing challenge")
    return {"answer": decrypt_challenge(challenge, keys.private_der_b64, registry_public_b64)}
