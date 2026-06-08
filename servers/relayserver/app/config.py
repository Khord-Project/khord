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
    # The same knob also gates POST /v1/media (ADR 029); there the PoW
    # subject is the SHA-256 of the uploaded blob rather than a mailbox_id.
    # 16 bits ≈ 65k hashes mean (tens of ms on any device) — a real cost to a
    # spammer creating mailboxes/uploads in bulk, still negligible for a human.
    proof_of_work_difficulty_bits: int = 16

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

    # ── Storage caps (spam/DoS hardening) ──────────────────────────────────
    # Max decoded size of a single text message blob. Images go through the
    # media endpoint (capped separately at media_max_size_bytes), so a text
    # message has no reason to be large; 256 KiB is generous headroom.
    message_max_size_bytes: int = 256 * 1024
    # Max live (unexpired) messages held for one mailbox. A backlog past this
    # is rejected with 429 so one sender can't fill the relay targeting a
    # single mailbox. Recipients ack-and-delete, keeping live counts small.
    mailbox_max_messages: int = 1000

    # ── WebSocket connection caps (spam/DoS hardening) ─────────────────────
    # Concurrent WS connections allowed from one IP across all mailboxes, and
    # concurrent subscribers allowed on one mailbox. Beyond either, new
    # connections are refused (close 1008). Normal clients hold one per mailbox.
    max_ws_connections_per_ip: int = 10
    max_ws_subscribers_per_mailbox: int = 5

    # ── Rate limiting (spam/DoS hardening, ADR 012) ────────────────────────
    # In-memory per-IP limiting via slowapi. Disabled in unit tests (conftest
    # flips it off, re-enabling only for the dedicated rate-limit tests).
    rate_limit_enabled: bool = True
    rate_limit_general: str = "100/minute"  # blanket per-IP ceiling
    rate_limit_create: str = "10/minute"    # mailbox creation (registration)

    # Hard cap on request body size, enforced at the ASGI layer before any
    # handler runs. 12 MiB covers the largest media upload (10 MiB) plus
    # multipart framing and headers.
    max_request_body_bytes: int = 12 * 1024 * 1024


settings = Settings()
