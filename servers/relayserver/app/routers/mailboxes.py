"""
Relay Server mailbox endpoints (PROTOCOL.md §5.1-5.4).

Auth model (intentional, ADR 011):
  - POST /v1/mailboxes                          NO auth (proof-of-work only)
  - POST /v1/mailboxes/{mailbox_id}/messages    NO auth (knowing the ID is enough)
  - GET  /v1/mailboxes/{mailbox_id}/messages    REQUIRES bearer token
  - POST /v1/mailboxes/{mailbox_id}/ack         REQUIRES bearer token
"""
import base64
import binascii
import secrets
from datetime import datetime, timedelta, timezone

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import delete, select, update
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app import pow as pow_module
from app.auth import require_mailbox_auth
from app.config import settings
from app.crypto import sha256_hex
from app.database import get_session
from app.models import Mailbox, Message
from app.notifier import notifier
from app.schemas import (
    AcknowledgeRequest,
    CreateMailboxRequest,
    CreateMailboxResponse,
    FetchedMessage,
    FetchMessagesResponse,
    SendMessageRequest,
    SendMessageResponse,
)

router = APIRouter(prefix="/mailboxes", tags=["mailboxes"])


def _now() -> datetime:
    return datetime.now(timezone.utc)


def _encode_bearer_token(token_bytes: bytes) -> str:
    """Base64url, no padding — matches what the client must put in `Authorization: Bearer`."""
    return base64.urlsafe_b64encode(token_bytes).rstrip(b"=").decode("ascii")


@router.post(
    "",
    status_code=status.HTTP_201_CREATED,
    response_model=CreateMailboxResponse,
)
async def create_mailbox(
    body: CreateMailboxRequest,
    session: AsyncSession = Depends(get_session),
) -> CreateMailboxResponse:
    """Create a mailbox. Requires a valid PoW solution; no bearer auth."""
    if len(body.mailbox_id) < settings.mailbox_id_min_length:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="mailbox_id too short",
        )

    if not pow_module.verify(
        mailbox_id=body.mailbox_id,
        nonce=body.proof_of_work,
        difficulty_bits=settings.proof_of_work_difficulty_bits,
    ):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="invalid proof of work",
        )

    token_bytes = secrets.token_bytes(32)
    token_hash = sha256_hex(token_bytes)

    session.add(
        Mailbox(
            mailbox_id=body.mailbox_id,
            bearer_token_hash=token_hash,
            proof_of_work_verified=True,
            last_sequence=0,
        )
    )
    try:
        await session.commit()
    except IntegrityError:
        # PK collision on mailbox_id — caller chose an ID already in use.
        await session.rollback()
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="mailbox already exists",
        )

    return CreateMailboxResponse(
        mailbox_id=body.mailbox_id,
        bearer_token=_encode_bearer_token(token_bytes),
    )


@router.post(
    "/{mailbox_id}/messages",
    status_code=status.HTTP_202_ACCEPTED,
    response_model=SendMessageResponse,
)
async def send_message(
    mailbox_id: str,
    body: SendMessageRequest,
    session: AsyncSession = Depends(get_session),
) -> SendMessageResponse:
    """Drop an opaque blob into `mailbox_id`. NO auth (PROTOCOL.md §5.2)."""
    # Validate the blob is well-formed base64 — this protects clients from
    # accidentally storing garbage that they later cannot decode. We do NOT
    # examine the contents.
    try:
        blob_bytes = base64.b64decode(body.blob, validate=True)
    except (binascii.Error, ValueError) as e:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="invalid base64 blob",
        ) from e

    # Atomic: bump last_sequence and return the new value. Uses a row-level
    # lock; concurrent senders to the same mailbox serialize cleanly.
    seq_result = await session.execute(
        update(Mailbox)
        .where(Mailbox.mailbox_id == mailbox_id)
        .values(last_sequence=Mailbox.last_sequence + 1)
        .returning(Mailbox.last_sequence)
    )
    seq_row = seq_result.first()
    if seq_row is None:
        # No mailbox row -> 404. Rollback the no-op UPDATE so the empty txn
        # doesn't leave the session in an awkward state for the next op.
        await session.rollback()
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="mailbox not found"
        )

    sequence = int(seq_row.last_sequence)
    expires_at = _now() + timedelta(seconds=settings.message_ttl_seconds)

    session.add(
        Message(
            mailbox_id=mailbox_id,
            sequence=sequence,
            blob=blob_bytes,
            expires_at=expires_at,
        )
    )

    # Opportunistic TTL cleanup for THIS mailbox only. Bounded work per
    # write, no scheduler required.
    # TODO(storage-hardening): add a background sweeper for idle mailboxes;
    # the opportunistic path here only fires on active ones.
    await session.execute(
        delete(Message).where(
            Message.mailbox_id == mailbox_id,
            Message.expires_at < _now(),
        )
    )

    await session.commit()

    # Push to any live WS subscribers AFTER the commit, so subscribers never
    # see a sequence number that the database hasn't accepted.
    await notifier.notify(
        mailbox_id,
        {
            "type": "message",
            "sequence": sequence,
            "blob": body.blob,  # already base64 — pass through verbatim
        },
    )

    return SendMessageResponse(sequence=sequence)


@router.get(
    "/{mailbox_id}/messages",
    response_model=FetchMessagesResponse,
)
async def fetch_messages(
    mailbox_id: str,
    after_sequence: int = 0,
    _: str = Depends(require_mailbox_auth),
    session: AsyncSession = Depends(get_session),
) -> FetchMessagesResponse:
    """Return undelivered, unexpired messages with sequence > after_sequence."""
    now = _now()
    rows = (
        await session.execute(
            select(Message)
            .where(
                Message.mailbox_id == mailbox_id,
                Message.sequence > after_sequence,
                Message.expires_at > now,
            )
            .order_by(Message.sequence)
        )
    ).scalars().all()

    return FetchMessagesResponse(
        messages=[
            FetchedMessage(
                sequence=int(row.sequence),
                blob=base64.b64encode(row.blob).decode("ascii"),
                expires=int(row.expires_at.timestamp()),
            )
            for row in rows
        ]
    )


@router.post("/{mailbox_id}/ack", status_code=status.HTTP_200_OK)
async def acknowledge(
    mailbox_id: str,
    body: AcknowledgeRequest,
    _: str = Depends(require_mailbox_auth),
    session: AsyncSession = Depends(get_session),
) -> dict:
    """Delete every message in `mailbox_id` with sequence ≤ `through_sequence`.

    The mailbox's `last_sequence` counter is NOT reset (ADR 007 — monotonic
    forever).
    """
    await session.execute(
        delete(Message).where(
            Message.mailbox_id == mailbox_id,
            Message.sequence <= body.through_sequence,
        )
    )
    await session.commit()
    return {"detail": "acknowledged"}
