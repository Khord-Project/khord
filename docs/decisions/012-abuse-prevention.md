# ADR 012: Rate Limiting and Abuse Prevention

**Status:** Accepted
**Date:** 2026-05-02

## Context

Servers must prevent abuse (spam, flooding, mass registration) without knowing user identities. Traditional rate limiting (per-account, per-IP) creates identity signals.

## Decision

**Key Server:** Rate limit per identity key fingerprint. "This fingerprint can only upload N pre-key bundles per hour." The Key Server already knows fingerprints by design, so this leaks no additional metadata.

**Relay Server:** Two mechanisms:
1. **Per-bearer-token rate limiting** for ongoing abuse — each mailbox token has request rate limits. Prevents flooding a specific mailbox.
2. **Proof of work for mailbox creation** — creating a new mailbox requires the client to solve a computational puzzle (Hashcash-style). Cheap for legitimate users creating a few dozen mailboxes, expensive for attackers creating thousands. No identity required, no social graph signal.

Proof-of-work difficulty is configurable — low for PoC, adjustable in production based on observed abuse.

## Consequences

- Legitimate users experience no meaningful friction — proof of work for mailbox creation takes seconds on modern devices.
- Mass-creation attacks are economically expensive for attackers.
- No IP-based rate limiting, which would create location metadata.
- Proof-of-work parameters should be documented and tunable without code changes.
