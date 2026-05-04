package org.khord.shared.crypto

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PreKeysTest {

    private suspend fun anIdentity(seed: String = "alice"): IdentityKey {
        Crypto.ensureInitialized()
        return IdentityKey.fromSeedPhrase(seed)
    }

    @Test
    fun signed_pre_key_signature_verifies_against_identity_key() = runTest {
        val identity = anIdentity()
        val gen = PreKeys.generateSignedPreKey(identity, keyId = 1)
        assertEquals(32, gen.signedPreKey.publicKey.size)
        assertEquals(64, gen.signedPreKey.signature.size)
        assertTrue(PreKeys.verifySignedPreKey(gen.signedPreKey, identity.ed25519PublicKey))
    }

    @Test
    fun signed_pre_key_signature_fails_against_wrong_identity_key() = runTest {
        val alice = anIdentity("alice")
        val mallory = anIdentity("mallory")
        val gen = PreKeys.generateSignedPreKey(alice, keyId = 1)
        assertFalse(PreKeys.verifySignedPreKey(gen.signedPreKey, mallory.ed25519PublicKey))
    }

    @Test
    fun signed_pre_key_signature_fails_when_public_key_is_tampered() = runTest {
        val identity = anIdentity()
        val gen = PreKeys.generateSignedPreKey(identity, keyId = 1)
        val tampered = gen.signedPreKey.copy(
            publicKey = gen.signedPreKey.publicKey.copyOf().also { it[0] = (it[0] + 1).toByte() }
        )
        assertFalse(PreKeys.verifySignedPreKey(tampered, identity.ed25519PublicKey))
    }

    @Test
    fun one_time_pre_keys_have_distinct_public_keys() = runTest {
        Crypto.ensureInitialized()
        val opks = PreKeys.generateOneTimePreKeys(1..10)
        val publicKeys = opks.map { it.oneTimePreKey.publicKey.toList() }.toSet()
        assertEquals(10, publicKeys.size, "all 10 OPK public keys should be distinct")
    }

    @Test
    fun one_time_pre_key_id_assignment_matches_input_range() = runTest {
        Crypto.ensureInitialized()
        val opks = PreKeys.generateOneTimePreKeys(100..103)
        assertEquals(listOf(100, 101, 102, 103), opks.map { it.oneTimePreKey.keyId })
    }

    @Test
    fun two_signed_pre_keys_for_same_identity_have_distinct_public_keys() = runTest {
        val identity = anIdentity()
        val a = PreKeys.generateSignedPreKey(identity, 1)
        val b = PreKeys.generateSignedPreKey(identity, 2)
        assertNotEquals(a.signedPreKey.publicKey.toList(), b.signedPreKey.publicKey.toList())
    }
}
