package org.khord.shared.storage

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import org.khord.shared.crypto.X25519KeyPair
import org.khord.shared.crypto.ratchet.RatchetState
import org.khord.shared.crypto.ratchet.SkippedKey

/**
 * Serialise/deserialise [RatchetState] to a stable JSON byte representation.
 *
 * Wire format (per persistence investigation Q4):
 *
 * ```json
 * {
 *   "DHs_pub":   "<base64>", "DHs_sec":  "<base64>",
 *   "DHr":       "<base64>" | null,
 *   "RK":        "<base64>",
 *   "CKs":       "<base64>" | null, "CKr":    "<base64>" | null,
 *   "Ns": <int>, "Nr": <int>, "PN": <int>,
 *   "MKSKIPPED": [{"dh_pub": "<base64>", "n": <int>, "mk": "<base64>"}, ...]
 * }
 * ```
 *
 * MKSKIPPED is encoded as an ARRAY of records, not a JSON object —
 * its keys are (ByteArray, Int) tuples, which JSON object keys cannot
 * represent unambiguously.
 *
 * Round-trip property: an existing RatchetState, serialised then
 * deserialised, must encrypt/decrypt the **next** message correctly.
 * See the unit test for the proof.
 */
@OptIn(ExperimentalEncodingApi::class)
internal object RatchetStateSerializer {

    @Serializable
    private data class SkippedEntry(val dh_pub: String, val n: Int, val mk: String)

    @Serializable
    private data class StateDto(
        val DHs_pub: String,
        val DHs_sec: String,
        val DHr: String? = null,
        val RK: String,
        val CKs: String? = null,
        val CKr: String? = null,
        val Ns: Int,
        val Nr: Int,
        val PN: Int,
        val MKSKIPPED: List<SkippedEntry>,
    )

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true  // forward-compat: future fields tolerated
    }

    fun serialize(state: RatchetState): ByteArray {
        val dto = StateDto(
            DHs_pub = b64(state.DHs.publicKey),
            DHs_sec = b64(state.DHs.secretKey),
            DHr = state.DHr?.let { b64(it) },
            RK = b64(state.RK),
            CKs = state.CKs?.let { b64(it) },
            CKr = state.CKr?.let { b64(it) },
            Ns = state.Ns,
            Nr = state.Nr,
            PN = state.PN,
            MKSKIPPED = state.MKSKIPPED.entries.map { (k, v) ->
                SkippedEntry(dh_pub = b64(k.dhPub), n = k.n, mk = b64(v))
            },
        )
        return json.encodeToString(StateDto.serializer(), dto).encodeToByteArray()
    }

    fun deserialize(bytes: ByteArray): RatchetState {
        val dto = json.decodeFromString(StateDto.serializer(), bytes.decodeToString())
        val state = RatchetState(
            DHs = X25519KeyPair(unb64(dto.DHs_pub), unb64(dto.DHs_sec)),
            DHr = dto.DHr?.let { unb64(it) },
            RK = unb64(dto.RK),
            CKs = dto.CKs?.let { unb64(it) },
            CKr = dto.CKr?.let { unb64(it) },
            Ns = dto.Ns,
            Nr = dto.Nr,
            PN = dto.PN,
        )
        for (e in dto.MKSKIPPED) {
            state.MKSKIPPED[SkippedKey(unb64(e.dh_pub), e.n)] = unb64(e.mk)
        }
        return state
    }

    private fun b64(bytes: ByteArray): String = Base64.encode(bytes)
    private fun unb64(s: String): ByteArray = Base64.decode(s)
}
