package org.khord.android.ui.screens

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
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
import org.khord.android.BuildConfig
import org.khord.android.nav.Routes
import org.khord.android.push.PushServiceController
import org.khord.android.ui.viewmodel.SplashState
import org.khord.android.ui.viewmodel.SplashViewModel
import org.khord.android.util.BugReporter
import org.khord.shared.diagnostic.DiagnosticLog

@Composable
fun SplashScreen(nav: NavController, vm: SplashViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var stateLossPrompt by remember { mutableStateOf<BugReporter.Report?>(null) }

    LaunchedEffect(state) {
        when (val s = state) {
            is SplashState.NeedsOnboarding -> {
                if (s.previouslySetUp && stateLossPrompt == null) {
                    DiagnosticLog.log(
                        "Khord",
                        "Splash: identity gone but leftover state " +
                            "(db=${s.dbFileExists}, prefs=${s.prefsHaveBlob}, " +
                            "keystoreRegen=${s.keystoreRegenerated}) — " +
                            "offering diagnostic report",
                    )
                    stateLossPrompt = buildStateLossReport(s)
                } else {
                    DiagnosticLog.log("Khord", "Splash: no identity found, routing to Welcome")
                    nav.navigate(Routes.WELCOME) {
                        popUpTo(nav.graph.startDestinationId) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            }
            is SplashState.Loaded -> {
                if (s.needsServerRegistration) {
                    DiagnosticLog.log("Khord", "Splash: needs server registration, routing to Registration")
                } else {
                    DiagnosticLog.log("Khord", "Splash: loaded existing identity")
                    // Returning user with a fully registered identity —
                    // fire up the push service so they receive
                    // notifications while the app is in the background.
                    // No-op if already running.
                    PushServiceController.start(context.applicationContext)
                }
                nav.navigate(
                    if (s.needsServerRegistration) Routes.REGISTRATION else Routes.CONTACTS
                ) {
                    popUpTo(nav.graph.startDestinationId) { inclusive = true }
                    launchSingleTop = true
                }
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

    stateLossPrompt?.let { prebuilt ->
        BugReportDialog(
            error = null,
            preBuiltReport = prebuilt,
            title = "Identity not found",
            explanation = "It looks like Khord was previously set up on " +
                "this device but the identity couldn't be loaded. This " +
                "might be caused by a system update or battery " +
                "optimization clearing app data.",
            sendLabel = "Send Report",
            dismissLabel = "Skip",
            onDismiss = {
                stateLossPrompt = null
                nav.navigate(Routes.WELCOME) {
                    popUpTo(nav.graph.startDestinationId) { inclusive = true }
                    launchSingleTop = true
                }
            },
        )
    }
}

/**
 * Builds a synthetic bug report for the state-loss path. There's no
 * Throwable to attach (bootstrap just returned `false`), so we hand
 * the dialog a pre-built [BugReporter.Report] with the device flags
 * stuffed into [BugReporter.Report.additionalContext] for the server
 * to grep on.
 */
private fun buildStateLossReport(s: SplashState.NeedsOnboarding): BugReporter.Report {
    val ctx = buildString {
        appendLine("db_file_exists=${s.dbFileExists}")
        appendLine("prefs_blob_exists=${s.prefsHaveBlob}")
        appendLine("keystore_regenerated=${s.keystoreRegenerated}")
        appendLine("miui_display=${Build.DISPLAY}")
    }
    val rootCause = if (s.keystoreRegenerated) {
        "state_loss: Keystore key invalidated — DB orphan deleted, " +
            "identity unrecoverable without seed phrase " +
            "(db_before=${s.dbFileExists}, prefs_before=${s.prefsHaveBlob})"
    } else {
        "state_loss: bootstrap returned no identity despite leftover " +
            "state (db=${s.dbFileExists}, prefs=${s.prefsHaveBlob})"
    }
    return BugReporter.Report(
        appVersion = BuildConfig.VERSION_NAME,
        androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
        errorMessage = rootCause,
        stackTrace = null,
        diagnosticPath = BugReporter.scrubSensitive(DiagnosticLog.dump())
            .takeIf { it.isNotEmpty() },
        additionalContext = ctx,
    )
}
