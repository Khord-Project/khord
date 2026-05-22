package org.khord.android.push

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.khord.android.MainActivity
import org.khord.android.R

/**
 * Khord notification surface.
 *
 *  - "Khord Messages" channel hosts both the foreground-service persistent
 *    notification ("Khord · Connected") and the per-contact message
 *    arrivals. Keeping them on one channel means the user has one
 *    enable/disable toggle in Android settings.
 *  - Per-message notifications use NotificationCompat group keys
 *    (`group_{fingerprint}`) so multiple arrivals from the same contact
 *    coalesce in the shade instead of each occupying their own row.
 *  - Tapping a message notification deep-links to MainActivity with an
 *    extra carrying the contact's fingerprint; MainActivity reads it on
 *    intent receipt and navigates to chat/{fingerprint}.
 */
object KhordNotifications {

    const val CHANNEL_ID = "khord_messages"
    const val SERVICE_NOTIFICATION_ID = 1

    /** Extra carrying the target chat fingerprint on a deep-link intent. */
    const val EXTRA_OPEN_CHAT_FINGERPRINT = "khord_open_chat_fp"

    /**
     * Create / update the notification channel. Safe to call multiple times.
     * Android coalesces repeated `createNotificationChannel` calls to a no-op
     * if the channel already exists with the same id.
     */
    fun ensureChannel(context: Context) {
        val nm = ContextCompat.getSystemService(context, NotificationManager::class.java)
            ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notif_channel_description)
            setShowBadge(true)
        }
        nm.createNotificationChannel(channel)
    }

    /** Foreground-service persistent notification ("Khord · Connected"). */
    fun foregroundServiceNotification(context: Context): Notification {
        // PendingIntent lands the user on the contact list — no specific chat.
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            context,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(context.getString(R.string.notif_service_title))
            .setContentText(context.getString(R.string.notif_service_text))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pi)
            .build()
    }

    /**
     * Per-message notification.
     *
     *   - `contactFingerprint` becomes part of the group key + the deep-link extra.
     *   - `notificationId` should be stable per (contact, message) so updates
     *     within the same conversation don't pile up — we use a hash of the
     *     fingerprint to keep the ID stable per-contact (one notification
     *     row per contact, updated on each new message).
     */
    fun messageNotification(
        context: Context,
        contactFingerprint: String,
        displayName: String,
        preview: String,
    ): Pair<Int, Notification> {
        val deepLink = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_CHAT_FINGERPRINT, contactFingerprint)
        }
        val pi = PendingIntent.getActivity(
            context,
            contactFingerprint.hashCode(),
            deepLink,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val truncated = if (preview.length > 60) preview.take(60) + "…" else preview
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentTitle(displayName)
            .setContentText(truncated)
            .setStyle(NotificationCompat.BigTextStyle().bigText(truncated))
            .setAutoCancel(true)
            .setGroup("group_$contactFingerprint")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(pi)
            .build()

        return notificationIdFor(contactFingerprint) to notif
    }

    /**
     * Deterministic per-contact notification ID. Stable hash of the
     * fingerprint, biased away from [SERVICE_NOTIFICATION_ID] (= 1)
     * so a per-contact banner can never collide with the foreground
     * service's persistent notification. Exposed so ChatScreen can
     * cancel the banner when the user opens the chat manually (i.e.
     * not via the notification tap, which auto-cancels on its own
     * via setAutoCancel(true)).
     */
    fun notificationIdFor(contactFingerprint: String): Int =
        (contactFingerprint.hashCode() and 0x7fffffff).coerceAtLeast(2)
}
