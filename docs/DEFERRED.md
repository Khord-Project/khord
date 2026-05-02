# Deferred Decisions

This document tracks decisions that were explicitly deferred during the initial design phase. Each entry includes context on why it was deferred, what the trade-offs are, and any constraints on future implementation (i.e., things we must not do now that would block these later).

## Format

Each entry follows:
- **What:** The decision that was deferred
- **Why deferred:** Why it's not needed for the PoC
- **Trade-offs:** What we know about the options so far
- **Blockers to avoid:** Things we must not do in the PoC that would make this harder later
- **Trigger:** When this decision should be revisited

---

## D-001: Multi-Device Sync

**What:** Supporting multiple devices per user (phone + laptop, etc.)

**Why deferred:** Adds significant client-side complexity (multiple ratchet sessions per contact, per device). Not needed to prove the protocol and architecture work.

**Trade-offs:** Signal's Sesame model (each device is an independent identity with its own key pair, pre-key bundles, and per-contact mailboxes) is the likely path. It preserves server-side simplicity — neither server needs to know that two devices belong to the same user. The cost is multiplicative encryption: sending a message to a contact with N devices means N separate encryptions.

Threema's mediator server model was considered and rejected — it introduces a third server that sees device groupings, conflicting with metadata minimization goals.

**Blockers to avoid:**
- Do NOT bake in a one-device assumption (e.g., single mailbox per identity key)
- Ensure the relationship is "identity key → many mailboxes" not "identity key → one mailbox"
- The per-contact mailbox model (ADR 005) already supports this naturally

**Trigger:** When the PoC is validated and the app moves toward daily-driver use.

---

## D-002: Group Messaging

**What:** End-to-end encrypted group conversations.

