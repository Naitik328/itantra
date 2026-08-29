package com.itantra.relay.alert

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.itantra.relay.MainActivity

/**
 * Notification plumbing for the relay: a low-key ongoing notification for the
 * foreground service, and a high-priority full-screen SOS notification that
 * takes over the screen (even from the background / lockscreen) when an alert
 * arrives. On Android 14+ the full-screen intent may be shown as a heads-up
 * banner unless the user has granted the full-screen permission.
 */
object AlertNotifier {

    const val CH_ONGOING = "itantra_relay"
    const val CH_ALERT = "itantra_alert"
    const val ONGOING_ID = 1001
    private const val ALERT_ID = 1002

    /** minSdk is 26, so notification channels always exist. Idempotent. */
    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CH_ONGOING, "Relay active", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while iTantra is listening for nearby alerts."
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "Emergency alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Full-screen SOS alerts from nearby iTantra phones."
                enableVibration(true)
                enableLights(true)
            },
        )
    }

    /** The ongoing notification the foreground service runs with. */
    fun ongoingNotification(context: Context): Notification =
        Notification.Builder(context, CH_ONGOING)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("iTantra relay active")
            .setContentText("Listening for nearby alerts")
            .setOngoing(true)
            .setContentIntent(openAppIntent(context))
            .build()

    /** Show (or refresh) the full-screen SOS alert. */
    fun showAlert(context: Context, text: String) {
        ensureChannels(context)
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val n = Notification.Builder(context, CH_ALERT)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("🚨 SOS Alert")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setPriority(Notification.PRIORITY_MAX)
            .setCategory(Notification.CATEGORY_CALL)
            .setAutoCancel(true)
            .setFullScreenIntent(openAppIntent(context), true)
            .setContentIntent(openAppIntent(context))
            .build()
        nm.notify(ALERT_ID, n)
    }

    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
