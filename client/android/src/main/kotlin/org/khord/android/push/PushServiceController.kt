package org.khord.android.push

import android.content.Context
import androidx.core.content.ContextCompat

/**
 * Thin wrapper around start/stop of [KhordPushService] so the screens
 * don't poke at Intent / ContextCompat plumbing directly.
 *
 * `start` is safe to call repeatedly — the service de-dups foreground
 * starts via [Service.startForeground]'s idempotency.
 */
object PushServiceController {

    fun start(context: Context) {
        val intent = KhordPushService.startIntent(context)
        ContextCompat.startForegroundService(context, intent)
    }

    /**
     * Tell the service to refresh its subscription list (e.g. after a
     * new contact appears). No-op if the service isn't running.
     */
    fun refresh(context: Context) {
        // Same intent as start; the service treats REFRESH as the default.
        val intent = KhordPushService.startIntent(context)
        ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context) {
        val intent = KhordPushService.stopIntent(context)
        // Send via startService — this delivers ACTION_STOP to onStartCommand
        // which then calls stopSelf(). Using context.stopService() would skip
        // the clean shutdown path inside the service.
        context.startService(intent)
    }
}
