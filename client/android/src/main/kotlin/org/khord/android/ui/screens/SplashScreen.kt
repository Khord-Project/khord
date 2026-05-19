package org.khord.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import org.khord.android.nav.Routes
import org.khord.android.push.PushServiceController
import org.khord.android.ui.viewmodel.SplashState
import org.khord.android.ui.viewmodel.SplashViewModel

@Composable
fun SplashScreen(nav: NavController, vm: SplashViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(state) {
        when (val s = state) {
            is SplashState.NeedsOnboarding -> {
                android.util.Log.w("Khord", "Splash: no identity found, routing to Welcome")
                nav.navigate(Routes.WELCOME) { popUpTo(Routes.SPLASH) { inclusive = true } }
            }
            is SplashState.Loaded -> {
                if (s.needsServerRegistration) {
                    android.util.Log.w("Khord", "Splash: needs server registration, routing to Registration")
                } else {
                    android.util.Log.w("Khord", "Splash: loaded existing identity")
                    // Returning user with a fully registered identity —
                    // fire up the push service so they receive
                    // notifications while the app is in the background.
                    // No-op if already running.
                    PushServiceController.start(context.applicationContext)
                }
                nav.navigate(
                    if (s.needsServerRegistration) Routes.REGISTRATION else Routes.CONTACTS
                ) { popUpTo(Routes.SPLASH) { inclusive = true } }
            }
            else -> Unit
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Khord", style = androidx.compose.material3.MaterialTheme.typography.displayMedium)
        when (val s = state) {
            is SplashState.Failed -> Text("Startup failed: ${s.message}")
            else -> CircularProgressIndicator()
        }
    }
}
