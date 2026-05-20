# ADR 025: Seed-Phrase Recovery Restores Identity, Not State

**Status:** Accepted
**Date:** 2026-05-20

## Context

Khord stores everything client-side. A user who loses their app state
— Keystore invalidation (MIUI / OnePlus aggressive background-kill
ROMs), device change, app reinstall after panic — currently has no
recovery path. They can re-onboard, but the new identity has a
different fingerprint and their existing contacts won't recognise
them.

The identity-derivation path is already deterministic:
`SeedPhrase` → `IdentityKey.fromSeedPhrase(canonical)` → Argon2id(
phrase, "khord-identity01") → Ed25519 keypair → fingerprint =
`SHA-256(pub)`. Same words ⇒ same key ⇒ same fingerprint.

The Key Server already supports re-registration with the same
fingerprint as long as the public identity key matches:
`servers/keyserver/app/routers/keys.py:97-109`. The check is by
construction satisfied by anyone holding the seed phrase.

What was missing:

  1. A UI entry point for "I already have a phrase".
  2. Recovery protocol behaviour: how does the counterparty's app
     react when a recovered user re-establishes their session?

## Decision

Recovery restores the **identity** (private/public keypair,
fingerprint) but not the **state** (message history, contacts,
ratchet sessions, group memberships, mailbox bindings). The seed
phrase is sufficient input.

After recovery, the user re-adds contacts via QR scan as if they were
new — but their contacts' apps recognise them as the same identity
because the fingerprint matches, and the Double Ratchet session is
**reset** by the next outbound X3DH initial. The chat history on the
counterparty's device stays readable; new messages append after a
visible "Session reset" marker.

### What IS recovered

- The Ed25519 / X25519 identity keypair
- The ability to re-register on the Key Server under the same
  fingerprint (challenge-response succeeds because the re-derived
  public key bit-matches the stored one)
- The fingerprint-identity that contacts use to recognise you

### What is NOT recovered

- Message history (lived in the old SQLCipher database with the lost
  passphrase)
- Contact list (same database)
- Per-contact Double Ratchet state (same database)
- Pending one-time pre-keys (the orchestrator generates fresh ones on
  re-register; old OPKs on the Key Server are replaced)
- Group memberships (groups are a client-side construct)

### Protocol changes

`Messaging.receiveMessages` previously assumed every envelope on a
bound mailbox was a `WireEnvelope.Ratchet`. It treated
`WireEnvelope.X3dhInitial` envelopes as if they were ratchet
ciphertexts and handed them to the existing ratchet — guaranteed MAC
failure, message silently dropped. This was always wrong in the
abstract; recovery makes it concretely necessary.

Two paths now handle re-initialised contacts:

**Case A — X3DH initial on a bound mailbox.**
`Messaging.receiveMessages` detects `WireEnvelope.X3dhInitial` and
delegates to `applyX3dhInitialReset`. The helper:

  1. Verifies `identityFingerprint(envelope.ikA) ==
     existingContact.contactFingerprint` (anti-impersonation gate —
     anyone in the wire can craft an X3DH-shaped envelope, but only
     the holder of the matching identity key can produce a valid
     X3DH respond).
  2. Runs `X3dh.respond` to derive a fresh shared secret. Consumes
     and wipes the referenced OPK as usual.
  3. Builds a new `Session` via `Session.fromResponder`.
  4. Updates the in-memory `ContactSession` (replacing the ratchet
     and the outbound coordinates from the new `reply_info`).
  5. Persists the new session row (UPSERT on contact_fingerprint
     PK, overwriting the stale state).
  6. Inserts a `MessageDirection.RECEIVED` row with the stable
     `Messaging.SESSION_RESET_MARKER_BODY` text so the chat reads
     continuously across the recovery boundary.
  7. Decrypts and stores the first post-reset message.

On fingerprint mismatch the helper throws; `receiveMessages` catches,
logs via `commonDiagnosticLog`, and skips the envelope so a malformed
initial can't wedge the mailbox.

