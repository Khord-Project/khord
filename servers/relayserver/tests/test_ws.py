"""
WebSocket tests — sync, using Starlette's TestClient.

httpx's AsyncClient does not speak the WebSocket protocol, so for these
tests we use the sync TestClient that ships with FastAPI/Starlette. Each
test mints its own random mailbox_id so isolation does not require a DB
truncate fixture (which would require an async fixture and pytest-asyncio
cannot bridge that into a sync test).
"""
import base64
import hashlib
import secrets
import time

import pytest
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

from app.main import app
from app import config as config_module

from ._helpers import mine_pow, random_mailbox_id


def _create_mailbox_sync(tc: TestClient) -> tuple[str, str]:
    mailbox_id = random_mailbox_id()
    nonce = mine_pow(
        mailbox_id, config_module.settings.proof_of_work_difficulty_bits
    )
    r = tc.post(
        "/v1/mailboxes",
        json={"mailbox_id": mailbox_id, "proof_of_work": nonce},
    )
    assert r.status_code == 201, r.text
    return mailbox_id, r.json()["bearer_token"]


def test_ws_auth_success_then_push_via_rest_send():
    """REST send → notifier → WS subscriber receives the message."""
    with TestClient(app) as tc:
        mailbox_id, token = _create_mailbox_sync(tc)

        with tc.websocket_connect(
            f"/v1/mailboxes/{mailbox_id}/ws"
        ) as ws:
            ws.send_json({"type": "auth", "token": token})

            blob = base64.b64encode(b"realtime hello").decode("ascii")
            r = tc.post(
                f"/v1/mailboxes/{mailbox_id}/messages",
                json={"blob": blob},
            )
            assert r.status_code == 202

            pushed = ws.receive_json()
            assert pushed == {"type": "message", "sequence": 1, "blob": blob}


def test_ws_auth_failure_with_bad_token_closes_1008():
    with TestClient(app) as tc:
        mailbox_id, _ = _create_mailbox_sync(tc)
        bogus_token = (
            base64.urlsafe_b64encode(secrets.token_bytes(32))
            .rstrip(b"=")
            .decode()
        )
        with tc.websocket_connect(
            f"/v1/mailboxes/{mailbox_id}/ws"
        ) as ws:
            ws.send_json({"type": "auth", "token": bogus_token})
            with pytest.raises(WebSocketDisconnect) as excinfo:
                ws.receive_text()
            assert excinfo.value.code == 1008


def test_ws_auth_failure_with_malformed_first_frame_closes_1008():
    with TestClient(app) as tc:
        mailbox_id, _ = _create_mailbox_sync(tc)
        with tc.websocket_connect(
            f"/v1/mailboxes/{mailbox_id}/ws"
        ) as ws:
            ws.send_text("this is not json")
            with pytest.raises(WebSocketDisconnect) as excinfo:
                ws.receive_text()
            assert excinfo.value.code == 1008


def test_ws_ack_via_websocket_deletes_messages():
    """An ack frame on the WS has the same effect as POST /ack.

    Note: TestClient's WS context manager does not wait for the handler
    task to drain queued client-to-server frames before closing the
    socket. We give the handler a brief grace period so that the ack
    frame is processed before we verify via REST.
    """
    with TestClient(app) as tc:
        mailbox_id, token = _create_mailbox_sync(tc)

        # Pre-load three messages.
        for i in range(3):
            tc.post(
                f"/v1/mailboxes/{mailbox_id}/messages",
                json={
                    "blob": base64.b64encode(f"m{i}".encode()).decode()
                },
            )

        with tc.websocket_connect(
            f"/v1/mailboxes/{mailbox_id}/ws"
        ) as ws:
            ws.send_json({"type": "auth", "token": token})
            ws.send_json({"type": "ack", "through_sequence": 2})
            # Yield to the event loop so the handler can drain the
            # ack frame before the WS context manager closes the socket.
            time.sleep(0.2)

        time.sleep(0.1)

        r = tc.get(
            f"/v1/mailboxes/{mailbox_id}/messages",
            headers={"Authorization": f"Bearer {token}"},
        )
        assert [m["sequence"] for m in r.json()["messages"]] == [3]


def test_ws_auth_timeout_closes(monkeypatch):
    """If the client never sends the first frame, the server closes."""
    # Lower the deadline so the test is fast.
    monkeypatch.setattr(
        config_module.settings, "ws_auth_deadline_seconds", 0.2
    )
    with TestClient(app) as tc:
        mailbox_id, _ = _create_mailbox_sync(tc)
        with tc.websocket_connect(
            f"/v1/mailboxes/{mailbox_id}/ws"
        ) as ws:
            with pytest.raises(WebSocketDisconnect) as excinfo:
                ws.receive_text()
            assert excinfo.value.code == 1008
