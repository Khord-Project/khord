"""
REST endpoints — mailbox creation, send, fetch, ack, auth, sequencing, TTL.
"""
import base64
from datetime import datetime, timedelta, timezone

from sqlalchemy import update

from app.config import settings
from app.models import Message

from ._helpers import b64encode_str, create_mailbox, mine_pow, random_mailbox_id


async def test_create_mailbox_returns_token(client):
    mailbox_id, token = await create_mailbox(client)
    assert mailbox_id
    # 32 bytes base64url, no padding → 43 chars.
    assert len(token) == 43


async def test_create_mailbox_short_id_rejected(client, db_session):
    bogus = "tooShort"  # 8 chars, well under the 22-char floor
    r = await client.post(
        "/v1/mailboxes",
        json={"mailbox_id": bogus, "proof_of_work": mine_pow(bogus, 8)},
    )
    assert r.status_code == 400


async def test_create_mailbox_duplicate_409(client, db_session):
    mailbox_id, _ = await create_mailbox(client)
    r = await client.post(
        "/v1/mailboxes",
        json={
            "mailbox_id": mailbox_id,
            "proof_of_work": mine_pow(
                mailbox_id, settings.proof_of_work_difficulty_bits
            ),
        },
    )
    assert r.status_code == 409


async def test_create_mailbox_bad_pow_rejected(client, db_session):
    mailbox_id = random_mailbox_id()
    r = await client.post(
        "/v1/mailboxes",
        json={"mailbox_id": mailbox_id, "proof_of_work": "0"},
    )
    assert r.status_code == 400


async def test_create_mailbox_non_decimal_pow_rejected(client, db_session):
    mailbox_id = random_mailbox_id()
    r = await client.post(
        "/v1/mailboxes",
        json={"mailbox_id": mailbox_id, "proof_of_work": "0xff"},
    )
    assert r.status_code == 400


async def test_send_to_unknown_mailbox_404(client, db_session):
    r = await client.post(
        f"/v1/mailboxes/{random_mailbox_id()}/messages",
        json={"blob": b64encode_str(b"hello")},
    )
    assert r.status_code == 404


async def test_send_then_fetch_round_trips(client, db_session):
    mailbox_id, token = await create_mailbox(client)
    blob = b64encode_str(b"opaque ciphertext")

    r = await client.post(
        f"/v1/mailboxes/{mailbox_id}/messages",
        json={"blob": blob},
    )
    assert r.status_code == 202
    assert r.json()["sequence"] == 1

    r = await client.get(
        f"/v1/mailboxes/{mailbox_id}/messages",
        headers={"Authorization": f"Bearer {token}"},
    )
    assert r.status_code == 200
    msgs = r.json()["messages"]
    assert len(msgs) == 1
    assert msgs[0]["sequence"] == 1
    assert msgs[0]["blob"] == blob


async def test_send_no_auth_required(client, db_session):
    mailbox_id, _ = await create_mailbox(client)
    r = await client.post(
        f"/v1/mailboxes/{mailbox_id}/messages",
        json={"blob": b64encode_str(b"x")},
    )
    assert r.status_code == 202


async def test_fetch_requires_token(client, db_session):
    mailbox_id, _ = await create_mailbox(client)
    r = await client.get(f"/v1/mailboxes/{mailbox_id}/messages")
    assert r.status_code == 401


async def test_fetch_with_wrong_token_401(client, db_session):
    mailbox_id, _ = await create_mailbox(client)
    other_token = b64encode_str(b"\x00" * 32).replace("+", "-").replace("/", "_").rstrip("=")
    r = await client.get(
        f"/v1/mailboxes/{mailbox_id}/messages",
        headers={"Authorization": f"Bearer {other_token}"},
    )
    assert r.status_code == 401


