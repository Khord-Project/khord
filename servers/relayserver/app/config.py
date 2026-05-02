from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    database_url: str
    api_v1_prefix: str = "/v1"
    message_ttl_seconds: int = 60 * 60 * 24 * 7
    proof_of_work_difficulty_bits: int = 16


settings = Settings()
