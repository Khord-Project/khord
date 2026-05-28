"""
Tests for the media endpoints (ADR 029).

The POST takes multipart/form-data: a binary `file` part plus a
`proof_of_work` form field. The PoW subject is the SHA-256 hex of the
uploaded bytes, so we mine the nonce against that hash (reusing the same
`mine_pow` helper the mailbox tests use, just with a different subject).
"""
import hashlib
from datetime import datetime, timedelta, timezone

import pytest
from sqlalchemy import update

from app.config import settings
from app.models import Media
from tests._helpers import mine_pow

pytestmark = pytest.mark.asyncio


def _pow_for(contents: bytes) -> str:
    """Mine a PoW nonce bound to these exact bytes."""
    subject = hashlib.sha256(contents).hexdigest()
    return mine_pow(subject, settings.proof_of_work_difficulty_bits)


async def _upload(client, contents: bytes, *, proof_of_work: str | None = None):
    if proof_of_work is None:
        proof_of_work = _pow_for(contents)
    return await client.post(
        "/v1/media",
        files={"file": ("blob", contents, "application/octet-stream")},
        data={"proof_of_work": proof_of_work},
    )


async def test_upload_then_download_round_trips(client, db_session):
    contents = b"opaque encrypted image bytes \x00\x01\x02\xff"

    r = await _upload(client, contents)
    assert r.status_code == 201, r.text
    media_id = r.json()["media_id"]
    assert len(media_id) == 32  # secrets.token_hex(16)

    r = await client.get(f"/v1/media/{media_id}")
    assert r.status_code == 200
    assert r.headers["content-type"] == "application/octet-stream"
    assert r.content == contents


async def test_second_download_returns_404(client, db_session):
    contents = b"one-time blob"
    media_id = (await _upload(client, contents)).json()["media_id"]

    first = await client.get(f"/v1/media/{media_id}")
    assert first.status_code == 200

    # Delete-on-read: the blob is consumed by the first fetch.
    second = await client.get(f"/v1/media/{media_id}")
    assert second.status_code == 404


async def test_unknown_media_id_returns_404(client, db_session):
    r = await client.get("/v1/media/deadbeefdeadbeefdeadbeefdeadbeef")
    assert r.status_code == 404


async def test_oversized_upload_rejected(client, db_session):
    too_big = b"\x00" * (settings.media_max_size_bytes + 1)
    # Size is checked before PoW, so the nonce here is irrelevant.
    r = await _upload(client, too_big, proof_of_work="0")
    assert r.status_code == 413


async def test_invalid_pow_rejected(client, db_session):
    contents = b"needs a valid nonce"
    r = await _upload(client, contents, proof_of_work="not-a-valid-nonce")
    assert r.status_code == 400
    assert "proof of work" in r.json()["detail"]


async def test_expired_media_is_not_served(client, db_session):
    contents = b"too old to serve"
    media_id = (await _upload(client, contents)).json()["media_id"]

    # Backdate the row so its TTL has already lapsed.
    await db_session.execute(
        update(Media)
        .where(Media.id == media_id)
        .values(expires_at=datetime.now(timezone.utc) - timedelta(seconds=1))
    )
    await db_session.commit()

    r = await client.get(f"/v1/media/{media_id}")
    assert r.status_code == 404
