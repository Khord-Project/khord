"""
Relay Server WebSocket endpoint (PROTOCOL.md §5.5).

Lifecycle:
  1. Client connects to /v1/mailboxes/{mailbox_id}/ws.
  2. Server accepts the upgrade.
  3. Server waits up to `settings.ws_auth_deadline_seconds` for the first
     text frame, which MUST be: {"type": "auth", "token": "<bearer>"}
  4. On auth success: server registers the connection with the in-memory
     MailboxNotifier and pushes new messages as they arrive on this mailbox.
  5. Client may send {"type": "ack", "through_sequence": N} at any time.
  6. On any auth/protocol violation: close with 1008. On internal error: 1011.

Single-process constraint: the in-memory MailboxNotifier is process-local.
Run with `--workers 1`. For multi-worker production, swap the notifier
implementation for one backed by Postgres LISTEN/NOTIFY.

The single per-connection AsyncSession is acquired via Depends(get_session)
and reused for the lifetime of the connection — both for the auth check
and for subsequent ack frames. This is acceptable because the SQLAlchemy
session does not hold a database connection idle between transactions.
"""
import asyncio
import json
import logging

from fastapi import APIRouter, Depends, WebSocket, WebSocketDisconnect
from sqlalchemy import delete
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import verify_mailbox_token
from app.config import settings
from app.database import get_session
from app.models import Message
from app.notifier import notifier

logger = logging.getLogger(__name__)

router = APIRouter(tags=["websocket"])

_CLOSE_AUTH_FAILED = 1008  # policy violation
_CLOSE_CONN_LIMIT = 1008  # policy violation — connection cap reached
_CLOSE_INTERNAL = 1011


@router.websocket("/mailboxes/{mailbox_id}/ws")
async def mailbox_ws(
    websocket: WebSocket,
    mailbox_id: str,
    session: AsyncSession = Depends(get_session),
) -> None:
    await websocket.accept()

    # Per-IP connection cap (spam/DoS hardening) — enforced right after accept,
    # BEFORE auth, so one IP can't tie up unbounded sockets pre-authentication.
    client_ip = websocket.client.host if websocket.client else "unknown"
    if not notifier.try_acquire_ip(client_ip):
        await websocket.close(code=_CLOSE_CONN_LIMIT, reason="connection limit")
        return

    # Everything past the IP reservation must release it on every exit path.
    try:
        # 1. Auth handshake — single text frame, deadline-bounded.
        try:
            first_frame = await asyncio.wait_for(
                websocket.receive_text(),
                timeout=settings.ws_auth_deadline_seconds,
            )
        except asyncio.TimeoutError:
            await websocket.close(code=_CLOSE_AUTH_FAILED, reason="auth timeout")
            return
        except WebSocketDisconnect:
            return

        try:
            message = json.loads(first_frame)
            if message.get("type") != "auth":
                raise ValueError("first frame must be type=auth")
            token = message["token"]
            if not isinstance(token, str):
                raise ValueError("token must be a string")
        except (json.JSONDecodeError, ValueError, KeyError):
            await websocket.close(code=_CLOSE_AUTH_FAILED, reason="auth required")
            return

        if not await verify_mailbox_token(mailbox_id, token, session):
            await websocket.close(code=_CLOSE_AUTH_FAILED, reason="auth required")
            return

        # 2. Steady state — bridge notifier events into this socket while
        # concurrently reading client frames (acks).
        async def _push(payload: dict) -> None:
            # Raises on slow / closed sockets so the notifier can drop us.
            await websocket.send_text(json.dumps(payload))

        # Per-mailbox subscriber cap (spam/DoS hardening). Refuse to register
        # a (cap+1)-th live subscriber on this mailbox.
        if not notifier.try_subscribe(mailbox_id, _push):
            await websocket.close(
                code=_CLOSE_CONN_LIMIT, reason="mailbox subscriber limit"
            )
            return

        try:
            while True:
                try:
                    text = await websocket.receive_text()
                except WebSocketDisconnect:
                    return

                try:
                    event = json.loads(text)
                    event_type = event.get("type")
                    if event_type == "ack":
                        seq = int(event["through_sequence"])
                        await session.execute(
                            delete(Message).where(
                                Message.mailbox_id == mailbox_id,
                                Message.sequence <= seq,
                            )
                        )
                        await session.commit()
                    # Unknown types are ignored silently — forward compatibility.
                except (json.JSONDecodeError, KeyError, ValueError, TypeError):
                    # Malformed client frames are ignored (no logging — ADR 016).
                    continue
        except Exception:  # noqa: BLE001 — hard envelope around handler
            try:
                await websocket.close(code=_CLOSE_INTERNAL)
            except Exception:
                pass
        finally:
            notifier.unsubscribe(mailbox_id, _push)
    finally:
        notifier.release_ip(client_ip)
