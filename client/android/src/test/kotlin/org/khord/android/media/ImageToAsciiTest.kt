package org.khord.android.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure-math tests for the ASCII converter's geometry + luminance ramp.
 * The actual Bitmap.getPixel sampling runs against the Android framework
 * and is exercised on-device; Robolectric's shadow Bitmap doesn't do real
 * scaling, so the testable surface is the row math + ramp.
 */
class ImageToAsciiTest {

    @Test
    fun rows_apply_aspect_correction_and_ratio() {
        // square image at 70 cols → 70 * 0.55 ≈ 39 rows (not 70).
        assertEquals(39, ImageToAscii.rowsFor(100, 100, 70))
        // landscape 2:1 → half the rows of a square.
        assertEquals(19, ImageToAscii.rowsFor(200, 100, 70))
    }

    @Test
    fun rows_zero_for_degenerate_width() {
        assertEquals(0, ImageToAscii.rowsFor(0, 100, 70))
    }

    @Test
    fun ramp_maps_dark_to_dense_and_bright_to_space() {
        // luminance 0 (black) → densest glyph (index 0)
        assertEquals(0, ImageToAscii.rampIndex(0.0))
        // luminance 1 (white) → last glyph (a space)
        assertEquals(10, ImageToAscii.rampIndex(1.0))
        // mid grey lands somewhere in the middle
        val mid = ImageToAscii.rampIndex(0.5)
        assertTrue(mid in 1..9, "mid grey should map mid-ramp, got $mid")
    }

    @Test
    fun ramp_clamps_out_of_range() {
        assertEquals(0, ImageToAscii.rampIndex(-0.3))
        assertEquals(10, ImageToAscii.rampIndex(1.7))
    }

    @Test
    fun luminance_weights_green_highest() {
        val r = ImageToAscii.luminanceOf(255, 0, 0)
        val g = ImageToAscii.luminanceOf(0, 255, 0)
        val b = ImageToAscii.luminanceOf(0, 0, 255)
        assertTrue(g > r && r > b, "Rec.601: green > red > blue")
        assertEquals(1.0, ImageToAscii.luminanceOf(255, 255, 255), 1e-9)
        assertEquals(0.0, ImageToAscii.luminanceOf(0, 0, 0), 1e-9)
    }
}