**Why deferred:** Group E2E encryption is a substantially harder problem than one-to-one. Requires either Sender Keys (Signal's approach — one encryption per message but weaker forward secrecy) or MLS (IETF standard — tree-based key agreement, better properties, more complex). Both require group key management and re-keying when members leave.

**Trade-offs:**
- Sender Keys: simpler, battle-tested in Signal. Re-keying on member removal is mandatory (former members still have the old key otherwise). This is computationally invisible to users — they see "Alice left the group" not "re-keying in progress."
- MLS: newer standard, better scaling for large groups, stronger forward secrecy. Fewer battle-tested implementations.
- Re-keying on member removal is non-negotiable regardless of approach — there is no cryptographic trick to avoid it.

**Blockers to avoid:**
- The encrypted payload type field (ADR 007) must accommodate group message types in the future
- Do NOT design the mailbox model in a way that assumes bilateral communication only

**Trigger:** After one-to-one messaging is stable and tested. Group messaging is a feature milestone, not an architectural prerequisite.

---

## D-003: Media / File Attachments

**What:** Sending images, files, voice messages, and other non-text content.

**Why deferred:** The protocol proves out identically regardless of message content — it's all bytes inside the encrypted envelope. Media adds infrastructure (file storage, upload/download endpoints, size limits, thumbnail generation) irrelevant to proving the privacy architecture.

**Trade-offs:** The standard pattern is: encrypt media with a one-time symmetric key, upload encrypted media to a storage endpoint (separate from regular message routing), send the symmetric key + download URL inside the regular encrypted message. Recipient fetches and decrypts separately.

**Blockers to avoid:**
- The message type field in the encrypted payload (ADR 007) MUST exist — media messages use a different type ("media_reference") than text
- The Relay Server's blob routing must not impose a size limit that would prevent future use for media references (the reference itself is small — the actual media goes elsewhere)

**Trigger:** After text messaging is working end-to-end. Media is the most-requested feature after group chat.

---

## D-004: Timed Disappearing Messages

**What:** Messages that auto-delete from local storage after a configured time period, with mutual agreement between conversation participants.

**Why deferred:** Purely client-side feature with no protocol implications beyond carrying timer metadata in the encrypted payload. The panic button (immediate wipe) is in PoC scope as it's simpler and higher-value.

**Trade-offs:** Option B (mutual agreement — both parties agree on a timer, either can propose changes, both must accept) was preferred over sender-controlled or receiver-controlled timers.

**Blockers to avoid:**
- The encrypted payload format must be extensible enough to carry optional metadata like a disappearing timer
- Using a fixed/rigid payload schema that can't accommodate new fields would block this

**Trigger:** After basic messaging is stable. This is a UX polish feature.

---

## D-005: Organizational Deployment Mode

**What:** A deployment mode for companies/organizations where key management is centralized, contact discovery is automated, and key recovery is admin-managed.

**Why deferred:** Fundamentally different trust model from individual use. Individual users need protection FROM central authority; org users need central authority to function. These are almost opposite requirements.

**Trade-offs:** Org deployment would include:
- Organization-operated Key Server (and possibly Relay Server)
- Admin console for key generation on behalf of users, or key escrow
- Key recovery through organizational policy (Shamir split held by IT admins, HSM, encrypted escrow)
- Optional discovery/introduction server for finding colleagues without QR scanning
- Automated onboarding (no seed phrase ceremony for employees)
- Explicit privacy caveats — the organization has recovery capabilities that individual users don't

**Blockers to avoid:**
- The protocol must not change for org deployment — same message format, same encryption, same server APIs
- Do NOT hardcode individual-only assumptions into the Key Server (e.g., "one identity key can only be registered by the key holder") — org deployment may need admin registration
- Keep the Key Server a standalone deployable unit — org deployment replaces the operator, not the software

**Trigger:** After the individual PoC is validated. Org deployment is a separate product milestone.

---

## D-006: Read Receipts and Typing Indicators

**What:** Indicators showing when a message was read or when the other party is typing.

**Why deferred:** Killed for individual use, not just deferred. These features create social pressure and leak activity metadata, contradicting the privacy-first positioning.

**Trade-offs:** May be relevant for organizational deployment where different norms apply. If implemented, they must be opt-in per contact, never default on.

**Blockers to avoid:** None — these are regular encrypted messages from a protocol perspective. No architectural consideration needed.

**Trigger:** Only revisit if org deployment requires it. Not planned for individual use.

---

## D-007: Voice / Video Calls

**What:** Real-time encrypted voice and video communication.

**Why deferred:** Entirely different protocol layer (WebRTC, SRTP). Different infrastructure requirements (TURN servers, media relay). Different privacy challenges (real-time traffic is much harder to obfuscate than asynchronous messaging).

**Blockers to avoid:** None specific — voice/video is additive infrastructure.

**Trigger:** After messaging (including group) is stable and the user base justifies the infrastructure investment.

---

## D-008: Key Server Governance

**What:** Who operates the Key Server in production, under what legal structure, with what audit and transparency obligations.

**Why deferred:** This is an organizational and legal decision, not a technical one. The architecture ensures the Key Server is a standalone deployable unit — the governance question is about who runs it and under what obligations.

**Trade-offs:** Non-profit operation is the preferred model. The operating entity should have a clear legal mandate not to log, be in a jurisdiction with strong privacy protections, and be independent from the Relay Server operator. Audit obligations (regular third-party security audits, transparency reports) should be part of the governance charter.

**Blockers to avoid:**
- The Key Server must remain a standalone deployable unit with no dependencies on the Relay Server
- Configuration and operational procedures must be documented well enough for a separate organization to operate it

**Trigger:** Before production deployment. This is a prerequisite for production, not a post-launch enhancement.

---

## D-009: Traffic Analysis Mitigations (Level 3)

**What:** Concrete implementations of padding, decoy traffic, key pre-fetching, artificial delays, and other mechanisms to resist traffic analysis.

**Why deferred:** These add latency and bandwidth overhead. The PoC needs to prove the protocol works before optimizing the metadata resistance layer.

**Trade-offs:**
- Key pre-fetching / caching: fetch pre-key bundles for contacts you already know periodically, not just when you want to message them. Prevents timing correlation between key fetches and messages.
- Decoy traffic: send meaningless encrypted blobs at random intervals so real messages are hidden in noise. Bandwidth cost.
- Padding: ensure all messages are the same size regardless of content. Prevents content-length analysis.
- Artificial delays: introduce random delays between key fetch and message send. Latency cost.

**Blockers to avoid:**
- Do NOT design the transport layer so tightly that padding or delays can't be inserted (the hybrid transport model in ADR 008 is flexible enough)
- Do NOT create APIs that only work with real-time request/response patterns — batch endpoints may be needed for decoy traffic

**Trigger:** After the PoC is validated and the project moves toward production security hardening.

---

## D-010: FCM Push Notification Support

**What:** Firebase Cloud Messaging as an additional push notification provider alongside UnifiedPush.

**Why deferred:** FCM involves Google infrastructure. Not needed for F-Droid distribution. Conflicts with some users' privacy expectations.

**Trade-offs:** FCM with empty payloads (wake-up signal only) is a reasonable compromise for users who don't want to configure a UnifiedPush provider. Google knows the app received a ping but nothing about content or sender.

**Blockers to avoid:**
- The push notification interface must be abstracted (ADR 013) so FCM drops in as a second implementation
- Do NOT hardcode UnifiedPush-specific logic outside the push abstraction layer

**Trigger:** When the app is distributed via Google Play Store alongside F-Droid.

---

## D-011: Internationalization / Accessibility

**What:** Multi-language support, screen reader compatibility, accessibility standards compliance.

**Why deferred:** Client-side UX concerns that don't affect protocol or architecture.

**Blockers to avoid:** Use standard platform UI components (Jetpack Compose) that support accessibility out of the box. Don't build custom UI components that would need to be retrofitted.

**Trigger:** Before public release. These are prerequisites for a usable product, not optional features.

---

## D-012: Blocking / Reporting

**What:** Ability to block a contact or report abusive behavior.

**Why deferred:** Interesting tension with privacy — how do you report someone to a server operator that doesn't know who they are? Requires careful protocol design.

**Trade-offs:** Blocking is purely client-side (stop accepting messages from a mailbox). Reporting is harder — it implies revealing some identifying information to an authority, which conflicts with the core privacy model. May need to be framed as "community trust" rather than "report to operator."

**Blockers to avoid:** None specific — this is additive.

**Trigger:** Before public release. Abuse prevention is a prerequisite for a real-world communication tool.
