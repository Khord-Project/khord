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
import org.khord.shared.protocol.orchestrator.GroupEntry
import org.khord.shared.protocol.orchestrator.GroupMemberEntry

/**
 * Backs [org.khord.android.ui.screens.GroupInfoScreen]. Shows the
 * group's name, member list, and admin-only actions (add member,
 * leave). The "rename" action is omitted from the v1 UI — the
 * protocol supports it via `group_name_changed`, but a dedicated UI
 * for that is deferred to keep this screen minimal.
 */
class GroupInfoViewModel(
    private val groupId: String,
) : ViewModel() {

    data class UiState(
        val group: GroupEntry? = null,
        val members: List<GroupMemberEntry> = emptyList(),
        /** Fingerprints of the user's contacts NOT already in the group. */
        val addableContacts: List<Pair<String, String>> = emptyList(),
        val left: Boolean = false,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init { reload() }

    private fun reload() {
        viewModelScope.launch(Dispatchers.IO) {
            val messaging = AppContainer.messaging ?: return@launch
            val group = messaging.groupSnapshot(groupId)
            val members = messaging.groupMembers(groupId)
            val memberFps = members.map { it.fingerprint }.toSet()
            val addable = messaging.contacts()
                .filter { it.contactFingerprint !in memberFps }
                .map {
                    val name = messaging.contactDisplayName(it.contactFingerprint)
                        ?: (it.contactFingerprint.take(8) + "…" + it.contactFingerprint.takeLast(8))
                    it.contactFingerprint to name
                }
            _state.update {
                it.copy(group = group, members = members, addableContacts = addable)
            }
        }
    }

    fun leave() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                AppContainer.messaging?.leaveGroup(groupId) ?: error("not initialised")
            }.onSuccess {
                _state.update { it.copy(left = true) }
            }.onFailure { e ->
                _state.update { it.copy(error = e.message ?: e::class.simpleName) }
            }
        }
    }

    fun addMember(fingerprint: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                AppContainer.messaging?.addGroupMember(groupId, fingerprint)
                    ?: error("not initialised")
                reload()
            }.onFailure { e ->
                _state.update { it.copy(error = e.message ?: e::class.simpleName) }
            }
        }
    }
}
