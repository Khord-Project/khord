"""
Hashcash-style proof-of-work for mailbox creation (ADR 012, PROTOCOL.md §5.6).

Input encoding (canonicalized — clarifies the spec's bare `||`):

    digest = SHA-256( utf8(mailbox_id) || utf8(decimal_nonce) )

`mailbox_id` is the client-generated string ID (not yet base64-decoded).
`decimal_nonce` is the unsigned-integer nonce as decimal ASCII.

A puzzle is "solved" iff `leading_zero_bits(digest) >= difficulty_bits`,
where `difficulty_bits` is the server's current setting at verification
time. The client discovers the current difficulty from `GET /v1/pow-params`.
"""
from app.crypto import leading_zero_bits, sha256_bytes


def verify(mailbox_id: str, nonce: str, difficulty_bits: int) -> bool:
    """Return True iff `(mailbox_id, nonce)` solves the puzzle at `difficulty_bits`.

    Strict input handling: nonce must be a non-empty all-decimal-digit string
    so the canonical encoding is unambiguous and the server never normalizes
    a client's nonce silently.
    """
    if not nonce or not nonce.isdigit():
        return False
    if difficulty_bits < 0:
        return False
    digest = sha256_bytes(mailbox_id.encode("utf-8") + nonce.encode("ascii"))
    return leading_zero_bits(digest) >= difficulty_bits
