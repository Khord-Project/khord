"""
Challenge-response authentication endpoints (PROTOCOL.md §4.4, ADR 011).

The flow:

  1. GET /v1/auth/challenge/{fingerprint}
     The server stores a fresh 32-byte challenge with a short TTL and returns
     it. No prior knowledge of the fingerprint is required — challenges can
     be issued for fingerprints the server has never seen.

  2. POST /v1/auth/verify
     The client sends fingerprint + challenge + Ed25519 signature. On first-
     time auth, the body MUST also include `identity_key` (base64-encoded
     public key); on subsequent auth the field is ignored unless it matches
     the stored key. The server verifies the signature, marks the challenge
     used (single-use), and issues a stateless HMAC session token.
"""
import base64
import binascii
import secrets
from datetime import datetime, timedelta, timezone

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app import tokens
from app.config import settings
from app.crypto import fingerprint_of, verify_ed25519
from app.database import get_session
from app.models import AuthChallenge, IdentityKey
from app.schemas import ChallengeResponse, VerifyRequest, VerifyResponse

router = APIRouter(prefix="/auth", tags=["auth"])

# A generic 401 for all auth failures — we do NOT distinguish "wrong sig"
# from "expired challenge" from "non-existent challenge" to the client. ADR
# 016 also forbids logging request-specific data, so failures stay terse.
_AUTH_FAILED = HTTPException(
    status_code=status.HTTP_401_UNAUTHORIZED, detail="auth failed"
)


def _b64decode(data: str) -> bytes:
    try:
        return base64.b64decode(data, validate=True)
    except (binascii.Error, ValueError) as e:
        raise _AUTH_FAILED from e


@router.get("/challenge/{fingerprint}", response_model=ChallengeResponse)
async def request_challenge(
    fingerprint: str,
    session: AsyncSession = Depends(get_session),
) -> ChallengeResponse:
    """Issue a fresh single-use challenge for the given fingerprint."""
    # Defensive: keep the auth_challenges table from accumulating expired rows.
    # Lazy cleanup on each issue is cheap and avoids a separate cron job for
    # the PoC. Production may want a periodic vacuum task.
    now = datetime.now(timezone.utc)
    await session.execute(
        AuthChallenge.__table__.delete().where(AuthChallenge.expires_at < now)
    )

    challenge_bytes = secrets.token_bytes(32)
    expires_at = now + timedelta(seconds=settings.challenge_ttl_seconds)

    session.add(
        AuthChallenge(
            fingerprint=fingerprint,
            challenge=challenge_bytes,
            expires_at=expires_at,
            used=False,
        )
    )
    await session.commit()

    return ChallengeResponse(
        challenge=base64.b64encode(challenge_bytes).decode("ascii"),
        expires=int(expires_at.timestamp()),
    )


@router.post("/verify", response_model=VerifyResponse)
async def verify_challenge(
    body: VerifyRequest,
    session: AsyncSession = Depends(get_session),
) -> VerifyResponse:
    """Verify a signed challenge and issue a session token."""
    challenge_bytes = _b64decode(body.challenge)
    signature_bytes = _b64decode(body.signature)

    # Locate and lock the matching unused, unexpired challenge row.
    now = datetime.now(timezone.utc)
    challenge_stmt = (
        select(AuthChallenge)
        .where(
            AuthChallenge.fingerprint == body.fingerprint,
            AuthChallenge.challenge == challenge_bytes,
            AuthChallenge.used.is_(False),
            AuthChallenge.expires_at > now,
        )
        .with_for_update()
    )
    challenge_row = (await session.execute(challenge_stmt)).scalar_one_or_none()
    if challenge_row is None:
        raise _AUTH_FAILED

    # Resolve which Ed25519 public key to verify against.
    identity_stmt = select(IdentityKey).where(
        IdentityKey.fingerprint == body.fingerprint
    )
    identity_row = (await session.execute(identity_stmt)).scalar_one_or_none()

    if identity_row is None:
        # First-time auth: identity_key in body is REQUIRED (ADR 020 step 9).
        if body.identity_key is None:
            raise _AUTH_FAILED
        identity_public_key = _b64decode(body.identity_key)
        if fingerprint_of(identity_public_key) != body.fingerprint:
            raise _AUTH_FAILED
    else:
        identity_public_key = identity_row.identity_key
        # Defense-in-depth: if a body identity_key is supplied for a known
        # fingerprint, it must match the stored key. This makes substitution
        # attempts loud rather than silent.
        if body.identity_key is not None:
            if _b64decode(body.identity_key) != identity_public_key:
                raise _AUTH_FAILED

    if not verify_ed25519(identity_public_key, signature_bytes, challenge_bytes):
        raise _AUTH_FAILED

    # Single-use: burn the challenge before issuing the token. The row stays
    # in the table briefly until the next request triggers lazy cleanup.
    challenge_row.used = True
    await session.commit()

    return VerifyResponse(
        token=tokens.issue(body.fingerprint, settings.session_token_ttl_seconds)
    )
