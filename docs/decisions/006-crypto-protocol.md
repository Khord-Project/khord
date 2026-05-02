# ADR 006: Cryptographic Protocol — libsodium + X3DH + Double Ratchet

**Status:** Accepted
**Date:** 2026-05-02

## Context

The cryptographic protocol is the foundation of Khord's security. It must provide end-to-end encryption, forward secrecy, and post-compromise recovery. The choice of protocol and implementation library directly affects security, auditability, and compatibility with the split-trust architecture.

## Decision

Khord implements the Signal Protocol (X3DH key agreement + Double Ratchet messaging) using libsodium cryptographic primitives. The protocol is implemented to the published specifications, not by wrapping an existing Signal library.

### Primitives (from libsodium)

| Primitive | Algorithm | Purpose |
|-----------|-----------|---------|
| Key exchange | X25519 | Diffie-Hellman key agreement in X3DH and Double Ratchet |
| Signatures | Ed25519 | Identity key signatures, signed pre-keys |
| Symmetric encryption | XSalsa20-Poly1305 (crypto_secretbox) | Message encryption |
| Key derivation | HKDF-SHA-256 | Ratchet key derivation, root key chain |
| Password-based KDF | Argon2id (crypto_pwhash) | Seed phrase → identity key derivation |
| Hash | SHA-256 | Key fingerprints, various protocol hashes |

### Protocol Components

**X3DH (Extended Triple Diffie-Hellman)** — Initial key agreement between two parties who have never communicated. Uses pre-key bundles stored on the Key Server.

**Double Ratchet** — Ongoing message encryption after X3DH establishes the initial shared secret. Provides forward secrecy (compromise of current keys doesn't reveal past messages) and post-compromise recovery (after key compromise, security is restored as the ratchet advances).

### Mapping to Split-Trust Architecture

- **Key Server** stores pre-key bundles exactly as X3DH specifies: identity key, signed pre-key, and one-time pre-keys per user
- **Relay Server** handles encrypted message blobs without any awareness of the crypto layer
- **Client** manages all cryptographic state: ratchet state, session keys, identity keys

### Mandatory Documentation Requirement

Every function implementing X3DH or Double Ratchet MUST include:
- A reference to the specific section of the published spec it implements
- Documentation of expected inputs and outputs
- Documentation of the security properties that step provides
- Explicit notes on any point where the spec allows flexibility and which option was chosen, with rationale

This spec traceability is a hard requirement, not optional. A future auditor must be able to walk the code against the spec line by line.

## Options Considered

1. **libsignal (Signal's official Rust library)** — Battle-tested, but assumes a single-server architecture. Fighting the library's assumptions to fit split-trust would be constant friction. GPLv3 license. Rejected.
2. **vodozemac (Matrix/Element's Rust library)** — More modular, Apache 2.0. Olm/Megolm are Double Ratchet variants but not identical to Signal Protocol. Less scrutinized than libsignal. Rejected.
3. **libsodium primitives + spec implementation (chosen)** — Total control over how the protocol maps to the split-trust architecture. Proven primitives, published protocol spec, auditable implementation.
4. **Custom protocol** — No established security properties, no formal analysis. Rejected outright.

## Consequences

- **Risk:** Implementing a ratcheting protocol correctly is hard. Subtle bugs create silent security failures. Mitigated by: implementing a published, formally verified protocol (not inventing crypto), using battle-tested primitives from libsodium, and requiring a professional security audit before production.
- **Benefit:** The implementation maps cleanly to the split-trust architecture without fighting library assumptions.
- **Library choice for client:** lazysodium-android provides libsodium bindings for Kotlin/Android. For future iOS via KMP, Kotlin/Native can call C libsodium directly.
- **A professional security audit is required before production deployment.** This is a future cost that must be budgeted.

## References

- X3DH specification: https://signal.org/docs/specifications/x3dh/
- Double Ratchet specification: https://signal.org/docs/specifications/doubleratchet/
- libsodium documentation: https://doc.libsodium.org/
