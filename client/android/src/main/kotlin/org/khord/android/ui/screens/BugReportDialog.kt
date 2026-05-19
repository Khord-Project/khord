package org.khord.android.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.khord.android.util.BugReporter

/**
 * Reusable error dialog for actionable failures: registration crashes,
 * message-send errors, recovered crash reports on next launch.
 *
 * Flow:
 *   1. Caller passes a Throwable (and optionally a pre-built Report
 *      for the crash-recovery path where the Throwable is gone).
 *   2. Dialog renders error message + an expandable "See what will
 *      be sent" section showing the full report JSON. The user can
 *      read every byte before choosing to send.
 *   3. User types optional additional context (free-form text — also
 *      scrubbed by BugReporter before submission).
 *   4. "Send Report" → BugReporter.submit → toast result.
 *   5. On submission failure: "Copy to clipboard" appears as a
 *      fallback so the user can paste the report into an email /
 *      issue tracker / wherever.
 *   6. "Dismiss" closes the dialog without sending.
 *
 * Submission requires the user to actively tap "Send Report" — the
 * dialog never sends a report on open or on dismiss.
 */
@Composable
fun BugReportDialog(
    error: Throwable?,
    preBuiltReport: BugReporter.Report? = null,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Resolve which report to render. If a Throwable was passed, build
    // one fresh; otherwise use the pre-built (e.g. from the crash
    // recovery path where the original Throwable is gone).
    var additionalContext by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var submitResult by remember { mutableStateOf<Result<String>?>(null) }

    // Re-collect the report whenever additionalContext changes so the
    // "what will be sent" preview stays in sync with the user's
    // edits. Cheap — no I/O on the hot path; logcat capture only
    // happens once on initial collect when error is non-null.
    val report = remember(error, preBuiltReport, additionalContext) {
        when {
            preBuiltReport != null -> {
                if (additionalContext.isBlank()) preBuiltReport
                else preBuiltReport.copy(
                    additionalContext = BugReporter.scrubSensitive(additionalContext),
                )
            }
            error != null -> BugReporter.collect(error, additionalContext.ifBlank { null })
            else -> null
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Something went wrong") },
        text = {
            // Allow the whole body to scroll — long stack traces in the
            // expanded preview can overflow on small screens.
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                if (report == null) {
                    Text("No error details available.")
                    return@Column
                }

                Text(
                    report.errorMessage,
                    style = MaterialTheme.typography.bodyMedium,
                )

                Spacer(Modifier.height(12.dp))

                // Optional user-context field — small inline label
                // since this is a dialog, not a form.
                androidx.compose.material3.OutlinedTextField(
                    value = additionalContext,
                    onValueChange = { additionalContext = it.take(500) },
                    label = { Text("What were you doing? (optional)") },
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                )

                Spacer(Modifier.height(12.dp))

                // Expandable JSON preview — full transparency about
                // what's about to be sent. Tap the header to toggle.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded }
                        .padding(vertical = 4.dp),
                ) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess
                        else Icons.Default.ExpandMore,
                        contentDescription = null,
                    )
                    Spacer(Modifier.height(0.dp))
                    Text(
                        "  See what will be sent",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (expanded) {
                    Text(
                        BugReporter.toJsonString(report),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(8.dp),
                    )
                }

                submitResult?.let { result ->
                    Spacer(Modifier.height(8.dp))
                    val isOk = result.isSuccess
                    Text(
                        if (isOk) "✓ ${result.getOrNull()}"
                        else "✗ Send failed: ${result.exceptionOrNull()?.message}",
                        color = if (isOk) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            // The active action depends on submission state:
            //  - Idle:          "Send Report" button
            //  - Submitting:    spinner
            //  - Submit failed: "Copy to clipboard" button (fallback)
            //  - Submit OK:     "Close" button
            when {
                submitting -> CircularProgressIndicator(
                    modifier = Modifier.height(24.dp),
                    strokeWidth = 2.dp,
                )
                submitResult?.isFailure == true && report != null -> {
                    Button(onClick = {
                        copyToClipboard(context, BugReporter.toJsonString(report))
                        Toast.makeText(
                            context, "Report copied to clipboard", Toast.LENGTH_SHORT,
                        ).show()
                    }) { Text("Copy to clipboard") }
                }
                submitResult?.isSuccess == true -> {
                    Button(onClick = onDismiss) { Text("Close") }
                }
                report != null -> {
                    Button(onClick = {
                        submitting = true
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { BugReporter.submit(report) }
                            submitResult = r
                            submitting = false
                            if (r.isSuccess) {
                                Toast.makeText(
                                    context, "Report submitted",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    }) { Text("Send Report") }
                }
                else -> Unit
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        },
    )
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Khord bug report", text))
}
