# ADR 030: Offline Message Queue with Auto-Retry

**Status:** Accepted
**Date:** 2026-05-30

## Context

Until now a send that hit a network error (no connectivity, relay
unreachable, transient 5xx) simply threw — the message was lost and the
user had to retype it. Mobile networks drop constantly; a messenger has
to tolerate "compose now, deliver when reachable" (#59).

Constraints unique to Khord:

1. **The Double Ratchet advances on every encrypt.** Encrypting a
   message mutates the sending chain in place. If we encrypt eagerly,
   fail to deliver, then re-encrypt on retry, the ratchet advances twice
   for one delivered message — the recipient sees a gap and burns a
   skipped-message key. Delivery order and ratchet order must stay in
   lockstep.
2. **No server-side store after delivery.** The relay discards messages
   on ack, so a queue can only live on the client.
3. **At-rest encryption.** The local DB is SQLCipher-encrypted, so
   queued plaintext is protected at rest.
4. **commonMain has no filesystem API.** `drainOutbox()` lives in the
   shared module, which can't read or write files portably.

## Decision

A persistent `outbox` table holds messages awaiting delivery. The
message is saved to chat history immediately as `pending`; delivery is
attempted, and on failure the message is queued and retried from several
triggers until it succeeds or hits a retry cap.

### Encrypt at delivery time, with ratchet rollback

The outbox stores **plaintext** (body + image bytes), never ciphertext.
Encryption happens inside `drainOutbox()` at the moment of delivery, so
the ratchet advances in true send order.

The subtle part: `encrypt()` is local and always succeeds; it's the
*network* send that fails — and by then the ratchet has already advanced.
So `deliverRatchet()` snapshots the **sending chain only** (`CKs` + `Ns`,
the exact fields `DoubleRatchet.encrypt` mutates) before encrypting and, if
the network send fails, rolls those back (`RatchetState.snapshotSendChain()
/restoreSendChain()`). A retry then re-encrypts from the identical
position — no skipped or duplicated ratchet steps.

It is deliberately **not** a full-state snapshot: `receiveMessages()`
mutates the *receiving* chain and runs unsynchronised with the send path
(driven by the push service). A whole-state rollback would clobber
receive progress committed while a send's relay POST was in flight,
silently dropping inbound messages and desyncing the channel.

The advanced state is persisted only after the relay accepts the blob —
and a *persist* failure at that point does **not** roll back (the message
was delivered; re-sending at a consumed message number would desync the
recipient). Send/edit/group-control encrypts all serialise on one
`sendMutex` so two encrypts never interleave on the same chain.

### Status model

`delivery_status` on `message` / `group_message` (nullable):

- `pending` — saved, an outbox row exists, will (re)try. Clock icon.
- `sent` — relay accepted it. No indicator.
- `failed` — gave up after the cap. Red warning; tap → Retry / Delete.

`null` on every received message and on pre-ADR-030 history (renders like
the old behaviour). The outbox row carries its own `status`
(`pending`/`sending`/`failed`) + `attempts` + `last_error`.

### Retry cap

`MAX_OUTBOX_ATTEMPTS = 10`. Past that an item is marked `failed` rather
than retried forever; the user retries (resets the counter) or deletes it
from the failed-message dialog.

### Drain triggers

`drainOutbox()` runs (oldest-first, each item independent so one dead
recipient doesn't block others) on:

- **network available** — `ConnectivityManager.registerDefaultNetworkCallback`
  in the push service; the primary trigger.
- **app launch** — after bootstrap, fire-and-forget.
- **chat open** — alongside the existing receive-drain.
- **push (re)connect** — the relay being reachable again is a good signal.

A `tryLock` mutex makes concurrent drains (and a drain racing a live
send) a no-op rather than double-sending or corrupting the ratchet.

### Panic integration

`panic()` calls `clearOutbox()` before the file-level wipe — unsent
plaintext must not survive a panic.

### Contact / block handling

Deleting a contact clears its queued items (`deleteOutboxForContact`), so
we never retry into a torn-down session. The drain skips blocked
contacts (held, not dropped — the user may unblock) and drops items whose
1:1 session no longer exists (marking the message failed).

## Deviations from the original spec (and why)

- **Image bytes are stored as BLOBs, not a `media_local_path`.**
  `drainOutbox()` runs in commonMain with no filesystem API, so it can
  neither write the file at queue time nor read it at drain time. The
  bytes are already EXIF-stripped + downscaled (ADR 029), the DB is
  encrypted, and the retry cap + delete-on-success bound growth.
- **Group queue is per-message, not per-member.** A group send fans out
  to N pairwise channels; the outbox holds one row keyed by `group_id`.
  If delivery partially succeeds (some members reached, some not) and the
  item is retried, members who already received it may see a duplicate.
  Accepted for the PoC — same-network fan-outs typically all-succeed or
  all-fail together, so partial failure is uncommon.
- **A live send can jump a backlog.** `sendMessage` tries immediate
  delivery; if older items are already queued for the same conversation
  and the network just recovered, the new message can be delivered before
  them. The drain flushes the backlog quickly; strict cross-boundary FIFO
  wasn't required.

## Consequences

- **Schema migration 12 → 13**: `outbox` table + `delivery_status`
  columns. Invisible to the FTS5 indexes (body-only).
- All outbound sends now save-first → the message appears instantly with
  a pending indicator, improving perceived latency even online.
- An orphaned media blob may be left on the relay if an image's upload
  succeeds but the message send then fails (the next retry re-uploads);
  these TTL out under ADR 029's one-time-read + expiry.
