async def test_pow_params_advertises_current_difficulty(client):
    r = await client.get("/v1/pow-params")
    assert r.status_code == 200
    body = r.json()
    assert body["difficulty_bits"] >= 0
    assert body["algorithm"] == "sha256-leading-zero-bits"
    assert "mailbox_id" in body["input"]
    assert body["mailbox_id_min_length"] >= 22
