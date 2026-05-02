from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    database_url: str
    api_v1_prefix: str = "/v1"
    challenge_ttl_seconds: int = 60
    session_token_ttl_seconds: int = 300


settings = Settings()
