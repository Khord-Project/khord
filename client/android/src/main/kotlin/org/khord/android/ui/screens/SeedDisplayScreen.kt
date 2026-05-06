package org.khord.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import org.khord.android.AppContainer
import org.khord.android.nav.Routes
import org.khord.android.ui.viewmodel.OnboardingViewModel

@Composable
fun SeedDisplayScreen(nav: NavController) {
    val vm = remember {
        AppContainer.onboardingViewModel
            ?: OnboardingViewModel().also { AppContainer.onboardingViewModel = it }
    }
    val status by vm.status.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        if (status is OnboardingViewModel.Status.Idle) vm.generate()
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
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
            is OnboardingViewModel.Status.Display -> {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(s.words.size) { i ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(12.dp),
                        ) {
                            Text("${i + 1}. ${s.words[i]}", style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = { nav.navigate(Routes.SEED_CONFIRM) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("I've written this down")
                }
            }
            is OnboardingViewModel.Status.Failed ->
                Text("Failed to generate phrase: ${s.message}")
            else -> Text("Generating…")
        }
    }
}
