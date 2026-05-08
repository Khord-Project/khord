package org.khord.android.ui.screens

import android.app.Activity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * Marks the hosting Activity's window as `FLAG_SECURE` for as long as the
 * caller is composed. This blocks:
 *
 *  - the screenshot key combination (the OS shows a "Can't take screenshot")
 *  - screen recordings / casting (frame goes black)
 *  - the recents-apps preview thumbnail (replaced with the app icon)
 *
 * We apply this on any screen that displays the seed phrase or asks the
 * user to retype words from it.
 *
 * Caveat: when navigating between two FLAG_SECURE screens (Display →
 * Confirm), there is a single-frame window where the outgoing screen's
 * `onDispose` clears the flag and the incoming screen's effect re-applies
 * it. In practice this is invisible — the recents thumbnail is rendered
 * from the Activity surface, not from inter-frame snapshots — but if a
 * tester sees a thumbnail flash during transition, that's why.
 */
@Composable
fun SecureScreen() {
    val activity = LocalContext.current as? Activity
    DisposableEffect(activity) {
        val window = activity?.window
        window?.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
