package org.khord.shared.protocol.wire

import kotlinx.serialization.Serializable

/**
 * DTOs for the Relay Server REST API — PROTOCOL.md §5.
 * Mirrors `servers/relayserver/app/schemas.py`.
 */
@Serializable
internal data class PowParamsResponse(
    val difficultyBits: Int,
    val algorithm: String,
    val input: String,
    val mailboxIdMinLength: Int,
)

@Serializable
internal data class CreateMailboxRequest(
    val mailboxId: String,
    val proofOfWork: String,
)

@Serializable
internal data class CreateMailboxResponse(
    val mailboxId: String,
    val bearerToken: String,
)

@Serializable
internal data class SendMessageRequest(
    val blob: String,             // base64
)

@Serializable
internal data class SendMessageResponse(
    val sequence: Long,
)

@Serializable
internal data class FetchedMessage(
    val sequence: Long,
    val blob: String,             // base64
    val expires: Long,            // unix ts
)

@Serializable
internal data class FetchMessagesResponse(
    val messages: List<FetchedMessage>,
)

@Serializable
internal data class AcknowledgeRequest(
    val throughSequence: Long,
)

/** Response from POST /v1/media (ADR 029). */
@Serializable
internal data class UploadMediaResponse(
    val mediaId: String,
)
