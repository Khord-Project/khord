package org.khord.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import org.khord.android.BuildConfig
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
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Identity", style = MaterialTheme.typography.titleMedium)
                Text(
                    state.fingerprint ?: "(loading)",
                    style = MaterialTheme.typography.bodySmall,
                )

                Spacer(Modifier.height(16.dp))

                Text("Servers", style = MaterialTheme.typography.titleMedium)
                // Combined row: status dot + label/URL stacked +
                // status text. One section instead of two parallel
                // sections (URL list, then status list) — keeps the
                // two pieces of information about each server next
                // to each other.
                ServerHealthRow("Key server", state.keyServerUrl, state.keyServerHealth)
                ServerHealthRow("Relay server", state.relayServerUrl, state.relayServerHealth)

                Spacer(Modifier.height(16.dp))

                XiaomiBatteryWarningCard()

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

                Spacer(Modifier.height(24.dp))

                AppVersionFooter()
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

/**
 * Footer row with `App version: <versionName>`. For non-prod-release
 * builds also shows the flavor + build type so testers can confirm
 * which APK they're running at a glance (helpful for diagnosing
 * "wait, am I on dev or prod?" confusion).
 *
 * Small, muted, non-interactive — purely informational. Placed at the
 * end of the Settings column.
 */
/**
 * Coloured-dot row showing one server's health + its URL. Layout:
 * small filled circle (green/red/muted) + a two-line block
 * (label on top, URL below in muted style) + status text aligned
 * right. One row replaces the previous parallel "Key server: <url>"
 * + "● Key server  Operational" pair — same information, half the
 * vertical space. Snapshot-only health — the SettingsViewModel
 * fires the probe once on screen open; back-out + re-enter to
 * refresh.
 */
@Composable
private fun ServerHealthRow(
    label: String,
    url: String?,
    health: org.khord.android.ui.viewmodel.SettingsViewModel.ServerHealth,
) {
    val (dotColor, statusText) = when (health) {
        org.khord.android.ui.viewmodel.SettingsViewModel.ServerHealth.Checking ->
            MaterialTheme.colorScheme.onSurfaceVariant to "Checking…"
        org.khord.android.ui.viewmodel.SettingsViewModel.ServerHealth.Operational ->
            androidx.compose.ui.graphics.Color(0xFF2ECC40) to "Operational"
        org.khord.android.ui.viewmodel.SettingsViewModel.ServerHealth.Unreachable ->
            MaterialTheme.colorScheme.error to "Unreachable"
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                url ?: "(loading)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            statusText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AppVersionFooter() {
    val versionName = BuildConfig.VERSION_NAME
    val isProdRelease = BuildConfig.BUILD_TYPE == "release" && !BuildConfig.IS_DEV_FLAVOR
    val label = if (isProdRelease) {
        "App version: $versionName"
    } else {
        val flavor = if (BuildConfig.IS_DEV_FLAVOR) "dev" else "prod"
        "App version: $versionName ($flavor / ${BuildConfig.BUILD_TYPE})"
    }
    Text(
        label,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Xiaomi (MIUI) ships aggressive battery management — by default it
 * suspends background apps and may even clear their data on a system
 * update. Both flags hit Khord hard: the push service dies and the
 * SQLCipher passphrase vanishes from the Keystore-backed prefs blob,
 * resulting in "lost identity" reports from MIUI testers. Detect those
 * devices and surface the workaround inline.
 */
@Composable
private fun XiaomiBatteryWarningCard() {
    val manufacturer = android.os.Build.MANUFACTURER.lowercase()
    val isMiui = manufacturer.contains("xiaomi") ||
        manufacturer.contains("redmi") ||
        manufacturer.contains("poco")
    if (!isMiui) return
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Xiaomi device detected",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Xiaomi devices may kill Khord in the background or clear " +
                    "its data. To prevent this: go to Settings → Apps → " +
                    "Khord → Battery saver → No restrictions, and enable " +
                    "Autostart under Settings → Apps → Manage apps → Khord.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
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
