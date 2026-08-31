package com.sih.itantra.audio

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.getSystemService

/**
 * Do Not Disturb bypass for the ALERT path.
 *
 * Two different things are often confused here, so to be precise about what this app can and
 * cannot do:
 *
 *  - **Alarm-usage audio already survives ordinary DND.** [PlaybackProfile.ALERT] declares
 *    `USAGE_ALARM`, and Android's "Priority only" and "Alarms only" modes both let alarms
 *    through. No permission is needed for that, and it covers the common case.
 *  - **Total Silence mode, and raising the alarm volume while DND is on, need Notification
 *    Policy access.** That is a special access the user grants in Settings; an app cannot
 *    request it with a runtime permission dialog. All this object can do is report whether it
 *    has been granted and hand back the Intent that opens the right Settings page.
 *
 * The demo should be run with this granted, and the UI should say so plainly when it isn't,
 * rather than quietly failing to sound an emergency alert.
 */
object DoNotDisturbAccess {

    fun isGranted(context: Context): Boolean =
        context.getSystemService<NotificationManager>()?.isNotificationPolicyAccessGranted == true

    /** Whether DND is currently filtering anything at all. */
    fun isActive(context: Context): Boolean {
        val filter = context.getSystemService<NotificationManager>()?.currentInterruptionFilter
            ?: return false
        return filter != NotificationManager.INTERRUPTION_FILTER_ALL &&
            filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    }

    /** The Settings screen where the user can grant policy access. Launch, never assume. */
    fun settingsIntent(): Intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
}
