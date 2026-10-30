package neth.iecal.curbox.blockers

import android.app.NotificationManager
import android.content.Context
import neth.iecal.curbox.R
import neth.iecal.curbox.utils.TimerNotification

/**
 * One notification for every running unlock: app groups, keyword groups and short videos. It
 * counts down to the unlock that ends first (the status bar chip on Android 16) and lists all of
 * them, so several blockers never post separate notifications that look the same.
 *
 * Fed by [WidgetTimers] with the merged unlock timers: wall clock end times, or minus the time
 * left for a paused unlock.
 */
object UnlockNotification {
    private const val NOTIFICATION_ID = 1005
    // Each blocker used to post its own countdown under these ids.
    private val OLD_NOTIFICATION_IDS = listOf(1001, 1003, 1004)

    private var timer: TimerNotification? = null

    @Synchronized
    fun update(context: Context, timers: Map<String, Long>) {
        val now = System.currentTimeMillis()
        val running = timers.filterValues { it > now }
        val nextEnd = running.values.minOrNull()
        val notification = timer ?: create(context).also { timer = it }
        if (nextEnd == null) {
            notification.stopTimer()
            return
        }
        notification.startTimer(
            totalMillis = nextEnd - now,
            timerId = "unlocks:" + timers.entries.joinToString(",") { "${it.key}=${it.value}" },
            title = context.getString(R.string.notification_unlocked_title),
            breakdown = timers
        )
    }

    @Synchronized
    fun stop() {
        timer?.release()
        timer = null
    }

    private fun create(context: Context): TimerNotification {
        val manager = context.getSystemService(NotificationManager::class.java)
        OLD_NOTIFICATION_IDS.forEach { runCatching { manager.cancel(it) } }
        return TimerNotification(context.applicationContext, NOTIFICATION_ID)
    }
}
