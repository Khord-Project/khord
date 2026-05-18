package org.khord.shared.protocol

import io.ktor.client.engine.java.Java
import kotlinx.coroutines.test.runTest
import org.khord.shared.crypto.Crypto
import org.khord.shared.crypto.IdentityKey
import org.khord.shared.protocol.orchestrator.Messaging
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient as JavaHttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.time.Duration
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Group messaging end-to-end test (ADR 023). Three orchestrator
 * instances (Alice, Bob, Carol) exchange messages over the real
 * Docker Compose stack:
 *
 *   1. Each user registers and exchanges QRs pairwise (Alice↔Bob,
 *      Alice↔Carol). Bob and Carol are NOT introduced — Bob has no
 *      Carol session and vice versa. This matches the "cross-
 *      friendship constraint" called out in ADR 023.
 *   2. Alice creates a group with Bob and Carol. Both receive the
 *      `group_invite` via their pairwise channel with Alice.
 *   3. Alice sends a group message. Both Bob and Carol receive it.
 *   4. Carol leaves the group (emits `group_member_left` to Alice
 *      and Bob, then deletes locally).
 *   5. Alice sends another message. Bob receives it; Carol does NOT
 *      (she's no longer in Alice's member list AND deleted the group
 *      locally).
 *
 * Gated by `-Dkhord.integration=true`. Run via:
 *
 *   ./gradlew :shared:jvmTest -Dkhord.integration=true \
 *       --tests "org.khord.shared.protocol.GroupMessagingIntegrationTest"
 */
class GroupMessagingIntegrationTest {

    private val keyServerUrl = "http://localhost:8001"
    private val relayServerUrl = "http://localhost:8002"

    private val isEnabled: Boolean
        get() = System.getProperty("khord.integration") == "true"

    @BeforeTest
    fun assumeStackReachable() {
        if (!isEnabled) return
        if (!ping(keyServerUrl) || !ping(relayServerUrl)) {
            fail(
                "Khord docker compose stack not reachable at $keyServerUrl / " +
                "$relayServerUrl — run `docker compose up` and retry."
            )
        }
    }

    private fun ping(baseUrl: String): Boolean = try {
        val client = JavaHttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
        val req = HttpRequest.newBuilder(URI("$baseUrl/v1/health"))
            .timeout(Duration.ofSeconds(2)).GET().build()
        client.send(req, BodyHandlers.ofString()).statusCode() == 200
    } catch (_: IOException) {
        false
    } catch (_: InterruptedException) {
        false
    }

    /**
     * Helper: deliver a single 1:1 X3DH-initial handshake from Alice to
     * Bob (or Carol) and return both sides' [ContactSession]s. Mirrors
     * the unidirectional pattern proven in [EndToEndIntegrationTest] —
     * only Alice scans Bob's QR; Bob's contact is auto-created from
     * reply_info.
     */
    private suspend fun pair(
        alice: Messaging,
        aliceLabel: String,
        bob: Messaging,
        firstMessage: String,
    ): Pair<org.khord.shared.protocol.orchestrator.ContactSession,
            org.khord.shared.protocol.orchestrator.ContactSession> {
        val bobQr = bob.myQrPayload()
        val aliceQr = alice.myQrPayload()
        alice.storeContact(bobQr)
        val aliceSide = alice.initiateContact(
            contactFingerprint = bobQr.fingerprint,
            myInboundMailboxId = aliceQr.relayMailbox,
            firstMessage = firstMessage,
        )
        val pending = bob.pollPendingMailboxes()
        assertEquals(1, pending.size,
            "$aliceLabel→${bob.myFingerprint.take(6)} pairing expected exactly one new contact")
        val bobSide = pending[0].session
        // Drain the priming message from Alice→Bob 1:1 channel (Bob
        // doesn't care about it; we just don't want it lingering as
        // unacked data).
        assertEquals(firstMessage, pending[0].firstMessage)
        return aliceSide to bobSide
    }

