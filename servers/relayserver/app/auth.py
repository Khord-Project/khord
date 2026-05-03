"""
Bearer-token verification for the Relay Server (ADR 011).

Tokens are 32 random bytes minted by the server on mailbox creation,
returned to the client base64url-encoded with no padding. The server stores
only `sha256_hex(token_bytes)` keyed by `mailbox_id`. Verifying a request:

  1. Extract the `Authorization: Bearer <token>` header (or the auth frame
     for WebSocket — different transport, same check).
  2. base64url-decode → 32 bytes.
  3. SHA-256 → 64-char hex digest.
  4. Look up the mailbox row's stored hash and compare with hmac.compare_digest.

The low-level `verify_mailbox_token` function is shared by REST and
WebSocket; only the *extraction* differs (header vs first frame).
"""
import base64
import binascii
import hmac
from typing import Annotated

from fastapi import Depends, Header, HTTPException, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.crypto import sha256_hex
from app.database import get_session
from app.models import Mailbox


async def verify_mailbox_token(
    mailbox_id: str, token: str, session: AsyncSession
) -> bool:
    """Hash `token` and confirm it matches the stored hash for `mailbox_id`."""
    try:
        token_bytes = base64.urlsafe_b64decode(token + "=" * (-len(token) % 4))
    except (binascii.Error, ValueError):
        return False
    if len(token_bytes) != 32:
        return False

    presented_hash = sha256_hex(token_bytes)

    row = (
        await session.execute(
            select(Mailbox.bearer_token_hash).where(
                Mailbox.mailbox_id == mailbox_id
            )
        )
    ).scalar_one_or_none()
    if row is None:
        return False

    return hmac.compare_digest(presented_hash, row)


def parse_bearer_header(authorization: str | None) -> str | None:
    """Return the raw bearer token from an Authorization header, or None."""
    if not authorization:
        return None
    scheme, _, value = authorization.partition(" ")
    if scheme.lower() != "bearer" or not value:
        return None
    return value


async def require_mailbox_auth(
    mailbox_id: str,
    authorization: Annotated[str | None, Header()] = None,
    session: AsyncSession = Depends(get_session),
) -> str:
    """REST dependency: 401 unless `Authorization: Bearer <token>` is valid for `mailbox_id`."""
    token = parse_bearer_header(authorization)
    if token is None or not await verify_mailbox_token(
        mailbox_id, token, session
    ):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="auth failed",
        )
    return mailbox_id
