package org.khord.android.media

import android.content.Context
import java.io.File

/**
 * Filesystem cache for decrypted full images (ADR 029).
 *
 * Decrypted images live in app-private storage, referenced by path — never
 * in the SQLite DB. Storing megapixel blobs in the encrypted DB would bloat
 * it and duplicate bytes; the DB keeps only the path (media_cached_path).
 * Files are keyed by the relay media id, which is unique per image.
 *
 * The relay's media endpoint is one-time-read, so once an image is fetched
 * and cached here it cannot be re-downloaded — this cache IS the only copy
 * the recipient (and the sender) keep.
 */
object MediaCache {

    private fun dir(context: Context): File =
        File(context.filesDir, "media").apply { if (!exists()) mkdirs() }

    fun fileFor(context: Context, mediaId: String): File = File(dir(context), "$mediaId.jpg")

    /** Write decrypted image bytes; returns the absolute path for media_cached_path. */
    fun write(context: Context, mediaId: String, bytes: ByteArray): String {
        val file = fileFor(context, mediaId)
        file.writeBytes(bytes)
        return file.absolutePath
    }

    /** Read cached bytes by path, or null if the file is gone. */
    fun read(path: String): ByteArray? = File(path).takeIf { it.exists() }?.readBytes()
}
