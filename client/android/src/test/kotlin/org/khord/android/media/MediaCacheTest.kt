package org.khord.android.media

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies the panic / contact-delete media wipe (fix/media-panic-cleanup).
 * Uses Robolectric only for a real [Context.filesDir]; MediaCache itself is
 * plain java.io, so this exercises the actual delete behaviour.
 */
@RunWith(RobolectricTestRunner::class)
// Use a stock Application, not KhordApp — its onCreate does
// System.loadLibrary("sqlcipher"), which isn't on the JVM test classpath.
// We only need a Context with a real filesDir.
@Config(sdk = [33], application = android.app.Application::class)
class MediaCacheTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun clear_removes_every_cached_image() {
        MediaCache.write(context, "a", byteArrayOf(1, 2, 3))
        MediaCache.write(context, "b", byteArrayOf(4, 5, 6))
        val dir = File(context.filesDir, "media")
        assertEquals(2, dir.listFiles()?.size, "two files should be cached")

        MediaCache.clear(context)

        // Panic guarantee: no decrypted bytes survive on disk.
        assertFalse(dir.exists() && (dir.listFiles()?.isNotEmpty() ?: false),
            "media dir must be empty/gone after clear()")
    }

    @Test
    fun write_lands_in_files_media_not_cache() {
        val path = MediaCache.write(context, "x", byteArrayOf(9))
        assertTrue(
            path.contains("/files/media/") && path.endsWith("x.jpg"),
            "expected files/media path, got $path",
        )
        // Must NOT be in the OS-evictable cache dir.
        assertFalse(path.contains("/cache/"), "decrypted images must not live in cache/")
    }

    @Test
    fun deletePaths_removes_named_files_only() {
        val p1 = MediaCache.write(context, "keep", byteArrayOf(1))
        val p2 = MediaCache.write(context, "drop", byteArrayOf(2))

        MediaCache.deletePaths(listOf(p2))

        assertTrue(File(p1).exists(), "untargeted file should remain")
        assertFalse(File(p2).exists(), "targeted file should be deleted")
        // Tolerates non-existent paths.
        MediaCache.deletePaths(listOf("/no/such/file.jpg"))
    }
}
