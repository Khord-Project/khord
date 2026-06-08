"""
Pre-key bundle endpoints (PROTOCOL.md §4.1-4.5).

  POST /v1/keys/{fingerprint}/bundle        bearer-auth, fp-bound
  GET  /v1/keys/{fingerprint}/bundle        public — atomic OPK consumption
  POST /v1/keys/{fingerprint}/prekeys       bearer-auth, fp-bound
  GET  /v1/keys/{fingerprint}/prekey-count  bearer-auth, fp-bound

The protected endpoints all enforce `path_fingerprint == token_fingerprint`.
This is the load-bearing authorization: the bearer token only authorizes
operations on its own fingerprint.

OPK consumption uses `UPDATE ... RETURNING` driven by a `FOR UPDATE SKIP LOCKED`
subquery so that two concurrent fetches of the same bundle deterministically
return two different one-time pre-keys (or one returns none).
"""
import base64
import binascii

from fastapi import APIRouter, Depends, HTTPException, Request, status
from sqlalchemy import func, select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.crypto import fingerprint_of
from app.database import get_session
from app.dependencies import require_session_token
from app.models import IdentityKey, OneTimePreKey
from app.ratelimit import fingerprint_key, limiter
from app.schemas import (
    BundleFetchResponse,
    BundleUploadRequest,
    OneTimePreKey as OneTimePreKeySchema,
    PreKeyCountResponse,
    ReplenishRequest,
    SignedPreKey,
)

router = APIRouter(prefix="/keys", tags=["keys"])


def _b64decode(data: str) -> bytes:
    try:
        return base64.b64decode(data, validate=True)
    except (binascii.Error, ValueError) as e:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="invalid base64",
        ) from e


def _require_fingerprint_match(path_fp: str, token_fp: str) -> None:
    """A bearer token may only act on its own fingerprint's resources."""
    if path_fp != token_fp:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="token does not authorize this fingerprint",
        )


@router.post("/{fingerprint}/bundle", status_code=status.HTTP_201_CREATED)
async def upload_pre_key_bundle(
    fingerprint: str,
    body: BundleUploadRequest,
    session: AsyncSession = Depends(get_session),
    token_fingerprint: str = Depends(require_session_token),
) -> dict:
    """Create or update a pre-key bundle for `fingerprint`. 201 on both."""
    # TODO(rate-limit): per-fingerprint, max N uploads per hour (ADR 012).
    _require_fingerprint_match(fingerprint, token_fingerprint)

    identity_key_bytes = _b64decode(body.identity_key)
    if fingerprint_of(identity_key_bytes) != fingerprint:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="fingerprint does not match identity_key",
        )

    spk_pub = _b64decode(body.signed_pre_key.public_key)
    spk_sig = _b64decode(body.signed_pre_key.signature)

    existing_stmt = (
        select(IdentityKey)
        .where(IdentityKey.fingerprint == fingerprint)
        .with_for_update()
    )
    existing = (await session.execute(existing_stmt)).scalar_one_or_none()

    if existing is None:
        session.add(
            IdentityKey(
                fingerprint=fingerprint,
                identity_key=identity_key_bytes,
                signed_pre_key_id=body.signed_pre_key.key_id,
                signed_pre_key=spk_pub,
                signed_pre_key_signature=spk_sig,
            )
        )
    else:
        # Defense-in-depth: the upload's identity_key must equal the stored
        # one. The fingerprint match above is necessary but not sufficient —
        # an attacker who controlled the auth path could try a different key
        # under the same fingerprint, and rebinding identity is forbidden.
        if existing.identity_key != identity_key_bytes:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="identity_key does not match stored key",
            )
        existing.signed_pre_key_id = body.signed_pre_key.key_id
        existing.signed_pre_key = spk_pub
        existing.signed_pre_key_signature = spk_sig

    for opk in body.one_time_pre_keys:
        session.add(
            OneTimePreKey(
                fingerprint=fingerprint,
                key_id=opk.key_id,
                public_key=_b64decode(opk.public_key),
                consumed=False,
            )
        )

    await session.commit()
    return {"detail": "created"}


