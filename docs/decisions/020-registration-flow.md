# ADR 020: Registration and Onboarding Flow

**Status:** Accepted
**Date:** 2026-05-02

## Context

The registration flow is the user's first interaction with Khord. It must generate cryptographic identity, ensure the user has a recovery path, and register with the Key Server — all without collecting personal information.

## Decision

### Flow

1. App generates a random seed using a cryptographically secure source
2. Seed is converted to a BIP39-style mnemonic phrase
3. Identity key pair (Ed25519 + X25519) is derived deterministically from the seed via Argon2id KDF
4. User is shown the generated seed phrase
5. User chooses: "Use this phrase" or "I want to provide my own"
6. If custom phrase: input field with live entropy estimation, hard minimum (80 bits), recommended minimum (128 bits), clear security communication
7. User must confirm they have recorded the phrase (verification step — e.g., re-enter selected words)
8. App generates initial batch of pre-key bundles (signed pre-key + N one-time pre-keys)
9. App uploads pre-key bundles to Key Server, authenticating via challenge-response against the identity key
10. User is shown their identity key fingerprint and QR code
11. No relay mailboxes exist yet — those are created per-contact at introduction time

### What is NOT collected

No email, no phone number, no username, no device ID, no analytics identifier. The servers learn the user exists only when pre-key bundles are uploaded (Key Server) or the first mailbox is created (Relay Server). Neither knows about the other.

## Consequences

- Onboarding is fast but requires user attention — recording the seed phrase is a critical step.
- The entropy estimation for custom phrases must be thorough — weak passphrases directly weaken identity key security.
- The KDF parameters (Argon2id memory, iterations, parallelism) must be consistent across all clients and documented precisely — any deviation means a different key from the same seed.
- Organizational deployment (deferred) uses a completely different onboarding flow — admin-provisioned keys, managed recovery. This is a separate mode, not a modification of the individual flow.
