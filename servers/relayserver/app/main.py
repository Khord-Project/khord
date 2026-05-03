from fastapi import FastAPI

from app.config import settings
from app.routers import mailboxes, meta, ws

app = FastAPI(title="Khord Relay Server", version="0.1.0")

app.include_router(mailboxes.router, prefix=settings.api_v1_prefix)
app.include_router(meta.router, prefix=settings.api_v1_prefix)
app.include_router(ws.router, prefix=settings.api_v1_prefix)


@app.get(f"{settings.api_v1_prefix}/health")
async def health() -> dict[str, str]:
    return {"status": "ok"}
