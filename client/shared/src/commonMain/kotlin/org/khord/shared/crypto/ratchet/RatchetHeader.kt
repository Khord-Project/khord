package org.khord.shared.crypto.ratchet

import com.ionspin.kotlin.crypto.util.encodeToUByteArray
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Double Ratchet message header — DR §3.3 / PROTOCOL.md §7.2.
 *
 * Wire form (canonical, locked field order, no whitespace):
 *
 *   {"dh_public_key":"<base64>","previous_chain_length":<int>,"message_number":<int>}
 *
 * Locked because the byte representation is part of the AEAD's associated
 * data — any divergence between sender and receiver produces a decrypt
 * failure even when the keys match. PROTOCOL.md §7.2 specifies this exact
 * canonical form.
 */
internal data class RatchetHeader(
    val dhPublicKey: ByteArray,
    val previousChainLength: Int,
    val messageNumber: Int,
) {
    @OptIn(ExperimentalEncodingApi::class)
    fun toBytes(): ByteArray {
        // Hand-rolled canonical JSON — locked field order, no whitespace, no
        // trailing comma. Avoids depending on a JSON library to guarantee
        // byte-for-byte determinism between sender and receiver.
        val dhB64 = Base64.encode(dhPublicKey)
        val s = """{"dh_public_key":"$dhB64",""" +
                """"previous_chain_length":$previousChainLength,""" +
                """"message_number":$messageNumber}"""
        return s.encodeToByteArray()
    }

    companion object {
        @OptIn(ExperimentalEncodingApi::class)
        fun fromBytes(bytes: ByteArray): RatchetHeader {
            val s = bytes.decodeToString()
            val dhB64 = extractStringField(s, "dh_public_key")
            val pn = extractIntField(s, "previous_chain_length")
            val n = extractIntField(s, "message_number")
            return RatchetHeader(
                dhPublicKey = Base64.decode(dhB64),
                previousChainLength = pn,
                messageNumber = n,
            )
        }

        private fun extractStringField(s: String, name: String): String {
            val needle = "\"$name\":\""
            val start = s.indexOf(needle)
            require(start >= 0) { "missing field: $name" }
            val from = start + needle.length
            val end = s.indexOf('"', from)
            require(end > from) { "unterminated string for: $name" }
            return s.substring(from, end)
        }

        private fun extractIntField(s: String, name: String): Int {
            val needle = "\"$name\":"
            val start = s.indexOf(needle)
            require(start >= 0) { "missing field: $name" }
            val from = start + needle.length
            var end = from
            while (end < s.length && (s[end].isDigit() || (end == from && s[end] == '-'))) {
                end++
            }
            return s.substring(from, end).toInt()
        }
    }
}
