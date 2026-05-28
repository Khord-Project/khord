from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    database_url: str
    api_v1_prefix: str = "/v1"

    # How long delivered-but-unacknowledged messages live before being lazily
    # filtered out / opportunistically deleted. ADR 007 says messages are
    # cleared on ack OR TTL — this is the TTL leg.
    message_ttl_seconds: int = 60 * 60 * 24 * 7  # 7 days

    # Difficulty bits for Hashcash-style PoW on POST /v1/mailboxes (ADR 012).
    # Low for the PoC — ~250 hashes mean, milliseconds on any device.
    # The same knob also gates POST /v1/media (ADR 029); there the PoW
    # subject is the SHA-256 of the uploaded blob rather than a mailbox_id.
    proof_of_work_difficulty_bits: int = 8

    # Max size of a single uploaded media blob (encrypted bytes). 10 MiB
    # is plenty for a 2048px-longest-side re-encoded JPEG; the client
    # downscales before it ever reaches here (ADR 029).
    media_max_size_bytes: int = 10 * 1024 * 1024

    # How long an uploaded media blob lives before it's lazily filtered /
    # opportunistically deleted. Mirrors message_ttl_seconds — media is a
    # transient courier blob, not durable storage (ADR 029).
    media_ttl_seconds: int = 60 * 60 * 24 * 7  # 7 days

    # Minimum mailbox_id length, in characters. Required to prevent attackers
    # from grinding very short IDs against the PoW, and from squatting common
    # IDs. 22 base64url chars = 16 random bytes after decode (~128 bits).
    mailbox_id_min_length: int = 22

    # WebSocket auth deadline — server closes if first frame is not received
    # in this many seconds (ADR 008 + Q3).
    ws_auth_deadline_seconds: float = 5.0

    # WebSocket per-frame send timeout — slow consumers don't block fast ones.
    ws_send_timeout_seconds: float = 1.0


settings = Settings()
