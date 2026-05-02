# ADR 004: QR-Only Contact Introduction

**Status:** Accepted
**Date:** 2026-05-02

## Context

The introduction model determines how two users establish their first encrypted conversation. In a split-trust architecture, this is especially critical because it bridges the identity world (Key Server) and the transport world (Relay Server).

## Decision

Contact introduction is QR-code only. No server-facilitated discovery.

The QR code contains:
- The user's public identity key (or fingerprint)
- A reference enabling the other party to fetch pre-key bundles from the Key Server
- The user's inbound relay mailbox ID for this contact (generated at introduction time)

Exchange happens out-of-band: in person, screenshot via another channel, printed on a business card, etc.

After scanning, Alice's client:
1. Stores Bob's identity key locally
2. Fetches Bob's pre-key bundle from Key Server using the fingerprint
3. Verifies the pre-key bundle's identity key matches the QR code (built-in verification)
4. Performs X3DH key agreement
5. Sends the first encrypted message to Bob's relay mailbox

The Key Server is never asked to broker introductions. It only serves pre-key bundles to clients that already know the target's identity key fingerprint.

**Future addon noted:** An optional discovery/introduction server may be added for organizational deployments (e.g., company-internal use) where convenience outweighs the metadata cost. This server would come with explicit warnings that it reduces privacy guarantees. It is a separate service, not a modification to the Key Server or Relay Server.

## Options Considered

1. **QR-only (chosen)** — maximum privacy, no introduction metadata on any server. Key Server never learns who contacts whom.
2. **Key Server brokers introductions** — Key Server would learn the social graph of who initiates contact with whom. Rejected for individual use.
3. **Public introduction mailbox** — Bob publishes a drop-box address alongside his public key. Creates a linkable artifact if both servers are compromised. Rejected.

## Consequences

- Onboarding requires physical or side-channel contact exchange. This limits viral growth but matches privacy-first positioning.
- The Key Server has no concept of "introductions" — it is purely a pre-key bundle store.
- Every contact requires generating a new per-contact mailbox on the Relay Server (see ADR 005).
- The QR code is the trust anchor. Its security depends on the out-of-band channel used to exchange it.
