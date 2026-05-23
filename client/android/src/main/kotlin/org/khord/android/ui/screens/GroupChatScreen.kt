package org.khord.android.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import org.khord.android.nav.Routes
import org.khord.android.ui.theme.LocalKhordChatColors
import org.khord.android.ui.viewmodel.GroupChatViewModel
import org.khord.android.util.TimestampFormat
import org.khord.shared.protocol.orchestrator.GroupMessageEntry
import org.khord.shared.protocol.orchestrator.MessageEntry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupChatScreen(nav: NavController, groupId: String) {
    val vm: GroupChatViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                GroupChatViewModel(groupId) as T
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    DisposableEffect(Unit) {
        vm.startPolling()
        onDispose { vm.stopPolling() }
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
                    Column {
                        Text(
                            state.group?.groupName ?: "Group",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "${state.memberCount} members",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { nav.navigate(Routes.groupInfo(groupId)) }) {
                        Icon(Icons.Default.Info, contentDescription = "Group info")
                    }
                },
            )
        },
    ) { padding ->
        var editingUuid by remember { mutableStateOf<String?>(null) }
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(state.messages) { idx, msg ->
                    val prev = state.messages.getOrNull(idx - 1)
                    val showDateHeader = prev == null ||
                        !TimestampFormat.sameDay(prev.timestamp, msg.timestamp)
                    if (showDateHeader) {
                        GroupDateSeparator(msg.timestamp)
                    }
                    GroupMessageRow(
                        msg = msg,
                        onEdit = { uuid, body ->
                            editingUuid = uuid
                            draft = body
                        },
                    )
                }
            }
            state.error?.let {
                Text("Error: $it",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp))
            }
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
                        Text(if (editingUuid != null) "Edit message…" else "Type a message")
                    },
                    singleLine = false,
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = !state.sending && draft.isNotBlank(),
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

@Composable
private fun GroupDateSeparator(iso: String) {
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupMessageRow(
    msg: GroupMessageEntry,
    onEdit: (uuid: String, body: String) -> Unit,
) {
    val isSent = msg.direction == MessageEntry.Direction.SENT
    val align = if (isSent) Alignment.End else Alignment.Start
    val arrange = if (isSent) Arrangement.End else Arrangement.Start
    val chat = LocalKhordChatColors.current
    val bg = if (isSent) chat.sentBubble else chat.receivedBubble
    val fg = if (isSent) chat.sentText else chat.receivedText
    val context = androidx.compose.ui.platform.LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    val canEdit = isSent && msg.messageUuid != null
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = align) {
        // Group chats always show the sender label above received bubbles
        // (the user sees N senders so attribution is mandatory). Sent
        // messages keep the implicit "from you" convention.
        if (!isSent) {
            val label = msg.senderDisplayName.ifEmpty {
                msg.senderFingerprint.take(8) + "…" + msg.senderFingerprint.takeLast(8)
            }
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = arrange) {
            Box(
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(bg)
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { menuOpen = true },
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(msg.body, color = fg, style = MaterialTheme.typography.bodyMedium)
                androidx.compose.material3.DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("Copy") },
                        onClick = {
                            val cm = context.getSystemService(
                                android.content.Context.CLIPBOARD_SERVICE,
                            ) as android.content.ClipboardManager
                            cm.setPrimaryClip(
                                android.content.ClipData.newPlainText("Khord message", msg.body),
                            )
                            menuOpen = false
                            if (android.os.Build.VERSION.SDK_INT
                                < android.os.Build.VERSION_CODES.TIRAMISU) {
                                android.widget.Toast.makeText(
                                    context, "Copied", android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                    )
                    if (canEdit) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("Edit") },
                            onClick = {
                                menuOpen = false
                                onEdit(msg.messageUuid!!, msg.body)
                            },
                        )
                    }
                }
            }
        }
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
