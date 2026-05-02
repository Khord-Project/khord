# Khord

**Privacy-first encrypted messaging built on split-trust architecture.**

Khord is an end-to-end encrypted messaging application where no single server operator can read messages, identify users, or observe who talks to whom. Privacy is achieved through architectural separation: two independent servers that each hold half the picture, neither able to reconstruct the whole.

## Project Status

Proof of Concept — under active development.

## Core Privacy Properties

- **End-to-end encryption (E2E):** Messages are encrypted on the sender's device and decrypted only on the recipient's device. Servers handle opaque ciphertext.
- **Metadata minimization:** The two-server split ensures that the server storing identity keys never sees message traffic, and the server routing messages never sees user identities.
- **Jurisdictional resistance:** The architecture is designed so that Key Servers and Relay Servers can be operated by independent organizations in different legal jurisdictions. No single legal authority can compel both operators to cooperate.
- **Traffic analysis awareness:** The architecture does not implement full traffic analysis resistance in the PoC, but is designed so that future mitigations (Tor support, padding, decoy traffic) can be layered on without protocol changes.

## Threat Model

| Level | Description | Status |
|-------|-------------|--------|
| L1 | Server operator cannot read messages (E2E encryption) | Hard requirement |
| L2 | Server operator cannot see who talks to whom (metadata minimization) | Hard requirement |
| L3 | Traffic analysis resistance (timing, volume correlation) | Design for, not implemented in PoC |
| L4a | Jurisdictional resistance (legal compulsion across borders) | Addressed architecturally via split-trust |
| L4b | Passive network observation by state-level adversary | Acknowledged, architecture does not prevent future mitigations |

## Architecture Overview

### Two-Server Split-Trust Model

**Key Server (Identity)**
- Stores public identity key fingerprints and pre-key bundles
- Handles X3DH pre-key distribution
- Knows nothing about messaging, mailboxes, or conversations
- Authentication: cryptographic challenge-response against identity key
- Operated independently from Relay Server

**Relay Server (Transport)**
- Routes opaque encrypted blobs to mailboxes
- Stores undelivered messages until delivery confirmation or TTL expiry
- Knows nothing about user identities
- Authentication: opaque bearer tokens per mailbox
- Per-contact directional mailboxes — each side of a conversation has its own inbound mailbox

**Client (Android / KMP)**
- Generates and holds all private keys (Android Keystore)
- Implements Signal Protocol (X3DH + Double Ratchet) using libsodium primitives
- Handles all encryption/decryption locally
- Talks to both servers independently

### Key Design Principles

- **Servers are deliberately dumb.** They route and store opaque blobs. They never parse, decrypt, or understand message content.
- **Identity is the key.** A user's identity is their long-lived Ed25519 identity key. No emails, phone numbers, or usernames.
- **QR code introductions.** Contacts are added by scanning QR codes containing the identity key, pre-key bundle reference, and relay mailbox ID. No server-facilitated discovery.
- **Per-contact directional mailboxes.** Each side of a relationship has its own inbound mailbox on the Relay Server. The server cannot correlate which mailboxes belong to the same user or conversation.
- **Minimal server-side state.** Messages are deleted on delivery confirmation. Sequence counters are per-mailbox and cleared with delivery. TTL expiry handles abandoned mailboxes.

## Technology Stack

| Component | Technology | Rationale |
|-----------|-----------|-----------|
| Client | Kotlin Multiplatform (KMP) | Shared crypto/protocol layer, native UI per platform. Android first (F-Droid), iOS later. |
| Key Server | Python / FastAPI | Mature async framework, simple REST API for pre-key bundle storage. |
| Relay Server | Python / FastAPI | Same stack, WebSocket + REST support for message routing. |
| Database | PostgreSQL | Both servers, separate instances. Handles scale of per-contact mailbox model. |
| Crypto primitives | libsodium (lazysodium-android / libsodium.js) | X25519, Ed25519, XSalsa20-Poly1305, Argon2id. |
| Crypto protocol | Signal Protocol (X3DH + Double Ratchet) | Implemented to spec on libsodium primitives. Not using libsignal library. |
| Push notifications | UnifiedPush | Open standard, no Google dependency, F-Droid compatible. |
| Deployment (PoC) | Docker Compose | Two FastAPI containers, two PostgreSQL containers, no shared networks. |

## Messaging Flow (Simplified)

1. **Registration:** Client generates identity key pair from seed phrase, uploads pre-key bundles to Key Server.
2. **Contact exchange:** Alice and Bob scan each other's QR codes (containing identity key + relay mailbox ID).
3. **First message:** Alice fetches Bob's pre-key bundle from Key Server, performs X3DH key agreement, creates a Double Ratchet session, encrypts the message, and sends the ciphertext to Bob's mailbox on Relay Server.
4. **Delivery:** Bob's client polls or receives via WebSocket, decrypts with Double Ratchet, message appears.
5. **Ongoing:** Double Ratchet advances with every message, providing forward secrecy and post-compromise recovery.

## Key Backup & Recovery

- **Seed phrase (primary):** Identity key is derived deterministically from a BIP39-style mnemonic via Argon2id KDF. User records this phrase on paper.
- **Custom passphrase (optional):** Users may provide their own passphrase, subject to a mandatory entropy estimation with a hard minimum floor (80 bits) and a recommended minimum (128 bits).
- **Shamir's Secret Sharing (optional):** Seed can be split into N parts where any K parts reconstruct the secret. Advanced users can distribute parts to trusted locations.
- **Message backup (separate):** Encrypted archive of conversation content only. Never contains identity keys, contact keys, or relay mailbox IDs.

## What Khord Does NOT Do (Explicit Non-Goals for PoC)

- Group messaging
- Multi-device sync
- Media/file attachments
- Read receipts or typing indicators
- Voice/video calls
- Server-facilitated contact discovery
- Organizational deployment mode

See [DEFERRED.md](DEFERRED.md) for the full deferred decisions list with context.

## Licensing

- **Code:** GNU Affero General Public License v3.0 (AGPL-3.0)
- **Protocol specification:** Creative Commons Attribution-ShareAlike 4.0 (CC-BY-SA-4.0)

AGPL ensures that anyone running a modified version of the server must publish their changes. The protocol spec is openly licensed so that independent clients and servers can be built without AGPL obligations.

## Security Considerations

- **This is a PoC.** The crypto implementation has not been professionally audited.
- **libsodium primitives are proven.** The risk is in our protocol-level implementation of X3DH and Double Ratchet on top of them.
- **A professional security audit is required before any production deployment.**
- **Reproducible builds are a production requirement** (not implemented in PoC) to allow verification that deployed binaries match published source.

## Project Links

- **Website:** https://khord.org (planned)
- **Repository:** TBD
- **Protocol Specification:** See [PROTOCOL.md](PROTOCOL.md)
- **Architecture Decisions:** See [decisions/](decisions/)
- **Deferred Decisions:** See [DEFERRED.md](DEFERRED.md)
