"""
Relay Server media endpoints (ADR 029).

A small store for opaque, client-encrypted file blobs. The server never
sees plaintext, a filename, a content type, a sender, or a recipient — it
holds random-id → encrypted-bytes and nothing else.

Auth model (mirrors the mailbox endpoints' "knowing the id is enough"):
  - POST /v1/media              NO bearer auth (proof-of-work only)
  - GET  /v1/media/{media_id}   NO bearer auth (the 128-bit id is the capability)

The GET is delete-on-read: a blob is consumed by its first successful
fetch, exactly like a mailbox message is consumed by ack. Combined with a
TTL it bounds storage — a blob that's never fetched is reaped after
`media_ttl_seconds`.

Proof-of-work: the upload reuses the Hashcash scheme from mailbox creation
(app.pow), but the puzzle subject is the SHA-256 hex of the uploaded bytes
rather than a mailbox_id. Binding the nonce to the exact blob stops a
client from mining one nonce and reusing it for unlimited uploads.
"""
import secrets
from datetime import datetime, timedelta, timezone

from fastapi import APIRouter, Depends, Form, HTTPException, Response, UploadFile, status
from sqlalchemy import delete
from sqlalchemy.ext.asyncio import AsyncSession

from app import pow as pow_module
from app.config import settings
from app.crypto import sha256_hex
from app.database import get_session
from app.models import Media
from app.schemas import UploadMediaResponse

router = APIRouter(prefix="/media", tags=["media"])


def _now() -> datetime:
    return datetime.now(timezone.utc)


@router.post(
    "",
    status_code=status.HTTP_201_CREATED,
    response_model=UploadMediaResponse,
)
async def upload_media(
    file: UploadFile,
    proof_of_work: str = Form(
        description="Decimal-ASCII nonce solving SHA-256(sha256_hex(blob) || nonce)"
    ),
    session: AsyncSession = Depends(get_session),
) -> UploadMediaResponse:
    """Store an encrypted blob and return its random id. PoW required, no auth."""
    contents = await file.read()

    # Size first — reject oversized payloads before spending a hash on PoW.
    # NB: the body is already buffered in memory by this point; a hardened
    # deployment should cap the request body at the ASGI layer too.
    if len(contents) > settings.media_max_size_bytes:
        raise HTTPException(
            status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
            detail="file too large",
        )

    # PoW subject is the blob's own hash, so a solved nonce only authorizes
    # *this* blob — it can't be replayed for a different upload.
    if not pow_module.verify(
        mailbox_id=sha256_hex(contents),
        nonce=proof_of_work,
        difficulty_bits=settings.proof_of_work_difficulty_bits,
    ):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="invalid proof of work",
        )

    media_id = secrets.token_hex(16)
    expires_at = _now() + timedelta(seconds=settings.media_ttl_seconds)

    session.add(Media(id=media_id, data=contents, expires_at=expires_at))

    # Opportunistic TTL cleanup — bounded work per write, no scheduler.
    # Sweeps any blob (across all uploaders) whose TTL has lapsed.
    # TODO(storage-hardening): a background sweeper for low-traffic relays;
    # this only fires while uploads keep coming.
    await session.execute(delete(Media).where(Media.expires_at < _now()))

    await session.commit()
    return UploadMediaResponse(media_id=media_id)


@router.get("/{media_id}")
async def download_media(
    media_id: str,
    session: AsyncSession = Depends(get_session),
) -> Response:
    """Return the encrypted blob and delete it (one-time read).

    A second fetch — or a fetch of an already-expired blob — returns 404.
    Deleting on read is what makes the blob one-time; the expiry filter
    means a TTL-lapsed-but-not-yet-swept blob is never served (and is
    cleaned up by this same DELETE).
    """
    now = _now()
    result = await session.execute(
        delete(Media)
        .where(Media.id == media_id)
        .returning(Media.data, Media.expires_at)
    )
    row = result.first()
    await session.commit()

    if row is None or row.expires_at <= now:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="media not found or already fetched",
        )

    return Response(content=row.data, media_type="application/octet-stream")
