"""
Rate-limiting tests (spam/DoS hardening, ADR 012).

Rate limiting is disabled for the rest of the suite (see conftest) and turned
on per-test here via the `rate_limited` fixture, which also resets the shared
in-memory counters so each test starts from zero.

slowapi checks the limit BEFORE the handler body runs, so these assertions
hold regardless of whether the target fingerprint exists — an over-limit
request returns 429 even when the underlying lookup would have 404'd.
"""
import pytest

from ._helpers import Identity, authenticate, make_bundle_payload


@pytest.mark.asyncio
async def test_opk_fetch_per_fingerprint_limit(client, db_session, rate_limited):
    """The 6th fetch of one fingerprint's bundle within the window is 429."""
    identity = Identity(seed=b"\x11" * 32)
    token = await authenticate(client, identity, first_time=True)
    # Upload a bundle with plenty of OPKs so depletion isn't what stops us.
    r = await client.post(
        f"/v1/keys/{identity.fingerprint}/bundle",
        json=make_bundle_payload(identity, n_opks=50),
        headers={"Authorization": f"Bearer {token}"},
    )
    assert r.status_code == 201, r.text

    # Limit is 5/minute per fingerprint.
    for i in range(5):
        r = await client.get(f"/v1/keys/{identity.fingerprint}/bundle")
        assert r.status_code == 200, f"fetch {i} unexpectedly {r.status_code}"

    r = await client.get(f"/v1/keys/{identity.fingerprint}/bundle")
    assert r.status_code == 429, "6th per-fingerprint fetch should be limited"


@pytest.mark.asyncio
async def test_opk_fetch_per_ip_limit(client, rate_limited):
    """Across many DISTINCT fingerprints, the per-IP ceiling (30/min) trips.

    Each fingerprint is hit once so the per-fingerprint limit (5/min) never
    fires first; the only thing that can stop us at 31 is the per-IP cap.
    Non-existent fingerprints still count (slowapi runs before the handler).
    """
    for i in range(30):
        r = await client.get(f"/v1/keys/{'a' * 63}{i % 10}/bundle")
        # 404 (no such fingerprint) is fine — what matters is it's not 429 yet.
        assert r.status_code != 429, f"per-IP limit tripped early at {i}"

    # Use a fresh distinct fingerprint for request #31.
    r = await client.get(f"/v1/keys/{'b' * 64}/bundle")
    assert r.status_code == 429, "31st per-IP fetch should be limited"


@pytest.mark.asyncio
async def test_auth_challenge_limit(client, rate_limited):
    """The challenge endpoint is capped at 10/min per IP."""
    fp = "c" * 64
    for i in range(10):
        r = await client.get(f"/v1/auth/challenge/{fp}")
        assert r.status_code == 200, f"challenge {i} unexpectedly {r.status_code}"

    r = await client.get(f"/v1/auth/challenge/{fp}")
    assert r.status_code == 429, "11th challenge should be limited"


@pytest.mark.asyncio
async def test_general_limit_disabled_does_not_block_normal_use(client, db_session):
    """Sanity: with limiting OFF (default), a burst well over any cap is fine."""
    for _ in range(40):
        r = await client.get("/v1/health")
        assert r.status_code == 200


@pytest.mark.asyncio
async def test_body_size_cap_rejects_oversized_body(client, rate_limited):
    """A body past the ASGI cap is rejected with 413 before the handler runs."""
    # The cap is 12 MiB; send 13 MiB of declared-length junk to /verify.
    oversized = b"x" * (13 * 1024 * 1024)
    r = await client.post(
        "/v1/auth/verify",
        content=oversized,
        headers={"Content-Type": "application/json"},
    )
    assert r.status_code == 413, r.status_code
