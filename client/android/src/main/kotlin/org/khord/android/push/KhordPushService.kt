package org.khord.android.push

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.khord.android.AppContainer
import org.khord.shared.diagnostic.DiagnosticLog
import org.khord.shared.protocol.client.PushSignalListener

/**
 * Foreground service hosting the per-mailbox WebSocket listeners.
 *
 *  - Started by [PushServiceController] after the splash bootstrap (or
 *    after registration). Runs in the foreground with a persistent
 *    notification so Android won't kill it when the activity is gone.
 *  - On every WS push, looks up the corresponding [ContactSession],
 *    calls `messaging.receiveMessages(...)`, then posts a per-contact
 *    notification with the freshly-decrypted plaintext preview.
 *  - On panic the service is told to stop (clears the notification +
 *    cancels the listener) before the process is killed; the kill alone
 *    is enough to take everything down but the explicit stop gives a
 *    clean shutdown.
 *
 * The contact list is read from [AppContainer.messaging] each time the
 * service needs to (re)build subscriptions. The [PushServiceController]
 * is responsible for calling [refreshSubscriptions] after a new contact
 * appears so the new mailbox gets a listener.
 */
class KhordPushService : Service() {

    private lateinit var scope: CoroutineScope
    private var listener: PushSignalListener? = null
    private var stateMirrorJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        KhordNotifications.ensureChannel(this)
        val notif = KhordNotifications.foregroundServiceNotification(this)

        // API 34+ requires a foreground service type. dataSync is the
        // conventional fit for "ongoing data delivery from a network
        // endpoint" — exactly what this service does.
        //
        // The try/catch defends against [android.app.ForegroundServiceStart
        // NotAllowedException] which Android 12+ throws when the system
        // tries to bring the service back into the foreground from a
        // non-foreground-eligible state. This happens most commonly when
        // the OS reaps our process under memory pressure and the service
        // is then re-created without an active Activity context. OEMs
        // like OnePlus / OPPO / Realme / Xiaomi are particularly
        // aggressive about killing background processes, so this path
        // gets hit on real devices even when our own start-call sites
        // are all from visible-Activity contexts.
        //
        // The exception class only exists on API 31+. Catching plain
        // [Exception] keeps us API-portable and also covers any other
        // permission-related failure (e.g. POST_NOTIFICATIONS denied at
        // an awkward moment). We log via DiagnosticLog so the next bug
        // report captures the cause, then [stopSelf] — the service dies
        // silently instead of crashing the process. The next
        // user-initiated app launch will start the service fresh from
        // an Activity context that IS foreground-eligible.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    KhordNotifications.SERVICE_NOTIFICATION_ID,
                    notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(KhordNotifications.SERVICE_NOTIFICATION_ID, notif)
            }
        } catch (e: Exception) {
            DiagnosticLog.log(
                "Khord",
                "PushService: startForeground denied " +
                    "(${e::class.simpleName}: ${e.message}); stopping self. " +
                    "Will retry on next activity-driven start.",
            )
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_REFRESH -> refreshSubscriptions()
            ACTION_STOP -> {
                stopSelfClean()
                return START_NOT_STICKY
            }
            else -> refreshSubscriptions()
        }
        // NOT_STICKY despite this being a long-running listener: on
        // Android 12+ a system-initiated restart of a foreground
        // service does NOT carry foreground-start grant, so when
        // Android reaps us under memory pressure and tries to bring
        // us back via START_STICKY, the next onCreate hits
        // ForegroundServiceStartNotAllowedException and crashes
        // (see issue #9). Returning NOT_STICKY tells Android not to
        // bother — the service just stays dead until the next
        // user-initiated Activity start fires
        // PushServiceController.start(), which is guaranteed to be
        // foreground-eligible. The user impact is "no push
        // notifications between memory kills and the next app open",
        // which is the best we can do without WorkManager-shaped
        // periodic re-arming.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stateMirrorJob?.cancel()
        scope.launch {
            try { listener?.stop() } catch (_: Throwable) { /* best effort */ }
            scope.cancel()
        }
        AppContainer.pushConnected.value = emptySet()
        super.onDestroy()
    }

    // ── internals ─────────────────────────────────────────────────────────

    private fun refreshSubscriptions() {
        val messaging = AppContainer.messaging ?: return
        val http = AppContainer.http ?: return
        val subs = messaging.pushSubscriptions()

        val existing = listener
        if (existing == null) {
            val l = PushSignalListener(
                http = http,
                onPush = { fp -> handlePush(fp) },
            )
            l.start(subs)
            // Mirror the listener's connected-fingerprints set into
            // AppContainer so ViewModels can observe.
            stateMirrorJob = l.connectedFingerprints
                .onEach { AppContainer.pushConnected.value = it }
                .launchIn(scope)
            listener = l
        } else {
            scope.launch {
                runCatching { existing.updateSubscriptions(subs) }
                    .onFailure { Log.w(TAG, "updateSubscriptions failed", it) }
            }
        }
    }

    private fun handlePush(contactFingerprint: String) {
        scope.launch(Dispatchers.IO) {
            val messaging = AppContainer.messaging ?: return@launch
            val contact = messaging.contacts().firstOrNull {
                it.contactFingerprint == contactFingerprint
            } ?: return@launch

            val plaintexts = runCatching {
                messaging.receiveMessages(contact)
            }.getOrElse {
                Log.w(TAG, "receiveMessages threw for $contactFingerprint", it)
                AppContainer.recordReceiveFailure(contactFingerprint)
                return@launch
            }
            // Reset the dead-contact streak — the drain worked even if it
            // returned zero new messages.
            AppContainer.recordReceiveSuccess(contactFingerprint)
            if (plaintexts.isEmpty()) return@launch

            val displayName = messaging.contactDisplayName(contactFingerprint)
                ?: shortFp(contactFingerprint)
            val preview = plaintexts.last() // latest message
            val (notifId, notif) = KhordNotifications.messageNotification(
                context = this@KhordPushService,
                contactFingerprint = contactFingerprint,
                displayName = displayName,
                preview = preview,
            )

            val nmc = NotificationManagerCompat.from(this@KhordPushService)
            try {
                if (nmc.areNotificationsEnabled()) {
                    nmc.notify(notifId, notif)
                }
            } catch (se: SecurityException) {
                // POST_NOTIFICATIONS denied on Android 13+ — swallow; service
                // keeps fetching, the user just won't see a banner.
                Log.w(TAG, "POST_NOTIFICATIONS denied; skipping banner", se)
            }
        }
    }

    private fun stopSelfClean() {
        scope.launch {
            stateMirrorJob?.cancel()
            try { listener?.stop() } catch (_: Throwable) { /* best effort */ }
            listener = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun shortFp(fp: String): String =
        if (fp.length > 16) fp.take(8) + "…" + fp.takeLast(8) else fp

    companion object {
        const val ACTION_REFRESH = "org.khord.android.push.REFRESH"
        const val ACTION_STOP = "org.khord.android.push.STOP"
        private const val TAG = "KhordPushService"

        fun startIntent(context: Context): Intent =
            Intent(context, KhordPushService::class.java).setAction(ACTION_REFRESH)

        fun stopIntent(context: Context): Intent =
            Intent(context, KhordPushService::class.java).setAction(ACTION_STOP)
    }
}
