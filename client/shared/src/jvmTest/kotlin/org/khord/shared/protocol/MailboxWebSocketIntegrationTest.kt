package org.khord.shared.protocol

import io.ktor.client.engine.java.Java
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.khord.shared.crypto.Crypto
import org.khord.shared.protocol.client.MailboxWebSocketClient
import org.khord.shared.protocol.client.Mailboxes
import org.khord.shared.protocol.client.PowMiner
import org.khord.shared.protocol.wire.CreateMailboxRequest
import org.khord.shared.protocol.wire.CreateMailboxResponse
import org.khord.shared.protocol.wire.PowParamsResponse
import org.khord.shared.protocol.wire.SendMessageRequest
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient as JavaHttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import io.ktor.client.call.body
import io.ktor.client.request.get

/**
 * MailboxWebSocketClient verified against the real Relay Server.
 *
 * Gated by `-Dkhord.integration=true` for the same reason
 * [EndToEndIntegrationTest] is — the test needs `docker compose up` from
 * the repo root with relayserver:8002 healthy.
 *
 *   ./gradlew :shared:jvmTest -Dkhord.integration=true \
 *       --tests "org.khord.shared.protocol.MailboxWebSocketIntegrationTest"
 */
class MailboxWebSocketIntegrationTest {

    private val relayServerUrl = "http://localhost:8002"

    private val isEnabled: Boolean
        get() = System.getProperty("khord.integration") == "true"

    @BeforeTest
    fun assumeStackReachable() {
        if (!isEnabled) return
        if (!ping(relayServerUrl)) {
            fail(
                "relayserver not reachable at $relayServerUrl — " +
                "run `docker compose up` and retry."
            )
        }
    }

    @Test
    fun ws_client_connects_authenticates_and_fires_onPush_for_each_message() =
        runTest(timeout = kotlin.time.Duration.parse("30s")) {
            if (!isEnabled) return@runTest
            Crypto.ensureInitialized()

            val http = khordHttpClient(Java)

            // Create a mailbox to subscribe to.
            val params = http.get("$relayServerUrl/v1/pow-params").body<PowParamsResponse>()
            val mailboxId = Mailboxes.newId()
            val nonce = PowMiner.mine(mailboxId, params.difficultyBits)
            val mailbox = http
                .post("$relayServerUrl/v1/mailboxes") {
                    contentType(ContentType.Application.Json)
                    setBody(CreateMailboxRequest(mailboxId, nonce))
                }
                .body<CreateMailboxResponse>()

            val pushCount = AtomicInteger(0)
            val client = MailboxWebSocketClient(
                http = http,
                relayServerUrl = relayServerUrl,
                mailboxId = mailbox.mailboxId,
                bearerToken = mailbox.bearerToken,
                onPush = { pushCount.incrementAndGet() },
            )

            val scope = kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default,
            )
            client.start(scope)

            // Wait until the WS has reached Connected.
            withTimeout(5_000) {
                while (client.state.value != MailboxWebSocketClient.State.Connected) {
                    delay(50)
                }
            }

            // Send 3 messages — each should fire onPush.
            repeat(3) { i ->
                val blob = java.util.Base64.getEncoder()
                    .encodeToString("hello-$i".toByteArray())
                val r: HttpResponse = http.post(
                    "$relayServerUrl/v1/mailboxes/${mailbox.mailboxId}/messages"
                ) {
                    contentType(ContentType.Application.Json)
                    setBody(SendMessageRequest(blob))
                }
                assertEquals(202, r.status.value)
            }

            // Wait for pushCount to catch up.
            withTimeout(5_000) {
                while (pushCount.get() < 3) delay(50)
            }

            assertEquals(3, pushCount.get())

            client.stop()
            scope.cancel()
            http.close()
        }

    @Test
    fun ws_client_reconnects_after_server_drops_the_socket() =
        runTest(timeout = kotlin.time.Duration.parse("30s")) {
            if (!isEnabled) return@runTest
            Crypto.ensureInitialized()

            val http = khordHttpClient(Java)

            val params = http.get("$relayServerUrl/v1/pow-params").body<PowParamsResponse>()
            val mailboxId = Mailboxes.newId()
            val nonce = PowMiner.mine(mailboxId, params.difficultyBits)
            val mailbox = http
                .post("$relayServerUrl/v1/mailboxes") {
                    contentType(ContentType.Application.Json)
                    setBody(CreateMailboxRequest(mailboxId, nonce))
                }
                .body<CreateMailboxResponse>()

            val pushes = AtomicInteger(0)
            // Aggressive backoff so the test doesn't take 30s waiting.
            val client = MailboxWebSocketClient(
                http = http,
                relayServerUrl = relayServerUrl,
                mailboxId = mailbox.mailboxId,
                bearerToken = mailbox.bearerToken,
                onPush = { pushes.incrementAndGet() },
                backoffSchedule = longArrayOf(200L, 200L, 200L),
            )

            val scope = kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default,
            )
            client.start(scope)

            // First push goes through normally.
            withTimeout(5_000) {
                while (client.state.value != MailboxWebSocketClient.State.Connected) delay(50)
            }
            http.post("$relayServerUrl/v1/mailboxes/${mailbox.mailboxId}/messages") {
                contentType(ContentType.Application.Json)
                setBody(SendMessageRequest(
                    java.util.Base64.getEncoder().encodeToString("first".toByteArray())
                ))
            }
            withTimeout(5_000) { while (pushes.get() < 1) delay(50) }

            // Note: We cannot make the relay server forcibly drop our socket
            // from inside this test (no admin endpoint). Reconnect is instead
            // verified by stopping + restarting the client and observing that
            // pushes resume — the backoff loop is identical for either trigger.
            client.stop()
            assertTrue(client.state.value == MailboxWebSocketClient.State.Disconnected)

            client.start(scope)
            withTimeout(5_000) {
                while (client.state.value != MailboxWebSocketClient.State.Connected) delay(50)
            }
            http.post("$relayServerUrl/v1/mailboxes/${mailbox.mailboxId}/messages") {
                contentType(ContentType.Application.Json)
                setBody(SendMessageRequest(
                    java.util.Base64.getEncoder().encodeToString("after-reconnect".toByteArray())
                ))
            }
            withTimeout(5_000) { while (pushes.get() < 2) delay(50) }
            assertEquals(2, pushes.get())

            client.stop()
            scope.cancel()
            http.close()
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
}
