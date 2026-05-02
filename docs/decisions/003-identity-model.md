# ADR 003: Identity Model — Key as Identity

**Status:** Accepted
**Date:** 2026-05-02

## Context

The identity model determines how users exist in the system, how they find each other, and how much metadata the identity layer creates. It directly affects Key Server design, onboarding UX, and the verification story.

## Decision

A user's identity IS their long-lived Ed25519 identity key. There are no usernames, email addresses, phone numbers, or server-assigned IDs.

The identity key rarely changes. Underneath it, the Signal Protocol key hierarchy handles key rotation: signed pre-keys rotate periodically, one-time pre-keys are consumed per session, and the Double Ratchet advances per message.

QR codes encode the identity key (or its fingerprint) for contact exchange. This provides built-in key verification — when Alice scans Bob's QR code, she receives his actual public key. When she later fetches his pre-key bundle from the Key Server, she can verify it matches.

The Key Server is a directory indexed by public identity key fingerprints, storing pre-key bundles. It has no concept of "accounts" — just keys pointing to key material.

## Options Considered

1. **Random server-assigned ID (Threema model)** — good privacy, but identity and key are separate concepts requiring an extra verification step. Rejected.
2. **Username/handle** — introduces a namespace (squatting, impersonation) and a persistent pseudonym. Rejected.
3. **Public key as identity (chosen)** — strongest verification, no server-side identity concept beyond key fingerprints.
4. **Layered (random ID + optional display name)** — considered as fallback, but the key-as-identity model is cleaner and provides built-in verification.

## Consequences

- Key change = identity change. If a user loses their seed phrase and generates new keys, they appear as a new person. All contacts must re-scan. This is the intended security property, not a bug.
- Key change detection with clear user warning is a PoC requirement (see ADR 001). The client must flag when a contact's identity key changes.
- The Key Server's data model is minimal: fingerprint → pre-key bundle. No accounts table, no user metadata.
- Display names are a client-side concept. Alice can label Bob "Bob Smith" in her local contacts. This name is never sent to any server.
- The seed phrase / KDF recovery path (ADR 014) allows deterministic key regeneration, so key loss only occurs if the seed phrase is also lost.
