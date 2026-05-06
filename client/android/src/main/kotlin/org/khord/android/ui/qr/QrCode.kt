package org.khord.android.ui.qr

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Render `text` as a QR code at `sizePx × sizePx` resolution.
 *
 * Pure ZXing core (no Android-specific scanner deps for the WRITE side).
 */
@Composable
fun QrCodeImage(
    text: String,
    sizePx: Int,
    modifier: Modifier = Modifier,
) {
    val bitmap = remember(text, sizePx) { encodeQr(text, sizePx) }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = "QR code",
        modifier = modifier,
        contentScale = ContentScale.Fit,
    )
}

private fun encodeQr(text: String, size: Int): Bitmap {
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 1,
    )
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
    val w = matrix.width; val h = matrix.height
    val pixels = IntArray(w * h)
    for (y in 0 until h) {
        val rowOffset = y * w
        for (x in 0 until w) {
            pixels[rowOffset + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
        }
    }
    val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    bm.setPixels(pixels, 0, w, 0, 0, w, h)
    return bm
}
