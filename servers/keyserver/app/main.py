from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.config import settings
from app.routers import auth, keys

app = FastAPI(title="Khord Key Server", version="0.1.0")

# CORS: allow the khord.org landing page to call /v1/health from
# the browser so the site can render a "server is up" indicator.
# Read-only, GET-only — write endpoints (PUT /bundle, challenge
# verification) are still cross-origin-blocked.
app.add_middleware(
    CORSMiddleware,
    allow_origins=["https://khord.org", "https://www.khord.org"],
    allow_methods=["GET"],
    allow_headers=["*"],
)

app.include_router(keys.router, prefix=settings.api_v1_prefix)
app.include_router(auth.router, prefix=settings.api_v1_prefix)


@app.get(f"{settings.api_v1_prefix}/health")
async def health() -> dict[str, str]:
    return {"status": "ok"}