**Case B — X3DH initial on a freshly minted pending mailbox from a
known fingerprint.** `receiveInitialBlobInternal` (driven by
`pollPendingMailboxes`) now checks `sessionForFingerprint(initiatorFp)`
before inserting. If a session with a DIFFERENT inbound mailbox
exists, it's removed from the in-memory map and a session-reset
marker is saved. The persisted session row UPSERTs by PK anyway, so
persistence stays consistent. Without this fix, the user would see
the same contact appear twice in their list (one in-memory entry per
mailbox) until the next process restart.

### UI flow

```
WelcomeScreen
    │
    ├── [Generate identity]  → ServerSetup → SeedDisplay → SeedConfirm → Registration
    │
    └── [I already have a seed phrase]  → SeedRecovery → ServerSetup → Registration
```

`SeedRecoveryScreen` is a single multi-line textarea. Validation runs
live: token count, BIP39 word membership (via the newly-public
`SeedPhrase.isValidWord`), and full-phrase checksum (via the existing
`SeedPhrase.toEntropy`). The Recover button is disabled until all
three pass.

`ServerSetupScreen` checks `AppContainer.onboardingViewModel?.isRecovering`
after the user picks servers — if set, it routes straight to
`RegistrationScreen`, skipping `SeedDisplay` / `SeedConfirm`.

`RegistrationScreen` reuses its existing "What should contacts call
you?" prompt + register path; the only deltas are the headline copy
("Recovering identity" vs "Almost done") and the progress label
("Re-deriving keys + re-registering your identity…").

### Replay-DOS caveat

Anyone holding a captured X3DH initial can re-send it to force a
session reset on the responder side. They cannot decrypt subsequent
messages (they don't hold the initiator's ephemeral secret), so the
attack is a one-shot DOS: the responder loses ratchet sync with the
real initiator until the latter sends another initial. We accept
this for the PoC; a future hardening would add a signed monotonic
counter to the X3DH initial wire format so replays are rejected.

### OPK exhaustion

Each X3DH respond consumes one OPK. After many recoveries against a
counterparty whose OPK supply hasn't been replenished, the
re-establishment would fall back to the fallback-no-OPK X3DH path
(legal but weakens forward secrecy for that one message). In
practice both parties' apps replenish OPKs on every cold start via
`register()`, so the supply normally stays healthy.

## Test coverage

`EndToEndIntegrationTest.seed_phrase_recovery_resets_session_on_known_fingerprint`
exercises the full recovery loop against the real Key Server + Relay
Server stack (`-Dkhord.integration=true`):

  1. Alice and Bob register and exchange two messages.
  2. Alice's orchestrator and persistence are closed; her DB file is
     discarded.
  3. Alice re-derives her `IdentityKey` from the same seed phrase
     and opens a brand new persistence + keystore.
  4. Alice re-registers (Key Server's identity-key match passes).
  5. Alice sends a fresh X3DH initial to Bob's original mailbox.
  6. Bob loads from his (untouched) persistence, polls his bound
     mailbox, hits `applyX3dhInitialReset`, decrypts the new
     message.
  7. Bidirectional exchange continues under the new ratchet.
  8. Bob's history contains the pre-recovery message, the reset
     marker, and the post-recovery message in order.

The InMemoryPersistence test suite (commonTest) doesn't cover the
reset path because the in-memory variant doesn't exercise the real
crypto/ratchet boundary; the integration test is the truth source.

## Consequences

- Users who lose state can recover into the same identity with no
  out-of-band coordination beyond the 12 words they wrote down at
  onboarding.
- Contacts' apps surface the reset event explicitly via the marker
  message — no silent state divergence.
- The X3DH-on-bound-mailbox case was a latent receive-path bug; this
  ADR also makes the protocol behaviour explicit.
- The relay-DOS surface widens slightly (replay-induced session
  resets); accepted for PoC, hardening tracked separately.
- One new public API on `SeedPhrase` (`isValidWord`) — small, stable.
- One new public constant on `Messaging.Companion`
  (`SESSION_RESET_MARKER_BODY`) so the chat UI can pattern-match if
  it wants styled rendering later.
