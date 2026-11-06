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
 * to the home screen widgets and the unlock notification ([UnlockNotification]), only when
 * something changed. The widgets count down on their own,
 * so this never wakes the phone; it only redraws when a timer ends.
 *
 * A timer value is the wall clock time it ends. A negative value is a frozen unlock: minus the
 * time it still has left.
 */
object WidgetTimers {
    enum class Kind { LOCKS, UNLOCKS }

    private val bySource = ConcurrentHashMap<String, Map<String, Long>>()
    // Per blocker, the timer names that belong to what is on screen now.
    private val inFrontBySource = ConcurrentHashMap<String, Set<String>>()
    @Volatile private var lastInFront: Set<String> = emptySet()
    private val lastSent = ConcurrentHashMap<Kind, Map<String, Long>>()
    private val handler = Handler(Looper.getMainLooper())
    private val expiryChecks = ConcurrentHashMap<Kind, Runnable>()

    fun publish(context: Context, kind: Kind, source: String, timers: Map<String, Long>) {
        bySource["${kind.name}/$source"] = timers
        send(context.applicationContext, kind, force = false)
    }

    /** The unlock notification counts down and shows its status bar chip only for these. */
    fun setInFront(context: Context, source: String, names: Set<String>) {
        if (inFrontBySource[source] == names) return
        inFrontBySource[source] = names
        send(context.applicationContext, Kind.UNLOCKS, force = false)
    }

    /** True while an app group, keyword group or short video unlock of what is on screen runs. */
    fun isUnlockedInFront(now: Long = System.currentTimeMillis()): Boolean {
        val inFront = inFrontBySource.values.flatten()
        if (inFront.isEmpty()) return false
        return bySource.entries.any { (key, timers) ->
            key.startsWith(Kind.UNLOCKS.name + "/") && inFront.any { (timers[it] ?: 0L) > now }
        }
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
        val inFront = if (kind == Kind.UNLOCKS) inFrontBySource.values.flatten().toSet() else emptySet()
        val timersChanged = force || merged != lastSent[kind]
        val frontChanged = kind == Kind.UNLOCKS && inFront != lastInFront
        if (!timersChanged && !frontChanged) return
        if (kind == Kind.UNLOCKS) {
            lastInFront = inFront
            updateNotification(context, merged, inFront)
        }
        // When only what is on screen changed, the widgets need no redraw.
        if (!timersChanged) return
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

    private fun updateNotification(context: Context, timers: Map<String, Long>, inFront: Set<String>) {
        try {
            UnlockNotification.update(context, timers, inFront)
        } catch (e: Exception) {
            Log.e("WidgetTimers", "Could not update the unlock notification", e)
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
