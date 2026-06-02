package org.khord.android.media

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import java.io.File

/**
 * Saves a decrypted image OUT of Khord's app-private storage into the
 * device's shared photo gallery (fix/media-panic-cleanup, Task 2).
 *
 * This is an explicit, per-image, opt-in escape hatch — the copy it creates
 * is readable by other apps and is NOT removed by the panic button. The
 * caller is responsible for showing the one-time warning ([shouldWarn]) and
 * for obtaining WRITE_EXTERNAL_STORAGE on pre-Q before calling [save].
 */
object GallerySaver {

    private const val PREFS_NAME = "khord_settings"
    private const val KEY_WARN = "gallery_save_warn"

    /** True if the "saving leaves encrypted storage" warning should still be shown. */
    fun shouldWarn(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_WARN, true)

    /** Persist whether to keep warning (the dialog's "Don't ask again" + the Settings toggle). */
    fun setWarn(context: Context, warn: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_WARN, warn).apply()
    }

    /**
     * Copy the JPEG at [sourcePath] into the gallery (Pictures/Khord on Q+).
     * Returns true on success. On Q+ no permission is needed; on pre-Q the
     * caller must already hold WRITE_EXTERNAL_STORAGE.
     */
    fun save(context: Context, sourcePath: String): Boolean {
        val bytes = File(sourcePath).takeIf { it.exists() }?.readBytes() ?: return false
        val resolver = context.contentResolver
        val name = "khord_${System.currentTimeMillis()}.jpg"
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Scoped storage: no permission, lands in Pictures/Khord, and
                // IS_PENDING hides the half-written file until we flip it.
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Khord")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(collection, values) ?: return false
        return try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: error("no output stream for $uri")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            true
        } catch (e: Throwable) {
            // Roll back the placeholder row so a failed save leaves no trace.
            runCatching { resolver.delete(uri, null, null) }
            false
        }
    }
}
