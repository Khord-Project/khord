"""
Relay Server WebSocket endpoint (PROTOCOL.md section 5.5).

Auth model:
  - WebSocket /v1/mailboxes/{mailbox_id}/ws
        REQUIRES bearer token, sent as the FIRST message after connect.
        Server closes the connection with 1008 (policy violation) if the first
        message is not a valid bearer token.
"""
from fastapi import APIRouter, WebSocket, status

router = APIRouter(tags=["websocket"])


@router.websocket("/mailboxes/{mailbox_id}/ws")
async def mailbox_ws(websocket: WebSocket, mailbox_id: str) -> None:
    """Real-time mailbox push. Bearer token expected as first frame."""
    await websocket.accept()
    await websocket.close(code=status.WS_1011_INTERNAL_ERROR, reason="not implemented")
