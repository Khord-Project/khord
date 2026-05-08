package org.khord.android.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import org.khord.android.ui.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(nav: NavController, vm: SettingsViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showConfirm by remember { mutableStateOf(false) }
    // Walk the ContextWrapper chain rather than `as? Activity` — Compose's
    // LocalContext is sometimes wrapped (theme, configuration), and the
    // single-cast version silently resolves to null, which made
    // activity.recreate() a no-op and stranded the user on the Wiping…
    // spinner forever.
    val activity = LocalContext.current.findActivity()

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )
    }) { padding ->
        if (state.wiping) {
            // Immediate, unambiguous feedback. Replaces the screen body so
            // the user can't tap Panic again or back out mid-wipe.
            Column(
                modifier = Modifier.padding(padding).fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(
                    "Wiping…",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Clearing identity, contacts, messages, and the encryption key.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Column(
                modifier = Modifier.padding(padding).fillMaxSize().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Identity", style = MaterialTheme.typography.titleMedium)
                Text(
                    state.fingerprint ?: "(loading)",
                    style = MaterialTheme.typography.bodySmall,
                )

                Spacer(Modifier.height(16.dp))

                Text(
                    "Panic — wipes everything on this device: identity, contacts, " +
                        "messages, ratchet state. Cannot be undone. Your seed phrase " +
                        "is the only way to recover afterwards.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    onClick = { showConfirm = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Panic") }
            }
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            confirmButton = {
                TextButton(onClick = {
                    showConfirm = false
                    // Callback fires from Main once cleanup completes (or
                    // throws — see SettingsViewModel.panic kdoc). Activity
                    // is non-null here because findActivity() unwraps any
                    // ContextWrapper before returning.
                    vm.panic { activity?.recreate() }
                }) { Text("Wipe everything", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("Cancel") }
            },
            title = { Text("Are you sure?") },
            text = {
                Text(
                    "This deletes the database, the encryption key in the " +
                        "Android Keystore, and every contact you've added. " +
                        "There is no undo."
                )
            },
        )
    }
}

/**
 * Walk the [ContextWrapper] chain to find the hosting [Activity].
 *
 * `LocalContext.current` in a Compose tree is sometimes a theme- or
 * configuration-wrapped Context, not the Activity itself. A plain
 * `as? Activity` cast on that wrapper silently yields null, so any code
 * that depends on the Activity reference (`recreate()`, `startActivity`,
 * window flags) becomes a no-op. This extension peels wrappers until it
 * finds the Activity, returning null only if the Context truly isn't
 * hosted in one (which shouldn't happen in normal Compose usage).
 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
