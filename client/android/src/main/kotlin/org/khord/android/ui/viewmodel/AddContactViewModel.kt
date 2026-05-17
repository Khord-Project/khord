package org.khord.android.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.khord.android.AppContainer
import org.khord.shared.protocol.KhordJson
import org.khord.shared.protocol.wire.QrPayload
import android.content.Context
import org.khord.android.push.PushServiceController
import org.khord.shared.storage.PlatformContextProvider

class AddContactViewModel : ViewModel() {

    data class UiState(
        val myQrPayload: QrPayload? = null,
        val myQrPayloadJson: String? = null,
        val newContactArrived: Boolean = false,
        val scanResult: ScanResult? = null,
        val sending: Boolean = false,
        val error: String? = null,
    )

    sealed interface ScanResult {
        data class Stored(val qr: QrPayload) : ScanResult
        data class InvalidQr(val message: String) : ScanResult
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private val mutex = Mutex()

    /** Mint a fresh QR-bound mailbox + return the wire JSON for the QR. */
    fun primeMyQr() {
        viewModelScope.launch(Dispatchers.IO) {
            mutex.withLock {
                runCatching {
                    val messaging = AppContainer.messaging ?: error("not initialised")
                    val qr = messaging.myQrPayload()
                    val json = KhordJson.encodeToString(QrPayload.serializer(), qr)
                    _state.update { it.copy(myQrPayload = qr, myQrPayloadJson = json) }
                }.onFailure { e ->
                    _state.update { it.copy(error = e.message ?: e::class.simpleName) }
                }
            }
        }
    }

    /** Foreground 3 s polling loop while the My-QR tab is visible. */
    fun pollWhileShowingMyQr() {
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                mutex.withLock {
                    runCatching {
                        val messaging = AppContainer.messaging ?: return@runCatching
                        val newContacts = messaging.pollPendingMailboxes()
                        if (newContacts.isNotEmpty()) {
                            _state.update { it.copy(newContactArrived = true) }
                            // New inbound mailbox is now bound to a contact —
                            // tell the push service to add a WebSocket for it.
                            refreshPushService()
                        }
                    }
                }
                if (_state.value.newContactArrived) break
                delay(3_000)
            }
        }
    }

    /** Decode a scanned QR JSON; store the contact (if valid). */
    fun onScanned(rawJson: String) {
        viewModelScope.launch(Dispatchers.IO) {
            mutex.withLock {
                runCatching {
                    val qr = KhordJson.decodeFromString(QrPayload.serializer(), rawJson)
                    val messaging = AppContainer.messaging ?: error("not initialised")
                    messaging.storeContact(qr)
                    _state.update { it.copy(scanResult = ScanResult.Stored(qr)) }
                }.onFailure { e ->
                    _state.update {
                        it.copy(scanResult = ScanResult.InvalidQr(e.message ?: "invalid QR"))
                    }
                }
            }
        }
    }

    /**
     * Best-effort: ping the push service to refresh its subscription set.
     * Reads the Application context from the same provider [SettingsViewModel]
     * uses for panic. No-op if context is unavailable or the service isn't
     * running yet.
     */
    private fun refreshPushService() {
        (PlatformContextProvider.get() as? Context)?.let {
            runCatching { PushServiceController.refresh(it) }
        }
    }

    /** Send the first message after a successful scan, completing the X3DH. */
    fun sendFirstMessage(contactFingerprint: String, firstMessage: String, onSent: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            mutex.withLock {
                _state.update { it.copy(sending = true, error = null) }
                runCatching {
                    val messaging = AppContainer.messaging ?: error("not initialised")
                    val myInbound = _state.value.myQrPayload?.relayMailbox
                        ?: error("no QR mailbox staged — open the My-QR tab first")
                    messaging.initiateContact(
                        contactFingerprint = contactFingerprint,
                        myInboundMailboxId = myInbound,
                        firstMessage = firstMessage,
                    )
                    // The pending mailbox just bound to a real contact —
                    // refresh the push service so its WS picks the inbound
                    // mailbox up immediately (rather than waiting for the
                    // next process start).
                    refreshPushService()
                    _state.update { it.copy(sending = false) }
                    // onSent typically calls NavController.navigate, which
                    // mutates LifecycleRegistry and asserts main-thread. We
                    // hop back to Main explicitly because the surrounding
                    // launch dispatched us to IO for the network/crypto work.
                    withContext(Dispatchers.Main) { onSent(contactFingerprint) }
                }.onFailure { e ->
                    _state.update {
                        it.copy(sending = false, error = e.message ?: e::class.simpleName)
                    }
                }
            }
        }
    }
}
