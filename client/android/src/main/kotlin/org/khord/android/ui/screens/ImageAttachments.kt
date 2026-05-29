package org.khord.android.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.khord.android.AppContainer
import org.khord.android.ui.theme.LocalKhordChatColors
import org.khord.shared.protocol.orchestrator.MediaEntry
import java.io.File

/**
 * Image-attachment UI shared by [ChatScreen] and [GroupChatScreen]
 * (ADR 029):
 *
 *   - [ImageBubble] — a message bubble for an attachment. Shows the
 *     decrypted inline thumbnail instantly; tapping fetches + decrypts the
 *     full image (one-time relay read), after which it shows the full image
 *     and a further tap opens [FullScreenImageViewer].
 *   - [ImagePreviewSheet] — confirm + caption before sending a picked image.
 *   - [FullScreenImageViewer] — pinch-zoom/pan viewer with a share button.
 *
 * No image-loading library (Coil/Glide) is a dependency, so decoding is
 * done directly via [BitmapFactory] off the main thread.
 */

@Composable
fun ImageBubble(
    media: MediaEntry,
    caption: String,
    isSent: Boolean,
    downloading: Boolean,
    onDownload: () -> Unit,
    onOpenFull: (path: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val chat = LocalKhordChatColors.current
    val bg = if (isSent) chat.sentBubble else chat.receivedBubble
    val fg = if (isSent) chat.sentText else chat.receivedText

    // Decrypt the tiny inline thumbnail once per media id — no network.
    val thumb by produceState<ImageBitmap?>(null, media.mediaId) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                val messaging = AppContainer.messaging ?: return@runCatching null
                val bytes = messaging.decryptThumbnail(media)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        }
    }
    // Full image (decrypted + cached to disk) — present once downloaded.
    val full by produceState<ImageBitmap?>(null, media.cachedPath) {
        val path = media.cachedPath
        value = if (path != null) {
            withContext(Dispatchers.IO) {
                runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull()
            }
        } else {
            null
        }
    }

    Column(
        modifier = modifier
            .widthIn(max = 240.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .padding(4.dp),
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable {
                    val path = media.cachedPath
                    if (path != null) onOpenFull(path) else if (!downloading) onDownload()
                },
            contentAlignment = Alignment.Center,
        ) {
            val shown = full ?: thumb
            if (shown != null) {
                Image(
                    bitmap = shown,
                    contentDescription = "Image attachment",
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.FillWidth,
                )
            } else {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .background(Color.Black.copy(alpha = 0.1f)),
                )
            }
            // Until the full image is cached, dim + overlay a download
            // affordance (or a spinner while fetching).
            if (full == null) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(Color.Black.copy(alpha = 0.28f)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (downloading) {
                        CircularProgressIndicator(color = Color.White)
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Download, contentDescription = null, tint = Color.White)
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "Tap to download",
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }
        }
        if (caption.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                caption,
                style = MaterialTheme.typography.bodyMedium,
                color = fg,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImagePreviewSheet(
    uri: Uri,
    sending: Boolean,
    onCancel: () -> Unit,
    onSend: (caption: String) -> Unit,
) {
    val context = LocalContext.current
    var caption by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onCancel) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            val preview by produceState<ImageBitmap?>(null, uri) {
                value = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use {
                            BitmapFactory.decodeStream(it)?.asImageBitmap()
                        }
                    }.getOrNull()
                }
            }
            preview?.let {
                Image(
                    bitmap = it,
                    contentDescription = "Selected image",
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                    contentScale = ContentScale.Fit,
                )
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = caption,
                onValueChange = { caption = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Add a caption (optional)") },
                singleLine = false,
            )
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onCancel, enabled = !sending) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { onSend(caption) }, enabled = !sending) {
                    Text(if (sending) "Sending…" else "Send")
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
fun FullScreenImageViewer(path: String, caption: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val bmp = remember(path) {
        runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull()
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (bmp != null) {
                var scale by remember { mutableStateOf(1f) }
                var offset by remember { mutableStateOf(Offset.Zero) }
                Image(
                    bitmap = bmp,
                    contentDescription = caption.ifEmpty { "Image" },
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 5f)
                                offset = if (scale > 1f) offset + pan else Offset.Zero
                            }
                        }
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y,
                        ),
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
            }
            IconButton(
                onClick = { shareImage(context, path) },
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            ) {
                Icon(Icons.Filled.Share, contentDescription = "Share", tint = Color.White)
            }
            if (caption.isNotEmpty()) {
                Text(
                    caption,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                )
            }
        }
    }
}

/** Share a cached image via the system sheet using the app's FileProvider. */
private fun shareImage(context: Context, path: String) {
    val uri = FileProvider.getUriForFile(
        context, "${context.packageName}.fileprovider", File(path),
    )
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "image/jpeg"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Share image"))
}
