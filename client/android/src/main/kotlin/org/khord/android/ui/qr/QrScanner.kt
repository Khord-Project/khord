package org.khord.android.ui.qr

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import com.google.zxing.BarcodeFormat

/**
 * ZXing Android Embedded scanner inside an AndroidView. Pure-Java, no
 * Google deps; F-Droid-friendly per ADR 009.
 *
 * Lifecycle wiring matters here: [DecoratedBarcodeView] does NOT auto-start
 * the camera — it expects the host activity to call `resume()` in onResume
 * and `pause()` in onPause. Without those, the surface texture comes up but
 * the camera never opens, and the scanner just sits black/blank forever.
 *
 * In a Composable host we wire the same contract via [LifecycleEventObserver]:
 *   - register on enter, unregister + pause() on dispose
 *   - call resume() once in the AndroidView factory so the very first
 *     composition kicks the camera (the lifecycle is already RESUMED at
 *     that point and we'd otherwise never see another ON_RESUME event)
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
    val scannedFlag = remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val viewRef = remember { mutableStateOf<DecoratedBarcodeView?>(null) }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            DecoratedBarcodeView(ctx).apply {
                barcodeView.decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
                statusView.visibility = android.view.View.GONE
                decodeContinuous(BarcodeCallback { result: BarcodeResult? ->
                    val text = result?.text ?: return@BarcodeCallback
                    if (!scannedFlag.value) {
                        scannedFlag.value = true
                        onScannedState.value(text)
                    }
                })
                viewRef.value = this
                // First-composition kick: the lifecycle is already RESUMED
                // by the time this factory runs, so the observer below
                // wouldn't fire ON_RESUME until the next foreground cycle
                // (e.g. coming back from a permission dialog). Without this
                // call, the camera stays dark on first show.
                resume()
            }
        },
    )

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewRef.value?.resume()
                Lifecycle.Event.ON_PAUSE -> viewRef.value?.pause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewRef.value?.pause()
        }
    }
}
