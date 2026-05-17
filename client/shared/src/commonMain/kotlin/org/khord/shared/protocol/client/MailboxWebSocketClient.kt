package org.khord.shared.protocol.client

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.khord.shared.protocol.KhordJson

/**
 * Single-mailbox WebSocket listener for the Relay Server push channel
 * (PROTOCOL.md §5.5).
 *
 * Lifecycle:
 *   1. [start] launches a coroutine on the supplied scope.
 *   2. The coroutine connects to `/v1/mailboxes/{id}/ws`, sends
 *      `{"type":"auth","token":...}`, then reads frames until the socket
 *      closes (gracefully or not).
 *   3. Every received text frame is parsed and any whose `type` field is
 *      `"message"` triggers [onPush] (the body itself is ignored — see
 *      ADR-022, signal-and-fetch).
 *   4. On disconnect the coroutine sleeps for the next backoff window
 *      and reconnects. The sequence is 2 → 4 → 8 → 16 → 32 → 60 (capped)
 *      and resets on every successful connect-and-auth.
 *   5. [stop] cancels the coroutine and closes the socket.
 *
 * This class does not authenticate, fetch, or decrypt — those concerns
 * stay in [Messaging.receiveMessages]. It just signals.
 *
 * `relayServerUrl` must be the same `https://` / `http://` URL used for
 * REST. The constructor flips the scheme to `wss://` / `ws://` for the
 * upgrade — callers do not need to know.
 */
class MailboxWebSocketClient(
    private val http: HttpClient,
    relayServerUrl: String,
    private val mailboxId: String,
    private val bearerToken: String,
    /** Called every time the server pushes a `{"type":"message",...}` frame. */
    private val onPush: suspend () -> Unit,
    /**
     * Backoff schedule in milliseconds. Iterated in order on consecutive
     * failures; once exhausted the last value is reused. Reset to the head
     * on every successful auth.
     */
    private val backoffSchedule: LongArray = longArrayOf(
        2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L,
    ),
) {

    private val wsBaseUrl: String = relayServerUrl
        .replaceFirst("https://", "wss://")
        .replaceFirst("http://", "ws://")
        .trimEnd('/')

    private val _state = MutableStateFlow(State.Disconnected)

    /** Observable connection state. UI uses this to suppress polling. */
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null

    enum class State { Disconnected, Connecting, Connected }

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch { runLoop() }
    }

    suspend fun stop() {
        job?.cancelAndJoin()
        job = null
        _state.value = State.Disconnected
    }

    private suspend fun runLoop() {
        var backoffIndex = 0
        while (true) {
            try {
                _state.value = State.Connecting
                runOneConnection()
                // Clean exit (server closed politely) — reset backoff.
                backoffIndex = 0
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Connection failed or dropped. Fall through to backoff.
            } finally {
                _state.value = State.Disconnected
            }

            // Backoff before the next attempt.
            val sleepMs = backoffSchedule[
                minOf(backoffIndex, backoffSchedule.size - 1)
            ]
            backoffIndex++
            try {
                delay(sleepMs)
            } catch (e: CancellationException) {
                throw e
            }
            if (!currentScopeActive()) return
        }
    }

    private suspend fun currentScopeActive(): Boolean {
        // CoroutineScope.isActive is on the scope, but we are inside a
        // launched job so a quick check on the job itself works.
        return job?.isActive == true
    }

    private suspend fun runOneConnection() {
        val path = "/v1/mailboxes/$mailboxId/ws"
        http.webSocket(urlString = "$wsBaseUrl$path") {
            // Auth handshake — first frame, MUST be sent before reading.
            val auth = buildJsonObject {
                put("type", JsonPrimitive("auth"))
                put("token", JsonPrimitive(bearerToken))
            }
            send(KhordJson.encodeToString(JsonObject.serializer(), auth))

            // From here on, every server frame is something we care about.
            // The server pushes `{"type":"message", "sequence":N, "blob":...}`
            // on every send to this mailbox; we treat any such frame as a
            // wake-up signal and ignore the embedded blob.
            _state.value = State.Connected

            while (isActive) {
                val frame = incoming.receive()
                if (frame !is Frame.Text) continue
                val text = frame.readText()
                val type = runCatching {
                    val obj = KhordJson.parseToJsonElement(text) as? JsonObject
                    (obj?.get("type") as? JsonPrimitive)?.content
                }.getOrNull()
                if (type == "message") {
                    // Fire callback; swallow exceptions so a bad callback
                    // doesn't tear down the WS unnecessarily.
                    runCatching { onPush() }
                }
                // Other types (none today, but forward-compat) are ignored.
            }
            // Polite close so the server sees a clean shutdown.
            close()
        }
    }
}
