# ADR 015: Message Persistence — Client-Local + Encrypted Backup

**Status:** Accepted
**Date:** 2026-05-02

## Context

Where message history lives determines the exposure surface if a device is compromised or lost.

## Decision

**Client-local storage only.** Messages are stored on each device's local storage. Servers never persist anything beyond the delivery window.

**Encrypted backup (optional).** Users can export an encrypted backup of their conversation history. The backup is encrypted under a backup-specific key derived from a user-chosen passphrase.

**Backup contents — strict rules:**
- Contains: conversation content (message text, client-generated timestamps, display names)
- NEVER contains: identity key pair, contact identity keys in linkable form, relay mailbox IDs, bearer tokens, ratchet state, or any protocol machinery

A leaked backup reveals message content if the passphrase is cracked. It cannot be used to impersonate the user, identify contacts on the network, or correlate with server-side data.

**Panic button.** A client-side feature that immediately wipes all local data — message history, keys, identity, everything. One tap, scorched earth. This is a PoC feature due to its simplicity and high value for the threat model.

The panic button destroys local data but does not destroy separately-stored encrypted backups. This is intentional — the backup is the user's responsibility and may be their recovery path.

## Consequences

- Losing a device without a backup means losing message history permanently.
- Backup/restore does not restore protocol state — after recovery, the user has readable history but must re-register keys and have contacts re-verify.
- The backup format must be documented so users are not locked in to a specific client implementation.
- Timed disappearing messages (auto-delete after N hours/days) are deferred. The architecture supports them as a future client-side feature with timer metadata in the encrypted payload.
