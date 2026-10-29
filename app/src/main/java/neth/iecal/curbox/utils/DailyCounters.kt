package neth.iecal.curbox.utils

import android.content.Context
import androidx.core.content.edit
import java.time.LocalDate

/**
 * Counts unlocks (Proceed on the wait screen, a QR or NFC unlock) and locks after unlock per
 * day, for the Summary page. Kept in a small preferences file, not Room, because a Room change
 * would wipe usage history. Written by the blocker process and read by the app, so reads use
 * MODE_MULTI_PROCESS to see the latest file.
 */
object DailyCounters {
    enum class Kind(val key: String) { UNLOCKS("unlocks"), LOCKS("locks") }

    private const val PREFS = "daily_counters"
    private const val KEEP_DAYS = 120L

    fun increment(context: Context, kind: Kind) {
        synchronized(this) {
            val prefs = prefs(context)
            val today = LocalDate.now().toEpochDay()
            val key = "${kind.key}_$today"
            prefs.edit(commit = true) {
                putInt(key, prefs.getInt(key, 0) + 1)
                // Drop counts older than KEEP_DAYS so the file never grows.
                prefs.all.keys
                    .filter { (it.substringAfter('_').toLongOrNull() ?: today) < today - KEEP_DAYS }
                    .forEach { remove(it) }
            }
        }
    }

    fun count(context: Context, kind: Kind, day: LocalDate): Int =
        prefs(context).getInt("${kind.key}_${day.toEpochDay()}", 0)

    @Suppress("DEPRECATION")
    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_MULTI_PROCESS)
}
