"""Shared test helpers — random mailbox_id, PoW mining, mailbox creation."""
import base64
import hashlib
import secrets


def random_mailbox_id(byte_len: int = 16) -> str:
    """Return a base64url-encoded random mailbox_id, ≥22 chars by default."""
    return (
        base64.urlsafe_b64encode(secrets.token_bytes(byte_len))
        .rstrip(b"=")
        .decode("ascii")
    )


def _leading_zero_bits(digest: bytes) -> int:
    count = 0
    for byte in digest:
        if byte == 0:
            count += 8
            continue
        count += 8 - byte.bit_length()
        break
    return count


def mine_pow(mailbox_id: str, difficulty_bits: int) -> str:
    """Find the smallest decimal-ASCII nonce that solves the puzzle."""
    nonce = 0
    mid_bytes = mailbox_id.encode("utf-8")
    while True:
        nonce_str = str(nonce)
        digest = hashlib.sha256(mid_bytes + nonce_str.encode("ascii")).digest()
        if _leading_zero_bits(digest) >= difficulty_bits:
            return nonce_str
        nonce += 1


async def create_mailbox(client, *, mailbox_id: str | None = None,
                         difficulty_bits: int = 8) -> tuple[str, str]:
    """Mine PoW, POST /v1/mailboxes, return (mailbox_id, bearer_token)."""
    if mailbox_id is None:
        mailbox_id = random_mailbox_id()
    pow_nonce = mine_pow(mailbox_id, difficulty_bits)
    r = await client.post(
        "/v1/mailboxes",
        json={"mailbox_id": mailbox_id, "proof_of_work": pow_nonce},
    )
    assert r.status_code == 201, r.text
    return mailbox_id, r.json()["bearer_token"]


def b64encode_str(data: bytes) -> str:
    return base64.b64encode(data).decode("ascii")
