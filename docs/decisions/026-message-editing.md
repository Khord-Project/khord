# ADR 026: Message Editing via UUID-Referenced Payload

**Status:** Accepted
**Date:** 2026-05-23

## Context

Users send messages with typos, autocorrect surprises, or wrong
contexts they want to amend. Without an edit feature the only
options today are deletion (which loses the user's intent and
leaves orphan reply context) or sending a correction in a follow-up
message (clutters history, doesn't actually fix the original).

Constraints unique to Khord:

1. **No server-side message store after delivery.** The Relay
   Server discards messages on ack, so an "edit" can't be a
   server-side mutation — there's nothing on the server to mutate.
2. **End-to-end encrypted.** The relay never sees plaintext, so
   it can't index messages by content.
3. **Pairwise channels only.** All communication between two
   users (1:1 OR within a group fan-out) flows through their
   Double Ratchet session. Edits must use the same path.
4. **No edit history requested.** The user spec is explicit:
   "Don't track edit history (no 'view original' feature for
   now)." That keeps the schema flat and avoids the moral panic
   around "can the recipient prove what was originally sent" that
   comes with edit history.

## Decision

Add a stable per-message UUID stamped at send time, and a new
`message_edit` payload type that targets a previously-sent
message by that UUID.

### Schema

Two new columns on both `message` and `group_message`:

- `message_uuid TEXT` (nullable) — the sender-issued UUID. Pre-
  alpha.14 rows have NULL here; they remain un-editable.
- `edited INTEGER NOT NULL DEFAULT 0` — set to 1 by an applied
  edit. The UI surfaces `(edited)` next to the timestamp.

A non-unique index on `message_uuid` accelerates the inbound-edit
lookup. Globally unique with overwhelming probability (128 bits
random), so a non-UNIQUE index keeps the door open to idempotent
reprocessing in degenerate cases (the same edit envelope
delivered twice — re-applying is harmless).

### Payload format

```json
{
  "type": "message_edit",
  "timestamp": "2026-05-23T10:15:00Z",
  "message_uuid": "<uuid-of-original-message>",
  "new_body": "the corrected text",
  "group_id": "<optional, present for group-message edits>",
  "reply_info": { ... standard reply_info ... }
}
```

`messageUuid` and `newBody` are required; everything else is
either inherited from the protocol's normal envelope (sender
fingerprint, AEAD, etc.) or optional context. `groupId` is
included on group edits so the receiver can route the lookup to
the `group_message` table without first probing the 1:1 table.
(Implementation actually probes both, but having `groupId` on the
wire allows future optimization.)

### Send side

`Messaging.editMessage(messageUuid, newBody)`:

1. Look up the message locally by UUID. If not found → return
   `false`. If direction is RECEIVED (we didn't send it) → return
   `false` (anti-spoof: we never fan out edits for messages we
   didn't author).
2. For 1:1: encrypt + send the edit payload through the contact's
   session. Update our local copy via
   `updateMessageBodyByUuid` (sets `body` + `edited=1`).
3. For group: fan out the edit to every group member with an
   active session (same pattern as group_message). Update our
   local group_message row.

### Receive side

`handleMessageEdit(contact, payload)` in the orchestrator's
`receiveMessages` dispatch:

1. Extract `messageUuid` + `newBody` from the payload. Drop on
   missing.
2. Try 1:1 first: `findMessageByUuid(uuid)`. If found, verify
   (a) the matched row is direction=RECEIVED (an edit targeting
   our own SENT row would be Eve impersonating us — drop), and
   (b) the matched row's contactFingerprint matches the session
   sender (an edit from a different contact targeting Alice's
   message — drop). On success, `updateMessageBodyByUuid`.
3. Fall through to group: `findGroupMessageByUuid(uuid)`. Verify
   the matched row's `sender_fingerprint` matches the session
   sender. On success, `updateGroupMessageBodyByUuid`.

The Double Ratchet AEAD already proves session authenticity; the
sender-match check above prevents Bob from sending us a forged
edit for Alice's message via Bob's session, claiming Alice's
content.

## Trade-offs

### Best-effort delivery

If a recipient is offline at the moment of edit, they see the
original body until the next time their app drains the mailbox
and the edit envelope is delivered. There is no retry-until-seen
mechanism — once the sender's `editMessage` returns, the edit is
fire-and-forget from their perspective. This is consistent with
how regular message delivery works in Khord today.

**Implication:** the "edited" badge can show inconsistently across
recipients. Alice edits a message; Bob is online and sees
"original" → "edited"; Carol is offline for a week and sees
"edited" directly when she comes back. Bob and Carol both
end up with the right final content. Hidden history of the
original body persists in Bob's cache between original delivery
and the edit's application — at the local-OS level only;
Khord's DB no longer holds it once the edit applies.

### Pre-alpha.14 messages remain un-editable

Migrating UUIDs onto existing rows would require either:

  - a back-fill that risks collisions if the same fingerprint
    appears across multiple devices, or
  - a heuristic like `hash(contact_fingerprint || timestamp ||
    body)` that's only stable until the body changes (which is
    exactly when it would matter).

Neither was worth the complexity. The "un-editable" range is
small (alpha.X messages from current testers), and new messages
are immediately editable from alpha.14 onward.

### Single-UUID-per-edit, no chain

We update the body in place rather than appending an edit chain.
This matches the spec ("Don't track edit history") and keeps the
data model simple. If a future version wants edit history it can
add a separate `message_edit_history` table without breaking
this design — the existing `edited=1` flag survives a chain-based
extension fine.

### Group fan-out cost

Editing a group message of size N members costs O(N) ratchet
encryptions + O(N) relay POSTs — same as sending a fresh group
message. No optimization here; group fan-out is already the
established model and edits are infrequent.

## Alternatives considered

- **Server-side replace** — rejected: no server-side store after
  ack, would require fundamental architecture change.
- **Message version chain (each edit a new message, original
  marked superseded)** — rejected: spec says no history, adds
  protocol complexity, recipients with delivery gaps would see
  the chain in fragments.
- **Timestamp + sender + first-N-chars as edit key** — rejected:
  brittle, breaks on duplicate messages within a second, doesn't
  survive body changes.
- **Per-edit fan-out signed by original sender** — rejected:
  we already get authenticity from the Double Ratchet AEAD on
  the edit envelope; no need for double signing.

## What this is NOT

- **Not retroactive.** Pre-alpha.14 messages stay un-editable
  for the reasons above.
- **Not editable from another device.** A user with seed-phrase
  recovery on a new device can't edit messages they sent before
  the recovery — the new device has no record of those UUIDs.
- **Not a delete-or-redact mechanism.** Editing to an empty
  string is rejected client-side; if the user wants to remove a
  message they should use the existing delete-conversation flow
  (which is local-only by design — see ADR 020-ish notes).
- **Not a "received message" feature.** Recipients can't edit
  messages they were sent. Long-press on a received message
  exposes Copy only, not Edit.
