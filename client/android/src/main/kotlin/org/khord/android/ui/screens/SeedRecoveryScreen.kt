package org.khord.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import org.khord.android.AppContainer
import org.khord.android.nav.Routes
import org.khord.android.ui.viewmodel.OnboardingViewModel
import org.khord.shared.identity.SeedPhrase

/**
 * Recovery entry: user types or pastes a 12-word seed phrase, we
 * validate against BIP39, then route through the existing server-setup
 * + registration path with [OnboardingViewModel.isRecovering] = true.
 *
 * UX choice (per spec): single textarea rather than a 12-cell grid.
 * Words are whitespace-separated; case-insensitive; live validation
 * surfaces problems as the user types so they don't have to wait for
 * the Recover button to figure out a typo.
 *
 * See ADR 025.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeedRecoveryScreen(nav: NavController) {
    var input by remember { mutableStateOf("") }
    val (status, validatedWords) = remember(input) { validate(input) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recover your identity") },
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
            verticalArrangement = Arrangement.Top,
        ) {
            Text(
                "Enter the 12 words from your seed phrase, in order. " +
                    "Separate them with spaces.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("Seed phrase (12 words)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                minLines = 3,
                maxLines = 4,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrect = false,
                ),
                isError = status is ValidationStatus.Invalid,
                supportingText = {
                    when (status) {
                        ValidationStatus.Empty ->
                            Text("Type or paste your 12-word phrase.")
                        ValidationStatus.Valid ->
                            Text(
                                "✓ Phrase looks valid.",
                                color = MaterialTheme.colorScheme.primary,
                            )
                        is ValidationStatus.Invalid ->
                            Text(
                                status.message,
                                color = MaterialTheme.colorScheme.error,
                            )
                    }
                },
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    val ovm = AppContainer.onboardingViewModel
                        ?: OnboardingViewModel().also { AppContainer.onboardingViewModel = it }
                    runCatching { ovm.acceptRecoveryPhrase(validatedWords!!) }
                        .onSuccess { nav.navigate(Routes.SERVER_SETUP) }
                    // onFailure is unreachable: validatedWords is non-null
                    // iff status == Valid, which is the only state in
                    // which the button is enabled.
                },
                enabled = status is ValidationStatus.Valid,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Recover") }
            Spacer(Modifier.height(16.dp))
            Text(
                "This will restore your identity (fingerprint). Contacts " +
                    "and messages from your previous installation cannot " +
                    "be recovered — you'll need to re-add contacts, but " +
                    "their app will recognise you by the same identity.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Three-state validation of the textarea contents. We avoid throwing
 * inside the composable hot path — instead the validator returns a
 * sealed status that the UI maps to colours / labels / button-enabled
 * state.
 */
private sealed interface ValidationStatus {
    data object Empty : ValidationStatus
    data object Valid : ValidationStatus
    data class Invalid(val message: String) : ValidationStatus
}

/**
 * Tokenise on whitespace, lower-case, then:
 *   - require exactly 12 tokens (the recovery flow only supports
 *     12-word phrases for now);
 *   - require every token to be in the BIP39 English wordlist (the
 *     SeedPhrase library would throw the same way, but we want to
 *     surface "word #N isn't in the list" sooner);
 *   - finally call [SeedPhrase.toEntropy] which verifies the
 *     checksum.
 *
 * Returns the canonicalised word list alongside the status when
 * Valid; null otherwise (so callers can't accidentally use a
 * malformed list).
 */
private fun validate(raw: String): Pair<ValidationStatus, List<String>?> {
    val tokens = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return ValidationStatus.Empty to null
    if (tokens.size != 12) {
        return ValidationStatus.Invalid(
            "Expected 12 words, got ${tokens.size}.",
        ) to null
    }
    val lower = tokens.map { it.lowercase() }
    val unknown = lower.withIndex().firstOrNull { (_, w) -> !SeedPhrase.isValidWord(w) }
    if (unknown != null) {
        return ValidationStatus.Invalid(
            "Word ${unknown.index + 1} (\"${unknown.value}\") is not in the BIP39 list.",
        ) to null
    }
    return try {
        SeedPhrase.toEntropy(lower)
        ValidationStatus.Valid to lower
    } catch (e: IllegalArgumentException) {
        // Likely a checksum failure — last word is wrong.
        ValidationStatus.Invalid(
            "Phrase checksum invalid — double-check the last word.",
        ) to null
    }
}
