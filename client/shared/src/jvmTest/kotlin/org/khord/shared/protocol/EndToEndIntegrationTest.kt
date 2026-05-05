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
import kotlin.test.fail

/**
 * Full Alice ↔ Bob round trip against a running Docker Compose stack.
 *
 * **This test is gated** by the system property `khord.integration`. By
 * default the test body is a no-op (the JVM `kotlin.test` annotations
 * have no native skip semantics). To actually run:
 *
 *   ./gradlew :shared:jvmTest -Dkhord.integration=true \
 *       --tests "org.khord.shared.protocol.EndToEndIntegrationTest"
 *
 * Pre-conditions:
 *   - `docker compose up` from the repo root has both keyserver:8001 and
 *     relayserver:8002 healthy.
 *   - The relayserver's PoW difficulty is the default (8 bits).
 */
class EndToEndIntegrationTest {

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
    } catch (e: IOException) {
        false
    } catch (e: InterruptedException) {
        false
    }

    @Test
    fun alice_and_bob_exchange_messages_through_real_servers() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        if (!isEnabled) return@runTest
        Crypto.ensureInitialized()

        // Unique seeds per run so this can re-run against a non-truncated stack.
        val nonce = System.nanoTime()
        val aliceIdentity = IdentityKey.fromSeedPhrase("alice e2e $nonce")
        val bobIdentity = IdentityKey.fromSeedPhrase("bob e2e $nonce")

        val aliceHttp = khordHttpClient(Java)
        val bobHttp = khordHttpClient(Java)
        val alice = Messaging.create(aliceIdentity, keyServerUrl, relayServerUrl, aliceHttp)
        val bob = Messaging.create(bobIdentity, keyServerUrl, relayServerUrl, bobHttp)

        // Both register pre-key bundles on the Key Server.
        alice.register(opkBatchSize = 5)
        bob.register(opkBatchSize = 5)
        assertEquals(5, bob.opkSecretCount, "Bob has all 5 OPKs initially")

        // Out-of-band QR exchange. Both QRs are minted simultaneously when the
        // parties meet; both clients learn the OTHER party's QR.
        val bobQr = bob.myQrPayload()
        val aliceQr = alice.myQrPayload()
        alice.storeContact(bobQr)
        bob.storeContact(aliceQr)

        // 1. Alice initiates. She passes the mailbox-id from HER OWN QR
        //    (the one she gave Bob) so Bob's replies land there.
        val aliceContact = alice.initiateContact(
            contactFingerprint = bobQr.fingerprint,
            myInboundMailboxId = aliceQr.relayMailbox,
            firstMessage = "Hello Bob",
        )

        // 2. Bob polls his pending mailboxes — picks up the X3dhInitial,
        //    runs X3DH respond, decrypts, returns the established session.
        val newContacts = bob.pollPendingMailboxes()
        assertEquals(1, newContacts.size, "Bob expected exactly one new contact")
        val (bobContact, helloBob) = newContacts[0].session to newContacts[0].firstMessage
        assertEquals("Hello Bob", helloBob)

        // OPK forward-secrecy invariant: Alice's X3DH consumed Bob's OPK,
        // so Bob's local secret store has dropped one entry.
        assertEquals(4, bob.opkSecretCount, "Bob's consumed OPK was wiped+removed")

        // 3. Bob replies; Alice fetches.
        bob.sendMessage(bobContact, "Hello Alice")
        assertEquals(listOf("Hello Alice"), alice.receiveMessages(aliceContact))

        // 4. 10 alternating messages.
        repeat(10) { i ->
            val msg = "msg $i"
            if (i % 2 == 0) {
                alice.sendMessage(aliceContact, msg)
                assertEquals(listOf(msg), bob.receiveMessages(bobContact))
            } else {
                bob.sendMessage(bobContact, msg)
                assertEquals(listOf(msg), alice.receiveMessages(aliceContact))
            }
        }
    }
}
