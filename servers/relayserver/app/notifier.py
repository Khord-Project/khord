"""
In-process WebSocket pub/sub for mailbox messages.

Used by the WS handler to receive new-message events that the REST send
endpoint publishes. Single-process only: this assumes one uvicorn worker.
For multi-worker production, swap this implementation for one backed by
PostgreSQL `LISTEN`/`NOTIFY` — callers do not need to change.

Backpressure: each per-socket send is wrapped in `asyncio.wait_for` with a
short timeout (`settings.ws_send_timeout_seconds`). A slow consumer is
disconnected, not used to stall the publisher or other consumers.
"""
import asyncio
import logging
from collections.abc import Awaitable, Callable
from typing import Any

logger = logging.getLogger(__name__)

# A subscriber is an async callable that receives a JSON-serialisable dict
# payload (the new-message event). Keeping the subscriber callable rather
# than the WebSocket itself decouples this module from FastAPI's WS API.
Subscriber = Callable[[dict[str, Any]], Awaitable[None]]


class MailboxNotifier:
    """Per-mailbox fan-out registry.

    `subscribe(mailbox_id, subscriber)` registers a coroutine to be invoked
    for every event on `mailbox_id`. `notify(mailbox_id, payload)` invokes
    every subscriber concurrently with `payload`. Slow/erroring subscribers
    are removed; their failures do not propagate to other subscribers.
    """

    def __init__(self, send_timeout: float) -> None:
        self._subscribers: dict[str, set[Subscriber]] = {}
        self._send_timeout = send_timeout

    def subscribe(self, mailbox_id: str, subscriber: Subscriber) -> None:
        self._subscribers.setdefault(mailbox_id, set()).add(subscriber)

    def unsubscribe(self, mailbox_id: str, subscriber: Subscriber) -> None:
        bucket = self._subscribers.get(mailbox_id)
        if bucket is None:
            return
        bucket.discard(subscriber)
        if not bucket:
            self._subscribers.pop(mailbox_id, None)

    async def notify(self, mailbox_id: str, payload: dict[str, Any]) -> None:
        """Deliver `payload` to every subscriber of `mailbox_id`.

        Subscribers that exceed `send_timeout` or raise are dropped. Returns
        once all delivery attempts have completed (successfully or not).
        """
        bucket = list(self._subscribers.get(mailbox_id, ()))
        if not bucket:
            return

        async def _deliver(sub: Subscriber) -> Subscriber | None:
            try:
                await asyncio.wait_for(sub(payload), timeout=self._send_timeout)
                return None
            except (asyncio.TimeoutError, Exception):
                # Per ADR 016: do not log mailbox_id, payload, or anything
                # else request-specific. The dropped subscriber is removed
                # silently — the WS handler will observe the closed socket.
                return sub

        results = await asyncio.gather(*(_deliver(s) for s in bucket))
        for failed in results:
            if failed is not None:
                self.unsubscribe(mailbox_id, failed)


# Module-level singleton — one notifier per process. The worker count must
# remain 1 (see WS module + docker-compose comment).
def _build_notifier() -> MailboxNotifier:
    from app.config import settings
    return MailboxNotifier(send_timeout=settings.ws_send_timeout_seconds)


notifier: MailboxNotifier = _build_notifier()
