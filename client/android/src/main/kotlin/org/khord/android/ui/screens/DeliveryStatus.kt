package org.khord.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Per-bubble outbound delivery indicator (ADR 030). Sits next to the
 * timestamp on SENT messages:
 *
 *   - "pending" → a muted clock (queued / in flight)
 *   - "failed"  → a red warning, tappable to open the retry/delete dialog
 *   - "sent" / null → nothing (normal appearance)
 *
 * Received messages never carry a delivery status, so this renders nothing
 * for them.
 */
@Composable
fun DeliveryStatusIndicator(
    status: String?,
    onFailedTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (status) {
        "pending" -> Icon(
            Icons.Filled.Schedule,
            contentDescription = "Sending…",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.size(14.dp),
        )
        "failed" -> Icon(
            Icons.Filled.ErrorOutline,
            contentDescription = "Failed to send — tap for options",
            tint = MaterialTheme.colorScheme.error,
            modifier = modifier.size(14.dp).clickable { onFailedTap() },
        )
        // "sent" or null → no indicator.
    }
}

/**
 * Dialog shown when the user taps a failed message's warning icon. Offers
 * to retry delivery (re-queue + drain) or delete the message.
 */
@Composable
fun FailedMessageDialog(
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Message not sent") },
        text = {
            Text(
                "This message couldn't be delivered after several attempts. " +
                    "Retry sending it, or delete it from this chat.",
            )
        },
        confirmButton = {
            TextButton(onClick = { onRetry(); onDismiss() }) { Text("Retry") }
        },
        dismissButton = {
            TextButton(onClick = { onDelete(); onDismiss() }) { Text("Delete") }
        },
    )
}
