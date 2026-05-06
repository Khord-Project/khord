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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import org.khord.android.AppContainer
import org.khord.android.nav.Routes
import org.khord.android.ui.viewmodel.OnboardingViewModel

@Composable
fun RegistrationScreen(nav: NavController) {
    val vm = AppContainer.onboardingViewModel

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
            val status by vm.status.collectAsStateWithLifecycle()
            LaunchedEffect(status) {
                if (status is OnboardingViewModel.Status.Idle ||
                    status is OnboardingViewModel.Status.Display
                ) {
                    vm.register()
                }
                if (status is OnboardingViewModel.Status.Done) {
                    AppContainer.onboardingViewModel = null
                    nav.navigate(Routes.CONTACTS) {
                        popUpTo(Routes.WELCOME) { inclusive = true }
                    }
                }
            }

            Text("Registering identity", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))
            when (val s = status) {
                is OnboardingViewModel.Status.Generating -> Text("Generating phrase…")
                is OnboardingViewModel.Status.Display -> Text("Phrase ready, registering…")
                is OnboardingViewModel.Status.Registering -> {
                    Text("Deriving keys + uploading bundle…")
                    Spacer(Modifier.height(16.dp))
                    CircularProgressIndicator()
                }
                is OnboardingViewModel.Status.Done -> Text("Done")
                is OnboardingViewModel.Status.Failed -> {
                    Text("Registration failed: ${s.message}",
                        color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { vm.register() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Retry")
                    }
                }
                else -> CircularProgressIndicator()
            }
        } else if (recoveryMessaging != null) {
            // We were here mid-registration last time; finish it.
            LaunchedEffect(Unit) {
                runCatching { recoveryMessaging.register(opkBatchSize = 50) }
                if (!recoveryMessaging.needsServerRegistration) {
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
