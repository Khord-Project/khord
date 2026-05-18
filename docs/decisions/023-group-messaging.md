# ADR 023: Client-Side Group Messaging via Pairwise Fan-Out

**Status:** Accepted
**Date:** 2026-05-18

## Context

Group conversations are a basic expectation of modern messaging. The
PoC needs them, but the constraint is sharp: nothing must compromise
the metadata-minimisation property the two-server architecture is
built on. The Relay Server is supposed to see opaque message
deliveries between anonymous mailboxes; the Key Server is supposed to
distribute pre-key bundles. Neither should know "Alice, Bob, and
Carol are in a group called Family."

Four options were considered.

### Option 1 — Pairwise fan-out (D2, the choice in this ADR)

Groups exist only on client devices. Each group has a `group_id` and a
member list known to participating clients. Sending a group message
encrypts and posts it `N` times — once per other-member — through
each member's existing pairwise Double Ratchet channel.

  - **Privacy:** the Relay Server sees `N` separate per-mailbox
    deliveries that look exactly like 1:1 messages. The Key Server
    sees nothing new. Neither learns that a group exists. Both
    architectural goals are preserved.
  - **Server impact:** zero. No new endpoints, no new tables, no new
    auth shapes. The entire feature is client-side.
  - **Cost:** `O(N)` ratchet encryptions and `O(N)` POSTs per outbound
    group message. For PoC group sizes (≤ ~20) this is acceptable.
  - **Consistency:** depends on per-channel message delivery. If one
    fan-out leg fails, that member is silently absent until the next
    successful send. Eventual consistency at the protocol layer; UI
    surfaces this via dead-contact detection (ADR's commit
    `3d90511`).

### Option 2 — Server-side group routing

The Relay Server learns about groups; clients post once to a group
mailbox and the server fans out to members.

  - **Privacy:** kills metadata minimisation. The Relay Server now
    knows the full membership and traffic pattern of every group.
    Hard rejection.

### Option 3 — Sender Keys (Signal's group protocol)

A per-group symmetric sender key is distributed pairwise, after which
messages encrypt once and the same ciphertext is fanned out. Mandatory
re-key on every member leave.

  - **Privacy:** equivalent to fan-out — server still sees `N` per-
    mailbox deliveries.
  - **Cost:** 1 encryption + `N` posts (we save `N-1` ratchet ops vs
    fan-out). Useful for large groups (~50+).
  - **Complexity:** introduces a new key-management layer, re-key
    semantics, and ciphertext-equality across recipients. Significant
    correctness surface for marginal PoC benefit.
  - **Decision:** deferred. The interfaces in this ADR (group_id,
    group_invite, group_member_added, etc.) are compatible with a
    later Sender Keys layer — we'd add a `group_sender_key` payload
    type and a per-group key state machine. No protocol changes here
    block it.

### Option 4 — Split-trust group server

A third server role with admin-managed group membership and ACLs.

  - **Privacy:** introduces a third trust boundary. Possibly worth it
    for an organisational deployment mode (see D-005 in DEFERRED.md)
    but a major architectural commitment for the individual PoC.
  - **Decision:** deferred to D-005's organisational track. Not in
    scope for individual messaging.

## Decision

**D2 — pairwise fan-out.** Groups exist only on clients. All group
state (membership, name, message log) lives in the local SQLDelight
database. All group communication travels through existing
per-contact Double Ratchet channels using new payload types defined
on the inner-payload `type` discriminator.

### Inner-payload types added

  - `group_invite` — carries `group_id`, `group_name`, and the full
    `members` list. Sent by the inviter to each new member.
  - `group_message` — text body addressed to a group context. Carries
    `group_id` + `body`.
  - `group_member_added` — admin notifies existing members.
  - `group_member_left` — sender (the leaver, or an admin removing
    them) notifies remaining members.
  - `group_name_changed` — admin renames.

All travel inside the existing AEAD-encrypted inner payload and
inherit the existing `reply_info` self-healing display-name update.
The Relay Server sees normal pairwise messages.

### Admin model

The group creator is the admin. Other members are not. Admin-only
operations (add member, remove member, rename) are gated client-side
on every receiver: a `group_member_added` or `group_name_changed`
arriving from a non-admin sender is silently ignored. Multi-admin and
admin transfer are out of scope for this PoC.

### Cross-member friendship constraint

Group messages reach all members **only if the sender has a pairwise
Double Ratchet session with each recipient.** Example:

  - Alice creates group `[Alice, Bob, Carol]`. She has sessions with
    both Bob and Carol. Her sends fan out to both.
  - Bob receives Alice's message and wants to reply. Bob's send goes
    to every other member via his sessions. If Bob has never scanned
    Carol's QR (no Bob↔Carol session), his reply reaches Alice only.
    Carol never sees Bob's reply in the group.

This is intrinsic to fan-out + zero cross-introduction. A future
"introduce group members to each other on join" feature would solve
it but needs its own consent model (do members want to be auto-
introduced to strangers in a group they just joined?). Deferred. For
the PoC the limitation is documented and acceptable.

### Persistence schema

Three new tables (`group`, `group_member`, `group_message`) with
`ON DELETE CASCADE` from the group row through members and messages.
Sent and received group messages share a log keyed by `group_id`;
they do NOT inherit the per-contact `message` table (so that a new
member added later does not accidentally see pre-join history if a
future "scrollback" feature is built).

### History on join

New members receive **no pre-join history.** This is a deliberate
property of fan-out — the protocol has no notion of replay. Members
who joined after a message was sent will never see that message.

## Privacy property

**Neither server learns that groups exist.**

  - Key Server: untouched. Group membership is never queried,
    posted, or hinted at.
  - Relay Server: sees `N` per-mailbox encrypted blobs per outbound
    group message, indistinguishable from a sender having a casual
    coincidence of writing to N different mailboxes in quick
    succession. No group_id, member list, or admin status crosses
    the wire unencrypted.

## Trade-offs

  - **Bandwidth:** `O(N)` outbound vs. `O(1)` for a server-routed
    group. For a 20-member group, every send is 20 individual posts.
    Acceptable up to ~50 members; degrades from there. Sender Keys
    (see deferred D-002) closes most of this gap.
  - **Latency:** N sequential ratchet encrypts per send. Currently
    not parallelised; each member's session is touched in turn. Could
    be parallelised over a coroutine pool if needed (not in PoC).
  - **Consistency:** if one fan-out leg fails (HTTP error, dead
    contact), that recipient silently misses the message. The dead-
    contact detection (ADR-equivalent in commit `3d90511`) already
    surfaces persistent failures via the contact list mute indicator.
  - **No admin transfer:** if the admin leaves, the group has no
    admin. Existing members remain in the group; subsequent add /
    rename operations from anyone are ignored by every other client's
    auth gate. Acceptable for PoC; admin transfer is a feature
    follow-up.

## Consequences

  - Zero server changes — the relay-side gotcha #6 stop+start dance
    is not triggered by this feature.
  - Group consistency depends on message delivery; dead-contact
    detection surfaces failure modes.
  - Sender Keys remains a clear optimisation path with no protocol-
    breaking change required to adopt.
  - Cross-introduction is an open feature; without it, large groups
    require every member to have scanned every other member's QR.
