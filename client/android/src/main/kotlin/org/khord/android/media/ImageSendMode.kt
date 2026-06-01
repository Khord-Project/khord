package org.khord.android.media

import android.content.Context

/**
 * Per-device default for how a picked image is sent (feat/ascii-art-send):
 *
 *  - [IMAGE] (default): send as an encrypted image attachment; ASCII is the
 *    secondary option in the preview sheet.
 *  - [ASCII]: convert to ASCII art and send as a plain text message; sending
 *    as an image is the secondary option.
 *
 * Privacy-maximalist users flip this once and every image leaves as text
 * (no media upload, no blob on the relay). Persisted in SharedPreferences;
 * the preview sheet reads it to decide which button is primary.
 */
enum class ImageSendMode {
    IMAGE,
    ASCII;

    val wire: String get() = if (this == ASCII) "ascii" else "image"

    companion object {
        private const val PREFS_NAME = "khord_settings"
        private const val KEY = "image_send_mode"

        fun load(context: Context): ImageSendMode {
            val v = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY, "image")
            return if (v == "ascii") ASCII else IMAGE
        }

        fun save(context: Context, mode: ImageSendMode) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY, mode.wire)
                .apply()
        }
    }
}
