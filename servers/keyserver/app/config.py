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


settings = Settings()
