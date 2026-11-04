package neth.iecal.curbox.blockers

import android.app.NotificationManager
import android.content.Context
import neth.iecal.curbox.R
import neth.iecal.curbox.utils.TimerNotification

/**
 * One notification for every running unlock: app groups, keyword groups and short videos. It
 * lists all of them, so several blockers never post separate notifications that look the same.
 * It counts down the unlock of what is on screen, as a status bar chip on Android 16. With
 * nothing unlocked on screen it is a plain notification that counts down the unlock ending first.
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
    fun update(context: Context, timers: Map<String, Long>, inFront: Set<String> = emptySet()) {
        val now = System.currentTimeMillis()
        val running = timers.filterValues { it > now }
        val notification = timer ?: create(context).also { timer = it }
        val shown = pickShown(running, inFront)
        if (shown == null) {
            notification.stopTimer()
            return
        }
        val isOnScreen = shown.key in inFront
        notification.startTimer(
            totalMillis = shown.value - now,
            timerId = "unlocks:" + timers.entries.joinToString(",") { "${it.key}=${it.value}" } +
                ";shown=${shown.key};chip=$isOnScreen",
            title = if (isOnScreen) {
                context.getString(R.string.notification_unlocked_title_app, shown.key)
            } else {
                context.getString(R.string.notification_unlocked_title)
            },
            breakdown = timers,
            promoted = isOnScreen
        )
    }

    /** The unlock of what is on screen, or else the one ending first. Null when none run. */
    internal fun pickShown(running: Map<String, Long>, inFront: Set<String>): Map.Entry<String, Long>? =
        running.entries.filter { it.key in inFront }.minByOrNull { it.value }
            ?: running.entries.minByOrNull { it.value }

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
