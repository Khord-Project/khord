package org.khord.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import org.khord.android.ui.qr.QrCodeImage
import org.khord.android.ui.viewmodel.FingerprintViewModel

/**
 * Identity-key fingerprint comparison screen. Shows the contact's
 * 64-hex-char fingerprint and the user's own fingerprint, both
 * formatted as 4-char groups in 8-group rows (the Signal-style
 * "safety number" layout — easier to read aloud or compare visually
 * across a video call than a continuous hex string).
 *
 * Also displays a QR code of the contact's fingerprint so the user
 * can hold up their device for the contact to scan and compare,
 * which avoids the read-aloud failure modes (rounding 0/O, similar
 * looking 1/l, listener mis-hearing).
 *
 * "Mark as verified" sets a local-only flag on the contact row.
 * The flag is dropped automatically on any subsequent X3DH session
 * reset (see Messaging.applyX3dhInitialReset) — so if the contact
 * recovers their identity from seed phrase, rotates keys, or
 * someone successfully impersonates them, the user must re-verify
 * before the badge reappears.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FingerprintScreen(nav: NavController, contactFingerprint: String) {
    val vm: FingerprintViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                FingerprintViewModel(contactFingerprint) as T
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showRename by remember { mutableStateOf(false) }
    var showBlockConfirm by remember { mutableStateOf(false) }

    // Surface a brief snackbar confirmation when verified flips.
    LaunchedEffect(state.justUpdated) {
        state.justUpdated?.let { confirmation ->
            scope.launch { snackbarHostState.showSnackbar(confirmation) }
            vm.clearJustUpdated()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                title = {
                    val label = state.contactDisplayName?.takeIf { it.isNotEmpty() }
                        ?: "Unknown contact"
                    Text("Verify $label", style = MaterialTheme.typography.titleMedium)
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Compare this fingerprint with your contact's device " +
                    "to verify their identity.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(4.dp))

            Text(
                state.contactDisplayName?.takeIf { it.isNotEmpty() }
                    ?: "Their fingerprint",
                style = MaterialTheme.typography.titleSmall,
            )
            FingerprintBlock(contactFingerprint)

            // QR encodes ONLY the 64-char hex fingerprint, NOT the
            // full QrPayload — that's the contact-add QR. This is a
            // verification-only QR; the receiving side compares the
            // scanned hex against its known fingerprint for this
            // contact.
            Spacer(Modifier.height(4.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                QrCodeImage(
                    text = contactFingerprint,
                    sizePx = 360,
                    modifier = Modifier.size(220.dp),
                )
            }

            Spacer(Modifier.height(8.dp))

            Text("Your fingerprint", style = MaterialTheme.typography.titleSmall)
            FingerprintBlock(state.myFingerprint.orEmpty())

            Spacer(Modifier.height(8.dp))

            // Toggle button — primary color for Mark, outlined neutral
            // for Unmark. Disabled while the contact lookup is still
            // resolving (myFingerprint not yet set).
            val canAct = state.myFingerprint != null && state.contactDisplayName != null
            if (state.verified) {
                OutlinedButton(
                    onClick = { vm.setVerified(false) },
                    enabled = canAct,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Remove verification") }
            } else {
                Button(
                    onClick = { vm.setVerified(true) },
                    enabled = canAct,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                    ),
                ) { Text("Mark as verified") }
            }

            OutlinedButton(
                onClick = { showRename = true },
                enabled = canAct,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Rename contact") }

            // Mute toggle — no confirmation, it's reversible + harmless.
            OutlinedButton(
                onClick = { vm.setMuted(!state.muted) },
                enabled = canAct,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (state.muted) "Unmute contact" else "Mute contact") }

            // Block toggle — blocking asks for confirmation; unblocking
            // is immediate. Error tint to signal the heavier action.
            if (state.blocked) {
                OutlinedButton(
                    onClick = { vm.setBlocked(false) },
                    enabled = canAct,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Unblock contact") }
            } else {
                OutlinedButton(
                    onClick = { showBlockConfirm = true },
                    enabled = canAct,
                    modifier = Modifier.fillMaxWidth(),
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Block contact") }
            }
        }
    }

    if (showBlockConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showBlockConfirm = false },
            title = { Text("Block ${state.contactDisplayName.orEmpty()}?") },
            text = {
                Text(
                    "You won't receive their messages. They won't be notified.",
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showBlockConfirm = false
                    vm.setBlocked(true)
                    // Leave the screen — a blocked contact is hidden
                    // from the list and their chat is no longer a place
                    // the user should be sitting.
                    nav.popBackStack()
                }) {
                    Text("Block", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showBlockConfirm = false
                }) { Text("Cancel") }
            },
        )
    }

    if (showRename) {
        RenameContactDialog(
            currentName = state.contactDisplayName.orEmpty(),
            onDismiss = { showRename = false },
            onConfirm = { newName ->
                showRename = false
                vm.rename(newName)
            },
        )
    }
}

@Composable
private fun RenameContactDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(currentName) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename contact") },
        text = {
            androidx.compose.foundation.layout.Column {
                androidx.compose.material3.OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text("Display name") },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Clear the field to reset to the contact's own name. " +
                        "This nickname is local to your device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = { onConfirm(text) },
            ) { Text("Save") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * Render a 64-hex-char fingerprint as 4-char groups, 8 groups per
 * row. Monospace font so columns align across the device when the
 * two users compare side-by-side.
 */
@Composable
private fun FingerprintBlock(fingerprint: String) {
    if (fingerprint.isEmpty()) {
        Text(
            "(loading)",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
        )
        return
    }
    // Group into chunks of 4 chars, then take 8 groups per row.
    // Pad the final row's groups separator if it has fewer than 8
    // groups — keeps shorter fingerprints aligned (not expected for
    // 32-byte sha256 but defensive).
    val groups = fingerprint.chunked(4)
    val rows = groups.chunked(8)
    for (row in rows) {
        Text(
            text = row.joinToString(" "),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
