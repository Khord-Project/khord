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
from contextlib import ExitStack

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


def test_ws_per_mailbox_subscriber_cap():
    """The (cap+1)-th subscriber to ONE mailbox is refused with 1008.

    The cap is enforced at subscribe time (post-auth), so each held connection
    authenticates first; the extra one authenticates too but is then refused.
    """
    cap = config_module.settings.max_ws_subscribers_per_mailbox
    with TestClient(app) as tc:
        mailbox_id, token = _create_mailbox_sync(tc)
        with ExitStack() as stack:
            for _ in range(cap):
                ws = stack.enter_context(
                    tc.websocket_connect(f"/v1/mailboxes/{mailbox_id}/ws")
                )
                ws.send_json({"type": "auth", "token": token})
                # Let the handler process auth + subscribe before the next one.
                time.sleep(0.05)

            # One past the cap on the same mailbox → subscriber limit.
            with tc.websocket_connect(
                f"/v1/mailboxes/{mailbox_id}/ws"
            ) as extra:
                extra.send_json({"type": "auth", "token": token})
                with pytest.raises(WebSocketDisconnect) as excinfo:
                    extra.receive_text()
                assert excinfo.value.code == 1008


def test_ws_per_ip_connection_cap():
    """The (cap+1)-th concurrent connection from one IP is refused with 1008.

    Spread the held connections across two mailboxes so the per-mailbox cap is
    never the thing that trips; only the per-IP ceiling can reject the extra
    connection (which targets a fresh, empty mailbox).
    """
    ip_cap = config_module.settings.max_ws_connections_per_ip
    mbox_cap = config_module.settings.max_ws_subscribers_per_mailbox
    assert ip_cap % mbox_cap == 0, "test assumes ip_cap is a multiple of mbox_cap"
    n_mailboxes = ip_cap // mbox_cap

    with TestClient(app) as tc:
        mailboxes = [_create_mailbox_sync(tc) for _ in range(n_mailboxes)]
        spare_id, spare_token = _create_mailbox_sync(tc)

        with ExitStack() as stack:
            for mailbox_id, token in mailboxes:
                for _ in range(mbox_cap):
                    ws = stack.enter_context(
                        tc.websocket_connect(f"/v1/mailboxes/{mailbox_id}/ws")
                    )
                    ws.send_json({"type": "auth", "token": token})
                    time.sleep(0.02)

            # ip_cap connections are now held from this IP. One more — to an
            # empty mailbox, so per-mailbox has room — must still be refused.
            with tc.websocket_connect(
                f"/v1/mailboxes/{spare_id}/ws"
            ) as extra:
                with pytest.raises(WebSocketDisconnect) as excinfo:
                    extra.receive_text()
                assert excinfo.value.code == 1008
