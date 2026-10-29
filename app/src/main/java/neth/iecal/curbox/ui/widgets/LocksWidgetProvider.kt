package neth.iecal.curbox.ui.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.edit
import neth.iecal.curbox.R
import neth.iecal.curbox.blockers.AppBlocker
import neth.iecal.curbox.ui.activity.FragmentActivity

/**
 * Shows each app group whose lock after unlock is running, with a live countdown. The blocker
 * sends the locks here whenever they change ([ACTION_LOCKS_CHANGED]); the countdowns then tick
 * on their own, so the widget never needs to wake the phone.
 */
class LocksWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        render(context)
        // Ask the blocker for the current locks, in case they changed while no widget listened.
        context.sendBroadcast(Intent(AppBlocker.INTENT_ACTION_REFRESH_LOCKS_WIDGET).setPackage(context.packageName))
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_LOCKS_CHANGED) return
        try {
            val names = intent.getStringArrayExtra(EXTRA_NAMES) ?: emptyArray()
            val endsAt = intent.getLongArrayExtra(EXTRA_ENDS_AT) ?: LongArray(0)
            prefs(context).edit {
                putString(KEY_NAMES, names.joinToString(SEPARATOR))
                putString(KEY_ENDS_AT, endsAt.joinToString(SEPARATOR))
            }
            render(context)
        } catch (e: Exception) {
            Log.e(TAG, "Could not update the locks widget", e)
        }
    }

    private fun render(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, LocksWidgetProvider::class.java))
        if (ids.isEmpty()) return

        val now = System.currentTimeMillis()
        val stored = prefs(context)
        val names = stored.getString(KEY_NAMES, "").orEmpty().split(SEPARATOR)
        val ends = stored.getString(KEY_ENDS_AT, "").orEmpty().split(SEPARATOR).map { it.toLongOrNull() ?: 0L }
        val locks = names.zip(ends).filter { (name, end) -> name.isNotEmpty() && end > now }.sortedBy { it.second }

        val views = RemoteViews(context.packageName, R.layout.widget_locks)
        views.setViewVisibility(R.id.locks_empty, if (locks.isEmpty()) View.VISIBLE else View.GONE)
        ROWS.forEachIndexed { index, (rowId, nameId, timerId) ->
            val lock = locks.getOrNull(index)
            views.setViewVisibility(rowId, if (lock == null) View.GONE else View.VISIBLE)
            if (lock != null) {
                views.setTextViewText(nameId, lock.first)
                views.setChronometer(timerId, SystemClock.elapsedRealtime() + (lock.second - now), null, true)
                views.setChronometerCountDown(timerId, true)
            }
        }
        val more = locks.size - ROWS.size
        views.setViewVisibility(R.id.locks_more, if (more > 0) View.VISIBLE else View.GONE)
        if (more > 0) views.setTextViewText(R.id.locks_more, context.getString(R.string.locks_widget_more, more))

        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, FragmentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.locks_widget_root, open)
        manager.updateAppWidget(ids, views)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    companion object {
        const val ACTION_LOCKS_CHANGED = "app.boundary.blocker.widget.LOCKS_CHANGED"
        const val EXTRA_NAMES = "names"
        const val EXTRA_ENDS_AT = "ends_at"

        private const val TAG = "LocksWidget"
        private const val PREFS = "locks_widget"
        private const val KEY_NAMES = "names"
        private const val KEY_ENDS_AT = "ends_at"
        private const val SEPARATOR = "\u001F"
        private val ROWS = listOf(
            Triple(R.id.lock_row_1, R.id.lock_name_1, R.id.lock_timer_1),
            Triple(R.id.lock_row_2, R.id.lock_name_2, R.id.lock_timer_2),
            Triple(R.id.lock_row_3, R.id.lock_name_3, R.id.lock_timer_3)
        )
    }
}
