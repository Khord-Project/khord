package org.khord.android.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure-geometry tests for [ImageProcessor.fitWithin] — the downscale +
 * thumbnail sizing math, which is the error-prone part. The actual
 * Bitmap decode / JPEG re-encode / EXIF strip runs against the Android
 * framework and is exercised on-device; Robolectric's shadow Bitmap
 * doesn't perform real codec work, so it can't meaningfully verify it.
 */
class ImageProcessorTest {

    @Test
    fun downscales_landscape_to_max_on_longest_side() {
        val (w, h) = ImageProcessor.fitWithin(4000, 3000, 2048)
        assertEquals(2048, w)
        assertEquals(1536, h) // 3000 * 2048/4000
    }

    @Test
    fun downscales_portrait_to_max_on_longest_side() {
        val (w, h) = ImageProcessor.fitWithin(3000, 4000, 2048)
        assertEquals(1536, w)
        assertEquals(2048, h)
    }

    @Test
    fun never_upscales_a_small_image() {
        val (w, h) = ImageProcessor.fitWithin(800, 600, 2048)
        assertEquals(800, w)
        assertEquals(600, h)
    }

    @Test
    fun thumbnail_longest_side_is_capped() {
        val (w, h) = ImageProcessor.fitWithin(1024, 768, 64)
        assertTrue(maxOf(w, h) <= 64, "longest side must be <= 64, got ${maxOf(w, h)}")
        assertEquals(64, w)
        assertEquals(48, h)
    }

    @Test
    fun aspect_ratio_is_preserved_within_rounding() {
        val (w, h) = ImageProcessor.fitWithin(1000, 250, 64)
        // 4:1 ratio preserved; longest side hits the cap.
        assertEquals(64, w)
        assertEquals(16, h)
    }
}