@router.get(
    "/{fingerprint}/bundle",
    response_model=BundleFetchResponse,
    # PROTOCOL.md §4.2: when no OPK remains, the field is omitted (not null).
    response_model_exclude_none=True,
)
# Two stacked limits guard the OPK pool (ADR 012): per-fingerprint stops one
# victim's one-time prekeys being drained (which silently degrades forward
# secrecy for new conversations), per-IP stops one caller sweeping many
# victims. Both must pass; the stricter one trips first.
@limiter.limit(settings.rate_limit_opk_per_fingerprint, key_func=fingerprint_key)
@limiter.limit(settings.rate_limit_opk_per_ip)
async def fetch_pre_key_bundle(
    request: Request,
    fingerprint: str,
    session: AsyncSession = Depends(get_session),
) -> BundleFetchResponse:
    """Public: fetch the bundle, atomically consuming one OPK if available."""
    identity = (
        await session.execute(
            select(IdentityKey).where(IdentityKey.fingerprint == fingerprint)
        )
    ).scalar_one_or_none()
    if identity is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="not found"
        )

    # Atomic consumption. The inner SELECT picks the oldest unconsumed OPK
    # and locks it with SKIP LOCKED so concurrent fetches see different rows.
    # The outer UPDATE flips `consumed` and RETURNING gives us the row data.
    #
    # TODO(security-hardening): Add periodic cleanup of consumed OPKs older
    # than N days. Accumulated consumed keys are metadata — count reveals
    # conversation initiation volume.
    inner = (
        select(OneTimePreKey.id)
        .where(
            OneTimePreKey.fingerprint == fingerprint,
            OneTimePreKey.consumed.is_(False),
        )
        .order_by(OneTimePreKey.id)
        .limit(1)
        .with_for_update(skip_locked=True)
    )
    consume = (
        update(OneTimePreKey)
        .where(OneTimePreKey.id == inner.scalar_subquery())
        .values(consumed=True)
        .returning(OneTimePreKey.key_id, OneTimePreKey.public_key)
    )
    consumed_row = (await session.execute(consume)).first()
    await session.commit()

    response = BundleFetchResponse(
        identity_key=base64.b64encode(identity.identity_key).decode("ascii"),
        signed_pre_key=SignedPreKey(
            key_id=identity.signed_pre_key_id,
            public_key=base64.b64encode(identity.signed_pre_key).decode("ascii"),
            signature=base64.b64encode(
                identity.signed_pre_key_signature
            ).decode("ascii"),
        ),
        one_time_pre_key=(
            OneTimePreKeySchema(
                key_id=consumed_row.key_id,
                public_key=base64.b64encode(consumed_row.public_key).decode(
                    "ascii"
                ),
            )
            if consumed_row is not None
            else None
        ),
    )
    return response


@router.post("/{fingerprint}/prekeys", status_code=status.HTTP_201_CREATED)
async def replenish_pre_keys(
    fingerprint: str,
    body: ReplenishRequest,
    session: AsyncSession = Depends(get_session),
    token_fingerprint: str = Depends(require_session_token),
) -> dict:
    """Append more one-time pre-keys to an existing bundle."""
    # TODO(rate-limit): per-fingerprint, max N replenish ops per hour (ADR 012).
    _require_fingerprint_match(fingerprint, token_fingerprint)

    identity = (
        await session.execute(
            select(IdentityKey).where(IdentityKey.fingerprint == fingerprint)
        )
    ).scalar_one_or_none()
    if identity is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="not found"
        )

    for opk in body.one_time_pre_keys:
        session.add(
            OneTimePreKey(
                fingerprint=fingerprint,
                key_id=opk.key_id,
                public_key=_b64decode(opk.public_key),
                consumed=False,
            )
        )
    await session.commit()
    return {"detail": "created"}


@router.get("/{fingerprint}/prekey-count", response_model=PreKeyCountResponse)
async def pre_key_count(
    fingerprint: str,
    session: AsyncSession = Depends(get_session),
    token_fingerprint: str = Depends(require_session_token),
) -> PreKeyCountResponse:
    """Return the number of remaining unconsumed OPKs for `fingerprint`."""
    _require_fingerprint_match(fingerprint, token_fingerprint)

    count = (
        await session.execute(
            select(func.count(OneTimePreKey.id)).where(
                OneTimePreKey.fingerprint == fingerprint,
                OneTimePreKey.consumed.is_(False),
            )
        )
    ).scalar_one()
    return PreKeyCountResponse(count=int(count))
