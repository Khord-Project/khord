"""
Auth-flow tests — happy path, plus every failure mode the spec calls out.

The "first-time" vs "subsequent" distinction is whether an `identity_keys`
row already exists. A bundle upload is what writes that row, so tests that
exercise subsequent-auth call upload first.
"""
import base64
from datetime import datetime, timedelta, timezone

from sqlalchemy import update

from app.models import AuthChallenge

from ._helpers import Identity, authenticate, b64, make_bundle_payload


async def test_challenge_returns_random_32_bytes(client):
    """Each request returns 32 fresh random bytes plus a future expiry."""
    identity = Identity()
    r1 = await client.get(f"/v1/auth/challenge/{identity.fingerprint}")
    r2 = await client.get(f"/v1/auth/challenge/{identity.fingerprint}")
    assert r1.status_code == r2.status_code == 200
    c1 = base64.b64decode(r1.json()["challenge"])
    c2 = base64.b64decode(r2.json()["challenge"])
    assert len(c1) == 32 and len(c2) == 32
    assert c1 != c2
    assert r1.json()["expires"] > 0


async def test_first_time_auth_happy_path(client):
    identity = Identity()
    token = await authenticate(client, identity, first_time=True)
    assert token  # non-empty, opaque
    assert token.count(".") == 1  # payload.signature shape


async def test_subsequent_auth_does_not_need_identity_key(client):
    """After the bundle upload, the identity_key field in verify is optional."""
    identity = Identity()
    # First-time auth + bundle upload to register the key.
    token = await authenticate(client, identity, first_time=True)
    r = await client.post(
        f"/v1/keys/{identity.fingerprint}/bundle",
        headers={"Authorization": f"Bearer {token}"},
        json=make_bundle_payload(identity, n_opks=2),
    )
    assert r.status_code == 201, r.text

    # Second auth without identity_key in the body — must succeed.
    second_token = await authenticate(client, identity, first_time=False)
    assert second_token


async def test_first_time_auth_missing_identity_key_fails(client):
    """No stored row + no identity_key in body → 401."""
    identity = Identity()
    r = await client.get(f"/v1/auth/challenge/{identity.fingerprint}")
    challenge_b64 = r.json()["challenge"]
    sig = identity.sign(base64.b64decode(challenge_b64))
    r = await client.post(
        "/v1/auth/verify",
        json={
            "fingerprint": identity.fingerprint,
            "challenge": challenge_b64,
            "signature": b64(sig),
        },
    )
    assert r.status_code == 401


async def test_first_time_auth_wrong_fingerprint_fails(client):
    """Body identity_key doesn't hash to the path fingerprint → 401."""
    a = Identity()
    b = Identity()  # different key
    r = await client.get(f"/v1/auth/challenge/{a.fingerprint}")
    challenge_b64 = r.json()["challenge"]
    sig = a.sign(base64.b64decode(challenge_b64))
    r = await client.post(
        "/v1/auth/verify",
        json={
            "fingerprint": a.fingerprint,
            "challenge": challenge_b64,
            "signature": b64(sig),
            "identity_key": b.identity_key_b64(),  # wrong key
        },
    )
    assert r.status_code == 401


async def test_subsequent_auth_with_mismatching_identity_key_fails(client):
    """If body identity_key is provided but doesn't match stored, 401."""
    a = Identity()
    b = Identity()
    token = await authenticate(client, a, first_time=True)
    r = await client.post(
        f"/v1/keys/{a.fingerprint}/bundle",
        headers={"Authorization": f"Bearer {token}"},
        json=make_bundle_payload(a),
    )
    assert r.status_code == 201

    # Now try to verify with a's signature but b's identity_key in body.
    r = await client.get(f"/v1/auth/challenge/{a.fingerprint}")
    challenge_b64 = r.json()["challenge"]
    sig = a.sign(base64.b64decode(challenge_b64))
    r = await client.post(
        "/v1/auth/verify",
        json={
            "fingerprint": a.fingerprint,
            "challenge": challenge_b64,
            "signature": b64(sig),
            "identity_key": b.identity_key_b64(),
        },
    )
    assert r.status_code == 401


async def test_wrong_signature_fails(client):
    a = Identity()
    b = Identity()
    r = await client.get(f"/v1/auth/challenge/{a.fingerprint}")
    challenge_b64 = r.json()["challenge"]
    # Sign with b's key but claim to be a.
    sig = b.sign(base64.b64decode(challenge_b64))
    r = await client.post(
        "/v1/auth/verify",
        json={
            "fingerprint": a.fingerprint,
            "challenge": challenge_b64,
            "signature": b64(sig),
            "identity_key": a.identity_key_b64(),
        },
    )
    assert r.status_code == 401


async def test_expired_challenge_fails(client, db_session):
    """Manually backdate the challenge and confirm verify rejects it."""
    identity = Identity()
    r = await client.get(f"/v1/auth/challenge/{identity.fingerprint}")
    challenge_b64 = r.json()["challenge"]

    # Force the row to be expired.
    await db_session.execute(
        update(AuthChallenge)
        .where(AuthChallenge.fingerprint == identity.fingerprint)
        .values(expires_at=datetime.now(timezone.utc) - timedelta(seconds=1))
    )
    await db_session.commit()

    sig = identity.sign(base64.b64decode(challenge_b64))
    r = await client.post(
        "/v1/auth/verify",
        json={
            "fingerprint": identity.fingerprint,
            "challenge": challenge_b64,
            "signature": b64(sig),
            "identity_key": identity.identity_key_b64(),
        },
    )
    assert r.status_code == 401


async def test_reused_challenge_fails(client):
    """A challenge is single-use: verifying twice with the same one fails."""
    identity = Identity()
    r = await client.get(f"/v1/auth/challenge/{identity.fingerprint}")
    challenge_b64 = r.json()["challenge"]
    sig = identity.sign(base64.b64decode(challenge_b64))

    body = {
        "fingerprint": identity.fingerprint,
        "challenge": challenge_b64,
        "signature": b64(sig),
        "identity_key": identity.identity_key_b64(),
    }
    r1 = await client.post("/v1/auth/verify", json=body)
    r2 = await client.post("/v1/auth/verify", json=body)
    assert r1.status_code == 200
    assert r2.status_code == 401


async def test_invalid_fingerprint_format_fails(client):
    """Garbage fingerprint → still 401 (no enumeration of valid formats)."""
    r = await client.get("/v1/auth/challenge/not-a-real-fingerprint")
    assert r.status_code == 200  # challenge issuance is fingerprint-agnostic
    challenge_b64 = r.json()["challenge"]
    r = await client.post(
        "/v1/auth/verify",
        json={
            "fingerprint": "not-a-real-fingerprint",
            "challenge": challenge_b64,
            "signature": b64(b"\x00" * 64),
            "identity_key": b64(b"\x00" * 32),
        },
    )
    assert r.status_code == 401
