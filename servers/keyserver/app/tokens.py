"""
Stateless session tokens for the Key Server (ADR 011).

A token is `base64url(payload).base64url(HMAC-SHA256(secret, payload))` where

    payload = b"khord-keyserver-v1\\x00"
              || fingerprint_bytes
              || b"\\x00"
              || u64_be(expires_at_unix_seconds)

The token therefore carries only:
  * a domain-separation prefix (so the secret can be reused for nothing else)
  * the fingerprint that authenticated (already in every URL we use the token on)
  * the expiry as an unsigned 64-bit big-endian unix timestamp

There is no JSON, no `alg` field, no claims namespace — this is deliberately
not a JWT (ADR 011 forbids them).

Verification is constant-time via hmac.compare_digest. The HMAC secret is loaded
from settings.token_secret at startup; rotating the secret invalidates all
outstanding tokens (acceptable: TTL is 15 min by default).
"""
import base64
import hmac
import struct
import time
from hashlib import sha256

from app.config import settings

_PREFIX = b"khord-keyserver-v1\x00"
_SEP = b"\x00"
_EXPIRES_STRUCT = struct.Struct(">Q")  # unsigned 64-bit big-endian


class InvalidToken(Exception):
    """Raised when a token is malformed, has a bad signature, or has expired."""


def _b64encode(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def _b64decode(data: str) -> bytes:
    pad = "=" * (-len(data) % 4)
    return base64.urlsafe_b64decode(data + pad)


def _hmac(payload: bytes) -> bytes:
    return hmac.new(
        settings.token_secret.encode("utf-8"), payload, sha256
    ).digest()


def issue(fingerprint: str, ttl_seconds: int) -> str:
    """Mint a session token binding `fingerprint` for `ttl_seconds`."""
    expires_at = int(time.time()) + ttl_seconds
    payload = (
        _PREFIX
        + fingerprint.encode("ascii")
        + _SEP
        + _EXPIRES_STRUCT.pack(expires_at)
    )
    sig = _hmac(payload)
    return f"{_b64encode(payload)}.{_b64encode(sig)}"


def verify(token: str) -> str:
    """Verify a token. Returns the bound fingerprint, or raises InvalidToken."""
    try:
        payload_b64, sig_b64 = token.split(".", 1)
        payload = _b64decode(payload_b64)
        sig = _b64decode(sig_b64)
    except (ValueError, base64.binascii.Error) as e:
        raise InvalidToken("malformed") from e

    expected_sig = _hmac(payload)
    if not hmac.compare_digest(sig, expected_sig):
        raise InvalidToken("bad signature")

    if not payload.startswith(_PREFIX):
        raise InvalidToken("wrong prefix")

    body = payload[len(_PREFIX) :]
    if len(body) < 1 + _EXPIRES_STRUCT.size:
        raise InvalidToken("truncated body")

    fingerprint_bytes = body[: -(1 + _EXPIRES_STRUCT.size)]
    sep = body[-(1 + _EXPIRES_STRUCT.size) : -_EXPIRES_STRUCT.size]
    expires_at_bytes = body[-_EXPIRES_STRUCT.size :]

    if sep != _SEP:
        raise InvalidToken("missing separator")

    (expires_at,) = _EXPIRES_STRUCT.unpack(expires_at_bytes)
    if int(time.time()) > expires_at:
        raise InvalidToken("expired")

    try:
        return fingerprint_bytes.decode("ascii")
    except UnicodeDecodeError as e:
        raise InvalidToken("non-ascii fingerprint") from e
