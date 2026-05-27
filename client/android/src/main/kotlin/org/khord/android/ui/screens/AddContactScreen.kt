package org.khord.android.ui.screens

import android.Manifest
import android.content.Intent
import android.widget.Toast
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionState
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import org.khord.android.AppContainer
import org.khord.android.nav.Routes
import org.khord.android.secret.OneTimeSecretLink
import org.khord.android.ui.qr.QrCodeImage
import org.khord.android.ui.qr.QrScannerView
import org.khord.android.ui.viewmodel.AddContactViewModel
import org.khord.android.util.ContactLink
import org.khord.android.util.SecureClipboard
import kotlinx.coroutines.launch

private enum class Tab { Share, Add }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddContactScreen(nav: NavController, vm: AddContactViewModel = viewModel()) {
    var tab by remember { mutableStateOf(Tab.Share) }
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        if (state.myQrPayload == null) vm.primeMyQr()
    }
    LaunchedEffect(state.newContactArrived) {
        if (state.newContactArrived) {
            nav.navigate(Routes.CONTACTS) { popUpTo(Routes.CONTACTS) { inclusive = true } }
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Add contact") },
            navigationIcon = {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )
    }) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab.ordinal) {
                Tab(selected = tab == Tab.Share, onClick = { tab = Tab.Share },
                    text = { Text("Share") })
                Tab(selected = tab == Tab.Add, onClick = { tab = Tab.Add },
                    text = { Text("Add") })
            }
            when (tab) {
                Tab.Share -> SharePane(vm, state)
                Tab.Add -> AddPane(nav, vm, state)
            }
        }
    }
}

@Composable
private fun SharePane(vm: AddContactViewModel, state: AddContactViewModel.UiState) {
    LaunchedEffect(Unit) { vm.pollWhileShowingMyQr() }
    val context = LocalContext.current

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Show this QR to the person you're adding. They scan it with " +
                "their Khord app.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))
        if (state.myQrPayloadJson != null) {
            QrCodeImage(
                text = state.myQrPayloadJson,
                sizePx = 800,
                modifier = Modifier.size(280.dp),
            )
            Spacer(Modifier.height(12.dp))
            state.myQrPayload?.let { payload ->
                FingerprintRow(fingerprint = payload.fingerprint)
            }
            Spacer(Modifier.height(16.dp))
            val link = remember(state.myQrPayloadJson) {
                ContactLink.encode(state.myQrPayloadJson)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        SecureClipboard.copy(
                            context = context,
                            label = "Khord contact link",
                            text = link,
                            scope = AppContainer.applicationScope,
                        )
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Copy contact link") }
                OutlinedButton(
                    onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, link)
                            putExtra(Intent.EXTRA_TITLE, "Khord contact link")
                        }
                        context.startActivity(
                            Intent.createChooser(send, "Share Khord contact link"),
                        )
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Share") }
            }

            Spacer(Modifier.height(12.dp))

            // One-time secret link (#23) — an OPTIONAL alternative to
            // the plain contact link above. Encrypts the link
            // client-side and uploads only the ciphertext; the key
            // rides in the URL fragment so it self-destructs after a
            // single view on khord.org.
            val scope = rememberCoroutineScope()
            var creatingLink by remember { mutableStateOf(false) }
            OutlinedButton(
                onClick = {
                    val http = AppContainer.http
                    if (http == null) {
                        Toast.makeText(
                            context,
                            "Couldn't create link. Try again or share your regular link.",
                            Toast.LENGTH_LONG,
                        ).show()
                        return@OutlinedButton
                    }
                    creatingLink = true
                    scope.launch {
                        val result = OneTimeSecretLink.create(http, link)
                        creatingLink = false
                        result.onSuccess { url ->
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, url)
                                putExtra(Intent.EXTRA_TITLE, "Khord one-time link")
                            }
                            context.startActivity(
                                Intent.createChooser(send, "Share one-time link"),
                            )
                        }.onFailure {
                            Toast.makeText(
                                context,
                                "Couldn't create link. Try again or share your regular link.",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                },
                enabled = !creatingLink,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (creatingLink) "Creating secure link…" else "Create one-time link")
            }
            Text(
                "Creates a link that can only be opened once, then self-destructs.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))
            Text(
                "Your contact link contains only public information. It's safe " +
                    "to share, but verify the sender's identity through a trusted " +
                    "channel if you receive one remotely.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text("Preparing QR…")
        }
    }
}

