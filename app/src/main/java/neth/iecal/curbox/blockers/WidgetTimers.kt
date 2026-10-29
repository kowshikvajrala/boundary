package neth.iecal.curbox.blockers

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import neth.iecal.curbox.ui.widgets.LocksWidgetProvider
import neth.iecal.curbox.ui.widgets.TimersWidgetProvider
import neth.iecal.curbox.ui.widgets.UnlocksWidgetProvider
import java.util.concurrent.ConcurrentHashMap

/**
 * Collects the lock and unlock timers of every blocker in the service process and sends them
 * to the home screen widgets, only when something changed. The widgets count down on their own,
 * so this never wakes the phone; it only redraws when a timer ends.
 *
 * A timer value is the wall clock time it ends. A negative value is a frozen unlock: minus the
 * time it still has left.
 */
object WidgetTimers {
    enum class Kind { LOCKS, UNLOCKS }

    private val bySource = ConcurrentHashMap<String, Map<String, Long>>()
    private val lastSent = ConcurrentHashMap<Kind, Map<String, Long>>()
    private val handler = Handler(Looper.getMainLooper())
    private val expiryChecks = ConcurrentHashMap<Kind, Runnable>()

    fun publish(context: Context, kind: Kind, source: String, timers: Map<String, Long>) {
        bySource["${kind.name}/$source"] = timers
        send(context.applicationContext, kind, force = false)
    }

    /** Sends everything again, for a widget that was just added. */
    fun resendAll(context: Context) {
        Kind.values().forEach { send(context.applicationContext, it, force = true) }
    }

    @Synchronized
    private fun send(context: Context, kind: Kind, force: Boolean) {
        val now = System.currentTimeMillis()
        val merged = sortedMapOf<String, Long>()
        bySource.filterKeys { it.startsWith(kind.name + "/") }.values.forEach { timers ->
            timers.forEach { (name, endsAt) -> if (endsAt < 0L || endsAt > now) merged[name] = endsAt }
        }
        scheduleExpiry(context, kind, merged.values.filter { it > now }.minOrNull(), now)
        if (!force && merged == lastSent[kind]) return
        lastSent[kind] = merged
        val provider = if (kind == Kind.LOCKS) LocksWidgetProvider::class.java else UnlocksWidgetProvider::class.java
        try {
            context.sendBroadcast(
                Intent(TimersWidgetProvider.ACTION_TIMERS_CHANGED)
                    .setClass(context, provider)
                    .putExtra(TimersWidgetProvider.EXTRA_NAMES, merged.keys.toTypedArray())
                    .putExtra(TimersWidgetProvider.EXTRA_ENDS_AT, merged.values.toLongArray())
            )
        } catch (e: Exception) {
            Log.e("WidgetTimers", "Could not update the widget", e)
        }
    }

    /** Redraws the moment the next timer ends, so a widget never shows a finished one. */
    private fun scheduleExpiry(context: Context, kind: Kind, nextEnd: Long?, now: Long) {
        expiryChecks.remove(kind)?.let(handler::removeCallbacks)
        nextEnd ?: return
        val check = Runnable {
            expiryChecks.remove(kind)
            send(context, kind, force = false)
        }
        expiryChecks[kind] = check
        handler.postDelayed(check, nextEnd - now + 500L)
    }
}
