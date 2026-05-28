from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.config import settings
from app.routers import mailboxes, media, meta, ws

app = FastAPI(title="Khord Relay Server", version="0.1.0")

# CORS: allow the khord.org landing page to call /v1/health from
# the browser so the site can render a "server is up" indicator.
# Read-only, GET-only — write endpoints (mailbox create, message
# send/ack, WS upgrade) are still cross-origin-blocked.
app.add_middleware(
    CORSMiddleware,
    allow_origins=["https://khord.org", "https://www.khord.org"],
    allow_methods=["GET"],
    allow_headers=["*"],
)

app.include_router(mailboxes.router, prefix=settings.api_v1_prefix)
app.include_router(media.router, prefix=settings.api_v1_prefix)
app.include_router(meta.router, prefix=settings.api_v1_prefix)
app.include_router(ws.router, prefix=settings.api_v1_prefix)


@app.get(f"{settings.api_v1_prefix}/health")
async def health() -> dict[str, str]:
    return {"status": "ok"}
