package org.khord.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import org.khord.android.nav.Routes
import org.khord.android.ui.viewmodel.SearchViewModel

/**
 * Local full-text message search (#60). Debounced FTS5 query; results
 * show conversation name + a snippet with the matched term bolded +
 * timestamp. Tapping a result opens the relevant chat. Local-only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(nav: NavController) {
    val vm: SearchViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Search") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = state.query,
                onValueChange = vm::onQueryChange,
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                singleLine = true,
                placeholder = { Text("Search messages") },
            )

            when {
                state.query.isBlank() -> EmptyState("Search your messages")
                state.searched && state.results.isEmpty() -> EmptyState("No messages found")
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.results) { result ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val route = if (result.isGroup) {
                                        Routes.groupChat(result.conversationId)
                                    } else {
                                        Routes.chat(result.conversationId)
                                    }
                                    nav.navigate(route)
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                        ) {
                            Text(
                                result.conversationLabel,
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                highlight(result.snippet, state.query.trim()),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(text: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Bold every case-insensitive occurrence of [term] within [body].
 * Best-effort visual highlight — independent of FTS tokenisation,
 * so it may not bold every FTS-matched token (e.g. stemmed forms),
 * but it covers the common verbatim-substring case.
 */
private fun highlight(body: String, term: String): AnnotatedString = buildAnnotatedString {
    if (term.isEmpty()) { append(body); return@buildAnnotatedString }
    val lowerBody = body.lowercase()
    val lowerTerm = term.lowercase()
    var i = 0
    while (i < body.length) {
        val hit = lowerBody.indexOf(lowerTerm, i)
        if (hit < 0) {
            append(body.substring(i))
            break
        }
        append(body.substring(i, hit))
        pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
        append(body.substring(hit, hit + term.length))
        pop()
        i = hit + term.length
    }
}