    @Test
    fun alice_creates_group_fans_out_and_carol_leaves() =
        runTest(timeout = kotlin.time.Duration.parse("120s")) {
            if (!isEnabled) return@runTest
            Crypto.ensureInitialized()

            val nonce = System.nanoTime()
            val aliceIdentity = IdentityKey.fromSeedPhrase("alice group $nonce")
            val bobIdentity = IdentityKey.fromSeedPhrase("bob group $nonce")
            val carolIdentity = IdentityKey.fromSeedPhrase("carol group $nonce")

            val aliceHttp = khordHttpClient(Java)
            val bobHttp = khordHttpClient(Java)
            val carolHttp = khordHttpClient(Java)
            val alice = Messaging.create(
                aliceIdentity, keyServerUrl, relayServerUrl, aliceHttp,
                displayName = "Alice",
            )
            val bob = Messaging.create(
                bobIdentity, keyServerUrl, relayServerUrl, bobHttp,
                displayName = "Bob",
            )
            val carol = Messaging.create(
                carolIdentity, keyServerUrl, relayServerUrl, carolHttp,
                displayName = "Carol",
            )

            alice.register(opkBatchSize = 5)
            bob.register(opkBatchSize = 5)
            carol.register(opkBatchSize = 5)

            // Alice pairs with Bob and Carol. Bob and Carol are NOT
            // introduced to each other.
            val (aliceBobSession, _) = pair(alice, "Alice", bob, "hi Bob")
            val (aliceCarolSession, _) = pair(alice, "Alice", carol, "hi Carol")

            // ── Step 1: Alice creates the group ──────────────────────────
            val groupId = alice.createGroup(
                groupName = "Family",
                memberFingerprints = listOf(
                    bobIdentity.fingerprint,
                    carolIdentity.fingerprint,
                ),
            )
            assertEquals(32, groupId.length, "group id is 32 hex chars")

            // Bob and Carol drain their channels — they receive the
            // group_invite and create the group locally.
            // We need each receiver's ContactSession-with-Alice to drive
            // receiveMessages. Bob and Carol each have exactly one
            // contact (Alice).
            val bobAliceSession = bob.contacts().first()
            val carolAliceSession = carol.contacts().first()
            bob.receiveMessages(bobAliceSession)
            carol.receiveMessages(carolAliceSession)

            // Both should now have the group locally with 3 members.
            assertNotNull(bob.groupSnapshot(groupId))
            assertNotNull(carol.groupSnapshot(groupId))
            assertEquals(3, bob.groupMembers(groupId).size)
            assertEquals(3, carol.groupMembers(groupId).size)

            // ── Step 2: Alice sends a group message ──────────────────────
            alice.sendGroupMessage(groupId, "hello everyone")
            bob.receiveMessages(bobAliceSession)
            carol.receiveMessages(carolAliceSession)

            val bobMsgs = bob.groupMessageHistory(groupId)
            val carolMsgs = carol.groupMessageHistory(groupId)
            assertEquals(1, bobMsgs.size, "Bob received the group message")
            assertEquals(1, carolMsgs.size, "Carol received the group message")
            assertEquals("hello everyone", bobMsgs[0].body)
            assertEquals(aliceIdentity.fingerprint, bobMsgs[0].senderFingerprint)
            assertEquals("Alice", bobMsgs[0].senderDisplayName)

            // Alice's local copy is logged as SENT.
            val aliceMsgs = alice.groupMessageHistory(groupId)
            assertEquals(1, aliceMsgs.size)
            assertEquals(
                org.khord.shared.protocol.orchestrator.MessageEntry.Direction.SENT,
                aliceMsgs[0].direction,
            )

            // ── Step 3: Carol leaves ─────────────────────────────────────
            carol.leaveGroup(groupId)
            // After leaving, Carol's local group is gone.
            assertNull(carol.groupSnapshot(groupId))

            // Alice and Bob receive the group_member_left.
            alice.receiveMessages(aliceCarolSession)
            bob.receiveMessages(bobAliceSession)
            // Bob would also need to hear from Carol, but Bob has no
            // session with Carol — so Bob's local view still has
            // Carol as a member until Alice (the admin) catches Bob
            // up. Per ADR 023's cross-friendship caveat, this is
            // expected. Alice DID see the leave directly though:
            assertEquals(2, alice.groupMembers(groupId).size,
                "Alice removed Carol after receiving the leave")

            // ── Step 4: Alice sends another message; only Bob gets it ───
            alice.sendGroupMessage(groupId, "after-carol-left")
            bob.receiveMessages(bobAliceSession)
            // Carol's session is dead (she left + wiped locally), but
            // even if we tried to drain it, Alice's send loop skipped
            // her because she's no longer in Alice's member list.

            val bobMsgsAfter = bob.groupMessageHistory(groupId)
            assertEquals(2, bobMsgsAfter.size, "Bob got the post-leave message")
            assertEquals("after-carol-left", bobMsgsAfter[1].body)

            // Carol must not have received the second message.
            assertNull(carol.groupSnapshot(groupId), "Carol has no group locally")
            assertTrue(
                carol.groupMessageHistory(groupId).isEmpty(),
                "Carol's group history is gone after leave",
            )
        }
}
