"""
Concurrency regression test for atomic OPK consumption.

The contract of `GET /v1/keys/{fp}/bundle` is that two concurrent fetches
NEVER return the same one-time pre-key. The implementation relies on
PostgreSQL's `FOR UPDATE SKIP LOCKED`, so this test must run against a real
Postgres — SQLite's MVCC would silently mask the bug.
"""
import asyncio

from ._helpers import Identity, authenticate, make_bundle_payload


async def test_concurrent_fetches_get_distinct_opks(client):
    identity = Identity()
    token = await authenticate(client, identity, first_time=True)
    n_opks = 10
    await client.post(
        f"/v1/keys/{identity.fingerprint}/bundle",
        headers={"Authorization": f"Bearer {token}"},
        json=make_bundle_payload(identity, n_opks=n_opks),
    )

    # Fire MORE concurrent fetches than available OPKs to also exercise the
    # exhaustion path — n_opks of them must succeed with distinct key_ids,
    # the rest must come back without an OPK field.
    n_fetches = n_opks + 3
    results = await asyncio.gather(
        *[
            client.get(f"/v1/keys/{identity.fingerprint}/bundle")
            for _ in range(n_fetches)
        ]
    )

    assert all(r.status_code == 200 for r in results)
    bodies = [r.json() for r in results]

    with_opk = [b for b in bodies if "one_time_pre_key" in b]
    without_opk = [b for b in bodies if "one_time_pre_key" not in b]

    # Exactly n_opks of the responses carry an OPK, the rest do not.
    assert len(with_opk) == n_opks, (
        f"expected {n_opks} OPKs returned, got {len(with_opk)}"
    )
    assert len(without_opk) == n_fetches - n_opks

    # All returned OPK key_ids must be unique — the core property.
    key_ids = sorted(b["one_time_pre_key"]["key_id"] for b in with_opk)
    assert len(set(key_ids)) == n_opks, (
        f"duplicate key_ids returned under concurrency: {key_ids}"
    )
    assert key_ids == list(range(1, n_opks + 1))
