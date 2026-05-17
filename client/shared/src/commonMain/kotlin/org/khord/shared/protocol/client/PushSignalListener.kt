package org.khord.shared.protocol.client

import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update

/**
 * Multiplexes [MailboxWebSocketClient] instances across all of a user's
 * active inbound mailboxes.
 *
 * The Relay Server's WebSocket endpoint is per-mailbox (PROTOCOL.md §5.5),
 * so a user with N active contacts maintains N WebSockets. OkHttp pools
 * the underlying TCP connections per host efficiently, so this is cheap
 * even for double-digit contact counts.
 *
 * Each mailbox is described by a [Subscription]:
 *   - the (mailbox, token) tuple needed by the WS handshake,
 *   - the [contactFingerprint] used to look up the corresponding
 *     [org.khord.shared.protocol.orchestrator.ContactSession] when the
 *     push fires.
 *
 * Lifecycle:
 *   - [start] launches one [MailboxWebSocketClient] per subscription on
 *     an internal supervisor scope. State is exposed via [connectedFingerprints]
 *     for the UI to suppress polling.
 *   - [updateSubscriptions] takes a new set; clients that disappeared are
 *     stopped, new ones are started, existing ones are left running.
 *   - [stop] tears the whole thing down.
 *
 * `onPush(fingerprint)` runs on the internal scope. It must be quick or
 * dispatch to its own scope — the WS reader will not deliver another push
 * to the same mailbox until it returns.
 */
class PushSignalListener(
    private val http: HttpClient,
    private val onPush: suspend (contactFingerprint: String) -> Unit,
) {

    data class Subscription(
        val contactFingerprint: String,
        val mailboxId: String,
        val bearerToken: String,
        val relayServerUrl: String,
    )

    /** Active clients keyed by mailbox-id. */
    private val clients = mutableMapOf<String, ClientHolder>()

    private var scope: CoroutineScope? = null

    private val _connected = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Set of contact fingerprints whose mailbox WebSocket is currently in
     * the Connected state. Updated whenever any per-mailbox state changes.
     * The UI subscribes to this and pauses polling for any fingerprint in
     * the set.
     */
    val connectedFingerprints: StateFlow<Set<String>> = _connected.asStateFlow()

    private data class ClientHolder(
        val subscription: Subscription,
        val client: MailboxWebSocketClient,
        val observerJob: Job,
    )

    /**
     * Start with an initial subscription set. Idempotent — second call
     * while already running is a no-op (use [updateSubscriptions] to
     * change the active set).
     */
    fun start(initial: Collection<Subscription>) {
        if (scope != null) return
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = s
        for (sub in initial) addClient(s, sub)
    }

    /**
     * Bring the running set of clients into line with [subscriptions]. New
     * entries are started; missing entries are stopped; carried entries
     * are left alone.
     */
    suspend fun updateSubscriptions(subscriptions: Collection<Subscription>) {
        val s = scope ?: return
        val byMailbox = subscriptions.associateBy { it.mailboxId }

        // Stop clients that vanished.
        val toRemove = clients.keys.filter { it !in byMailbox }
        for (mb in toRemove) {
            val holder = clients.remove(mb) ?: continue
            holder.observerJob.cancel()
            holder.client.stop()
            _connected.update { it - holder.subscription.contactFingerprint }
        }

        // Start clients that appeared.
        for ((mb, sub) in byMailbox) {
            if (clients.containsKey(mb)) continue
            addClient(s, sub)
        }
    }

    /** Stop every client and drop scope. After this, [start] may be called again. */
    suspend fun stop() {
        for (holder in clients.values) {
            holder.observerJob.cancel()
            holder.client.stop()
        }
        clients.clear()
        _connected.value = emptySet()
        scope?.cancel()
        scope = null
    }

    // ── internals ─────────────────────────────────────────────────────────

    private fun addClient(parentScope: CoroutineScope, sub: Subscription) {
        val client = MailboxWebSocketClient(
            http = http,
            relayServerUrl = sub.relayServerUrl,
            mailboxId = sub.mailboxId,
            bearerToken = sub.bearerToken,
            onPush = { onPush(sub.contactFingerprint) },
        )
        client.start(parentScope)

        // Mirror the per-client state into our flat connected-fingerprints set.
        val observerJob = client.state
            .onEach { state ->
                _connected.update { current ->
                    if (state == MailboxWebSocketClient.State.Connected) {
                        current + sub.contactFingerprint
                    } else {
                        current - sub.contactFingerprint
                    }
                }
            }
            .launchIn(parentScope)

        clients[sub.mailboxId] = ClientHolder(sub, client, observerJob)
    }
}
