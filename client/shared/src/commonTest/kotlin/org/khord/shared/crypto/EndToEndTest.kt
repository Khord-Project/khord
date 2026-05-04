package org.khord.shared.crypto

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * End-to-end test: full X3DH bootstrap + 50 alternating Double Ratchet messages.
 * This exercises the entire encrypted-messaging surface in-process.
 */
class EndToEndTest {

    @Test
    fun fifty_alternating_messages_round_trip() = runTest {
        Crypto.ensureInitialized()

        // Alice and Bob have identities + Bob has uploaded a bundle.
        val alice = IdentityKey.fromSeedPhrase("alice phrase")
        val bob = IdentityKey.fromSeedPhrase("bob phrase")
        val spkGen = PreKeys.generateSignedPreKey(bob, keyId = 1)
        val opkGen = PreKeys.generateOneTimePreKeys(1..1).single()
        val bundle = PreKeyBundle(
            identityKeyEd25519 = bob.ed25519PublicKey,
            signedPreKey = spkGen.signedPreKey,
            oneTimePreKey = opkGen.oneTimePreKey,
        )

        // Alice initiates X3DH and starts a session.
        val initOut = X3dh.initiate(alice, bundle)
        val aliceSession = Session.fromInitiator(initOut, spkGen.signedPreKey.publicKey)

        // Bob receives the initial values, runs respond, starts his session.
        val bobSk = X3dh.respond(
            X3dh.ResponderInput(
                initiatorIdentityKeyEd25519 = initOut.identityKeyEd25519,
                initiatorEphemeralPublicKey = initOut.ephemeralPublicKey,
                responderIdentity = bob,
                signedPreKeySecret = spkGen.secretKey,
                oneTimePreKeySecret = opkGen.secretKey,
            )
        )
        val bobSession = Session.fromResponder(
            sk = bobSk,
            bobSignedPreKeyPair = X25519KeyPair(
                spkGen.signedPreKey.publicKey,
                spkGen.secretKey,
            ),
            associatedData = X3dh.associatedDataFor(initOut.identityKeyEd25519, bob),
        )

        // 50 alternating messages.
        var nextSender: Session = aliceSession
        var nextReceiver: Session = bobSession
        var direction = "alice→bob"
        for (i in 1..50) {
            val plaintext = "msg $i ($direction)".encodeToByteArray()
            val msg = nextSender.encrypt(plaintext)
            val received = nextReceiver.decrypt(msg.headerBytes, msg.ciphertext)
            assertEquals(plaintext.decodeToString(), received.decodeToString())

            // Swap.
            val tmp = nextSender
            nextSender = nextReceiver
            nextReceiver = tmp
            direction = if (direction == "alice→bob") "bob→alice" else "alice→bob"
        }
    }
}
