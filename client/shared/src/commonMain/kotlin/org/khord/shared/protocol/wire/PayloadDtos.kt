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
 * Khord PoC understands only `"text"`. For any other value, the orchestrator
 * raises [UnsupportedPayloadType] and the caller surfaces "unsupported
 * message type" to the user.
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
    val body: String? = null,     // present for type="text"; future types may use other fields
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
