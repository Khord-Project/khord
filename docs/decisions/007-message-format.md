# ADR 007: Message Envelope Format and Ordering

**Status:** Accepted
**Date:** 2026-05-02

## Context

The Relay Server needs enough structure to route messages, but everything beyond routing must be opaque. The format determines what metadata leaks server-side.

## Decision

### Server-visible envelope (plaintext)

| Field | Purpose |
|-------|---------|
| Destination mailbox ID | Routing — which mailbox receives this blob |
| Per-mailbox sequence number | Ordering — monotonic counter within this mailbox |
| Encrypted blob | The actual message — opaque to the server |
| TTL expiry timestamp | Garbage collection — when to delete if undelivered |

### Ordering mechanism

Each mailbox maintains a monotonic sequence counter. When a message arrives, it receives the next number in that mailbox's sequence. Sequence numbers are per-mailbox, not global — the Relay Server cannot use sequence numbers to correlate activity across mailboxes.

All four fields are cleared on delivery confirmation. The server retains nothing after the client acknowledges receipt.

### No server timestamps

The server does not attach wall-clock timestamps. The server already knows when a blob arrived (because it received it), but this information is not persisted in any form. Temporal metadata exists only in the server's transient processing and is not written to the database.

Client-generated timestamps live inside the encrypted payload — the server never sees them.

### Encrypted payload (inside the blob)

The encrypted payload format must include a message type field for forward compatibility. For the PoC, the only type is "text." Future types (media reference, key change notification, etc.) are additive — old clients receiving an unknown type display "unsupported message type" gracefully.

## Consequences

- Message ordering is guaranteed within a single mailbox (single contact's messages arrive in order).
- Cross-contact ordering relies on client-side timestamps inside encrypted payloads.
- Multi-device (deferred) works cleanly: each device's mailbox has its own independent sequence counter.
- The Relay Server's storage per undelivered message is minimal: mailbox ID + sequence number + blob + TTL.
