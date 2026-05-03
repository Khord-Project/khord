"""Shared test helpers — Ed25519 keypair generation, fingerprint, signing."""
import base64
import hashlib

from nacl.signing import SigningKey


def b64(data: bytes) -> str:
    return base64.b64encode(data).decode("ascii")


class Identity:
    """A test identity with deterministic keypair, fingerprint, and signing."""

    def __init__(self, seed: bytes | None = None) -> None:
        self.signing_key = (
            SigningKey(seed) if seed is not None else SigningKey.generate()
        )
        self.verify_key_bytes = bytes(self.signing_key.verify_key)
        self.fingerprint = hashlib.sha256(self.verify_key_bytes).hexdigest()

    def sign(self, message: bytes) -> bytes:
        return self.signing_key.sign(message).signature

    def identity_key_b64(self) -> str:
        return b64(self.verify_key_bytes)


async def authenticate(client, identity: Identity, *, first_time: bool) -> str:
    """Drive a full challenge-response and return the session token."""
    r = await client.get(f"/v1/auth/challenge/{identity.fingerprint}")
    assert r.status_code == 200, r.text
    challenge_b64 = r.json()["challenge"]
    challenge_bytes = base64.b64decode(challenge_b64)
    signature = identity.sign(challenge_bytes)

    body: dict = {
        "fingerprint": identity.fingerprint,
        "challenge": challenge_b64,
        "signature": b64(signature),
    }
    if first_time:
        body["identity_key"] = identity.identity_key_b64()

    r = await client.post("/v1/auth/verify", json=body)
    assert r.status_code == 200, r.text
    return r.json()["token"]


def make_bundle_payload(identity: Identity, *, n_opks: int = 5) -> dict:
    """Build a syntactically-correct bundle upload body for `identity`."""
    # Signed pre-key: a fresh X25519-shaped 32-byte public (we don't need it
    # to actually be a valid X25519 point for the keyserver — it stores
    # opaque bytes — but signing is real Ed25519 over the public bytes).
    spk_pub = b"\x01" * 32
    spk_id = 1
    spk_sig = identity.sign(spk_pub)
    return {
        "identity_key": identity.identity_key_b64(),
        "signed_pre_key": {
            "key_id": spk_id,
            "public_key": b64(spk_pub),
            "signature": b64(spk_sig),
        },
        "one_time_pre_keys": [
            {"key_id": i, "public_key": b64(bytes([i]) * 32)}
            for i in range(1, n_opks + 1)
        ],
    }
