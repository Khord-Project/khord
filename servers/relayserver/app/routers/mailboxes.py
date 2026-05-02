"""
Relay Server mailbox endpoints (PROTOCOL.md sections 5.1-5.4).

Auth model (intentional, see PROTOCOL.md and ADR 011):
  - POST /v1/mailboxes                          NO auth (proof-of-work only)
  - POST /v1/mailboxes/{mailbox_id}/messages    NO auth (knowing the ID is enough)
  - GET  /v1/mailboxes/{mailbox_id}/messages    REQUIRES bearer token
  - POST /v1/mailboxes/{mailbox_id}/ack         REQUIRES bearer token

Sending is unauthenticated by design: the sender must not have to prove
identity to the Relay Server. Receiving is bearer-authenticated because the
mailbox owner is the only party who should drain it.
"""
from fastapi import APIRouter, status
from fastapi.responses import JSONResponse

router = APIRouter(prefix="/mailboxes", tags=["mailboxes"])

_NOT_IMPLEMENTED = JSONResponse(
    status_code=status.HTTP_501_NOT_IMPLEMENTED,
    content={"detail": "not implemented"},
)


@router.post("", status_code=status.HTTP_501_NOT_IMPLEMENTED)
async def create_mailbox() -> JSONResponse:
    """Create a mailbox. NO bearer auth; proof-of-work required (ADR 012)."""
    return _NOT_IMPLEMENTED


@router.post("/{mailbox_id}/messages", status_code=status.HTTP_501_NOT_IMPLEMENTED)
async def send_message(mailbox_id: str) -> JSONResponse:
    """Send to a mailbox. NO auth — knowing the mailbox ID is sufficient."""
    return _NOT_IMPLEMENTED


@router.get("/{mailbox_id}/messages", status_code=status.HTTP_501_NOT_IMPLEMENTED)
async def fetch_messages(mailbox_id: str, after_sequence: int = 0) -> JSONResponse:
    """Fetch messages. REQUIRES bearer token (mailbox owner only)."""
    return _NOT_IMPLEMENTED


@router.post("/{mailbox_id}/ack", status_code=status.HTTP_501_NOT_IMPLEMENTED)
async def acknowledge(mailbox_id: str) -> JSONResponse:
    """Acknowledge delivery. REQUIRES bearer token (mailbox owner only)."""
    return _NOT_IMPLEMENTED
