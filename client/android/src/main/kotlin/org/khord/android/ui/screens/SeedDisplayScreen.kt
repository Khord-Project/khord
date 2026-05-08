package org.khord.android.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import org.khord.android.AppContainer
import org.khord.android.nav.Routes
import org.khord.android.ui.viewmodel.OnboardingViewModel
import org.khord.android.util.SecureClipboard

@Composable
fun SeedDisplayScreen(nav: NavController) {
    SecureScreen()

    val vm = remember {
        AppContainer.onboardingViewModel
            ?: OnboardingViewModel().also { AppContainer.onboardingViewModel = it }
    }
    val status by vm.status.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        if (status is OnboardingViewModel.Status.Idle) vm.generate()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text("Recovery phrase", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Write these 12 words down — in order — somewhere only you can read. " +
                "They are the only way to recover this account.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(16.dp))

        when (val s = status) {
            is OnboardingViewModel.Status.Display -> SeedBody(s.words, nav)
            is OnboardingViewModel.Status.Failed ->
                Text("Failed to generate phrase: ${s.message}")
            else -> Text("Generating…")
        }
    }
}

@Composable
private fun SeedBody(words: List<String>, nav: NavController) {
    // Two-column grid via plain Rows so the screen can verticalScroll
    // without nesting a LazyVerticalGrid in a scrollable parent.
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (rowStart in 0 until words.size step 2) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SeedWordChip(index = rowStart, word = words[rowStart], modifier = Modifier.weight(1f))
                if (rowStart + 1 < words.size) {
                    SeedWordChip(
                        index = rowStart + 1,
                        word = words[rowStart + 1],
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    Spacer(Modifier.height(20.dp))
    Text(
        "Write these words down on paper and store them somewhere physically " +
            "secure — a safe, a locked drawer, or a safety deposit box. This " +
            "phrase is the only way to recover your identity if you lose this " +
            "device. Anyone who has these words can become you.",
        style = MaterialTheme.typography.bodyMedium,
    )

    Spacer(Modifier.height(16.dp))
    StorageTips()

    Spacer(Modifier.height(12.dp))
    CopyToClipboardLink(words)

    Spacer(Modifier.height(24.dp))
    Button(
        onClick = { nav.navigate(Routes.SEED_CONFIRM) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("I've written this down")
    }
}

@Composable
private fun SeedWordChip(index: Int, word: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
    ) {
        Text("${index + 1}. $word", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun StorageTips() {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = if (expanded) "Hide storage tips ▴" else "Storage tips ▾",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
        )
        if (expanded) {
            Spacer(Modifier.height(4.dp))
            TipBullet("Paper in a fireproof safe or safety deposit box")
            TipBullet("A hardware-encrypted password manager (KeePass, 1Password)")
            TipBullet(
                "Split across trusted people using Shamir's Secret Sharing " +
                    "(advanced — see docs)",
            )
            TipBullet(
                "NEVER store in plain text files, notes apps, or cloud storage " +
                    "without encryption",
            )
        }
    }
}

@Composable
private fun TipBullet(text: String) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text("•  ", style = MaterialTheme.typography.bodySmall)
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun CopyToClipboardLink(words: List<String>) {
    val context = LocalContext.current
    var showConfirm by remember { mutableStateOf(false) }

    Text(
        text = "Need to copy to a password manager?",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clickable { showConfirm = true }
            .padding(vertical = 4.dp),
    )

    if (showConfirm) {
        val autoClearLine = if (SecureClipboard.supportsAutoClear) {
            " The clipboard will be cleared after 60 seconds."
        } else {
            " (Auto-clear is unavailable on Android 8.x — clear the clipboard " +
                "manually after pasting.)"
        }
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("Copy seed phrase to clipboard?") },
            text = {
                Text(
                    "Copying your seed phrase to the clipboard makes it accessible " +
                        "to other apps on your device. Only do this if you're pasting " +
                        "it directly into a secure, encrypted password manager." +
                        autoClearLine +
                        " Continue?",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    SecureClipboard.copy(
                        context = context,
                        label = "Khord recovery phrase",
                        text = words.joinToString(" "),
                        scope = AppContainer.applicationScope,
                        autoClearMs = 60_000L,
                    )
                    showConfirm = false
                }) { Text("Copy") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("Cancel") }
            },
        )
    }
}
