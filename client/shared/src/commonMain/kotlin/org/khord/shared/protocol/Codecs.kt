package org.khord.shared.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * The canonical JSON instance for the Khord wire protocol.
 *
 * - `namingStrategy = SnakeCase` — matches the server JSON exactly without
 *   sprinkling `@SerialName` everywhere; Kotlin sources stay camelCase.
 * - `encodeDefaults = true` — explicit version fields appear on the wire
 *   even when the Kotlin default is used.
 * - `ignoreUnknownKeys = true` — an older client decoding a newer envelope
 *   tolerates added fields rather than failing.
 * - `classDiscriminator = "type"` — sealed envelopes use the existing
 *   `type` field per PROTOCOL.md §10.
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
val KhordJson = Json {
    namingStrategy = JsonNamingStrategy.SnakeCase
    encodeDefaults = true
    ignoreUnknownKeys = true
    classDiscriminator = "type"
}

@OptIn(ExperimentalEncodingApi::class)
internal object Base64Std {
    /** Standard base64 (PROTOCOL.md uses `<base64 ...>` everywhere — standard, not URL-safe). */
    fun encode(bytes: ByteArray): String = Base64.encode(bytes)
    fun decode(s: String): ByteArray = Base64.decode(s)
}
