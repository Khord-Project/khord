package org.khord.shared.crypto

/**
 * X25519 keypair used as a pre-key. The secret is held only on the device
 * that minted the keypair; the public half is uploaded to the Key Server.
 */
internal data class X25519KeyPair(
    val publicKey: ByteArray,
    val secretKey: ByteArray,
)

/**
 * A signed pre-key — Bob's medium-lifetime X25519 keypair, with a signature
 * by his Ed25519 identity key over the public bytes (X3DH §3.2).
 */
data class SignedPreKey(
    val keyId: Int,
    val publicKey: ByteArray,
    val signature: ByteArray,
) {
    /** Internal constructor companion exposing the secret half (server upload omits this). */
    internal data class Generated(
        val signedPreKey: SignedPreKey,
        val secretKey: ByteArray,
    )
}

/**
 * A one-time pre-key — single-use X25519 keypair (X3DH §3.2). The Key
 * Server returns the public half once per fetch, then deletes it. Forward
 * secrecy of the X3DH session depends on Bob deleting the secret half
 * on his side after the OPK has been used (X3DH §3.4).
 */
data class OneTimePreKey(
    val keyId: Int,
    val publicKey: ByteArray,
) {
    internal data class Generated(
        val oneTimePreKey: OneTimePreKey,
        val secretKey: ByteArray,
    )
}

/**
 * The bundle Alice fetches from the Key Server before initiating an X3DH
 * session with Bob — PROTOCOL.md §6.1 / X3DH §3.2.
 *
 * `oneTimePreKey` is null if the server has none left for this fingerprint
 * (PROTOCOL.md §4.2 / X3DH §3.3 — protocol still works, with reduced
 * forward-secrecy properties).
 */
data class PreKeyBundle(
    val identityKeyEd25519: ByteArray,
    val signedPreKey: SignedPreKey,
    val oneTimePreKey: OneTimePreKey?,
)
