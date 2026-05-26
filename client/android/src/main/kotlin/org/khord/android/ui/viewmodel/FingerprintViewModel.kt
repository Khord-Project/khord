package org.khord.android.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.khord.android.AppContainer

/**
 * Backs [org.khord.android.ui.screens.FingerprintScreen]. Reads the
 * contact's display name + verified flag and the user's own
 * fingerprint from [Messaging]; exposes a [setVerified] mutation
 * that fans through to persistence and updates the UI state.
 */
class FingerprintViewModel(
    private val contactFingerprint: String,
) : ViewModel() {

    data class UiState(
        val contactDisplayName: String? = null,
        val myFingerprint: String? = null,
        val verified: Boolean = false,
        val blocked: Boolean = false,
        val muted: Boolean = false,
        /**
         * Set to a short confirmation string ("Contact verified ✓" /
         * "Verification removed") on the tick following a successful
         * [setVerified] call, then cleared by [clearJustUpdated] once
         * the screen has surfaced it via snackbar. One-shot — does
         * not persist across configuration change.
         */
        val justUpdated: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init { refreshNameState() }

    private fun refreshNameState() {
        viewModelScope.launch(Dispatchers.IO) {
            val messaging = AppContainer.messaging ?: return@launch
            _state.update {
                it.copy(
                    contactDisplayName = messaging.contactDisplayName(contactFingerprint)
                        ?: "",
                    myFingerprint = messaging.myFingerprint,
                    verified = messaging.isContactVerified(contactFingerprint),
                    blocked = messaging.isContactBlocked(contactFingerprint),
                    muted = messaging.isContactMuted(contactFingerprint),
                )
            }
        }
    }

    fun setBlocked(blocked: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val messaging = AppContainer.messaging ?: return@launch
            messaging.setContactBlocked(contactFingerprint, blocked)
            _state.update {
                it.copy(
                    blocked = blocked,
                    justUpdated = if (blocked) "Contact blocked" else "Contact unblocked",
                )
            }
        }
    }

    fun setMuted(muted: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val messaging = AppContainer.messaging ?: return@launch
            messaging.setContactMuted(contactFingerprint, muted)
            _state.update {
                it.copy(
                    muted = muted,
                    justUpdated = if (muted) "Contact muted" else "Contact unmuted",
                )
            }
        }
    }

    /**
     * Set (blank clears) the local nickname override for this contact.
     * Refreshes the displayed name afterward so the title updates in
     * place.
     */
    fun rename(newName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val messaging = AppContainer.messaging ?: return@launch
            messaging.setContactLocalName(contactFingerprint, newName)
            refreshNameState()
            _state.update {
                it.copy(
                    justUpdated = if (newName.isBlank()) "Name reset"
                                  else "Contact renamed",
                )
            }
        }
    }

    fun setVerified(verified: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val messaging = AppContainer.messaging ?: return@launch
            messaging.setContactVerified(contactFingerprint, verified)
            _state.update {
                it.copy(
                    verified = verified,
                    justUpdated = if (verified) "Contact verified ✓"
                                  else "Verification removed",
                )
            }
        }
    }

    fun clearJustUpdated() {
        _state.update { it.copy(justUpdated = null) }
    }
}
