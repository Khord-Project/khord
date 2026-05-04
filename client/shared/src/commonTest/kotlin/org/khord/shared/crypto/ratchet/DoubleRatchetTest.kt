package org.khord.shared.crypto.ratchet

import kotlinx.coroutines.test.runTest
import org.khord.shared.crypto.Crypto
import org.khord.shared.crypto.IdentityKey
import org.khord.shared.crypto.PreKeyBundle
import org.khord.shared.crypto.PreKeys
import org.khord.shared.crypto.X25519KeyPair
import org.khord.shared.crypto.X3dh
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Double Ratchet behaviour tests — covers the cases from ADR 021:
 *   * basic alternating + burst
 *   * out-of-order delivery (skipped keys cached and consumed)
 *   * MAX_SKIP enforcement
 *   * decrypt safety (failed decrypt does not advance state)
 *   * forward secrecy (chain key cannot rederive past message keys)
 *   * post-compromise recovery (DH ratchet advances RK)
 */
class DoubleRatchetTest {

    /** Build a fresh Alice/Bob session via X3DH. Returns (aliceState, bobState, ad). */
    private suspend fun setupSession(): Triple<RatchetState, RatchetState, ByteArray> {
        Crypto.ensureInitialized()
        val alice = IdentityKey.fromSeedPhrase("alice phrase")
        val bob = IdentityKey.fromSeedPhrase("bob phrase")
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
        return Triple(aliceState, bobState, initOut.associatedData)
    }

    @Test
    fun simple_alternating_messages() = runTest {
        val (alice, bob, ad) = setupSession()

        val a1 = DoubleRatchet.encrypt(alice, "alice→bob 1".encodeToByteArray(), ad)
        val gotA1 = DoubleRatchet.decrypt(bob, a1.headerBytes, a1.ciphertext, ad)
        assertEquals("alice→bob 1", gotA1.decodeToString())

        val b1 = DoubleRatchet.encrypt(bob, "bob→alice 1".encodeToByteArray(), ad)
        val gotB1 = DoubleRatchet.decrypt(alice, b1.headerBytes, b1.ciphertext, ad)
        assertEquals("bob→alice 1", gotB1.decodeToString())

        val a2 = DoubleRatchet.encrypt(alice, "alice→bob 2".encodeToByteArray(), ad)
        val gotA2 = DoubleRatchet.decrypt(bob, a2.headerBytes, a2.ciphertext, ad)
        assertEquals("alice→bob 2", gotA2.decodeToString())
    }

    @Test
    fun alice_burst_then_bob_decrypts_in_order() = runTest {
        val (alice, bob, ad) = setupSession()
        val ms = (1..5).map {
            DoubleRatchet.encrypt(alice, "msg-$it".encodeToByteArray(), ad)
        }
        val out = ms.map {
            DoubleRatchet.decrypt(bob, it.headerBytes, it.ciphertext, ad).decodeToString()
        }
        assertEquals(listOf("msg-1", "msg-2", "msg-3", "msg-4", "msg-5"), out)
    }

    @Test
    fun out_of_order_delivery_uses_skipped_keys() = runTest {
        val (alice, bob, ad) = setupSession()
        val m1 = DoubleRatchet.encrypt(alice, "m1".encodeToByteArray(), ad)
        val m2 = DoubleRatchet.encrypt(alice, "m2".encodeToByteArray(), ad)
        val m3 = DoubleRatchet.encrypt(alice, "m3".encodeToByteArray(), ad)

        // Bob receives in the order: m3, m1, m2.
        val r3 = DoubleRatchet.decrypt(bob, m3.headerBytes, m3.ciphertext, ad)
        val r1 = DoubleRatchet.decrypt(bob, m1.headerBytes, m1.ciphertext, ad)
        val r2 = DoubleRatchet.decrypt(bob, m2.headerBytes, m2.ciphertext, ad)

        assertEquals("m3", r3.decodeToString())
        assertEquals("m1", r1.decodeToString())
        assertEquals("m2", r2.decodeToString())
    }

