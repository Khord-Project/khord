package org.khord.android.ui.screens

import android.Manifest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import org.khord.android.nav.Routes
import org.khord.android.ui.qr.QrCodeImage
import org.khord.android.ui.qr.QrScannerView
import org.khord.android.ui.viewmodel.AddContactViewModel

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

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
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
            state.myQrPayload?.let {
                Text("FP: ${it.fingerprint.take(16)}…",
                    style = MaterialTheme.typography.bodySmall,
                    overflow = TextOverflow.Ellipsis)
            }
        } else {
            Text("Preparing QR…")
        }
    }
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
                Text("FP: ${scanResult.qr.fingerprint.take(16)}…",
                    style = MaterialTheme.typography.bodySmall)
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
            QrScannerView(onScanned = { vm.onScanned(it) })
        }
    }
}
