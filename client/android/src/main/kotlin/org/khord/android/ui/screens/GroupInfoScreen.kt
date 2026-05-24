package org.khord.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import org.khord.android.nav.Routes
import org.khord.android.ui.viewmodel.GroupInfoViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupInfoScreen(nav: NavController, groupId: String) {
    val vm: GroupInfoViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                GroupInfoViewModel(groupId) as T
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    var showAddPicker by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }

    // After leaving, route the user back to the contact list — the
    // group route is now invalid (group row was deleted).
    LaunchedEffect(state.left) {
        if (state.left) {
            nav.navigate(Routes.CONTACTS) {
                popUpTo(Routes.CONTACTS) { inclusive = false }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.group?.groupName ?: "Group info") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            Text(
                "Members (${state.members.size})",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(state.members) { m ->
                    // Tap a member row → fingerprint-verification
                    // screen for that contact. Verification flow
                    // works identically to 1:1 — but the verified
                    // badge does NOT render in group chats (per the
                    // feature spec; per-member badges in messages
                    // are deferred).
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                nav.navigate(
                                    org.khord.android.nav.Routes.fingerprint(m.fingerprint),
                                )
                            }
                            .padding(vertical = 8.dp),
                    ) {
                        Text(
                            m.displayName.ifEmpty {
                                m.fingerprint.take(8) + "…" + m.fingerprint.takeLast(8)
                            },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        if (m.fingerprint == state.group?.createdByFingerprint) {
                            Text(
                                "Admin",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }

            state.error?.let {
                Text(it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 4.dp))
            }

            if (state.group?.isAdmin == true) {
                OutlinedButton(
                    onClick = { showRename = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Rename group") }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { showAddPicker = true },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.addableContacts.isNotEmpty(),
                ) { Text("Add member") }
                Spacer(Modifier.height(8.dp))
            }
            OutlinedButton(
                onClick = { confirmLeave = true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Leave group") }
        }
    }

    if (showRename) {
        RenameGroupDialog(
            currentName = state.group?.groupName ?: "",
            onDismiss = { showRename = false },
            onConfirm = { newName ->
                showRename = false
                vm.rename(newName)
            },
        )
    }
    if (showAddPicker) {
        AddMemberDialog(
            candidates = state.addableContacts,
            onDismiss = { showAddPicker = false },
            onPick = { fp ->
                showAddPicker = false
                vm.addMember(fp)
            },
        )
    }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Leave group?") },
            text = {
                Text(
                    "You will stop receiving messages from this group. " +
                        "Members will be notified.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    vm.leave()
                }) { Text("Leave") }
            },
            dismissButton = {
                TextButton(onClick = { confirmLeave = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun RenameGroupDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename group") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("Group name") },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text) },
                enabled = text.isNotBlank() && text.trim() != currentName,
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun AddMemberDialog(
    candidates: List<Pair<String, String>>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add member") },
        text = {
            Column {
                if (candidates.isEmpty()) {
                    Text("No contacts available to add.")
                } else {
                    candidates.forEach { (fp, name) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(fp) }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(name, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
