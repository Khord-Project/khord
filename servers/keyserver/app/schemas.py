"""Pydantic request/response schemas — wire-format definitions only.

Mirrors PROTOCOL.md sections 4.1-4.5 and 4.4. All public-key and signature
fields are base64-encoded (standard, NOT urlsafe). The fingerprint is hex.
"""
from typing import Optional

from pydantic import BaseModel, Field


class ChallengeResponse(BaseModel):
    challenge: str = Field(description="base64-encoded random 32 bytes")
    expires: int = Field(description="unix timestamp (seconds)")


class VerifyRequest(BaseModel):
    fingerprint: str
    challenge: str = Field(description="base64-encoded challenge bytes")
    signature: str = Field(description="base64-encoded Ed25519 signature")
    # Optional — REQUIRED for first-time auth (no stored identity_keys row),
    # IGNORED on subsequent auths. If provided when a row already exists, must
    # match the stored key (defense-in-depth). See PROTOCOL.md §4.4.
    identity_key: Optional[str] = Field(
        default=None,
        description="base64-encoded Ed25519 public key (first-time auth only)",
    )


class VerifyResponse(BaseModel):
    token: str


class SignedPreKey(BaseModel):
    key_id: int
    public_key: str
    signature: str


class OneTimePreKey(BaseModel):
    key_id: int
    public_key: str


class BundleUploadRequest(BaseModel):
    identity_key: str
    signed_pre_key: SignedPreKey
    one_time_pre_keys: list[OneTimePreKey]


class BundleFetchResponse(BaseModel):
    identity_key: str
    signed_pre_key: SignedPreKey
    # Field is OMITTED entirely (not null) when no OPK remains — see
    # PROTOCOL.md §4.2 and the X3DH spec on reduced-OPK sessions.
    one_time_pre_key: Optional[OneTimePreKey] = None


class ReplenishRequest(BaseModel):
    one_time_pre_keys: list[OneTimePreKey]


class PreKeyCountResponse(BaseModel):
    count: int
