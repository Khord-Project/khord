package org.khord.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * Subtle "prefers text-only" banner shown at the top of a chat whose
 * contact (or any group member) has signalled images_accepted = false
 * (feat/capability-notice). Informational, not an error — uses the muted
 * surface-variant palette.
 */
@Composable
fun TextOnlyBanner(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Shown when the user taps attach for a contact who prefers text-only. Not
 * a hard block — offers to convert to ASCII, send the image anyway, or
 * cancel (feat/capability-notice).
 */
@Composable
fun TextOnlyPreferenceDialog(
    who: String,
    onSendAscii: () -> Unit,
    onSendImage: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("$who prefers text-only") },
        text = {
            Text(
                "$who has indicated they prefer not to receive images. You can " +
                    "send it as ASCII art, send it as an image anyway, or cancel.",
            )
        },
        confirmButton = {
            Row {
                TextButton(onClick = onSendAscii) { Text("Send as ASCII") }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = onSendImage) { Text("Send as image") }
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancel") }
        },
    )
}
