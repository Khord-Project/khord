package org.khord.android.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Helpers for clipboard interactions where the data is sensitive enough
 * that we'd rather it didn't sit on the OS clipboard forever.
 *
 * Auto-clear is API 28+ only — `ClipboardManager.clearPrimaryClip()` was
 * added in Android 9. Khord ships with `minSdk = 26`, so on Android 8.x
 * we just copy and skip the timer (the dialog wording adapts to match).
 *
 * Safety: when the timer fires, we only clear the clipboard if its
 * primary item still equals the text we copied. If the user copied
 * something else from another app in the meantime, we leave it alone —
 * obliterating an unrelated clipboard would be a worse UX than leaving
 * the seed phrase on it.
 */
object SecureClipboard {

    /** True iff the running OS supports `clearPrimaryClip()`. */
    val supportsAutoClear: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    /**
     * Copy [text] to the system clipboard under [label] (the label appears
     * in the Android 13+ "what was just copied" toast). When [autoClearMs]
     * is positive AND the OS supports auto-clear, also schedule a clear
     * via [scope] after that many ms — but only if the clipboard at that
     * point still contains exactly [text].
     */
    fun copy(
        context: Context,
        label: String,
        text: String,
        scope: CoroutineScope,
        autoClearMs: Long = 0L,
    ) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))

        if (autoClearMs > 0 && supportsAutoClear) {
            scope.launch {
                delay(autoClearMs)
                val current = cm.primaryClip
                val stillOurs = current != null
                    && current.itemCount > 0
                    && current.getItemAt(0).text?.toString() == text
                if (stillOurs) cm.clearPrimaryClip()
            }
        }
    }
}
