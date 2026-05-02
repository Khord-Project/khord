# ADR 008: Hybrid Transport — Poll + Time-Boxed WebSocket

**Status:** Accepted
**Date:** 2026-05-02

## Context

Real-time message delivery requires a persistent connection (WebSocket), but persistent connections leak presence metadata — the Relay Server knows exactly when a user is online and for how long.

## Decision

Khord uses a three-phase hybrid transport:

**Phase 1 — Poll on app open.** When the app starts, the client makes REST requests to each of its mailboxes to fetch any stored blobs. This catches up on messages received while offline. Simple, stateless, no persistent connection.

**Phase 2 — Time-boxed WebSocket.** After catchup, the client opens a WebSocket for real-time delivery. The connection remains open for a configurable duration (e.g., 15 minutes). Sending a message optionally resets the timer.

**Phase 3 — Idle fallback to polling.** After the WebSocket timeout, the client drops the connection and falls back to periodic polling. The app appears active but the Relay Server cannot distinguish "actively chatting" from "app is open but idle."

### Privacy properties

The Relay Server sees "someone connected to mailbox X for [timeout duration]" but cannot determine actual activity patterns within that window. The fixed-duration connection is not correlated with conversation length.

### Implementation

The message retrieval interface is identical regardless of transport — "give me blobs for this mailbox." The client switches transport underneath. The Relay Server supports both REST and WebSocket endpoints.

## Consequences

- The Relay Server must support both REST polling and WebSocket push for the same mailbox endpoints.
- The WebSocket timeout is a client-side configuration, not server-enforced. The server simply accepts connections.
- FastAPI supports both REST and WebSocket natively.
- The abstraction boundary is clean enough that future transport mechanisms (e.g., onion-routed delivery for Level 4b) can be added as alternative implementations.
