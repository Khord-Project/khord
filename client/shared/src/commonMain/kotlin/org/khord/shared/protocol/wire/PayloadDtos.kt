package org.khord.shared.protocol.wire

import kotlinx.serialization.Serializable

/**
 * Inner payload format — what the Double Ratchet AEAD encrypts.
 * See PROTOCOL.md §8.
 *
 * Two reasons this is JSON (and not a binary format):
 *   * forward compatibility — new fields can land non-breakingly, and the
 *     `type` discriminator already supports unknown types gracefully;
 *   * the payload is the smallest part of any message anyway — JSON
 *     overhead is irrelevant compared to the AEAD framing and ratchet
 *     headers around it.
 *
 * The `type` field is mandatory (PROTOCOL.md §8 + ADR 015): unknown
 * types must be handled gracefully rather than crashing the receiver.
 * Supported types in the PoC:
 *   - `"text"`                 — one-to-one text message, uses [body]
 *   - `"group_invite"`         — invitation to a client-side group
 *                                (ADR 023). Uses [groupId], [groupName],
 *                                [members].
 *   - `"group_message"`        — text message addressed to a known group.
 *                                Uses [groupId] + [body].
 *   - `"group_member_added"`   — admin notifies existing members that
 *                                someone new joined. Uses [groupId] +
 *                                [added] (the new member).
 *   - `"group_member_left"`    — a member left (or admin removed them).
 *                                Uses [groupId] + [groupMemberFingerprint].
 *   - `"group_name_changed"`   — admin renamed the group. Uses [groupId]
 *                                + [groupName].
 *   - `"message_edit"`         — update the body of a previously-sent
 *                                message identified by [messageUuid].
 *                                Uses [messageUuid] + [newBody], plus
 *                                [groupId] when the original was a
 *                                group_message. Sender verification
 *                                lives in the orchestrator.
 * Unknown types still raise [UnsupportedPayloadType] for the legacy
 * one-to-one path; the group dispatch in receiveMessages routes by
 * `type` BEFORE the text-only path runs.
 *
 * Field naming: KhordJson sets `JsonNamingStrategy.SnakeCase` globally,
 * so `replyInfo` serialises as `reply_info`, `relayServer` as
 * `relay_server`, etc. Don't add @SerialName annotations for snake_case.
 */
@Serializable
internal data class InnerPayload(
    val version: Int = 1,
    val type: String,
    val timestamp: String,        // ISO 8601 / RFC 3339, client-generated
    val body: String? = null,     // present for type="text" and "group_message"
    /**
     * How the recipient can reply to me — encrypted, never seen by the
     * relay server (it sits inside the AEAD ciphertext). On the X3DH
     * initial this is REQUIRED (without it Bob can't auto-create the
     * Alice contact and falls back to the legacy bidirectional-QR error).
     * On subsequent ratchet messages it's OPTIONAL — Khord PoC currently
     * sends it on every message so a contact's display-name change or
     * relay-server migration self-heals on the next inbound message.
     */
    val replyInfo: ReplyInfo? = null,

    // ── Group fields (ADR 023). Optional on every type; semantics depend
    // on `type`. All group_* messages travel through existing pairwise
    // Double Ratchet channels — the Relay Server never learns the
    // group exists.

    /** Group identifier (32 hex chars). Present on every group_* type. */
    val groupId: String? = null,
    /** Group display name. Present on `group_invite` and `group_name_changed`. */
    val groupName: String? = null,
    /** Full membership list. Present on `group_invite` only. */
    val members: List<GroupMember>? = null,
    /** Newly-added member. Present on `group_member_added` only. */
    val added: GroupMember? = null,
    /**
     * Fingerprint of the member who left. Present on `group_member_left`.
     * Serialises as `group_member_fingerprint` (snake_case) — distinct
     * from `reply_info.fingerprint` (which is the SENDER's fingerprint,
     * not the leaver's; admins kicking someone need to point at a
     * DIFFERENT fingerprint than their own).
     */
    val groupMemberFingerprint: String? = null,

    // ── Message-editing fields (alpha.14, ADR 026) ───────────────────
    /**
     * Stable per-message UUID, sender-issued at insert time and
     * persisted by both sides. Present on every "text" and
     * "group_message" payload sent by alpha.14+ clients; also
     * required on "message_edit" payloads (it's the reference to
     * the original message). Null on pre-alpha.14 messages (those
     * remain un-editable).
     */
    val messageUuid: String? = null,
    /**
     * Replacement body for a "message_edit" payload. Required on
     * message_edit; null on all other types. Distinct from [body]
     * so we don't have to overload its semantics.
     */
    val newBody: String? = null,

    /**
     * For a "text" or "group_message" that is a quote-reply (#61):
     * the [messageUuid] of the message being replied to. Null = not
     * a reply. The quoted TEXT is never carried on the wire — the
     * receiver resolves it locally by UUID, so a sender can't
     * fabricate a quote.
     */
    val replyToUuid: String? = null,
)

/**
 * Sender's reply-to coordinates + display name, embedded inside every
 * encrypted [InnerPayload]. Lives entirely inside the Double Ratchet
 * ciphertext — the relay server never sees these fields.
 *
 * The `fingerprint` here is redundant with the X3DH envelope's `ik_a`
 * field (recipients can derive it themselves) but keeping it in the
 * payload lets the orchestrator build a [QrPayload] for the contact
 * record from a single source of truth without re-deriving the hash.
 */
@Serializable
internal data class ReplyInfo(
    val mailbox: String,        // Sender's inbound mailbox ID for replies
    val relayServer: String,    // Sender's relay-server base URL
    val keyServer: String,      // Sender's key-server base URL
    val fingerprint: String,    // Sender's identity fingerprint (hex sha256 of ik_a)
    val displayName: String,    // Sender's chosen display name (or "Anonymous")
)

/**
 * One entry in a [InnerPayload.members] list (ADR 023). Carries just
 * enough for a recipient to know who to display in the group UI; the
 * display name is the sender's best knowledge and may be stale
 * (recipients self-heal as the named member's own messages arrive
 * carrying fresher reply_info.displayName).
 */
@Serializable
internal data class GroupMember(
    val fingerprint: String,
    val displayName: String,
)
