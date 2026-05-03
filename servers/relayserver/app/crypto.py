"""
Server-side cryptographic helpers for the Relay Server.

The Relay Server has very modest crypto needs — it does not perform any
public-key operations. We hash bearer tokens (SHA-256) and verify proof-of-
work puzzles (SHA-256 + leading-zero count). Both via stdlib `hashlib`.
Deliberately *no* shared code with the Key Server (ADR 002).
"""
import hashlib


def sha256_bytes(data: bytes) -> bytes:
    """Return the 32-byte SHA-256 digest of `data`."""
    return hashlib.sha256(data).digest()


def sha256_hex(data: bytes) -> str:
    """Return the 64-char hex SHA-256 digest of `data`."""
    return hashlib.sha256(data).hexdigest()


def leading_zero_bits(digest: bytes) -> int:
    """Count leading zero bits in `digest`, MSB-first."""
    count = 0
    for byte in digest:
        if byte == 0:
            count += 8
            continue
        # bit_length of a non-zero byte is 1..8; (8 - bit_length) is the
        # number of leading zeros within that byte.
        count += 8 - byte.bit_length()
        break
    return count