async def test_fetch_with_token_for_different_mailbox_401(client, db_session):
    a_id, a_token = await create_mailbox(client)
    b_id, _ = await create_mailbox(client)
    r = await client.get(
        f"/v1/mailboxes/{b_id}/messages",
        headers={"Authorization": f"Bearer {a_token}"},  # A's token, B's mbox
    )
    assert r.status_code == 401


async def test_messages_returned_in_sequence_order(client, db_session):
    mailbox_id, token = await create_mailbox(client)
    for i in range(5):
        await client.post(
            f"/v1/mailboxes/{mailbox_id}/messages",
            json={"blob": b64encode_str(f"msg-{i}".encode())},
        )

    r = await client.get(
        f"/v1/mailboxes/{mailbox_id}/messages",
        headers={"Authorization": f"Bearer {token}"},
    )
    seqs = [m["sequence"] for m in r.json()["messages"]]
    assert seqs == [1, 2, 3, 4, 5]


async def test_after_sequence_filters(client, db_session):
    mailbox_id, token = await create_mailbox(client)
    for i in range(5):
        await client.post(
            f"/v1/mailboxes/{mailbox_id}/messages",
            json={"blob": b64encode_str(f"msg-{i}".encode())},
        )

    r = await client.get(
        f"/v1/mailboxes/{mailbox_id}/messages?after_sequence=3",
        headers={"Authorization": f"Bearer {token}"},
    )
    seqs = [m["sequence"] for m in r.json()["messages"]]
    assert seqs == [4, 5]


async def test_ack_deletes_acked_messages(client, db_session):
    mailbox_id, token = await create_mailbox(client)
    for i in range(5):
        await client.post(
            f"/v1/mailboxes/{mailbox_id}/messages",
            json={"blob": b64encode_str(f"msg-{i}".encode())},
        )

    r = await client.post(
        f"/v1/mailboxes/{mailbox_id}/ack",
        headers={"Authorization": f"Bearer {token}"},
        json={"through_sequence": 3},
    )
    assert r.status_code == 200

    r = await client.get(
        f"/v1/mailboxes/{mailbox_id}/messages",
        headers={"Authorization": f"Bearer {token}"},
    )
    seqs = [m["sequence"] for m in r.json()["messages"]]
    assert seqs == [4, 5]


async def test_ack_does_not_reset_sequence_counter(client, db_session):
    """ADR 007: counter is monotonic forever — ack never lowers it."""
    mailbox_id, token = await create_mailbox(client)
    for _ in range(5):
        await client.post(
            f"/v1/mailboxes/{mailbox_id}/messages",
            json={"blob": b64encode_str(b"x")},
        )

    # Ack everything.
    await client.post(
        f"/v1/mailboxes/{mailbox_id}/ack",
        headers={"Authorization": f"Bearer {token}"},
        json={"through_sequence": 5},
    )

    # Next message must be sequence 6, not 1.
    r = await client.post(
        f"/v1/mailboxes/{mailbox_id}/messages",
        json={"blob": b64encode_str(b"after-ack")},
    )
    assert r.status_code == 202
    assert r.json()["sequence"] == 6


async def test_expired_messages_are_filtered_on_fetch(client, db_session):
    mailbox_id, token = await create_mailbox(client)
    await client.post(
        f"/v1/mailboxes/{mailbox_id}/messages",
        json={"blob": b64encode_str(b"too-old")},
    )

    # Backdate the row so it's already expired.
    await db_session.execute(
        update(Message)
        .where(Message.mailbox_id == mailbox_id)
        .values(expires_at=datetime.now(timezone.utc) - timedelta(seconds=1))
    )
    await db_session.commit()

    r = await client.get(
        f"/v1/mailboxes/{mailbox_id}/messages",
        headers={"Authorization": f"Bearer {token}"},
    )
    assert r.status_code == 200
    assert r.json()["messages"] == []


