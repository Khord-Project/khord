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
 * Backs [org.khord.android.ui.screens.CreateGroupScreen]. Lists the
 * user's existing contacts so they can be ticked into a new group, then
 * calls [org.khord.shared.protocol.orchestrator.Messaging.createGroup]
 * with the user-chosen name and the selected fingerprints. On success
 * the screen navigates to the new group's chat.
 */
class CreateGroupViewModel : ViewModel() {

    data class ContactPick(
        val fingerprint: String,
        val displayLabel: String,
        val selected: Boolean,
    )

    data class UiState(
        val groupName: String = "",
        val contacts: List<ContactPick> = emptyList(),
        val creating: Boolean = false,
        val error: String? = null,
        /** Non-null when the group is created; the screen navigates on this. */
        val createdGroupId: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init { loadContacts() }

    private fun loadContacts() {
        viewModelScope.launch(Dispatchers.IO) {
            val messaging = AppContainer.messaging ?: return@launch
            val picks = messaging.contacts().map { c ->
                val label = messaging.contactDisplayName(c.contactFingerprint)
                    ?: (c.contactFingerprint.take(8) + "…" + c.contactFingerprint.takeLast(8))
                ContactPick(c.contactFingerprint, label, selected = false)
            }
            _state.update { it.copy(contacts = picks.sortedBy { p -> p.displayLabel }) }
        }
    }

    fun onGroupNameChange(name: String) {
        _state.update { it.copy(groupName = name.take(64), error = null) }
    }

    fun toggle(fingerprint: String) {
        _state.update {
            it.copy(contacts = it.contacts.map { c ->
                if (c.fingerprint == fingerprint) c.copy(selected = !c.selected) else c
            })
        }
    }

    fun create() {
        val s = _state.value
        if (s.creating) return
        val name = s.groupName.trim()
        if (name.isEmpty()) {
            _state.update { it.copy(error = "Group name is required.") }
            return
        }
        val picked = s.contacts.filter { it.selected }.map { it.fingerprint }
        if (picked.isEmpty()) {
            _state.update { it.copy(error = "Pick at least one contact.") }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(creating = true, error = null) }
            runCatching {
                val messaging = AppContainer.messaging ?: error("not initialised")
                messaging.createGroup(name, picked)
            }.onSuccess { gid ->
                _state.update { it.copy(creating = false, createdGroupId = gid) }
            }.onFailure { e ->
                _state.update {
                    it.copy(creating = false, error = e.message ?: e::class.simpleName)
                }
            }
        }
    }
}
