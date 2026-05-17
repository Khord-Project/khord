# ADR 022: WebSocket Push as the Default Privacy-Preserving Channel

**Status:** Accepted
**Date:** 2026-05-17
**Supersedes scope of:** ADR 013 (UnifiedPush) — see below.

## Context

ADR 013 chose UnifiedPush as the push transport for the PoC: an open-standard,
user-configurable push provider, with FCM available later as a second backend
via an abstracted dispatcher. Push payloads are always empty so the provider
sees only a wake-up signal, never sender/recipient/content.

Implementing UnifiedPush requires:
  - Every user to install + configure a distributor app (ntfy, etc.).
  - Server-side dispatch wiring.
  - A separate trust decision about which third-party push relay to use.

The Relay Server already implements a per-mailbox WebSocket endpoint
(`/v1/mailboxes/{id}/ws`) that pushes a notification frame whenever a
new message is stored for that mailbox. The client just hasn't been
listening to it.

A user-facing app that maintains an Android **foreground service** can
keep that WebSocket alive without any third-party push provider. The
trade-off is battery use: a foreground service holds enough wakelocks
to receive a TCP frame, but it is one of the most reliable Android
delivery channels in modern Android.

## Decision

**Use a WebSocket-backed Android foreground service as the default
push transport.** UnifiedPush (and eventually FCM) remain on the ADR 013
roadmap as opt-in battery-optimisation backends — they are not removed.

  - The foreground service (`KhordPushService`) maintains one
    WebSocket per active contact's inbound mailbox. The
    `MailboxWebSocketClient` handles the auth handshake, frame
    decode, and exponential-backoff reconnect.
  - On a server push, the service ignores the embedded `blob` and
    calls `Messaging.receiveMessages(contact)` — i.e. the same
    HTTP-fetch / decrypt / ACK path that pull-to-refresh uses. The
    push frame is purely a **signal**.
  - The client exposes `AppContainer.pushConnected: Set<fingerprint>`
    so per-chat ViewModels can pause their 5 s fallback poll while
    the WebSocket is healthy.
  - Server-side: **no change**. The existing
    `MailboxNotifier.notify(mailbox, {…})` in
    `app/routers/mailboxes.py` is already sufficient.

## Why the existing rich push is fine (signal-and-fetch)

The relay server's push currently carries `{"type":"message",
"sequence":N, "blob":"<base64>"}`. The PROTOCOL.md §5.5 spec calls
for "minimal payload" — but the blob is end-to-end encrypted, the
server cannot read it, and a passive observer on the WebSocket can
already see the per-byte frame size of every message regardless of
whether we strip the blob or not (the same blob is downloaded over
HTTP a moment later anyway).

Treating the push frame as **a wake-up only** also means the entire
existing decrypt path stays canonical:

  - X3DH initial vs. ratchet envelope dispatch,
  - OPK secret consumption + wipe (X3DH §3.4),
  - `reply_info` self-healing display-name update,
  - ACK after persist.

This logic lives in `Messaging.receiveMessages(...)` and is well
tested. Replicating it in the WebSocket reader path would duplicate
subtle invariants for ~no measurable benefit.

## Privacy implications

This decision is intentional and matters for the project's stance:

| Channel | Third-party visibility | Notes |
|---|---|---|
| WebSocket / foreground service (this ADR) | None — the only network observer is the Relay Server operator, who already sees mailbox traffic. | Default. |
| UnifiedPush (ADR 013) | The user's chosen distributor sees notification timing. Payload is empty. | Future opt-in for users who want lower battery use. |
| FCM | Google sees notification timing, even with empty payload. | Future opt-in for Play Store distribution. |

The WebSocket-first default means a fresh Khord install with no
UnifiedPush / FCM configuration **never** introduces a third-party
observer of message timing. Adding push backends later strictly
adds choices — it never removes the privacy-preserving baseline.

## Consequences

  - The Android client maintains a foreground service while the
    user has any contacts. This is visible in the notification shade
    as a persistent "Khord — Connected" entry, required by Android.
  - Battery: a quiet TCP keepalive every ~30 s. OkHttp pools the
    connections so multiple contacts hosted on the same relay share
    the underlying TCP socket and TLS session efficiently.
  - The Relay Server protocol is unchanged — wire-compatible with
    PROTOCOL.md as-is. Existing tests pass.
  - The 5 s per-chat polling loop is now suppressed while the WS is
    healthy. Pull-to-refresh is independent and continues to work.
  - The 10 s contact-list poll for pending-mailbox X3DH initials is
    kept (the foreground service subscribes only to bound contacts,
    not pending QR mailboxes — adding pending-mailbox subscriptions
    is straightforward but kept out of v1 to honour "keep the
    service minimal").
  - On panic, the service is stopped before the process is killed
    so the persistent notification disappears immediately and no
    final WS push fires `receiveMessages()` against an already-wiped
    persistence layer.

## What's not in this ADR

  - Battery-optimisation exemption (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`)
    is invasive UX and not requested. Modern Android keeps
    foreground services alive without it.
  - Multi-process or multi-device sync. The service runs in the
    main app process; same-device only.
  - WebSocket multiplexing or server-side LISTEN/NOTIFY for
    multi-worker Relay Server deployments — see the notifier
    module's docstring. Khord's `docker-compose.yml` pins
    `uvicorn --workers 1` accordingly.
