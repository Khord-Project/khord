from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env", extra="ignore", populate_by_name=True
    )

    database_url: str
    api_v1_prefix: str = "/v1"

    # Auth challenge lifetime — see PROTOCOL.md §4.4. Short on purpose so a
    # leaked challenge cannot be replayed for long.
    challenge_ttl_seconds: int = 300  # 5 minutes

    # Session token lifetime after a successful challenge-response. Short
    # because tokens cannot be revoked individually (stateless HMAC, ADR 011).
    session_token_ttl_seconds: int = 900  # 15 minutes

    # HMAC-SHA256 secret used to sign session tokens. Loaded from env, never
    # logged. See ADR 011 + the `tokens` module for the wire format.
    token_secret: str = Field(alias="KEY_SERVER_TOKEN_SECRET")

    # ── Rate limiting (spam/DoS hardening, ADR 012) ────────────────────────
    # In-memory per-IP/per-key limiting via slowapi. Disabled in unit tests
    # (the conftest flips this off and re-enables it only for the dedicated
    # rate-limit tests). Limits are slowapi "N/period" strings.
    rate_limit_enabled: bool = True
    # Blanket per-IP ceiling applied to every endpoint.
    rate_limit_general: str = "100/minute"
    # Stricter ceiling for the auth/challenge endpoints (cheap to spam,
    # back the challenge-response flow).
    rate_limit_auth: str = "10/minute"
    # Public OPK-consuming bundle fetch: a per-IP ceiling AND a per-fingerprint
    # ceiling, so one IP can't drain many victims and many IPs can't drain one
    # victim's one-time-prekey pool (which would degrade forward secrecy).
    rate_limit_opk_per_ip: str = "30/minute"
    rate_limit_opk_per_fingerprint: str = "5/minute"

    # Hard cap on request body size, enforced at the ASGI layer before any
    # handler runs. 12 MiB leaves headroom over the largest legitimate body
    # (bundle uploads are small); the relay shares the same knob for media.
    max_request_body_bytes: int = 12 * 1024 * 1024


settings = Settings()
