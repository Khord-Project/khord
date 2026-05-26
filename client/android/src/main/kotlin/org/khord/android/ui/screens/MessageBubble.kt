package org.khord.android.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import org.khord.android.ui.theme.LocalKhordChatColors

/**
 * Shared message-bubble composable used by both [ChatScreen] and
 * [GroupChatScreen]. Three responsibilities the original inline bubbles
 * each had to repeat:
 *
 *   1. **URL auto-linkify.** Body text is scanned for http/https/www
 *      URLs (see [URL_REGEX]); each match is wrapped in a
 *      [LinkAnnotation.Url] so it renders underlined in the same
 *      colour as the surrounding text and routes through Compose's
 *      built-in link click handler — which delegates to the system
 *      Intent.ACTION_VIEW, which opens whichever browser the user has
 *      configured.
 *   2. **Long-press → Copy.** Bubble accepts long-press via
 *      `combinedClickable`; opens a [DropdownMenu] with a Copy entry
 *      that writes the body to the clipboard via
 *      [ClipboardManager.setPrimaryClip].
 *   3. **Long-press → Edit** (alpha.14+, ADR 026). When the bubble
 *      is for a message we SENT and we know its [messageUuid], the
 *      menu also includes Edit; tapping it fires [onEdit] with the
 *      UUID + current body so the host screen can enter edit-mode.
 *      Pre-alpha.14 messages don't carry a UUID and stay un-editable.
 *
 * Future enhancements (Reply, Reactions) land once here.
 */
/**
 * Resolved quote shown inside a reply's bubble: the original sender's
 * label + a (truncated) snippet of their text. [found] is false when
 * the referenced message couldn't be located locally (deleted, or
 * pre-UUID) — the bubble then renders a muted "Original message"
 * placeholder.
 */
data class QuotedPreview(
    val senderLabel: String,
    val snippet: String,
    val found: Boolean,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    body: String,
    isSent: Boolean,
    messageUuid: String? = null,
    onEdit: ((uuid: String, body: String) -> Unit)? = null,
    onReply: ((uuid: String, body: String) -> Unit)? = null,
    quoted: QuotedPreview? = null,
    onQuotedTap: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val chat = LocalKhordChatColors.current
    val bg: Color = if (isSent) chat.sentBubble else chat.receivedBubble
    val fg: Color = if (isSent) chat.sentText else chat.receivedText
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    val canEdit = isSent && messageUuid != null && onEdit != null
    val canReply = messageUuid != null && onReply != null

    Box(
        modifier = modifier
            .widthIn(max = 280.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .combinedClickable(
                onClick = {},
                onLongClick = { menuOpen = true },
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column {
            // Quote block at the top of the bubble when this message
            // is a reply. Tappable to jump to the original.
            if (quoted != null) {
                QuoteBlock(quoted, fg, onQuotedTap)
                Spacer(Modifier.height(4.dp))
            }
            Text(
                text = remember(body, fg) { linkify(body, fg) },
                style = MaterialTheme.typography.bodyMedium,
                color = fg,
            )
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
        ) {
            DropdownMenuItem(
                text = { Text("Copy") },
                onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                        as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Khord message", body))
                    menuOpen = false
                    // Android 13+ shows its own clipboard-write notification.
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                    }
                },
            )
            if (canReply) {
                DropdownMenuItem(
                    text = { Text("Reply") },
                    onClick = {
                        menuOpen = false
                        onReply!!(messageUuid!!, body)
                    },
                )
            }
            if (canEdit) {
                DropdownMenuItem(
                    text = { Text("Edit") },
                    onClick = {
                        menuOpen = false
                        onEdit!!(messageUuid!!, body)
                    },
                )
            }
        }
    }
}

/**
 * The quote card rendered at the top of a reply bubble. A thin
 * left accent bar + sender label + one-line snippet, all in a
 * slightly-dimmed tint of the bubble's foreground colour.
 */
@Composable
private fun QuoteBlock(
    quoted: QuotedPreview,
    fg: Color,
    onTap: (() -> Unit)?,
) {
    val dim = fg.copy(alpha = 0.7f)
    Row(
        modifier = (if (onTap != null) Modifier.clickable { onTap() } else Modifier)
            .height(IntrinsicSize.Min),
    ) {
        // Left accent bar — matches the text column's height via the
        // Row's IntrinsicSize.Min.
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(dim),
        )
        Spacer(Modifier.width(6.dp))
        Column {
            Text(
                quoted.senderLabel,
                style = MaterialTheme.typography.labelSmall,
                color = dim,
            )
            Text(
                if (quoted.found) quoted.snippet else "Original message",
                style = MaterialTheme.typography.bodySmall,
                color = dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontStyle = if (quoted.found) null else FontStyle.Italic,
            )
        }
    }
}

/**
 * URL matcher. Handles http://, https://, and bare www. prefixes.
 * Stops at common trailing punctuation (period at end of sentence,
 * closing paren, etc.) by requiring the final character to be in
 * the URL-safe set — this is the trick most chat clients use.
 *
 * Conservative on purpose: we don't try to recognise mailto: links
 * or schemes like ftp:// (uncommon in messaging), and we don't try
 * to be smart about TLDs without a scheme (would match too much).
 */
private val URL_REGEX = Regex(
    """(?i)\b((?:https?://|www\.)[-A-Z0-9+&@#/%?=~_|!:,.;]*[-A-Z0-9+&@#/%=~_|])""",
)

/**
 * Build an [AnnotatedString] where URL substrings are wrapped in a
 * [LinkAnnotation.Url] with underline + same-colour styling. Bare
 * www-prefixed URLs get a synthetic https:// scheme so the system
 * Intent can resolve them; the user still sees the original text.
 */
private fun linkify(text: String, baseColor: Color): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    for (m in URL_REGEX.findAll(text)) {
        if (m.range.first > cursor) {
            append(text.substring(cursor, m.range.first))
        }
        val display = m.value
        val href = if (display.startsWith("www.", ignoreCase = true)) "https://$display"
                   else display
        withLink(
            LinkAnnotation.Url(
                url = href,
                styles = TextLinkStyles(
                    style = SpanStyle(
                        color = baseColor,
                        textDecoration = TextDecoration.Underline,
                    ),
                ),
            ),
        ) {
            append(display)
        }
        cursor = m.range.last + 1
    }
    if (cursor < text.length) {
        append(text.substring(cursor))
    }
}
