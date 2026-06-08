"""
Rate-limiting + body-size tests (spam/DoS hardening, ADR 012).

Rate limiting is disabled for the rest of the suite (see conftest) and turned
on per-test here via the `rate_limited` fixture, which also resets the shared
in-memory counters so each test starts from zero.

slowapi checks the limit BEFORE the handler body runs, so the create-mailbox
assertions hold even though the requests carry an invalid proof-of-work: the
limit trips before the PoW (or any other) handler logic is reached.
"""
import pytest

from ._helpers import random_mailbox_id


@pytest.mark.asyncio
async def test_create_mailbox_rate_limit(client, db_session, rate_limited):
    """Mailbox creation (registration) is capped at 10/min per IP."""
    # Bad-PoW bodies are fine: the limiter runs first, so each still counts.
    for i in range(10):
        r = await client.post(
            "/v1/mailboxes",
            json={"mailbox_id": random_mailbox_id(), "proof_of_work": "0"},
        )
        assert r.status_code != 429, f"create {i} limited too early ({r.status_code})"

    r = await client.post(
        "/v1/mailboxes",
        json={"mailbox_id": random_mailbox_id(), "proof_of_work": "0"},
    )
    assert r.status_code == 429, "11th mailbox creation should be limited"


@pytest.mark.asyncio
async def test_general_per_ip_limit(client, rate_limited):
    """Every endpoint inherits the blanket 100/min per-IP ceiling."""
    for i in range(100):
        r = await client.get("/v1/health")
        assert r.status_code == 200, f"health {i} unexpectedly {r.status_code}"

    r = await client.get("/v1/health")
    assert r.status_code == 429, "101st request should hit the general limit"


@pytest.mark.asyncio
async def test_body_size_cap_declared_length(client):
    """A body whose Content-Length exceeds the cap is rejected with 413."""
    oversized = b"z" * (13 * 1024 * 1024)  # 13 MiB > 12 MiB cap
    r = await client.post(
        "/v1/mailboxes",
        content=oversized,
        headers={"Content-Type": "application/json"},
    )
    assert r.status_code == 413, r.status_code


@pytest.mark.asyncio
async def test_body_size_cap_chunked_no_content_length(client):
    """A chunked body (no Content-Length) is still capped via the streaming
    guard — the middleware buffers up to the cap and rejects on overflow."""

    async def chunks():
        # 13 x 1 MiB chunks, sent without a Content-Length header.
        for _ in range(13):
            yield b"z" * (1024 * 1024)

    r = await client.post(
        "/v1/mailboxes",
        content=chunks(),
        headers={"Content-Type": "application/json"},
    )
    assert r.status_code == 413, r.status_code


@pytest.mark.asyncio
async def test_normal_body_under_cap_passes(client, db_session):
    """A normal small request is unaffected by the body-size middleware."""
    r = await client.post(
        "/v1/mailboxes",
        json={"mailbox_id": random_mailbox_id(), "proof_of_work": "0"},
    )
    # Reaches the handler → rejected on PoW (400), not on body size.
    assert r.status_code == 400
