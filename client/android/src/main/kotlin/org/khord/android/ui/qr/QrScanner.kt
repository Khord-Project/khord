package org.khord.android.ui.qr

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import com.google.zxing.BarcodeFormat

/**
 * ZXing Android Embedded scanner inside an AndroidView. Pure-Java, no
 * Google deps; F-Droid-friendly per ADR 009.
 *
 * `onScanned` is invoked once per successful decode; the caller is
 * expected to navigate away to prevent re-fires.
 */
@Composable
fun QrScannerView(
    onScanned: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val onScannedState = rememberUpdatedState(onScanned)
    var scanned = false
    val callback = remember {
        BarcodeCallback { result: BarcodeResult? ->
            val text = result?.text ?: return@BarcodeCallback
            if (!scanned) {
                scanned = true
                onScannedState.value(text)
            }
        }
    }
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            DecoratedBarcodeView(ctx).apply {
                barcodeView.decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
                statusView.visibility = android.view.View.GONE
                decodeContinuous(callback)
            }
        },
    )
    DisposableEffect(Unit) {
        onDispose { /* DecoratedBarcodeView handles its own lifecycle on detach. */ }
    }
}
