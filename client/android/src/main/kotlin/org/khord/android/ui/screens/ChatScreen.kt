package org.khord.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavController
import org.khord.shared.protocol.orchestrator.MessageEntry
import org.khord.android.ui.viewmodel.ChatViewModel
import org.khord.android.util.TimestampFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(nav: NavController, contactFingerprint: String) {
    val vm: ChatViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ChatViewModel(contactFingerprint) as T
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val context = androidx.compose.ui.platform.LocalContext.current

    DisposableEffect(Unit) {
        vm.startPolling()
        // Tell the push service to suppress notification banners
        // for this contact while we're foregrounded. Also dismiss
        // any banner that's already sitting in the shade — covers
        // the "user opened the chat manually (not via the
        // notification tap)" path. The tap path is auto-cancelled
        // by the notification builder's setAutoCancel(true).
        org.khord.android.AppContainer.openChatFingerprint = contactFingerprint
        androidx.core.app.NotificationManagerCompat.from(context)
            .cancel(org.khord.android.push.KhordNotifications.notificationIdFor(contactFingerprint))
        onDispose {
            org.khord.android.AppContainer.openChatFingerprint = null
            vm.stopPolling()
        }
    }

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    // Whole header is tappable → fingerprint verification
                    // screen. Clickable on the Column so both rows
                    // (name + truncated fingerprint) act as one target.
                    Column(
                        modifier = Modifier.clickable {
                            nav.navigate(
                                org.khord.android.nav.Routes.fingerprint(contactFingerprint),
                            )
                        },
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                state.contactDisplayName ?: "Unknown contact",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            if (state.verified) {
                                Spacer(Modifier.width(6.dp))
                                Icon(
                                    Icons.Filled.Verified,
                                    contentDescription = "Verified",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                        Text(
                            "${contactFingerprint.take(8)}…${contactFingerprint.takeLast(8)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { padding ->
        // Edit-mode state lives at the screen level (not inside the
        // ViewModel) — purely a UI concern. When non-null we're
        // editing the message with this UUID; the input pre-fills
        // with the current body, a small banner above the input
        // shows "Editing message" with an X to cancel, and the send
        // button submits an edit instead of a new message.
        var editingUuid by remember { mutableStateOf<String?>(null) }
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(state.messages) { idx, msg ->
                    val prev = state.messages.getOrNull(idx - 1)
                    val showDateHeader = prev == null
                        || !TimestampFormat.sameDay(prev.timestamp, msg.timestamp)
                    if (showDateHeader) {
                        DateSeparator(msg.timestamp)
                    }
                    MessageRow(
                        msg = msg,
                        senderName = state.contactDisplayName,
                        onEdit = { uuid, body ->
                            editingUuid = uuid
                            draft = body
                        },
                    )
                }
            }
            var showReport by remember { mutableStateOf(false) }
            state.error?.let { msg ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Error: $msg",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    if (state.errorCause != null) {
                        androidx.compose.material3.TextButton(
                            onClick = { showReport = true },
                        ) { Text("Report") }
                    }
                }
            }
            if (showReport && state.errorCause != null) {
                BugReportDialog(
                    error = state.errorCause,
                    onDismiss = {
                        showReport = false
                        vm.clearError()
                    },
                )
            }
            val unavailable = state.contactStatus ==
                ChatViewModel.ContactStatus.Unavailable
            if (unavailable) {
                UnavailableBanner()
            }
            // Edit-mode banner — sits between the message list and
            // the composer. Cancel reverts to fresh-message mode
            // and clears the pre-filled draft.
            if (editingUuid != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Editing message",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    androidx.compose.material3.TextButton(onClick = {
                        editingUuid = null
                        draft = ""
                    }) { Text("Cancel") }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            if (unavailable) "Contact unavailable"
                            else if (editingUuid != null) "Edit message…"
                            else "Type a message"
                        )
                    },
                    singleLine = false,
                    enabled = !unavailable,
                    // Ask the keyboard not to learn from / autocomplete-
                    // suggest user input. Gboard / SwiftKey / Samsung /
                    // most mainstream IMEs honour the "nm" hint. End
                    // result: typed chat content doesn't seed the
                    // keyboard's personalisation model. Doesn't cover
                    // IMEs that ignore the hint — best-effort.
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        platformImeOptions = androidx.compose.ui.text.input.PlatformImeOptions("nm"),
                    ),
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = !state.sending && !unavailable && draft.isNotBlank(),
                    onClick = {
                        val uuid = editingUuid
                        if (uuid != null) {
                            vm.edit(uuid, draft)
                            editingUuid = null
                        } else {
                            vm.send(draft)
                        }
                        draft = ""
                    },
                ) {
                    Text(if (editingUuid != null) "Save" else "Send")
                }
            }
        }
    }
}

/**
 * Inline banner above the input row when the contact's relay endpoint
 * has stopped responding (404 or network errors). The chat history
 * stays visible — the user may still want to re-read old messages.
 */
@Composable
private fun UnavailableBanner() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            "This contact appears to be unavailable. " +
                "Their account may have been removed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun DateSeparator(iso: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            TimestampFormat.dateHeaderLabel(iso),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MessageRow(
    msg: MessageEntry,
    senderName: String?,
    onEdit: (uuid: String, body: String) -> Unit,
) {
    val isSent = msg.direction == MessageEntry.Direction.SENT
    val arrange = if (isSent) Arrangement.End else Arrangement.Start
    val align = if (isSent) Alignment.End else Alignment.Start
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = align) {
        // Sender label only above received messages — outgoing messages
        // implicitly belong to the user, no need to label them.
        if (!isSent && senderName != null) {
            Text(
                senderName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = arrange) {
            MessageBubble(
                body = msg.body,
                isSent = msg.direction == MessageEntry.Direction.SENT,
                messageUuid = msg.messageUuid,
                onEdit = onEdit,
            )
        }
        // Timestamp + optional (edited) tag on the same line. The
        // tag goes after the time, in the same muted style.
        val timestampLabel = TimestampFormat.formatMessageTime(msg.timestamp)
        val full = if (msg.edited) "$timestampLabel · (edited)" else timestampLabel
        Text(
            full,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        )
    }
}

// MessageBubble — shared between ChatScreen and GroupChatScreen, lives
// in MessageBubble.kt. URL linkify + long-press Copy + (alpha.14+) Edit
// are handled there. Pass `messageUuid` + `onEdit` so the bubble can
// expose Edit on sent messages that carry a UUID.
