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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.navigation.NavController
import org.khord.android.AppContainer
import org.khord.android.nav.Routes
import org.khord.android.ui.viewmodel.OnboardingViewModel

@Composable
fun SeedConfirmScreen(nav: NavController) {
    val vm = AppContainer.onboardingViewModel
    if (vm == null) {
        // We landed here without a phrase — shouldn't happen in normal flow.
        Text("Onboarding state lost; restart.")
        return
    }

    val indices = remember { vm.confirmIndices() }
    var inputs by remember { mutableStateOf(List(indices.size) { "" }) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
    ) {
        Text("Confirm your phrase", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Type the words at positions ${indices.map { it + 1 }.joinToString(", ")} " +
                "to confirm you've recorded the phrase.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))

        for (i in indices.indices) {
            OutlinedTextField(
                value = inputs[i],
                onValueChange = { v -> inputs = inputs.toMutableList().apply { this[i] = v } },
                label = { Text("Word #${indices[i] + 1}") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    imeAction = if (i == indices.lastIndex) ImeAction.Done else ImeAction.Next,
                ),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
        }

        Spacer(Modifier.height(16.dp))
        if (error != null) {
            Text(
                error!!, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        Button(
            onClick = {
                if (vm.checkConfirmation(inputs.map { it.trim() })) {
                    nav.navigate(Routes.REGISTRATION) {
                        popUpTo(Routes.WELCOME) { inclusive = true }
                    }
                } else {
                    error = "One or more words don't match. Tap back to re-read the phrase."
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Confirm and register") }
    }
}
