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
import kotlin.test.assertTrue
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
        val alice = Messaging.create(
            aliceIdentity, keyServerUrl, relayServerUrl, aliceHttp,
            displayName = "Alice",
        )
        val bob = Messaging.create(
            bobIdentity, keyServerUrl, relayServerUrl, bobHttp,
            displayName = "Bob",
        )

        // Both register pre-key bundles on the Key Server.
        alice.register(opkBatchSize = 5)
        bob.register(opkBatchSize = 5)
        assertEquals(5, bob.opkSecretCount, "Bob has all 5 OPKs initially")

        // Unidirectional QR exchange — ONLY Alice scans Bob's QR. Bob does
        // NOT pre-store Alice's QR; the orchestrator must auto-create Alice's
        // contact entry from the encrypted reply_info on the X3DH initial.
        val bobQr = bob.myQrPayload()
        val aliceQr = alice.myQrPayload()
        alice.storeContact(bobQr)
        // bob.storeContact(aliceQr)  // ← intentionally NOT called

        // 1. Alice initiates. She passes the mailbox-id from HER OWN QR
        //    (the one she gave Bob) so Bob's replies land there.
        val aliceContact = alice.initiateContact(
            contactFingerprint = bobQr.fingerprint,
            myInboundMailboxId = aliceQr.relayMailbox,
            firstMessage = "Hello Bob",
        )

        // 2. Bob polls his pending mailboxes — picks up the X3dhInitial,
        //    runs X3DH respond, decrypts, AUTO-CREATES the Alice contact
        //    from reply_info, and returns the established session.
        val newContacts = bob.pollPendingMailboxes()
        assertEquals(1, newContacts.size, "Bob expected exactly one new contact")
        val (bobContact, helloBob) = newContacts[0].session to newContacts[0].firstMessage
        assertEquals("Hello Bob", helloBob)
        assertEquals(
            "Alice",
            bob.contactDisplayName(bobContact.contactFingerprint),
            "Bob should have learned Alice's display name from reply_info",
        )

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

    /**
     * App-restart simulation — the load-bearing persistence test.
     *
     * Alice and Bob both back their orchestrators with a real on-disk
     * SQLDelight database. After exchanging a few messages, BOTH instances
     * are torn down (closed). New orchestrators are constructed via
     * [org.khord.shared.protocol.orchestrator.Messaging.load] from the
     * same DB files, which must:
     *   - restore identity, SPK, OPKs, contacts, sessions, pending mailboxes
     *   - allow encryption + decryption of fresh messages using the
     *     reloaded ratchet state.
     */
    @Test
    fun state_survives_app_restart() = runTest(timeout = kotlin.time.Duration.parse("90s")) {
        if (!isEnabled) return@runTest
        Crypto.ensureInitialized()

        val tempDir = kotlin.io.path.createTempDirectory("khord-e2e-restart").toFile().apply {
            deleteOnExit()
        }
        val aliceDb = "${tempDir.absolutePath}/alice-${System.nanoTime()}.db"
        val bobDb = "${tempDir.absolutePath}/bob-${System.nanoTime()}.db"

        val aliceKs = org.khord.shared.storage.InMemoryKeyStore()
        val bobKs = org.khord.shared.storage.InMemoryKeyStore()

        // ─── Phase 1: bootstrap from scratch ─────────────────────────────
        run {
            val nonce = System.nanoTime()
            val aliceIdentity = IdentityKey.fromSeedPhrase("alice persist $nonce")
            val bobIdentity = IdentityKey.fromSeedPhrase("bob persist $nonce")

            val aliceHttp = khordHttpClient(Java)
            val bobHttp = khordHttpClient(Java)
            val alicePersist = org.khord.shared.storage.openDbPersistence(aliceDb, aliceKs)
            val bobPersist = org.khord.shared.storage.openDbPersistence(bobDb, bobKs)

            val alice = org.khord.shared.protocol.orchestrator.Messaging.createWithPersistence(
                aliceIdentity, keyServerUrl, relayServerUrl, aliceHttp, alicePersist,
                displayName = "Alice",
            )
            val bob = org.khord.shared.protocol.orchestrator.Messaging.createWithPersistence(
                bobIdentity, keyServerUrl, relayServerUrl, bobHttp, bobPersist,
                displayName = "Bob",
            )

            alice.register(opkBatchSize = 5)
            bob.register(opkBatchSize = 5)

            // Same unidirectional QR exchange as the e2e test: only Alice scans Bob.
            val bobQr = bob.myQrPayload()
            val aliceQr = alice.myQrPayload()
            alice.storeContact(bobQr)
            // bob.storeContact(aliceQr)  // ← intentionally omitted; auto-created on receive

            val aliceContact = alice.initiateContact(
                contactFingerprint = bobQr.fingerprint,
                myInboundMailboxId = aliceQr.relayMailbox,
                firstMessage = "before-restart-1",
            )
            val newContacts = bob.pollPendingMailboxes()
            assertEquals(1, newContacts.size)
            val bobContact = newContacts[0].session
            assertEquals("before-restart-1", newContacts[0].firstMessage)

            bob.sendMessage(bobContact, "before-restart-2")
            assertEquals(listOf("before-restart-2"), alice.receiveMessages(aliceContact))

            // Tear down both orchestrators (simulates app close).
            alicePersist.close()
            bobPersist.close()
        }

        // ─── Phase 2: reload from disk + continue ────────────────────────
        run {
            val aliceHttp = khordHttpClient(Java)
            val bobHttp = khordHttpClient(Java)
            val alicePersist = org.khord.shared.storage.openDbPersistence(aliceDb, aliceKs)
            val bobPersist = org.khord.shared.storage.openDbPersistence(bobDb, bobKs)

            val alice = org.khord.shared.protocol.orchestrator.Messaging.load(aliceHttp, alicePersist)
                ?: error("alice identity not loaded from $aliceDb")
            val bob = org.khord.shared.protocol.orchestrator.Messaging.load(bobHttp, bobPersist)
                ?: error("bob identity not loaded from $bobDb")

            // The reloaded orchestrators must still see their contacts + sessions.
            val aliceContacts = alice.contacts()
            val bobContacts = bob.contacts()
            assertEquals(1, aliceContacts.size, "alice should have 1 contact session after reload")
            assertEquals(1, bobContacts.size, "bob should have 1 contact session after reload")

            val aliceContact = aliceContacts.single()
            val bobContact = bobContacts.single()

            // Continue the conversation using the reloaded ratchet states.
            alice.sendMessage(aliceContact, "after-restart-1")
            assertEquals(listOf("after-restart-1"), bob.receiveMessages(bobContact))
            bob.sendMessage(bobContact, "after-restart-2")
            assertEquals(listOf("after-restart-2"), alice.receiveMessages(aliceContact))

            // Local message history is keyed by the OTHER party's fingerprint
            // (i.e., the contact's): Alice's history with Bob lives under
            // Bob's fingerprint = aliceContact.contactFingerprint.
            val aliceHistory = alice.messageHistory(aliceContact.contactFingerprint).map { it.body }
            val bobHistory = bob.messageHistory(bobContact.contactFingerprint).map { it.body }
            assertTrue("before-restart-1" in aliceHistory, "alice missing before-restart-1: $aliceHistory")
            assertTrue("after-restart-2" in aliceHistory, "alice missing after-restart-2: $aliceHistory")
            assertTrue("before-restart-1" in bobHistory, "bob missing before-restart-1: $bobHistory")
            assertTrue("after-restart-2" in bobHistory, "bob missing after-restart-2: $bobHistory")

            alicePersist.close()
            bobPersist.close()
        }
    }

    /**
     * Seed-phrase recovery — the load-bearing test for ADR 025.
     *
     * Alice and Bob exchange messages. Alice then loses ALL state
     * (orchestrator + persistence + keystore). Alice re-derives her
     * identity from the SAME seed phrase, opens a fresh persistence
     * with a fresh keystore, and re-registers on the Key Server. She
     * then sends a new X3DH initial to Bob — landing on Bob's
     * ALREADY-BOUND inbound mailbox (the same one he gave her in the
     * original QR, since real-world testers might re-use the same
     * printed QR).
     *
     * Expected behaviour (ADR 025):
     *   - Bob's `receiveMessages` detects the X3DH initial on the
     *     bound mailbox, runs `applyX3dhInitialReset`, replaces the
     *     stale session in-place, decrypts the new first message.
     *   - A "Session reset" marker is inserted into Bob's local
     *     message log just before the new first message.
     *   - Both directions of the conversation work afterward.
     *   - Older messages from before the reset stay readable in
     *     Bob's history.
     */
    @Test
    fun seed_phrase_recovery_resets_session_on_known_fingerprint() = runTest(timeout = kotlin.time.Duration.parse("90s")) {
        if (!isEnabled) return@runTest
        Crypto.ensureInitialized()

        val tempDir = kotlin.io.path.createTempDirectory("khord-recovery").toFile().apply {
            deleteOnExit()
        }
        val aliceDb1 = "${tempDir.absolutePath}/alice1-${System.nanoTime()}.db"
        val aliceDb2 = "${tempDir.absolutePath}/alice2-${System.nanoTime()}.db"
        val bobDb = "${tempDir.absolutePath}/bob-${System.nanoTime()}.db"

        // Alice's seed — the entire premise: same input here ⇒ same
        // IdentityKey ⇒ same fingerprint ⇒ Key Server accepts the
        // re-registration. The seed string is the canonical
        // space-joined word form a real recovery flow would feed in
        // (the UI calls SeedPhrase.toCanonicalString first).
        val aliceSeed = "alice recovery ${System.nanoTime()}"
        val bobSeed = "bob recovery ${System.nanoTime()}"
        val bobIdentity = IdentityKey.fromSeedPhrase(bobSeed)

        // Bob's keystore is shared across phases — he hasn't lost
        // state, so SQLCipher must decrypt his DB with the SAME
        // passphrase across reloads. Alice's keystore is recreated
        // in phase 2 (she lost it; that's the whole point).
        val bobKs = org.khord.shared.storage.InMemoryKeyStore()

        // ─── Phase 1: original install, Alice ↔ Bob exchange ───────
        val (bobQr, aliceFingerprint) = run {
            val aliceIdentity = IdentityKey.fromSeedPhrase(aliceSeed)
            val aliceHttp = khordHttpClient(Java)
            val bobHttp = khordHttpClient(Java)
            val alicePersist = org.khord.shared.storage.openDbPersistence(
                aliceDb1, org.khord.shared.storage.InMemoryKeyStore(),
            )
            val bobPersist = org.khord.shared.storage.openDbPersistence(bobDb, bobKs)
            val alice = org.khord.shared.protocol.orchestrator.Messaging.createWithPersistence(
                aliceIdentity, keyServerUrl, relayServerUrl, aliceHttp, alicePersist,
                displayName = "Alice",
            )
            val bob = org.khord.shared.protocol.orchestrator.Messaging.createWithPersistence(
                bobIdentity, keyServerUrl, relayServerUrl, bobHttp, bobPersist,
                displayName = "Bob",
            )
            alice.register(opkBatchSize = 5)
            bob.register(opkBatchSize = 5)

            val bobQr = bob.myQrPayload()
            val aliceQr = alice.myQrPayload()
            alice.storeContact(bobQr)

            val aliceContact = alice.initiateContact(
                contactFingerprint = bobQr.fingerprint,
                myInboundMailboxId = aliceQr.relayMailbox,
                firstMessage = "before-recovery",
            )
            val newContacts = bob.pollPendingMailboxes()
            assertEquals(1, newContacts.size)
            val bobContact = newContacts[0].session
            assertEquals("before-recovery", newContacts[0].firstMessage)

            // Exchange one round so both sides have a meaningfully
            // advanced ratchet (the reset path replaces THIS state).
            bob.sendMessage(bobContact, "hi-back-before-recovery")
            assertEquals(
                listOf("hi-back-before-recovery"),
                alice.receiveMessages(aliceContact),
            )

            alicePersist.close()
            bobPersist.close()
            bobQr to aliceIdentity.fingerprint
        }

        // ─── Phase 2: Alice recovers from seed phrase ───────────────
        val aliceHttp = khordHttpClient(Java)
        val alicePersist2 = org.khord.shared.storage.openDbPersistence(
            aliceDb2, org.khord.shared.storage.InMemoryKeyStore(),
        )
        // Same seed → same identity → same fingerprint.
        val aliceRecoveredIdentity = IdentityKey.fromSeedPhrase(aliceSeed)
        assertEquals(
            aliceFingerprint,
            aliceRecoveredIdentity.fingerprint,
            "seed phrase recovery must be deterministic",
        )

        val aliceRecovered = org.khord.shared.protocol.orchestrator.Messaging.createWithPersistence(
            aliceRecoveredIdentity, keyServerUrl, relayServerUrl, aliceHttp, alicePersist2,
            displayName = "Alice",
        )
        // Re-registration MUST succeed — the Key Server's identity-key
        // match check accepts it because the re-derived public key is
        // bit-identical.
        aliceRecovered.register(opkBatchSize = 5)

        // Alice re-scans Bob's original QR (same printed card, same
        // mailbox). Sends a fresh X3DH initial to Bob's now-BOUND
        // mailbox.
        aliceRecovered.storeContact(bobQr)
        val aliceQr2 = aliceRecovered.myQrPayload()
        val aliceContact2 = aliceRecovered.initiateContact(
            contactFingerprint = bobQr.fingerprint,
            myInboundMailboxId = aliceQr2.relayMailbox,
            firstMessage = "hello-from-recovered-alice",
        )

        // ─── Phase 3: Bob reloads, polls the bound mailbox ──────────
        val bobHttp = khordHttpClient(Java)
        val bobPersist2 = org.khord.shared.storage.openDbPersistence(bobDb, bobKs)
        val bob2 = org.khord.shared.protocol.orchestrator.Messaging.load(bobHttp, bobPersist2)
            ?: error("bob's identity should reload from $bobDb")
        val bobContact2 = bob2.contacts().single()

        // The actual reset path: receiveMessages encounters a
        // WireEnvelope.X3dhInitial on the bound mailbox, runs the
        // reset, decrypts under the new ratchet.
        val received = bob2.receiveMessages(bobContact2)
        assertEquals(listOf("hello-from-recovered-alice"), received)

        // ─── Phase 4: continued conversation under fresh session ────
        bob2.sendMessage(bob2.contacts().single(), "ack-after-reset")
        assertEquals(
            listOf("ack-after-reset"),
            aliceRecovered.receiveMessages(aliceContact2),
        )

        // ─── Phase 5: history sanity ────────────────────────────────
        // Bob's history with Alice should include: the original
        // "before-recovery" line, the "[session reset]" marker, and
        // the new "hello-from-recovered-alice".
        val bobHistory = bob2.messageHistory(aliceFingerprint).map { it.body }
        assertTrue("before-recovery" in bobHistory, "old message lost: $bobHistory")
        assertTrue(
            bobHistory.any { it.contains("Session reset") },
            "expected reset marker in bob's history: $bobHistory",
        )
        assertTrue(
            "hello-from-recovered-alice" in bobHistory,
            "post-reset message missing: $bobHistory",
        )

        alicePersist2.close()
        bobPersist2.close()
    }
}
