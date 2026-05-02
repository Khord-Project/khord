# ADR 005: Per-Contact Directional Mailboxes

**Status:** Accepted
**Date:** 2026-05-02

## Context

The Relay Server needs an addressing scheme for message delivery. The choice directly affects what metadata the Relay Server can observe about communication patterns.

## Decision

Each side of a contact relationship gets its own dedicated inbound mailbox on the Relay Server.

When Alice and Bob exchange QR codes:
- Alice generates a new random mailbox ID for receiving messages from Bob, and includes it in her QR code
- Bob generates a new random mailbox ID for receiving messages from Alice, and includes it in his QR code
- Alice sends to Bob's mailbox; Bob sends to Alice's mailbox
- The Relay Server sees two completely unrelated mailboxes with no linkage between them

**Properties:**
- The Relay Server cannot determine that two mailboxes belong to the same user
- The Relay Server cannot determine that two mailboxes are part of the same conversation
- Mailbox IDs are random, opaque, and unrelated to identity keys
- Each mailbox has its own bearer token for authentication (see ADR 011)

**Scaling:** The number of mailboxes scales linearly with the number of contact relationships across all users. For a user with 50 contacts, they have 50 inbound mailboxes. The Relay Server's logic is identical regardless of mailbox count — it is a dumb blob router.

## Options Considered

1. **Single mailbox per user** — simpler, but the Relay Server sees all traffic to/from one endpoint. Can build traffic profiles. Rejected.
2. **Per-conversation shared mailbox** — both parties read/write the same mailbox. The Relay Server sees two clients connected to the same mailbox, revealing they are in a conversation. Rejected.
3. **Per-contact directional mailboxes (chosen)** — maximum separation. No metadata linkage possible from Relay Server's perspective.
4. **Rotating mailbox IDs** — periodic rotation to prevent long-term traffic analysis. Deferred as a Level 3 enhancement; current model doesn't prevent future implementation.

## Consequences

- Multi-device (deferred) works naturally: each device creates its own per-contact mailboxes. No architectural changes needed.
- Connection management scales with contacts × users, not just users. PostgreSQL handles this without issue.
- Mailbox creation requires proof of work to prevent mass-creation abuse (see ADR 012).
- The QR code payload includes the mailbox ID, tying introduction and mailbox creation together.
