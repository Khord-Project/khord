"""Pydantic request/response schemas for the Relay Server."""
from pydantic import BaseModel, Field


class CreateMailboxRequest(BaseModel):
    mailbox_id: str = Field(
        description="Client-generated random ID, ≥22 base64url chars"
    )
    proof_of_work: str = Field(
        description="Decimal-ASCII nonce solving SHA-256(mailbox_id || nonce)"
    )


class CreateMailboxResponse(BaseModel):
    mailbox_id: str
    bearer_token: str = Field(
        description="32 random bytes, base64url-encoded (no padding)"
    )


class SendMessageRequest(BaseModel):
    blob: str = Field(description="Opaque encrypted message, base64-encoded")


class SendMessageResponse(BaseModel):
    sequence: int


class FetchedMessage(BaseModel):
    sequence: int
    blob: str
    expires: int = Field(description="Unix timestamp in seconds")


class FetchMessagesResponse(BaseModel):
    messages: list[FetchedMessage]


class AcknowledgeRequest(BaseModel):
    through_sequence: int


class UploadMediaResponse(BaseModel):
    media_id: str = Field(
        description="Random 32-char hex id; fetch the blob at GET /v1/media/{media_id}"
    )


class PowParamsResponse(BaseModel):
    difficulty_bits: int
    algorithm: str = "sha256-leading-zero-bits"
    input: str = "utf8(mailbox_id) || utf8(decimal_nonce)"
    mailbox_id_min_length: int