@Composable
private fun FingerprintRow(fingerprint: String) {
    var expanded by remember { mutableStateOf(false) }
    val display = if (expanded || fingerprint.length <= 18) {
        fingerprint
    } else {
        "${fingerprint.take(8)}…${fingerprint.takeLast(8)}"
    }
    Text(
        text = "FP: $display",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier
            .clickable { expanded = !expanded }
            .padding(vertical = 4.dp),
    )
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun AddPane(
    nav: NavController,
    vm: AddContactViewModel,
    state: AddContactViewModel.UiState,
) {
    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)
    var firstMessage by remember { mutableStateOf("") }

    val scanResult = state.scanResult
    when (scanResult) {
        is AddContactViewModel.ScanResult.Stored -> {
            Column(
                modifier = Modifier.fillMaxSize().padding(20.dp),
            ) {
                Text("Contact added", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                FingerprintRow(fingerprint = scanResult.qr.fingerprint)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = firstMessage,
                    onValueChange = { firstMessage = it },
                    label = { Text("First message") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    enabled = !state.sending && firstMessage.isNotBlank(),
                    onClick = {
                        vm.sendFirstMessage(scanResult.qr.fingerprint, firstMessage) {
                            nav.navigate(Routes.chat(scanResult.qr.fingerprint)) {
                                popUpTo(Routes.CONTACTS)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (state.sending) "Sending…" else "Send first message") }
                state.error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text("Error: $it",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        is AddContactViewModel.ScanResult.InvalidQr -> {
            Box(modifier = Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
                Text("Invalid QR: ${scanResult.message}",
                    color = MaterialTheme.colorScheme.error)
            }
        }
        null -> ManualEntryAndScanner(vm, cameraPermission)
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun ManualEntryAndScanner(
    vm: AddContactViewModel,
    cameraPermission: PermissionState,
) {
    var manualInput by remember { mutableStateOf("") }
    var parseError by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        // ── Manual entry section ────────────────────────────────────────────
        // Always visible. Users who paste a contact link from Signal/email/etc.
        // never trigger a camera permission dialog this way — privacy-sensitive
        // users get a friction-free first-contact path.
        Text("Add by contact link", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = manualInput,
            onValueChange = {
                manualInput = it
                parseError = null
            },
            label = { Text("Paste khord://contact/…") },
            singleLine = false,
            modifier = Modifier.fillMaxWidth(),
        )
        if (parseError != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                parseError!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                val json = try {
                    ContactLink.toJson(manualInput)
                } catch (e: IllegalArgumentException) {
                    parseError = "Invalid contact data."
                    return@Button
                }
                vm.onScanned(json)
            },
            enabled = manualInput.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Add contact") }
        Spacer(Modifier.height(8.dp))
        Text(
            "Paste a contact link your friend sent you " +
                "(Signal, email, AirDrop-equivalent, etc.).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(24.dp))
        DividerWithText("or")
        Spacer(Modifier.height(24.dp))

        // ── Scanner section ─────────────────────────────────────────────────
        Text("Scan a QR code", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        if (cameraPermission.status.isGranted) {
            QrScannerView(
                onScanned = { vm.onScanned(it) },
                modifier = Modifier.fillMaxWidth().height(320.dp),
            )
        } else {
            Button(
                onClick = { cameraPermission.launchPermissionRequest() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Scan QR Code") }
            Spacer(Modifier.height(4.dp))
            Text(
                "Camera permission is requested only when you tap.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DividerWithText(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f))
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
}
