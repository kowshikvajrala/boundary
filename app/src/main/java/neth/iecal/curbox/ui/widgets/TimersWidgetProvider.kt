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
import neth.iecal.curbox.utils.SettingsChangeDelayUtils

/**
 * A home screen list of named countdowns, fed by the blockers through WidgetTimers. The
 * countdowns tick on their own, so the widget never needs to wake the phone.
 */
abstract class TimersWidgetProvider : AppWidgetProvider() {

    protected abstract val titleRes: Int
    protected abstract val emptyRes: Int
    protected abstract val prefsName: String

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        render(context)
        // Ask the blocker for the current timers, in case they changed while no widget listened.
        context.sendBroadcast(Intent(AppBlocker.INTENT_ACTION_REFRESH_WIDGETS).setPackage(context.packageName))
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_TIMERS_CHANGED) return
        try {
            val names = intent.getStringArrayExtra(EXTRA_NAMES) ?: emptyArray()
            val endsAt = intent.getLongArrayExtra(EXTRA_ENDS_AT) ?: LongArray(0)
            context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit {
                putString(KEY_NAMES, names.joinToString(SEPARATOR))
                putString(KEY_ENDS_AT, endsAt.joinToString(SEPARATOR))
            }
            render(context)
        } catch (e: Exception) {
            Log.e(TAG, "Could not update the widget", e)
        }
    }

    private fun render(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, javaClass))
        if (ids.isEmpty()) return

        val now = System.currentTimeMillis()
        val stored = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val names = stored.getString(KEY_NAMES, "").orEmpty().split(SEPARATOR)
        val ends = stored.getString(KEY_ENDS_AT, "").orEmpty().split(SEPARATOR).map { it.toLongOrNull() ?: 0L }
        val timers = names.zip(ends)
            .filter { (name, end) -> name.isNotEmpty() && (end < 0L || end > now) }
            // Running timers first, soonest end at the top; frozen ones after.
            .sortedWith(compareBy({ it.second < 0L }, { if (it.second < 0L) -it.second else it.second }))

        val views = RemoteViews(context.packageName, R.layout.widget_locks)
        views.setTextViewText(R.id.timers_title, context.getString(titleRes))
        views.setTextViewText(R.id.locks_empty, context.getString(emptyRes))
        views.setViewVisibility(R.id.locks_empty, if (timers.isEmpty()) View.VISIBLE else View.GONE)
        ROWS.forEachIndexed { index, row ->
            val timer = timers.getOrNull(index)
            views.setViewVisibility(row.row, if (timer == null) View.GONE else View.VISIBLE)
            if (timer == null) return@forEachIndexed
            views.setTextViewText(row.name, timer.first)
            val frozen = timer.second < 0L
            views.setViewVisibility(row.timer, if (frozen) View.GONE else View.VISIBLE)
            views.setViewVisibility(row.frozen, if (frozen) View.VISIBLE else View.GONE)
            if (frozen) {
                views.setTextViewText(
                    row.frozen,
                    context.getString(R.string.unlocks_widget_frozen, SettingsChangeDelayUtils.formatRemaining(context, -timer.second))
                )
            } else {
                views.setChronometer(row.timer, SystemClock.elapsedRealtime() + (timer.second - now), null, true)
                views.setChronometerCountDown(row.timer, true)
            }
        }
        val more = timers.size - ROWS.size
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

    private data class Row(val row: Int, val name: Int, val timer: Int, val frozen: Int)

    companion object {
        const val ACTION_TIMERS_CHANGED = "app.boundary.blocker.widget.TIMERS_CHANGED"
        const val EXTRA_NAMES = "names"
        const val EXTRA_ENDS_AT = "ends_at"

        private const val TAG = "TimersWidget"
        private const val KEY_NAMES = "names"
        private const val KEY_ENDS_AT = "ends_at"
        private const val SEPARATOR = "\u001F"
        private val ROWS = listOf(
            Row(R.id.lock_row_1, R.id.lock_name_1, R.id.lock_timer_1, R.id.lock_frozen_1),
            Row(R.id.lock_row_2, R.id.lock_name_2, R.id.lock_timer_2, R.id.lock_frozen_2),
            Row(R.id.lock_row_3, R.id.lock_name_3, R.id.lock_timer_3, R.id.lock_frozen_3)
        )
    }
}
