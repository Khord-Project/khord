from fastapi import FastAPI

from app.config import settings
from app.routers import auth, keys

app = FastAPI(title="Khord Key Server", version="0.1.0")

app.include_router(keys.router, prefix=settings.api_v1_prefix)
app.include_router(auth.router, prefix=settings.api_v1_prefix)


@app.get(f"{settings.api_v1_prefix}/health")
async def health() -> dict[str, str]:
    return {"status": "ok"}
