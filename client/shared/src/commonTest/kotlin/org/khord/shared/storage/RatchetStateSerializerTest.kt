package org.khord.shared.storage

import kotlinx.coroutines.test.runTest
import org.khord.shared.crypto.Crypto
import org.khord.shared.crypto.IdentityKey
import org.khord.shared.crypto.PreKeyBundle
import org.khord.shared.crypto.PreKeys
import org.khord.shared.crypto.X25519KeyPair
import org.khord.shared.crypto.X3dh
import org.khord.shared.crypto.ratchet.DoubleRatchet
import org.khord.shared.crypto.ratchet.RatchetState
import org.khord.shared.crypto.ratchet.SkippedKey
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RatchetStateSerializerTest {

    @Test
    fun round_trips_a_freshly_initialised_alice_state() = runTest {
        Crypto.ensureInitialized()
        val alice = IdentityKey.fromSeedPhrase("alice persistence")
        val bob = IdentityKey.fromSeedPhrase("bob persistence")
        val spkGen = PreKeys.generateSignedPreKey(bob, keyId = 1)
        val opkGen = PreKeys.generateOneTimePreKeys(1..1).single()
        val bundle = PreKeyBundle(
            identityKeyEd25519 = bob.ed25519PublicKey,
            signedPreKey = spkGen.signedPreKey,
            oneTimePreKey = opkGen.oneTimePreKey,
        )
        val initOut = X3dh.initiate(alice, bundle)
        val state = DoubleRatchet.initAlice(initOut.sharedSecret, spkGen.signedPreKey.publicKey)

        val bytes = RatchetStateSerializer.serialize(state)
        val restored = RatchetStateSerializer.deserialize(bytes)

        assertContentEquals(state.DHs.publicKey, restored.DHs.publicKey)
        assertContentEquals(state.DHs.secretKey, restored.DHs.secretKey)
        assertContentEquals(state.DHr, restored.DHr)
        assertContentEquals(state.RK, restored.RK)
        assertContentEquals(state.CKs, restored.CKs)
        assertContentEquals(state.CKr, restored.CKr)
        assertEquals(state.Ns, restored.Ns)
        assertEquals(state.Nr, restored.Nr)
        assertEquals(state.PN, restored.PN)
        assertEquals(state.MKSKIPPED.size, restored.MKSKIPPED.size)
    }

    /**
     * The truly load-bearing property: a serialised-then-deserialised state
     * must encrypt/decrypt the NEXT message correctly. If MKSKIPPED ordering,
     * counter restoration, or key bytes are off by one byte, this fails.
     */
    @Test
    fun serialise_deserialise_continues_a_real_session() = runTest {
        Crypto.ensureInitialized()
        val alice = IdentityKey.fromSeedPhrase("alice continue")
        val bob = IdentityKey.fromSeedPhrase("bob continue")
        val spkGen = PreKeys.generateSignedPreKey(bob, keyId = 1)
        val opkGen = PreKeys.generateOneTimePreKeys(1..1).single()
        val bundle = PreKeyBundle(
            identityKeyEd25519 = bob.ed25519PublicKey,
            signedPreKey = spkGen.signedPreKey,
            oneTimePreKey = opkGen.oneTimePreKey,
        )
        val initOut = X3dh.initiate(alice, bundle)
        val bobSk = X3dh.respond(
            X3dh.ResponderInput(
                initiatorIdentityKeyEd25519 = initOut.identityKeyEd25519,
                initiatorEphemeralPublicKey = initOut.ephemeralPublicKey,
                responderIdentity = bob,
                signedPreKeySecret = spkGen.secretKey,
                oneTimePreKeySecret = opkGen.secretKey,
            )
        )

        val aliceState = DoubleRatchet.initAlice(initOut.sharedSecret, spkGen.signedPreKey.publicKey)
        val bobState = DoubleRatchet.initBob(
            bobSk,
            X25519KeyPair(spkGen.signedPreKey.publicKey, spkGen.secretKey),
        )
        val ad = initOut.associatedData

        // Round 1 — Alice → Bob.
        val a1 = DoubleRatchet.encrypt(aliceState, "msg-1".encodeToByteArray(), ad)
        val r1 = DoubleRatchet.decrypt(bobState, a1.headerBytes, a1.ciphertext, ad)
        assertEquals("msg-1", r1.decodeToString())

        // Serialise both states; deserialise into FRESH instances.
        val aliceRestored = RatchetStateSerializer.deserialize(
            RatchetStateSerializer.serialize(aliceState)
        )
        val bobRestored = RatchetStateSerializer.deserialize(
            RatchetStateSerializer.serialize(bobState)
        )

        // Round 2 — Bob → Alice using restored states.
        val b1 = DoubleRatchet.encrypt(bobRestored, "msg-2".encodeToByteArray(), ad)
        val r2 = DoubleRatchet.decrypt(aliceRestored, b1.headerBytes, b1.ciphertext, ad)
        assertEquals("msg-2", r2.decodeToString())

        // Round 3 — Alice → Bob (DH ratchet step happened on alice side).
        val a2 = DoubleRatchet.encrypt(aliceRestored, "msg-3".encodeToByteArray(), ad)
        val r3 = DoubleRatchet.decrypt(bobRestored, a2.headerBytes, a2.ciphertext, ad)
        assertEquals("msg-3", r3.decodeToString())
    }

    @Test
    fun mkskipped_entries_round_trip() {
        // Synthetic state with a populated MKSKIPPED.
        val state = RatchetState(
            DHs = X25519KeyPair(ByteArray(32) { 1 }, ByteArray(32) { 2 }),
            DHr = ByteArray(32) { 3 },
            RK = ByteArray(32) { 4 },
            CKs = ByteArray(32) { 5 },
            CKr = ByteArray(32) { 6 },
            Ns = 10, Nr = 7, PN = 4,
        )
        state.MKSKIPPED[SkippedKey(ByteArray(32) { 7 }, 1)] = ByteArray(32) { 0xAA.toByte() }
        state.MKSKIPPED[SkippedKey(ByteArray(32) { 8 }, 2)] = ByteArray(32) { 0xBB.toByte() }

        val bytes = RatchetStateSerializer.serialize(state)
        val restored = RatchetStateSerializer.deserialize(bytes)

        assertEquals(2, restored.MKSKIPPED.size)
        val v1 = restored.MKSKIPPED[SkippedKey(ByteArray(32) { 7 }, 1)]
        val v2 = restored.MKSKIPPED[SkippedKey(ByteArray(32) { 8 }, 2)]
        assertTrue(v1 != null && v1.all { it == 0xAA.toByte() })
        assertTrue(v2 != null && v2.all { it == 0xBB.toByte() })
    }
}
