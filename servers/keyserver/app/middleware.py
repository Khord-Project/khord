"""
ASGI request-body size cap (spam-DoS hardening).

Uvicorn does not bound request body size on its own, so a single client can
stream an arbitrarily large body and force the server to buffer it in memory
before any handler (or that handler's own size check) runs. This raw-ASGI
middleware rejects oversized bodies as early as possible:

  1. Fast path — if the client declares an oversized `Content-Length`, reject
     immediately with 413 (covers every standard client: OkHttp, httpx, curl).
  2. Streaming guard — for chunked / no-Content-Length requests, the body is
     buffered up to the cap and rejected at 413 the moment it overflows, then
     replayed verbatim to the downstream app so normal requests are unaffected.

Bounded by the cap itself: at most `max_body_bytes` (+ one chunk) is ever held.
Non-HTTP scopes (websocket, lifespan) pass straight through.
"""
from starlette.types import ASGIApp, Message, Receive, Scope, Send

_TOO_LARGE_BODY = b'{"detail":"request body too large"}'


class BodySizeLimitMiddleware:
    def __init__(self, app: ASGIApp, max_body_bytes: int) -> None:
        self.app = app
        self.max_body_bytes = max_body_bytes

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        # 1. Reject early on a declared oversized Content-Length.
        for name, value in scope.get("headers", []):
            if name == b"content-length":
                try:
                    if int(value) > self.max_body_bytes:
                        await self._reject(send)
                        return
                except ValueError:
                    pass
                break

        # 2. Buffer the body (bounded by the cap) so a chunked / spoofed-length
        #    client cannot slip past the check above, then replay it intact.
        buffered: list[Message] = []
        total = 0
        while True:
            message = await receive()
            if message["type"] != "http.request":
                buffered.append(message)
                break
            total += len(message.get("body", b""))
            if total > self.max_body_bytes:
                await self._reject(send)
                return
            buffered.append(message)
            if not message.get("more_body", False):
                break

        iterator = iter(buffered)

        async def replay() -> Message:
            try:
                return next(iterator)
            except StopIteration:
                return await receive()

        await self.app(scope, replay, send)

    async def _reject(self, send: Send) -> None:
        await send(
            {
                "type": "http.response.start",
                "status": 413,
                "headers": [
                    (b"content-type", b"application/json"),
                    (b"content-length", str(len(_TOO_LARGE_BODY)).encode()),
                ],
            }
        )
        await send({"type": "http.response.body", "body": _TOO_LARGE_BODY})
