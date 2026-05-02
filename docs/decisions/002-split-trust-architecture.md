# ADR 002: Split-Trust Two-Server Architecture

**Status:** Accepted
**Date:** 2026-05-02

## Context

Traditional messaging architectures use a single server that handles both identity/key management and message routing. This means a single operator has access to both user identities and communication patterns. Even with E2E encryption, the server sees metadata: who messages whom, when, and how often.

## Decision

Khord uses two independent servers under separate control:

**Key Server (Identity)**
- Stores public identity key fingerprints and signed pre-key bundles for X3DH
- Handles key rotation and pre-key replenishment
- Authenticates clients via cryptographic challenge-response against their identity key
- Has no concept of mailboxes, messages, or conversations
- Cannot determine who communicates with whom

**Relay Server (Transport)**
- Accepts, stores, and delivers opaque encrypted blobs to mailboxes
- Identifies clients only by opaque bearer tokens tied to specific mailboxes
- Has no concept of user identity, public keys, or cryptographic state
- Cannot determine who owns a mailbox or what is in a message

**Critical separation properties:**
- The two servers share zero state
- The two servers have no communication channel between them
- Neither server's database schema references concepts from the other server
- Compromise of either server alone does not yield the full metadata picture

## Options Considered

1. **Single server with access controls** — simpler, but a single compromise or legal compulsion yields everything. Rejected.
2. **Two-server split-trust (chosen)** — structural separation. Neither server alone can answer "what did Alice say to Bob, and when?"
3. **Three-server model (with separate introduction server)** — noted for future org deployment mode, not needed for individual PoC.

## Consequences

- The protocol must handle the bootstrap problem: how does Alice's first message reach Bob when identity and transport are on separate servers? Solved via QR code exchange (see ADR 004).
- Deployment complexity increases — two separate services, two databases, eventually two separate operators.
- The Key Server is a candidate for non-profit operation in production. This governance decision is deferred but the architecture must ensure the Key Server is a standalone deployable unit.
- Timing correlation attacks remain possible if an adversary observes both servers' network traffic simultaneously. This is a Level 3/4b concern, acknowledged but not mitigated in PoC.
