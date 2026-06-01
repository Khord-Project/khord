package org.khord.android.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import kotlin.math.roundToInt

/**
 * Convert a [Bitmap] to ASCII art (feat/ascii-art-send). The result is a
 * plain string sent as an ordinary text message — no media upload, no
 * encryption key, no thumbnail. Because the output is derived purely from
 * downscaled pixel luminance, it carries none of the original's EXIF /
 * metadata, so the privacy posture is the same as any text message.
 *
 * The ramp runs from darkest glyph to lightest. Note: it maps LOW luminance
 * (dark pixels) to the *densest* glyph ('@') and HIGH luminance (bright
 * pixels) to ' ', which reads correctly as light-on-dark or dark-on-light
 * art in a monospace bubble.
 */
object ImageToAscii {

    /** Dark → light. Index 0 ('@') is densest; last (' ') is empty. */
    private const val CHARS = "@%#*+=;:,. "

    /**
     * Character-cell aspect correction: monospace glyphs are roughly twice
     * as tall as they are wide, so we sample fewer rows than columns to keep
     * the picture from stretching vertically.
     */
    private const val ASPECT = 0.55

    /** Decode an image from a content [uri] and convert it to ASCII art. */
    fun fromUri(context: Context, uri: Uri, columns: Int = 70): String {
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it)
        } ?: error("cannot open image: $uri")
        return convert(bitmap, columns)
    }

    fun convert(bitmap: Bitmap, columns: Int = 70): String {
        val cols = columns.coerceAtLeast(1)
        val rows = rowsFor(bitmap.width, bitmap.height, cols).coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, cols, rows, true)
        return buildString(rows * (cols + 1)) {
            for (y in 0 until scaled.height) {
                for (x in 0 until scaled.width) {
                    val pixel = scaled.getPixel(x, y)
                    val luminance = luminanceOf(
                        Color.red(pixel), Color.green(pixel), Color.blue(pixel),
                    )
                    append(CHARS[rampIndex(luminance)])
                }
                append('\n')
            }
        }
    }

    /** Rows to sample for an image of [width]×[height] rendered at [columns]. */
    fun rowsFor(width: Int, height: Int, columns: Int): Int {
        if (width <= 0) return 0
        return (height.toDouble() / width * columns * ASPECT).roundToInt()
    }

    /** Rec. 601 luma, normalised to 0.0 (black) … 1.0 (white). */
    fun luminanceOf(r: Int, g: Int, b: Int): Double =
        (r * 0.299 + g * 0.587 + b * 0.114) / 255.0

    /** Map a 0..1 luminance to a [CHARS] index, clamped. */
    fun rampIndex(luminance: Double): Int =
        (luminance.coerceIn(0.0, 1.0) * (CHARS.length - 1)).roundToInt()
}
