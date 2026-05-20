package org.khord.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import org.khord.android.nav.Routes

@Composable
fun WelcomeScreen(nav: NavController) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Welcome to Khord", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        Text(
            "Khord stores your identity entirely on this device. The next " +
                "screen will show you a 12-word recovery phrase. Write it down — " +
                "it is the only way to recover your account if you lose this phone.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = { nav.navigate(Routes.SERVER_SETUP) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Generate identity")
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = { nav.navigate(Routes.SEED_RECOVERY) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("I already have a seed phrase")
        }
    }
}
