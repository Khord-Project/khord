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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import org.khord.android.AppContainer
import org.khord.android.ui.theme.KhordThemeChoice
import org.khord.android.ui.theme.swatchColor
import org.khord.android.ui.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(nav: NavController, vm: SettingsViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showConfirm by remember { mutableStateOf(false) }

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

                ThemeSection()

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
                    // ViewModel kills the process in its finally block —
                    // user sees the app vanish, then cold-boots back to
                    // Welcome on the next icon tap. See SettingsViewModel.
                    vm.panic()
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

@Composable
private fun ThemeSection() {
    val context = LocalContext.current
    val current by AppContainer.themeChoice.collectAsStateWithLifecycle()

    Column {
        Text("Theme", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        for (choice in KhordThemeChoice.entries) {
            ThemeRow(
                choice = choice,
                selected = choice == current,
                onSelect = { AppContainer.setThemeChoice(context, choice) },
            )
        }
    }
}

@Composable
private fun ThemeRow(
    choice: KhordThemeChoice,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(Modifier.width(4.dp))
        // Color swatch — visual hint of what each theme looks like.
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(choice.swatchColor),
        )
        Spacer(Modifier.width(12.dp))
        Text(choice.displayName, style = MaterialTheme.typography.bodyLarge)
    }
}