    @Test
    fun missing_intermediate_message_keeps_cached_key_until_arrival() = runTest {
        val (alice, bob, ad) = setupSession()
        val m1 = DoubleRatchet.encrypt(alice, "m1".encodeToByteArray(), ad)
        val m2 = DoubleRatchet.encrypt(alice, "m2".encodeToByteArray(), ad)
        val m3 = DoubleRatchet.encrypt(alice, "m3".encodeToByteArray(), ad)

        // Bob gets m1 and m3, then later m2.
        DoubleRatchet.decrypt(bob, m1.headerBytes, m1.ciphertext, ad)
        DoubleRatchet.decrypt(bob, m3.headerBytes, m3.ciphertext, ad)
        assertTrue(bob.MKSKIPPED.isNotEmpty(), "skipped key store should retain m2's key")
        val r2 = DoubleRatchet.decrypt(bob, m2.headerBytes, m2.ciphertext, ad)
        assertEquals("m2", r2.decodeToString())
        assertTrue(bob.MKSKIPPED.isEmpty(), "skipped key consumed after m2 arrives")
    }

    @Test
    fun max_skip_per_chain_is_enforced() = runTest {
        val (alice, bob, ad) = setupSession()
        // Encrypt a message past the skip limit.
        repeat(MAX_SKIP_PER_CHAIN + 5) {
            DoubleRatchet.encrypt(alice, "x".encodeToByteArray(), ad)
        }
        val far = DoubleRatchet.encrypt(alice, "far".encodeToByteArray(), ad)
        assertFailsWith<RatchetSkipLimitExceeded> {
            DoubleRatchet.decrypt(bob, far.headerBytes, far.ciphertext, ad)
        }
    }

    @Test
    fun tampered_ciphertext_does_not_advance_state() = runTest {
        val (alice, bob, ad) = setupSession()
        val m = DoubleRatchet.encrypt(alice, "secret".encodeToByteArray(), ad)
        val tampered = m.ciphertext.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }

        val nrBefore = bob.Nr
        val rkBefore = bob.RK.copyOf()

        // First decrypt fails (tag mismatch).
        assertFailsWith<Exception> {
            DoubleRatchet.decrypt(bob, m.headerBytes, tampered, ad)
        }
        // State must NOT have advanced — Nr unchanged, RK unchanged.
        assertEquals(nrBefore, bob.Nr)
        assertContentEquals(rkBefore, bob.RK)

        // Now the genuine decrypt succeeds (proves rollback worked).
        val ok = DoubleRatchet.decrypt(bob, m.headerBytes, m.ciphertext, ad)
        assertEquals("secret", ok.decodeToString())
    }

    @Test
    fun replay_of_already_decrypted_message_fails() = runTest {
        val (alice, bob, ad) = setupSession()
        val m = DoubleRatchet.encrypt(alice, "once".encodeToByteArray(), ad)
        DoubleRatchet.decrypt(bob, m.headerBytes, m.ciphertext, ad)
        // Replaying the same (header, ct) — the chain key has advanced past
        // n=0, so the cached MK does not exist and the receiving chain
        // cannot regenerate it.
        assertFailsWith<Exception> {
            DoubleRatchet.decrypt(bob, m.headerBytes, m.ciphertext, ad)
        }
    }

    @Test
    fun dh_ratchet_step_advances_root_key() = runTest {
        val (alice, bob, ad) = setupSession()
        val a1 = DoubleRatchet.encrypt(alice, "alice 1".encodeToByteArray(), ad)
        DoubleRatchet.decrypt(bob, a1.headerBytes, a1.ciphertext, ad)

        val rkBefore = bob.RK.copyOf()
        val b1 = DoubleRatchet.encrypt(bob, "bob 1".encodeToByteArray(), ad)
        DoubleRatchet.decrypt(alice, b1.headerBytes, b1.ciphertext, ad)
        // Alice's RK should have advanced after Bob's DH ratchet.
        // Bob's RK ALSO advances when encrypting (because his initBob's
        // CKs is null, the next encrypt isn't possible without a DH step).
        // We can't easily compare bob.RK before/after his own send, but
        // we CAN verify alice's RK changed after receiving bob's reply.
        val a2 = DoubleRatchet.encrypt(alice, "alice 2".encodeToByteArray(), ad)
        DoubleRatchet.decrypt(bob, a2.headerBytes, a2.ciphertext, ad)

        // bob.RK must differ from before bob sent his first message.
        assertTrue(!rkBefore.contentEquals(bob.RK), "RK should have advanced")
    }
}
