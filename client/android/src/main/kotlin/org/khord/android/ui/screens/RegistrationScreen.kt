package org.khord.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import org.khord.android.AppContainer
import org.khord.android.nav.Routes
import org.khord.android.push.PushServiceController
import org.khord.android.ui.viewmodel.OnboardingViewModel

@Composable
fun RegistrationScreen(nav: NavController) {
    val vm = AppContainer.onboardingViewModel
    val context = LocalContext.current

    // Re-entry path after a partial-registration crash: vm is null but
    // AppContainer.messaging is non-null with needsServerRegistration=true.
    val recoveryMessaging = AppContainer.messaging?.takeIf {
        vm == null && it.needsServerRegistration
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (vm != null) {
            FreshOnboardingBody(vm = vm, nav = nav)
        } else if (recoveryMessaging != null) {
            // We were here mid-registration last time; finish it.
            LaunchedEffect(Unit) {
                runCatching { recoveryMessaging.register(opkBatchSize = 50) }
                if (!recoveryMessaging.needsServerRegistration) {
                    PushServiceController.start(context.applicationContext)
                    nav.navigate(Routes.CONTACTS) {
                        popUpTo(Routes.SPLASH) { inclusive = true }
                    }
                }
            }
            Text("Recovering registration…", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))
            CircularProgressIndicator()
        } else {
            Text("Registration state lost.")
            Spacer(Modifier.height(16.dp))
            Button(onClick = { nav.navigate(Routes.WELCOME) }) { Text("Start over") }
        }
    }
}

@Composable
private fun FreshOnboardingBody(vm: OnboardingViewModel, nav: NavController) {
    val status by vm.status.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var displayNameInput by remember { mutableStateOf("") }

    LaunchedEffect(status) {
        if (status is OnboardingViewModel.Status.Done) {
            AppContainer.onboardingViewModel = null
            PushServiceController.start(context.applicationContext)
            nav.navigate(Routes.CONTACTS) {
                popUpTo(Routes.WELCOME) { inclusive = true }
            }
        }
    }

    when (val s = status) {
        is OnboardingViewModel.Status.Idle,
        is OnboardingViewModel.Status.Display -> {
            // Pre-register prompt — let the user pick a display name.
            Text("Almost done", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                "What should contacts call you?",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Optional. If you skip this, your contacts will see " +
                    "\"Anonymous\" until you set a name later.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = displayNameInput,
                onValueChange = { displayNameInput = it.take(64) },
                label = { Text("Display name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    vm.setDisplayName(displayNameInput)
                    vm.register()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Continue") }
        }
        is OnboardingViewModel.Status.Generating -> {
            Text("Generating phrase…")
        }
        is OnboardingViewModel.Status.Registering -> {
            Text("Deriving keys + uploading bundle…",
                style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))
            CircularProgressIndicator()
        }
        is OnboardingViewModel.Status.Done -> Text("Done")
        is OnboardingViewModel.Status.Failed -> {
            var showReport by remember { mutableStateOf(false) }
            Text("Registration failed: ${s.message}",
                color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(16.dp))
            Button(onClick = { vm.register() }, modifier = Modifier.fillMaxWidth()) {
                Text("Retry")
            }
            if (s.cause != null) {
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.OutlinedButton(
                    onClick = { showReport = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Report problem") }
            }
            if (showReport) {
                BugReportDialog(
                    error = s.cause,
                    onDismiss = { showReport = false },
                )
            }
        }
    }
}
