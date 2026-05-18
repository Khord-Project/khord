package org.khord.android.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.khord.android.nav.Routes
import org.khord.android.ui.viewmodel.ContactListViewModel
import org.khord.android.util.TimestampFormat

private const val RECENT_CHATS_LIMIT = 5
private const val POLL_INTERVAL_MS = 10_000L

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalPermissionsApi::class,
    ExperimentalFoundationApi::class,
)
@Composable
fun ContactListScreen(nav: NavController, vm: ContactListViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showAll by remember { mutableStateOf(false) }
    // Long-press on the FAB surfaces a "create group" choice in addition
    // to the normal "add contact" action.
    var showFabMenu by remember { mutableStateOf(false) }

    // Request POST_NOTIFICATIONS on Android 13+. The push service runs
    // either way — without permission the user just doesn't see banners.
    // Asking once when the user first reaches their contact list keeps the
    // prompt away from the onboarding wizard but still happens before any
    // contact has had a chance to send a message.
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        val notifPermission = rememberPermissionState(
            android.Manifest.permission.POST_NOTIFICATIONS
        )
        LaunchedEffect(Unit) {
            if (!notifPermission.status.isGranted) {
                notifPermission.launchPermissionRequest()
            }
        }
    }

    // Auto-poll while this screen is visible. Refresh on initial mount and
    // every 10 s thereafter; cancelled on dispose so background tabs don't
    // burn battery / data. Pull-to-refresh remains via the toolbar icon.
    LaunchedEffect(Unit) {
        while (isActive) {
            vm.refresh(pollServer = true)
            delay(POLL_INTERVAL_MS)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Khord") },
                actions = {
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = { nav.navigate(Routes.SETTINGS) }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .combinedClickable(
                        onClick = { nav.navigate(Routes.ADD_CONTACT) },
                        onLongClick = { showFabMenu = true },
                    ),
            ) {
                FloatingActionButton(onClick = { nav.navigate(Routes.ADD_CONTACT) }) {
                    Icon(Icons.Default.Add, contentDescription = "Add contact")
                }
            }
        },
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            if (state.rows.isEmpty()) {
                EmptyState(error = state.error)
            } else {
                RecentChats(
                    rows = state.rows,
                    showAll = showAll,
                    onToggleShowAll = { showAll = !showAll },
                    onRowClick = { row ->
                        when (row.kind) {
                            ContactListViewModel.Row.Kind.Contact ->
                                nav.navigate(Routes.chat(row.id))
                            ContactListViewModel.Row.Kind.Group ->
                                nav.navigate(Routes.groupChat(row.id))
                        }
                    },
                )
            }
        }
    }

    if (showFabMenu) {
        AlertDialog(
            onDismissRequest = { showFabMenu = false },
            title = { Text("Create") },
            text = {
                Column {
                    Text(
                        "Add contact — scan a QR code or paste a contact link.",
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showFabMenu = false
                                nav.navigate(Routes.ADD_CONTACT)
                            }
                            .padding(vertical = 12.dp),
                    )
                    HorizontalDivider()
                    Text(
                        "New group — pick contacts to invite.",
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showFabMenu = false
                                nav.navigate(Routes.CREATE_GROUP)
                            }
                            .padding(vertical = 12.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showFabMenu = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun EmptyState(error: String?) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No contacts yet", style = MaterialTheme.typography.titleMedium)
        Text(
            "Tap + to share your QR or scan one.",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (error != null) {
            Text(
                "Error: $error",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

@Composable
private fun RecentChats(
    rows: List<ContactListViewModel.Row>,
    showAll: Boolean,
    onToggleShowAll: () -> Unit,
    onRowClick: (ContactListViewModel.Row) -> Unit,
) {
    // Section header — always shown above the list.
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            "Recent Chats",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )

        val visible = if (showAll) rows else rows.take(RECENT_CHATS_LIMIT)

        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(visible) { row ->
                ConversationRow(row, onClick = { onRowClick(row) })
                HorizontalDivider()
            }
            if (rows.size > RECENT_CHATS_LIMIT) {
                item {
                    Text(
                        text = if (showAll) "Show recent only"
                               else "See all conversations (${rows.size})",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggleShowAll() }
                            .padding(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(
    row: ContactListViewModel.Row,
    onClick: () -> Unit,
) {
    // Mute the row visually when its contact's receiveMessages has
    // been failing repeatedly. Row stays tappable — the user can open
    // the chat to see what's wrong (chat screen has its own banner).
    val nameColor = if (row.unavailable)
        MaterialTheme.colorScheme.onSurfaceVariant
    else
        MaterialTheme.colorScheme.onSurface
    val previewText = if (row.unavailable) {
        "Unavailable — " + (row.lastMessageBody ?: "(no messages yet)")
    } else {
        row.lastMessageBody ?: "(no messages yet)"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (row.kind == ContactListViewModel.Row.Kind.Group)
                Icons.Default.Group else Icons.Default.Person,
            contentDescription = if (row.kind == ContactListViewModel.Row.Kind.Group)
                "Group" else "Contact",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(28.dp).padding(end = 12.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                row.displayLabel,
                style = MaterialTheme.typography.titleMedium,
                color = nameColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                previewText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (row.lastTimestamp != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                TimestampFormat.formatRelativeShort(row.lastTimestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
