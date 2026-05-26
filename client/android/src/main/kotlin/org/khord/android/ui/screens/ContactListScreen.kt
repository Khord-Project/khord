package org.khord.android.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Verified
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
import androidx.compose.material3.SmallFloatingActionButton
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
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.khord.android.AppContainer
import org.khord.android.nav.Routes
import org.khord.android.ui.viewmodel.ContactListViewModel
import org.khord.android.util.BugReporter
import org.khord.android.util.CrashReportStore
import org.khord.android.util.TimestampFormat
import org.khord.android.util.UpdateInfo

private const val RECENT_CHATS_LIMIT = 5
private const val POLL_INTERVAL_MS = 10_000L

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalPermissionsApi::class,
)
@Composable
fun ContactListScreen(nav: NavController, vm: ContactListViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showAll by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // ContactList is the terminal post-onboarding destination — all
    // navigation paths into it use popUpTo(graph.startDestinationId,
    // inclusive=true), so the back stack should be just [CONTACTS]
    // and a back press should exit the app. In practice some Compose
    // Navigation versions / OEM back-stack implementations (Xiaomi,
    // Motorola) revive a stale destination instead of finishing the
    // Activity — which surfaces as "Registration state lost." or a
    // bogus state-loss dialog even though the push service is happily
    // serving the live identity. Intercept here and call finish()
    // explicitly so the back press is unambiguous.
    //
    // Scoped to the CONTACTS composable: leaving the screen
    // (Settings, Chat, AddContact, etc.) unregisters the handler and
    // the standard popBackStack behaviour resumes for the destinations
    // pushed on top.
    BackHandler(enabled = true) {
        org.khord.shared.diagnostic.DiagnosticLog.log(
            "Khord",
            "ContactList: back pressed, finishing Activity",
        )
        (context as? android.app.Activity)?.finish()
    }

    // Recovered crash report path: if KhordApp's uncaught-exception
    // handler saved a Report during the previous session, surface
    // it via BugReportDialog so the user can choose to submit. Cleared
    // from SharedPreferences on dismiss either way so the dialog
    // doesn't reappear on every screen mount.
    var pendingCrash by remember {
        mutableStateOf<BugReporter.Report?>(CrashReportStore.load(context))
    }
    pendingCrash?.let { report ->
        BugReportDialog(
            error = null,
            preBuiltReport = report,
            onDismiss = {
                CrashReportStore.clear(context)
                pendingCrash = null
            },
        )
    }

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
            // Two stacked FABs: the smaller "New group" sits above the
            // primary "Add contact". Both are always visible — the
            // earlier long-press-on-FAB approach didn't fire because
            // FloatingActionButton's own onClick intercepts touches
            // before any parent gesture detector sees them.
            Column(horizontalAlignment = Alignment.End) {
                SmallFloatingActionButton(
                    onClick = { nav.navigate(Routes.CREATE_GROUP) },
                ) {
                    Icon(Icons.Default.GroupAdd, contentDescription = "New group")
                }
                Spacer(Modifier.height(12.dp))
                FloatingActionButton(onClick = { nav.navigate(Routes.ADD_CONTACT) }) {
                    Icon(Icons.Default.Add, contentDescription = "Add contact")
                }
            }
        },
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            // In-app update banner — appears once UpdateChecker (fired
            // from SplashViewModel after bootstrap) finds a newer
            // GitHub release. Stays visible until the user taps
            // "Dismiss" or restarts the app.
            val update by AppContainer.availableUpdate.collectAsStateWithLifecycle()
            update?.let { UpdateBanner(it) }

            // Pending-contacts banner — appears whenever someone has
            // sent us an X3DH initial that we haven't accepted yet.
            // Tap routes to PendingContactsScreen for Accept / Decline.
            // See ROADMAP "Contact acceptance gate".
            if (state.pendingContactCount > 0) {
                PendingContactsBanner(
                    count = state.pendingContactCount,
                    onClick = { nav.navigate(Routes.PENDING_CONTACTS) },
                )
            }

            Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                if (state.rows.isEmpty()) {
                    EmptyState(error = state.error)
                } else {
                    var pendingDelete by remember {
                        mutableStateOf<ContactListViewModel.Row?>(null)
                    }
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
                        onRowLongClick = { row -> pendingDelete = row },
                    )
                    pendingDelete?.let { row ->
                        DeleteConversationDialog(
                            row = row,
                            onConfirm = {
                                when (row.kind) {
                                    ContactListViewModel.Row.Kind.Contact ->
                                        vm.deleteContact(row.id)
                                    ContactListViewModel.Row.Kind.Group ->
                                        vm.leaveGroup(row.id)
                                }
                                pendingDelete = null
                            },
                            onDismiss = { pendingDelete = null },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Subtle "Khord <version> available" banner. Non-blocking — sits at the
 * top of the contact list, doesn't intercept any other gesture, and
 * has both an explicit Update action (opens the release page in the
 * system browser) and a Dismiss that clears the state for the session.
 */
@Composable
private fun UpdateBanner(info: UpdateInfo) {
    val uriHandler = LocalUriHandler.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Khord ${info.version} available",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        OutlinedButton(
            onClick = { AppContainer.dismissAvailableUpdate() },
        ) { Text("Dismiss") }
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = { uriHandler.openUri(info.htmlUrl) },
        ) { Text("Update") }
    }
}

/**
 * Subtle banner that surfaces above the chat list when pending
 * contacts exist. Tap routes to PendingContactsScreen for the
 * accept/decline flow. See ROADMAP "Contact acceptance gate".
 */
@Composable
private fun PendingContactsBanner(count: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (count == 1) "1 new contact request"
                   else "$count new contact requests",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "Review →",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
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
    onRowLongClick: (ContactListViewModel.Row) -> Unit,
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
                ConversationRow(
                    row = row,
                    onClick = { onRowClick(row) },
                    onLongClick = { onRowLongClick(row) },
                )
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

/**
 * Confirmation dialog raised by a long-press on a conversation row.
 * The copy and the destructive action both branch on [Row.Kind]:
 *
 *   - Contact rows offer "Delete conversation with <name>?", warning
 *     the user that the other person keeps their copy. On confirm we
 *     call [ContactListViewModel.deleteContact].
 *   - Group rows offer "Leave and delete group?", which fans out a
 *     group_member_left payload to remaining members and then drops
 *     the local group + members + messages. On confirm we call
 *     [ContactListViewModel.leaveGroup].
 */
@Composable
private fun DeleteConversationDialog(
    row: ContactListViewModel.Row,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val (title, body) = when (row.kind) {
        ContactListViewModel.Row.Kind.Contact ->
            "Delete conversation with ${row.displayLabel}?" to
                ("This will remove the contact and all messages from " +
                    "your device. The other person will still have " +
                    "their copy.")
        ContactListViewModel.Row.Kind.Group ->
            "Leave and delete group?" to
                ("This will notify the other members that you've left, " +
                    "then remove the group and all its messages from " +
                    "your device. Other members keep their copy of " +
                    "the conversation.")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    if (row.kind == ContactListViewModel.Row.Kind.Group) "Leave"
                    else "Delete",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        title = { Text(title) },
        text = { Text(body) },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    row: ContactListViewModel.Row,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
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
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.displayLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = nameColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (row.verified) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Filled.Verified,
                        contentDescription = "Verified",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                }
                if (row.muted) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Filled.NotificationsOff,
                        contentDescription = "Muted",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
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
