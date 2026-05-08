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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import org.khord.android.ServerUrls
import org.khord.android.nav.Routes
import org.khord.android.ui.viewmodel.ServerSetupViewModel

private enum class Mode { Community, Custom }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerSetupScreen(
    nav: NavController,
    vm: ServerSetupViewModel = viewModel(),
) {
    val status by vm.status.collectAsStateWithLifecycle()
    var mode by remember { mutableStateOf(Mode.Community) }
    var customKeyServer by remember { mutableStateOf("") }
    var customRelayServer by remember { mutableStateOf("") }

    LaunchedEffect(status) {
        if (status is ServerSetupViewModel.Status.Success) {
            nav.navigate(Routes.SEED_DISPLAY)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Server setup") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                "Khord doesn't run on a single backend. Pick which servers " +
                    "this device will use for keys and message relay. You can " +
                    "change this later by panic-wiping and re-onboarding.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(20.dp))

            CommunityChoice(
                selected = mode == Mode.Community,
                onSelect = {
                    mode = Mode.Community
                    vm.clearError()
                },
            )
            Spacer(Modifier.height(8.dp))
            CustomChoice(
                selected = mode == Mode.Custom,
                onSelect = {
                    mode = Mode.Custom
                    vm.clearError()
                },
                keyServerUrl = customKeyServer,
                onKeyServerChange = {
                    customKeyServer = it
                    vm.clearError()
                },
                relayServerUrl = customRelayServer,
                onRelayServerChange = {
                    customRelayServer = it
                    vm.clearError()
                },
            )

            Spacer(Modifier.height(24.dp))

            when (val s = status) {
                is ServerSetupViewModel.Status.Failed -> {
                    Text(
                        s.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                else -> Unit
            }

            Button(
                onClick = {
                    val (ks, rs) = when (mode) {
                        Mode.Community ->
                            ServerUrls.DEFAULT_KEY_SERVER to ServerUrls.DEFAULT_RELAY_SERVER
                        Mode.Custom -> customKeyServer to customRelayServer
                    }
                    vm.validateAndContinue(ks, rs)
                },
                enabled = status !is ServerSetupViewModel.Status.Validating &&
                    (mode == Mode.Community ||
                        (customKeyServer.isNotBlank() && customRelayServer.isNotBlank())),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (status is ServerSetupViewModel.Status.Validating) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.height(0.dp))
                        Text("  Checking servers…")
                    }
                } else {
                    Text("Continue")
                }
            }
        }
    }
}

@Composable
private fun CommunityChoice(selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(modifier = Modifier.padding(top = 12.dp)) {
            Text(
                "Use Khord Community Servers (recommended)",
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                ServerUrls.DEFAULT_KEY_SERVER,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                ServerUrls.DEFAULT_RELAY_SERVER,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CustomChoice(
    selected: Boolean,
    onSelect: () -> Unit,
    keyServerUrl: String,
    onKeyServerChange: (String) -> Unit,
    relayServerUrl: String,
    onRelayServerChange: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
            Text("Use custom servers", style = MaterialTheme.typography.bodyLarge)
            if (selected) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = keyServerUrl,
                    onValueChange = onKeyServerChange,
                    label = { Text("Key server URL") },
                    placeholder = { Text("https://your-key-server.example") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        keyboardType = KeyboardType.Uri,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = relayServerUrl,
                    onValueChange = onRelayServerChange,
                    label = { Text("Relay server URL") },
                    placeholder = { Text("https://your-relay-server.example") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        keyboardType = KeyboardType.Uri,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
