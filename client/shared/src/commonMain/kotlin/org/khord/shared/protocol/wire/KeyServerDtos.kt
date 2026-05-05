package org.khord.shared.protocol.wire

import kotlinx.serialization.Serializable

/**
 * DTOs for the Key Server REST API — PROTOCOL.md §4.
 *
 * Field names (in Kotlin) are camelCase; the wire form is snake_case
 * because the [KhordJson] instance is configured with
 * [JsonNamingStrategy.SnakeCase]. Keep these classes flat and serializable
 * with no logic — they are pure transport-layer mirrors of the server
 * schemas in `servers/keyserver/app/schemas.py`.
 */
@Serializable
internal data class ChallengeResponse(
    val challenge: String,        // base64
    val expires: Long,             // unix ts
)

@Serializable
internal data class VerifyRequest(
    val fingerprint: String,
    val challenge: String,         // base64
    val signature: String,         // base64
    /** REQUIRED on first-time auth (no row yet); ignored on returning auth. */
    val identityKey: String? = null,  // base64 Ed25519 public
)

@Serializable
internal data class VerifyResponse(
    val token: String,
)

@Serializable
internal data class SignedPreKeyDto(
    val keyId: Int,
    val publicKey: String,         // base64 X25519
    val signature: String,         // base64 Ed25519 over publicKey bytes
)

@Serializable
internal data class OneTimePreKeyDto(
    val keyId: Int,
    val publicKey: String,         // base64 X25519
)

@Serializable
internal data class BundleUploadRequest(
    val identityKey: String,       // base64 Ed25519
    val signedPreKey: SignedPreKeyDto,
    val oneTimePreKeys: List<OneTimePreKeyDto>,
)

@Serializable
internal data class BundleFetchResponse(
    val identityKey: String,
    val signedPreKey: SignedPreKeyDto,
    /** Field is absent when the server has no remaining OPK (PROTOCOL.md §4.2). */
    val oneTimePreKey: OneTimePreKeyDto? = null,
)
