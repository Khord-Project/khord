package org.khord.shared.protocol.wire

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

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
 */
@Serializable
internal data class InnerPayload(
    val version: Int = 1,
    val type: String,
    val timestamp: String,        // ISO 8601 / RFC 3339, client-generated
    val body: String? = null,     // present for type="text"; future types may use other fields
)
