"""
Pre-key bundle endpoint tests:
  * upload, fetch, OPK consumption
  * exhaustion handling (field omitted)
  * replenishment increases count
  * fingerprint authorization
"""
from ._helpers import Identity, authenticate, make_bundle_payload


async def test_upload_then_fetch_bundle_round_trips(client):
    identity = Identity()
    token = await authenticate(client, identity, first_time=True)
    payload = make_bundle_payload(identity, n_opks=3)

    r = await client.post(
        f"/v1/keys/{identity.fingerprint}/bundle",
        headers={"Authorization": f"Bearer {token}"},
        json=payload,
    )
    assert r.status_code == 201

    r = await client.get(f"/v1/keys/{identity.fingerprint}/bundle")
    assert r.status_code == 200
    body = r.json()
    assert body["identity_key"] == payload["identity_key"]
    assert body["signed_pre_key"]["key_id"] == payload["signed_pre_key"]["key_id"]
    assert (
        body["signed_pre_key"]["public_key"]
        == payload["signed_pre_key"]["public_key"]
    )
    assert body["one_time_pre_key"]["key_id"] == 1  # FIFO consumption


async def test_fetch_consumes_one_time_pre_key(client):
    """After a fetch, the prekey-count drops by exactly 1."""
    identity = Identity()
    token = await authenticate(client, identity, first_time=True)
    await client.post(
        f"/v1/keys/{identity.fingerprint}/bundle",
        headers={"Authorization": f"Bearer {token}"},
        json=make_bundle_payload(identity, n_opks=3),
    )

    r = await client.get(
        f"/v1/keys/{identity.fingerprint}/prekey-count",
        headers={"Authorization": f"Bearer {token}"},
    )
    assert r.json()["count"] == 3

    await client.get(f"/v1/keys/{identity.fingerprint}/bundle")

    r = await client.get(
        f"/v1/keys/{identity.fingerprint}/prekey-count",
        headers={"Authorization": f"Bearer {token}"},
    )
    assert r.json()["count"] == 2


async def test_fetch_omits_opk_field_when_exhausted(client):
    """PROTOCOL.md §4.2: when no OPK remains, the field is OMITTED, not null."""
    identity = Identity()
    token = await authenticate(client, identity, first_time=True)
    await client.post(
        f"/v1/keys/{identity.fingerprint}/bundle",
        headers={"Authorization": f"Bearer {token}"},
        json=make_bundle_payload(identity, n_opks=1),
    )

    # Consume the only OPK.
    r1 = await client.get(f"/v1/keys/{identity.fingerprint}/bundle")
    assert "one_time_pre_key" in r1.json()

    # Second fetch: OPK exhausted → field omitted.
    r2 = await client.get(f"/v1/keys/{identity.fingerprint}/bundle")
    assert r2.status_code == 200
    body = r2.json()
    assert "one_time_pre_key" not in body
    # Identity + signed pre-key still served.
    assert body["identity_key"]
    assert body["signed_pre_key"]


async def test_fetch_unknown_fingerprint_404(client):
    bogus = "0" * 64
    r = await client.get(f"/v1/keys/{bogus}/bundle")
    assert r.status_code == 404


async def test_upload_requires_bearer_token(client):
    identity = Identity()
    r = await client.post(
        f"/v1/keys/{identity.fingerprint}/bundle",
        json=make_bundle_payload(identity),
    )
    assert r.status_code == 401


async def test_upload_with_token_for_different_fingerprint_403(client):
    """A's token cannot upload to B's fingerprint."""
    a = Identity()
    b = Identity()
    a_token = await authenticate(client, a, first_time=True)
    r = await client.post(
        f"/v1/keys/{b.fingerprint}/bundle",  # B's URL …
        headers={"Authorization": f"Bearer {a_token}"},  # … A's token
        json=make_bundle_payload(b),
    )
    assert r.status_code == 403


async def test_upload_with_path_fingerprint_not_matching_identity_key_400(client):
    """SHA-256(identity_key) must equal the path fingerprint."""
    a = Identity()
    b = Identity()
    a_token = await authenticate(client, a, first_time=True)
    payload = make_bundle_payload(a)
    payload["identity_key"] = b.identity_key_b64()  # mismatch with path
    r = await client.post(
        f"/v1/keys/{a.fingerprint}/bundle",
        headers={"Authorization": f"Bearer {a_token}"},
        json=payload,
    )
    assert r.status_code == 400


async def test_replenish_increases_count(client):
    identity = Identity()
    token = await authenticate(client, identity, first_time=True)
    await client.post(
        f"/v1/keys/{identity.fingerprint}/bundle",
        headers={"Authorization": f"Bearer {token}"},
        json=make_bundle_payload(identity, n_opks=2),
    )

    r = await client.get(
        f"/v1/keys/{identity.fingerprint}/prekey-count",
        headers={"Authorization": f"Bearer {token}"},
    )
    assert r.json()["count"] == 2

    from ._helpers import b64

    r = await client.post(
        f"/v1/keys/{identity.fingerprint}/prekeys",
        headers={"Authorization": f"Bearer {token}"},
        json={
            "one_time_pre_keys": [
                {"key_id": i, "public_key": b64(bytes([i]) * 32)}
                for i in range(100, 104)
            ]
        },
    )
    assert r.status_code == 201

    r = await client.get(
        f"/v1/keys/{identity.fingerprint}/prekey-count",
        headers={"Authorization": f"Bearer {token}"},
    )
    assert r.json()["count"] == 6


async def test_prekey_count_requires_token(client):
    identity = Identity()
    r = await client.get(f"/v1/keys/{identity.fingerprint}/prekey-count")
    assert r.status_code == 401


async def test_replenish_for_unknown_fingerprint_404(client):
    """Cannot replenish into a fingerprint that has no bundle yet."""
    # We need a valid token for this fingerprint without a registered bundle —
    # i.e. the auth succeeded (first-time) but no upload happened yet.
    identity = Identity()
    token = await authenticate(client, identity, first_time=True)
    r = await client.post(
        f"/v1/keys/{identity.fingerprint}/prekeys",
        headers={"Authorization": f"Bearer {token}"},
        json={"one_time_pre_keys": []},
    )
    assert r.status_code == 404
