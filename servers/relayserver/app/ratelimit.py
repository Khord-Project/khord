"""
Per-IP rate limiting (spam-DoS hardening, ADR 012).

A single module-level `slowapi.Limiter` backs every limited endpoint. Storage
is in-memory, which is correct for the single-worker deployment (the in-process
notifier already pins this server to one worker): there is no shared limiter
state to coordinate across processes.

The blanket per-IP ceiling (`rate_limit_general`) is applied to every HTTP
route through `SlowAPIMiddleware` (wired in `app.main`). Mailbox creation —
the registration-shaped, PoW-gated endpoint — stacks a stricter explicit
limit. WebSocket connection limits are handled separately in the notifier;
slowapi does not cover the WS upgrade.

`key_style="endpoint"` buckets each limit by handler name rather than URL path
(the default). Relay paths embed the mailbox_id, so the default "url" style
would silently scope per-IP limits per-mailbox; keying on the endpoint makes
the per-IP ceiling span every mailbox, as intended.
"""
from slowapi import Limiter
from slowapi.util import get_remote_address

from app.config import settings

limiter = Limiter(
    key_func=get_remote_address,
    default_limits=[settings.rate_limit_general],
    enabled=settings.rate_limit_enabled,
    storage_uri="memory://",
    key_style="endpoint",
    # No success-response header injection — that would force a `response:
    # Response` param on every limited handler. The 429 itself still carries
    # Retry-After via slowapi's exception handler, which is what clients need.
    headers_enabled=False,
)


def reset_limiter() -> None:
    """Clear every counter. Used by the test-suite between rate-limit tests."""
    limiter.reset()