async def test_send_opportunistically_cleans_up_expired(client, db_session):
    """Posting a new message also clears expired rows in that mailbox."""
    from sqlalchemy import select, func
    mailbox_id, token = await create_mailbox(client)
    # Insert an expired row.
    await client.post(
        f"/v1/mailboxes/{mailbox_id}/messages",
        json={"blob": b64encode_str(b"expired-soon")},
    )
    await db_session.execute(
        update(Message)
        .where(Message.mailbox_id == mailbox_id)
        .values(expires_at=datetime.now(timezone.utc) - timedelta(seconds=1))
    )
    await db_session.commit()

    # Post a fresh message — should also delete the expired one.
    await client.post(
        f"/v1/mailboxes/{mailbox_id}/messages",
        json={"blob": b64encode_str(b"fresh")},
    )

    # Only the fresh row remains.
    count = (
        await db_session.execute(
            select(func.count(Message.id)).where(
                Message.mailbox_id == mailbox_id
            )
        )
    ).scalar_one()
    assert count == 1


async def test_send_invalid_base64_400(client, db_session):
    mailbox_id, _ = await create_mailbox(client)
    r = await client.post(
        f"/v1/mailboxes/{mailbox_id}/messages",
        json={"blob": "not_base64!!!"},
    )
    assert r.status_code == 400


async def test_send_oversized_message_413(client, db_session):
    """A text blob past message_max_size_bytes is rejected with 413."""
    mailbox_id, _ = await create_mailbox(client)
    # One byte over the cap, base64-encoded (still far under the 12 MiB body
    # cap, so it reaches the handler's own size check rather than the ASGI one).
    oversized = b"x" * (settings.message_max_size_bytes + 1)
    r = await client.post(
        f"/v1/mailboxes/{mailbox_id}/messages",
        json={"blob": b64encode_str(oversized)},
    )
    assert r.status_code == 413


async def test_send_at_size_cap_boundary_accepted(client, db_session):
    """A blob exactly at the cap is still accepted (boundary is inclusive)."""
    mailbox_id, _ = await create_mailbox(client)
    at_cap = b"y" * settings.message_max_size_bytes
    r = await client.post(
        f"/v1/mailboxes/{mailbox_id}/messages",
        json={"blob": b64encode_str(at_cap)},
    )
    assert r.status_code == 202


async def test_mailbox_backlog_cap_429(client, db_session):
    """Once a mailbox holds mailbox_max_messages live messages, the next send
    is refused with 429 — one sender can't fill the relay's storage."""
    mailbox_id, _ = await create_mailbox(client)

    # Seed the cap directly (much faster than POSTing them all over HTTP).
    future = datetime.now(timezone.utc) + timedelta(hours=1)
    for seq in range(1, settings.mailbox_max_messages + 1):
        db_session.add(
            Message(
                mailbox_id=mailbox_id,
                sequence=seq,
                blob=b"x",
                expires_at=future,
            )
        )
    await db_session.commit()

    # The mailbox now holds exactly the cap → the next send is rejected.
    r = await client.post(
        f"/v1/mailboxes/{mailbox_id}/messages",
        json={"blob": b64encode_str(b"one too many")},
    )
    assert r.status_code == 429


async def test_mailbox_backlog_cap_excludes_expired(client, db_session):
    """Expired messages don't count toward the backlog cap — they're swept on
    send, so a mailbox full of stale rows still accepts a fresh message."""
    mailbox_id, _ = await create_mailbox(client)

    past = datetime.now(timezone.utc) - timedelta(seconds=1)
    for seq in range(1, settings.mailbox_max_messages + 1):
        db_session.add(
            Message(
                mailbox_id=mailbox_id,
                sequence=seq,
                blob=b"x",
                expires_at=past,  # already expired
            )
        )
    await db_session.commit()

    # All seeded rows are expired → cleanup drops them, count is 0, send is OK.
    r = await client.post(
        f"/v1/mailboxes/{mailbox_id}/messages",
        json={"blob": b64encode_str(b"fresh after expiry")},
    )
    assert r.status_code == 202
