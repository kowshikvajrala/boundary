package neth.iecal.curbox.services

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import neth.iecal.curbox.blockers.AppBlocker

/**
 * Hides notifications from apps whose lock after unlock is running. They are snoozed until the
 * lock ends, not deleted, so they come back by themselves afterwards.
 *
 * Runs in the blocker's process so it can read [AppBlocker.lockedUntil] directly.
 */
class LockedAppNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        instance = this
        snoozeLockedNow()
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        snoozeIfLocked(sbn, System.currentTimeMillis())
    }

    /** Snoozes every notification already showing from an app that is locked right now. */
    fun snoozeLockedNow() {
        try {
            val now = System.currentTimeMillis()
            activeNotifications?.forEach { snoozeIfLocked(it, now) }
        } catch (e: Exception) {
            Log.e(TAG, "could not hide notifications", e)
        }
    }

    private fun snoozeIfLocked(sbn: StatusBarNotification, now: Long) {
        try {
            if (!sbn.isClearable) return
            val lockedUntil = AppBlocker.lockedUntil(sbn.packageName)
            if (lockedUntil > now) snoozeNotification(sbn.key, lockedUntil - now)
        } catch (e: Exception) {
            Log.e(TAG, "could not snooze ${sbn.packageName}", e)
        }
    }

    companion object {
        private const val TAG = "LockedAppNotifications"

        @Volatile
        var instance: LockedAppNotificationListener? = null
            private set
    }
}
