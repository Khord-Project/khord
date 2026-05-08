package org.khord.android.ui.screens

import android.Manifest
import android.content.Intent
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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import org.khord.android.AppContainer
import org.khord.android.nav.Routes
import org.khord.android.ui.qr.QrCodeImage
import org.khord.android.ui.qr.QrScannerView
import org.khord.android.ui.viewmodel.AddContactViewModel
import org.khord.android.util.ContactLink
import org.khord.android.util.SecureClipboard

private enum class Tab { MyQr, Scan }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddContactScreen(nav: NavController, vm: AddContactViewModel = viewModel()) {
    var tab by remember { mutableStateOf(Tab.MyQr) }
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        if (state.myQrPayload == null) vm.primeMyQr()
    }
    LaunchedEffect(state.newContactArrived) {
        if (state.newContactArrived) {
            nav.navigate(Routes.CONTACTS) { popUpTo(Routes.CONTACTS) { inclusive = true } }
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Add contact") }) }) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab.ordinal) {
                Tab(selected = tab == Tab.MyQr, onClick = { tab = Tab.MyQr },
                    text = { Text("My QR") })
                Tab(selected = tab == Tab.Scan, onClick = { tab = Tab.Scan },
                    text = { Text("Scan") })
            }
            when (tab) {
                Tab.MyQr -> MyQrPane(vm, state)
                Tab.Scan -> ScanPane(nav, vm, state)
            }
        }
    }
}

@Composable
private fun MyQrPane(vm: AddContactViewModel, state: AddContactViewModel.UiState) {
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
private fun ScanPane(
    nav: NavController,
    vm: AddContactViewModel,
    state: AddContactViewModel.UiState,
) {
    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)
    var firstMessage by remember { mutableStateOf("") }

    if (!cameraPermission.status.isGranted) {
        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Camera permission is required to scan a QR code.",
                style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))
            Button(onClick = { cameraPermission.launchPermissionRequest() }) {
                Text("Grant camera permission")
            }
        }
        return
    }

    val scanResult = state.scanResult
    when (scanResult) {
        is AddContactViewModel.ScanResult.Stored -> {
            Column(
                modifier = Modifier.fillMaxSize().padding(20.dp),
            ) {
                Text("Contact scanned",
                    style = MaterialTheme.typography.headlineSmall)
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
        null -> {
            ScanCaptureWithManualFallback(vm)
        }
    }
}

@Composable
private fun ScanCaptureWithManualFallback(vm: AddContactViewModel) {
    var manualOpen by remember { mutableStateOf(false) }
    var manualInput by remember { mutableStateOf("") }
    var parseError by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            QrScannerView(onScanned = { vm.onScanned(it) })
        }
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = if (manualOpen) "Hide manual entry ▴" else "Enter contact link manually ▾",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable {
                        manualOpen = !manualOpen
                        if (!manualOpen) parseError = null
                    }
                    .padding(vertical = 4.dp),
            )
            if (manualOpen) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = manualInput,
                    onValueChange = {
                        manualInput = it
                        parseError = null
                    },
                    label = { Text("khord://contact/… or pasted JSON") },
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
                        // Hand off to the same path as a scanned QR; downstream
                        // JSON-shape validation lives in onScanned().
                        vm.onScanned(json)
                    },
                    enabled = manualInput.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Use this contact link") }
            }
        }
    }
}
