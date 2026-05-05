package org.khord.shared.protocol.wire

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Khord wire envelope — the bytes inside a relay server's `blob` field.
 *
 * See PROTOCOL.md §10. Two shapes, discriminated by the `type` field
 * (kotlinx.serialization sealed-class polymorphism with `KhordJson.classDiscriminator = "type"`):
 *
 *   - `x3dh_initial` — Alice's first message to a new contact.
 *     Carries the X3DH metadata (IK_A, EK_A, SPK/OPK key ids) plus the
 *     ratchet header + ciphertext encrypted under SK.
 *   - `ratchet` — every subsequent message in the established session.
 *
 * Field names use snake_case on the wire via [KhordJson]'s naming strategy.
 *
 * Encoding from envelope to blob:
 *   1. Serialise the envelope to canonical JSON via [KhordJson].
 *   2. base64-encode the resulting bytes.
 *   3. Drop the base64 string into the relay server's `blob` field.
 */
@Serializable
internal sealed class WireEnvelope {

    abstract val version: Int

    @Serializable
    @SerialName("x3dh_initial")
    data class X3dhInitial(
        override val version: Int = 1,
        val ikA: String,             // base64 Alice's Ed25519 public
        val ekA: String,             // base64 Alice's ephemeral X25519 public
        val spkId: Int,              // Bob's signed-pre-key id used by Alice
        val opkId: Int? = null,      // Bob's OPK id used (null if none)
        val header: String,          // base64 of canonical ratchet-header bytes
        val ciphertext: String,      // base64 (nonce || aead_ct) from RatchetAead.encrypt
    ) : WireEnvelope()

    @Serializable
    @SerialName("ratchet")
    data class Ratchet(
        override val version: Int = 1,
        val header: String,
        val ciphertext: String,
    ) : WireEnvelope()
}
