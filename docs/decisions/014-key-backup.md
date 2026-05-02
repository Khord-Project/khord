# ADR 014: Key Backup — Seed Phrase + Optional Shamir

**Status:** Accepted
**Date:** 2026-05-02

## Context

Identity is the key. Losing the key means losing the identity — all contacts must re-scan. A recovery mechanism is essential, but it must not involve any server or create a digital attack surface.

## Decision

**Primary: Seed phrase (BIP39-style mnemonic).** The identity key is derived deterministically from a seed phrase via Argon2id KDF. Users record this phrase on paper. Recovery means entering the words on a new device, deterministically regenerating the identity key pair.

**Custom passphrase option:** Users may provide their own passphrase instead of using the generated one, subject to:
- Live entropy estimation displayed in real time (zxcvbn-style, adapted for passphrases)
- Hard minimum floor of 80 bits entropy — below this, the app refuses (not warns, refuses)
- Recommended minimum of 128 bits — between 80 and 128, the app warns clearly but allows
- Above 128 bits — green, no warning
- Argon2id with aggressive parameters regardless of passphrase source

**Optional: Shamir's Secret Sharing.** The seed can be split into N parts where any K parts reconstruct the secret (e.g., 3-of-5). Individual parts are mathematically useless — having fewer than K parts gives zero advantage. Users distribute parts to trusted locations (safe deposit box, trusted person, printed copy, etc.).

**Separate from message backup.** Key backup recovers identity only. Message backup (ADR 015) recovers conversation content only. The two never mix. A recovered identity still requires re-establishing server-side state (fresh pre-key uploads, new mailbox creation) and having contacts re-verify.

## Consequences

- The KDF path (Argon2id, parameters, derivation to Ed25519/X25519 key pair) must be documented precisely — it is the critical recovery mechanism.
- Seed phrase generation must use a cryptographically secure random source.
- Custom passphrase validation logic must be part of the client, not offloaded to any server.
- Organizational deployment (deferred) will use a different recovery model — admin-managed key escrow. The individual flow is not modified for this; it is a separate deployment mode.
