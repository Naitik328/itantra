package com.sih.itantra.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.getSystemService
import com.sih.itantra.ITantraApp
import com.sih.itantra.MainActivity
import com.sih.itantra.R

/**
 * Foreground service that keeps voice capture alive when the app is not on screen.
 *
 * Without this the microphone is silenced the moment the Activity stops — Android has blocked
 * background mic access since API 28 — which would make the app useless for its actual job:
 * relaying a message while the phone is in a pocket. The notification is not decoration, it is
 * the price of admission, so it earns its space by carrying a working Stop action.
 *
 * The service owns no state of its own; the session it fronts lives in [ITantraApp]. When the
 * transport work in section D lands, the Wi-Fi Direct link moves in here alongside it so a
 * connection survives screen-off too.
 */
class VoiceSessionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            (application as? ITantraApp)?.voiceSession?.stopCapture()
            stopSelf()
            return START_NOT_STICKY
        }

        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                0
            },
        )

        // Deliberately not sticky: if the process is killed, silently reopening the microphone
        // without the user asking would be indefensible.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, VoiceSessionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.voice_service_title))
            .setContentText(getString(R.string.voice_service_text))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .addAction(0, getString(R.string.voice_service_stop), stop)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService<NotificationManager>() ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        // IMPORTANCE_LOW: silent and collapsed. The channel exists to satisfy the foreground
        // service requirement, not to interrupt anyone — ALERT frames get their own path.
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.voice_service_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.voice_service_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "voice_session"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP = "com.sih.itantra.action.STOP_CAPTURE"

        fun start(context: Context) {
            val intent = Intent(context, VoiceSessionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VoiceSessionService::class.java))
        }
    }
}
