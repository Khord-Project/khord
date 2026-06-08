"""
Per-IP / per-key rate limiting (spam-DoS hardening, ADR 012).

A single module-level `slowapi.Limiter` backs every limited endpoint. Storage
is in-memory, which is correct for the single-worker deployment: there is no
shared limiter state to coordinate across processes (mirrors the in-process
constraints elsewhere in the stack).

The blanket per-IP ceiling (`rate_limit_general`) is applied to *every* route
through `SlowAPIMiddleware` (wired in `app.main`). Endpoints that need a
stricter or differently-keyed limit stack an explicit `@limiter.limit(...)`
decorator on top — those handlers must take a `request: Request` parameter so
slowapi can find the client address.

`client_ip_key` is slowapi's default (X-Forwarded-For-aware via
`get_remote_address`). `fingerprint_key` keys on the `{fingerprint}` path
param so the OPK-fetch limit is per-victim, not per-caller.
"""
from slowapi import Limiter
from slowapi.util import get_remote_address
from starlette.requests import Request

from app.config import settings

# Default key: the remote IP. Behind the production reverse proxy the real
# client address arrives in the forwarded headers get_remote_address honours.
client_ip_key = get_remote_address


def fingerprint_key(request: Request) -> str:
    """Key on the path's `{fingerprint}`, falling back to IP if absent."""
    return request.path_params.get("fingerprint") or get_remote_address(request)


limiter = Limiter(
    key_func=client_ip_key,
    default_limits=[settings.rate_limit_general],
    enabled=settings.rate_limit_enabled,
    storage_uri="memory://",
    # key_style="endpoint" buckets each limit by the handler name, NOT the URL
    # path. This matters: the OPK/auth paths embed the fingerprint, so the
    # default "url" style would silently scope the per-IP limit per-fingerprint
    # — letting one IP sweep many victims. Keying on the endpoint makes the
    # per-IP ceiling span every fingerprint, as intended.
    key_style="endpoint",
    # No success-response header injection — that would force a `response:
    # Response` param on every limited handler. The 429 itself still carries
    # Retry-After via slowapi's exception handler, which is what clients need.
    headers_enabled=False,
)


def reset_limiter() -> None:
    """Clear every counter. Used by the test-suite between rate-limit tests."""
    limiter.reset()
