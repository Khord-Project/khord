package org.khord.shared.crypto

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/**
 * X3DH cross-side parity tests.
 *
 * The Signal X3DH spec does not publish KAT (known-answer test) vectors,
 * so we verify correctness by ensuring the two SIDES of the agreement
 * (Alice and Bob) deterministically arrive at the SAME 32-byte SK from
 * their respective inputs. This is the strongest property we can test
 * without an external reference implementation.
 */
class X3dhTest {

    private suspend fun aliceAndBob(): Pair<IdentityKey, IdentityKey> {
        Crypto.ensureInitialized()
        return IdentityKey.fromSeedPhrase("alice phrase") to
                IdentityKey.fromSeedPhrase("bob phrase")
    }

    @Test
    fun alice_and_bob_derive_the_same_sk() = runTest {
        val (alice, bob) = aliceAndBob()
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

        assertEquals(32, initOut.sharedSecret.size)
        assertContentEquals(initOut.sharedSecret, bobSk)
    }

    @Test
    fun alice_and_bob_agree_when_no_opk_is_available() = runTest {
        val (alice, bob) = aliceAndBob()
        val spkGen = PreKeys.generateSignedPreKey(bob, keyId = 1)

        val bundle = PreKeyBundle(
            identityKeyEd25519 = bob.ed25519PublicKey,
            signedPreKey = spkGen.signedPreKey,
            oneTimePreKey = null,  // exhausted
        )

        val initOut = X3dh.initiate(alice, bundle)
        val bobSk = X3dh.respond(
            X3dh.ResponderInput(
                initiatorIdentityKeyEd25519 = initOut.identityKeyEd25519,
                initiatorEphemeralPublicKey = initOut.ephemeralPublicKey,
                responderIdentity = bob,
                signedPreKeySecret = spkGen.secretKey,
                oneTimePreKeySecret = null,
            )
        )

        assertContentEquals(initOut.sharedSecret, bobSk)
        assertEquals(null, initOut.oneTimePreKeyId)
    }

    @Test
    fun forged_signed_pre_key_signature_is_rejected() = runTest {
        val (alice, bob) = aliceAndBob()
        val spkGen = PreKeys.generateSignedPreKey(bob, keyId = 1)

        // Tamper with the public key so the (genuine) signature no longer matches.
        val tamperedSpk = spkGen.signedPreKey.copy(
            publicKey = spkGen.signedPreKey.publicKey.copyOf().also {
                it[0] = (it[0] + 1).toByte()
            }
        )
        val bundle = PreKeyBundle(
            identityKeyEd25519 = bob.ed25519PublicKey,
            signedPreKey = tamperedSpk,
            oneTimePreKey = null,
        )
        assertFailsWith<IllegalArgumentException> {
            X3dh.initiate(alice, bundle)
        }
    }

    @Test
    fun two_x3dh_sessions_for_same_pair_yield_different_sk() = runTest {
        val (alice, bob) = aliceAndBob()
        val spkGen = PreKeys.generateSignedPreKey(bob, keyId = 1)
        val bundle = PreKeyBundle(
            identityKeyEd25519 = bob.ed25519PublicKey,
            signedPreKey = spkGen.signedPreKey,
            oneTimePreKey = null,
        )
        val first = X3dh.initiate(alice, bundle)
        val second = X3dh.initiate(alice, bundle)
        // Different ephemerals → different SKs.
        assertNotEquals(first.sharedSecret.toList(), second.sharedSecret.toList())
    }

    @Test
    fun associated_data_is_concatenation_of_identity_keys() = runTest {
        val (alice, bob) = aliceAndBob()
        val spkGen = PreKeys.generateSignedPreKey(bob, keyId = 1)
        val bundle = PreKeyBundle(
            identityKeyEd25519 = bob.ed25519PublicKey,
            signedPreKey = spkGen.signedPreKey,
            oneTimePreKey = null,
        )
        val out = X3dh.initiate(alice, bundle)
        val expectedAd = alice.ed25519PublicKey + bob.ed25519PublicKey
        assertContentEquals(expectedAd, out.associatedData)
        assertContentEquals(
            expectedAd,
            X3dh.associatedDataFor(out.identityKeyEd25519, bob),
        )
    }
}
