package org.khord.android.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.khord.android.media.GallerySaver
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

/**
 * Preview + send sheet for one or more picked images (feat/ascii-art-send).
 * Shows a horizontal strip of the selection and two send actions whose
 * primacy follows the user's [asciiDefault] preference:
 *   - default off: primary "Send" (as encrypted images), secondary "as ASCII"
 *   - default on:  primary "Send" (as ASCII text), secondary "as image"
 * Each selected image is sent as its own message. [onSend]'s `asAscii` says
 * which mode was chosen; `caption` applies to image sends (ASCII messages
 * are just the art).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImagePreviewSheet(
    uris: List<Uri>,
    sending: Boolean,
    asciiDefault: Boolean,
    onCancel: () -> Unit,
    onSend: (uris: List<Uri>, caption: String, asAscii: Boolean) -> Unit,
) {
    var caption by remember { mutableStateOf("") }
    val count = uris.size
    ModalBottomSheet(onDismissRequest = onCancel) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(uris) { uri -> PreviewThumb(uri) }
            }
            Spacer(Modifier.height(12.dp))
            // Caption is for image sends; ASCII messages are the art itself.
            if (!asciiDefault) {
                OutlinedTextField(
                    value = caption,
                    onValueChange = { caption = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Add a caption (optional)") },
                    singleLine = false,
                )
                Spacer(Modifier.height(12.dp))
            }
            val primaryLabel = if (count > 1) "Send all ($count)" else "Send"
            val secondaryLabel = if (asciiDefault) "Send as image" else "Send as ASCII"
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onCancel, enabled = !sending) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                // Secondary = the non-default mode.
                TextButton(
                    onClick = { onSend(uris, caption, !asciiDefault) },
                    enabled = !sending,
                ) { Text(secondaryLabel) }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { onSend(uris, caption, asciiDefault) },
                    enabled = !sending,
                ) { Text(if (sending) "Sending…" else primaryLabel) }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun PreviewThumb(uri: Uri) {
    val context = LocalContext.current
    val bmp by produceState<ImageBitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it)?.asImageBitmap()
                }
            }.getOrNull()
        }
    }
    Box(
        modifier = Modifier.size(140.dp).clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.1f)),
        contentAlignment = Alignment.Center,
    ) {
        bmp?.let {
            Image(
                bitmap = it,
                contentDescription = "Selected image",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
fun FullScreenImageViewer(path: String, caption: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bmp = remember(path) {
        runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull()
    }

    // "Save to device" — opt-in copy OUT of encrypted storage (Task 2).
    var showSaveWarning by remember { mutableStateOf(false) }
    val saveNow = {
        scope.launch(Dispatchers.IO) {
            val ok = GallerySaver.save(context, path)
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    if (ok) "Saved to gallery" else "Couldn't save image",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
        Unit
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) saveNow()
        else Toast.makeText(
            context, "Storage permission needed to save", Toast.LENGTH_SHORT,
        ).show()
    }
    // Pre-Q needs WRITE_EXTERNAL_STORAGE; Q+ uses scoped storage (no perm).
    val ensurePermAndSave = {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            saveNow()
        }
    }
    val onSaveClick = {
        if (GallerySaver.shouldWarn(context)) showSaveWarning = true else ensurePermAndSave()
    }

    if (showSaveWarning) {
        SaveToDeviceWarningDialog(
            onConfirm = { dontAskAgain ->
                if (dontAskAgain) GallerySaver.setWarn(context, false)
                showSaveWarning = false
                ensurePermAndSave()
            },
            onDismiss = { showSaveWarning = false },
        )
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
            Row(modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                IconButton(onClick = onSaveClick) {
                    Icon(
                        Icons.Filled.Save,
                        contentDescription = "Save to device",
                        tint = Color.White,
                    )
                }
                IconButton(onClick = { shareImage(context, path) }) {
                    Icon(Icons.Filled.Share, contentDescription = "Share", tint = Color.White)
                }
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

/**
 * One-time warning before copying an image out of Khord's encrypted storage
 * (Task 2). The "Don't ask again" checkbox persists via [GallerySaver.setWarn]
 * — and can be re-enabled from Settings, so the warning is never permanently
 * bypassed against the user's wish.
 */
@Composable
private fun SaveToDeviceWarningDialog(
    onConfirm: (dontAskAgain: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var dontAskAgain by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save to device storage?") },
        text = {
            Column {
                Text(
                    "This copies the image outside of Khord's encrypted storage. " +
                        "Other apps may be able to access it, and it will not be " +
                        "deleted by the panic button.",
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = dontAskAgain, onCheckedChange = { dontAskAgain = it })
                    Spacer(Modifier.width(4.dp))
                    Text("Don't ask again")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(dontAskAgain) }) { Text("Save anyway") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
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
