package org.khord.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import org.khord.android.ui.viewmodel.PendingContactsViewModel

/**
 * Surface for contacts whose first X3DH initial arrived without prior
 * approval — the user accepts or declines each. See ROADMAP "Contact
 * acceptance gate" + Messaging.pendingContacts / acceptContact.
 *
 * Layout: top-bar with back arrow, list of rows. Each row shows the
 * sender's display name (or short fingerprint if no name yet), the
 * first message they sent as preview, and two buttons: Accept (filled,
 * primary colour) and Decline (outlined, error tint).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PendingContactsScreen(
    nav: NavController,
    vm: PendingContactsViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Contact requests") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier.padding(innerPadding).fillMaxSize(),
        ) {
            if (state.rows.isEmpty() && !state.loading) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "No pending requests.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "When someone scans your QR code and sends you a " +
                            "message, you'll see them here so you can accept " +
                            "or decline before they appear in your chat list.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.rows, key = { it.fingerprint }) { row ->
                        PendingContactRow(
                            row = row,
                            onAccept = {
                                // After acceptance, drop the user
                                // straight into the chat — they
                                // just decided they want to talk
                                // to this person. popUpTo this
                                // screen with inclusive so back
                                // from chat returns to the contact
                                // list, not to a now-empty (or
                                // smaller) pending list.
                                vm.accept(row.fingerprint) {
                                    nav.navigate(
                                        org.khord.android.nav.Routes.chat(row.fingerprint),
                                    ) {
                                        popUpTo(
                                            org.khord.android.nav.Routes.PENDING_CONTACTS,
                                        ) { inclusive = true }
                                        launchSingleTop = true
                                    }
                                }
                            },
                            onDecline = { vm.decline(row.fingerprint) },
                        )
                        HorizontalDivider()
                    }
                }
            }
            state.error?.let { err ->
                Text(
                    "Error: $err",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun PendingContactRow(
    row: PendingContactsViewModel.Row,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text(
            row.displayLabel,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            row.firstMessagePreview ?: "(no message yet)",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = onDecline,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) { Text("Decline") }
            Spacer(Modifier.width(12.dp))
            Button(
                onClick = onAccept,
                modifier = Modifier.weight(1f),
            ) { Text("Accept") }
        }
    }
}
