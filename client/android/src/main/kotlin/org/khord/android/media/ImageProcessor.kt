package org.khord.android.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Decode an image picked from a content [Uri], strip its EXIF metadata,
 * downscale it, and re-encode it as JPEG — plus produce a tiny thumbnail.
 * (ADR 029.)
 *
 * EXIF stripping is intrinsic to the decode → [Bitmap] → re-encode
 * round-trip: a Bitmap carries pixels only, so the JPEG written back from
 * it has none of the original's GPS / camera-model / timestamp tags. We DO
 * read the original's orientation tag first and bake the rotation into the
 * pixels, so the stripped image still displays upright (orientation is the
 * one EXIF tag whose loss is visible).
 *
 * This is the privacy-critical boundary: nothing past here should ever see
 * the original file's metadata. The shared module deliberately has no image
 * codec, so this lives in the Android layer.
 */
object ImageProcessor {

    /** Longest side of the full image after downscale (bandwidth + privacy). */
    const val MAX_FULL_DIMENSION = 2048

    /** Longest side of the inline thumbnail. */
    const val THUMBNAIL_DIMENSION = 64

    private const val FULL_QUALITY = 85
    private const val THUMBNAIL_QUALITY = 60

    /** EXIF-stripped, downscaled full JPEG + its tiny thumbnail JPEG. */
    data class Processed(val full: ByteArray, val thumbnail: ByteArray)

    fun process(context: Context, uri: Uri): Processed {
        val source = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("cannot open image: $uri")
        val orientation = readOrientation(source)
        val decoded = BitmapFactory.decodeByteArray(source, 0, source.size)
            ?: error("cannot decode image")
        val upright = applyOrientation(decoded, orientation)
        return Processed(
            full = encodeJpeg(downscale(upright, MAX_FULL_DIMENSION), FULL_QUALITY),
            thumbnail = encodeJpeg(downscale(upright, THUMBNAIL_DIMENSION), THUMBNAIL_QUALITY),
        )
    }

    /**
     * Fit (width, height) within [maxSide] on the longest edge, preserving
     * aspect ratio. Never upscales. Pure — unit-testable without Android.
     */
    fun fitWithin(width: Int, height: Int, maxSide: Int): Pair<Int, Int> {
        val longest = max(width, height)
        if (longest <= maxSide) return width to height
        val scale = maxSide.toFloat() / longest
        return (width * scale).roundToInt().coerceAtLeast(1) to
            (height * scale).roundToInt().coerceAtLeast(1)
    }

    private fun downscale(bitmap: Bitmap, maxSide: Int): Bitmap {
        val (w, h) = fitWithin(bitmap.width, bitmap.height, maxSide)
        if (w == bitmap.width && h == bitmap.height) return bitmap
        return Bitmap.createScaledBitmap(bitmap, w, h, true)
    }

    private fun encodeJpeg(bitmap: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            out.toByteArray()
        }

    private fun readOrientation(bytes: ByteArray): Int =
        try {
            ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL,
            )
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }

    private fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
