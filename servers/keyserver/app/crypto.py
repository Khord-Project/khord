"""
Server-side cryptographic helpers for the Key Server.

Uses PyNaCl (the libsodium binding for Python) to match ADR 006 — the same
crypto library is used on the client (lazysodium) and server. We only need
two operations server-side:

  * fingerprint_of(identity_key) — compute the canonical fingerprint of an
    Ed25519 public key. PROTOCOL.md §3.1: hex(SHA-256(identity_public_key)).
  * verify_ed25519(public_key, signature, message) — constant-time signature
    verification, returns bool (does not raise).
"""
import hashlib

from nacl.exceptions import BadSignatureError
from nacl.signing import VerifyKey


def fingerprint_of(identity_public_key: bytes) -> str:
    """Canonical Khord fingerprint: hex-encoded SHA-256 of the Ed25519 public key.

    Returns a 64-character lowercase hex string.
    """
    return hashlib.sha256(identity_public_key).hexdigest()


def verify_ed25519(public_key: bytes, signature: bytes, message: bytes) -> bool:
    """Verify an Ed25519 signature. Returns False on any verification failure.

    Catches BadSignatureError as well as any malformed-input ValueError so
    callers do not need to discriminate between "wrong sig" and "wrong-shape
    sig" — both are equivalent failures from the API's perspective.
    """
    try:
        VerifyKey(public_key).verify(message, signature)
        return True
    except (BadSignatureError, ValueError):
        return False
