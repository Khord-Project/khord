# ADR 021: Testing Strategy — Mandatory Protocol and Adversarial Tests

**Status:** Accepted
**Date:** 2026-05-02

## Context

The crypto implementation is built on libsodium primitives with X3DH and Double Ratchet implemented to spec. Bugs in protocol implementation are silent security failures — messages may appear to work but lack the claimed security properties. Testing is not optional; it is a hard requirement.

## Decision

Three mandatory test categories:

### 1. Protocol-level tests

Verify that the crypto implementation matches the published specifications:
- X3DH key agreement produces correct shared secrets for known test vectors
- Double Ratchet advances correctly — ratchet state, chain keys, message keys
- Messages encrypted by one client are decryptable by the intended recipient
- Messages are unreadable by any third party
- Forward secrecy: compromise of current keys does not reveal past message keys
- Post-compromise recovery: after key compromise, ratchet advancement restores security

Each test must reference the specific section of the X3DH or Double Ratchet spec it validates.

### 2. Integration tests

Full end-to-end flow against real server instances:
- Client A generates keys, uploads pre-key bundles to Key Server
- Client B fetches pre-key bundles, performs X3DH, sends encrypted message through Relay Server
- Client A receives and decrypts
- Verify across multiple messages (ratchet advancement)
- Verify with offline delivery (store-and-forward)
- Verify key change detection when a contact re-registers

### 3. Adversarial tests

Deliberately malformed or malicious inputs:
- Tampered ciphertext — verify rejection, not silent corruption
- Replayed messages — verify detection and rejection
- Wrong keys — verify decryption failure, not partial decryption
- Expired pre-keys — verify graceful handling
- Manipulated sequence numbers — verify ordering is not corrupted
- Invalid proof of work — verify mailbox creation rejection
- Forged bearer tokens — verify access denial
- Oversized payloads — verify rejection within bounds

The system must fail safely — reject bad data explicitly rather than silently processing it.

## Consequences

- Tests are a prerequisite for implementation sign-off, not an afterthought.
- The spec traceability document (ADR 006) serves as the test plan — every documented function maps to tests.
- CI must run all three test categories on every commit.
- Adversarial tests should be designed by someone thinking like an attacker, not the implementer.
