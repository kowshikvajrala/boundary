package neth.iecal.curbox.blockers

import android.content.Context
import androidx.core.content.edit
import neth.iecal.curbox.R
import neth.iecal.curbox.utils.DailyCounters
import neth.iecal.curbox.utils.SettingsChangeDelayUtils
import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers when each group's unlock ran out, so the group can stay fully locked for its
 * "lock after unlock" time. The end time comes from the group's current config, so turning the
 * lock off or shorter takes effect at once. Saved to disk so a service restart keeps the lock.
 */
class UnlockLocks(context: Context, prefsName: String) {
    private val appContext = context.applicationContext
    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    private val unlockEndedAt = ConcurrentHashMap<String, Long>()

    init {
        prefs.all.forEach { (id, value) -> (value as? Long)?.let { unlockEndedAt[id] = it } }
    }

    /** Pass [lockAfterMs] so a lock that starts now is counted for the Summary page. */
    fun unlockEnded(id: String, endedAtMs: Long, lockAfterMs: Long = 0L) {
        val isNewEnd = unlockEndedAt[id] != endedAtMs
        unlockEndedAt[id] = endedAtMs
        prefs.edit { putLong(id, endedAtMs) }
        if (isNewEnd && lockEndsAt(id, lockAfterMs) > 0L) DailyCounters.increment(appContext, DailyCounters.Kind.LOCKS)
    }

    /** When the lock ends, or 0 when [id] is not locked right now. */
    fun lockEndsAt(id: String, lockAfterMs: Long, now: Long = System.currentTimeMillis()): Long {
        if (lockAfterMs <= 0L) return 0L
        val endedAt = unlockEndedAt[id] ?: return 0L
        val endsAt = if (lockAfterMs > Long.MAX_VALUE - endedAt) Long.MAX_VALUE else endedAt + lockAfterMs
        return if (endsAt > now) endsAt else 0L
    }

    companion object {
        fun pillText(context: Context, lockEndsAt: Long): String = context.getString(
            R.string.locked_after_unlock_pill,
            SettingsChangeDelayUtils.formatRemaining(context, lockEndsAt - System.currentTimeMillis())
        )
    }
}
