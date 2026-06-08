from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from slowapi import _rate_limit_exceeded_handler
from slowapi.errors import RateLimitExceeded
from slowapi.middleware import SlowAPIMiddleware

from app.config import settings
from app.middleware import BodySizeLimitMiddleware
from app.ratelimit import limiter
from app.routers import mailboxes, media, meta, ws

app = FastAPI(title="Khord Relay Server", version="0.1.0")

# Rate limiting (spam/DoS hardening, ADR 012). The limiter + middleware apply
# the blanket per-IP ceiling to every HTTP route; mailbox creation stacks a
# stricter limit in its router. The handler turns a tripped limit into a 429.
app.state.limiter = limiter
app.add_exception_handler(RateLimitExceeded, _rate_limit_exceeded_handler)
app.add_middleware(SlowAPIMiddleware)

# Reject oversized request bodies at the ASGI layer (outermost, so it runs
# before routing / rate limiting / handler buffering).
app.add_middleware(
    BodySizeLimitMiddleware, max_body_bytes=settings.max_request_body_bytes
)

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
