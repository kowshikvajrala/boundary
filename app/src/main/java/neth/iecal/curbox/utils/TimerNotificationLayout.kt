package neth.iecal.curbox.utils

import android.content.Context
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import neth.iecal.curbox.R

/**
 * The look shared by every timer notification: a short label over a large countdown, and when
 * expanded a detail line and one row per timer. Android keeps its own header with the icon above.
 */
object TimerNotificationLayout {

    /** One timer in the expanded list. It counts down to [endsAtMs], or shows [stillText]. */
    class Row(val label: String, val endsAtMs: Long?, val stillText: String? = null)

    /**
     * Puts the layout on [builder]. The countdown runs down to the wall clock time [endsAtMs];
     * when that is null, [stillText] shows in its place.
     */
    fun apply(
        builder: NotificationCompat.Builder,
        context: Context,
        label: String,
        endsAtMs: Long?,
        stillText: String? = null,
        detail: String? = null,
        rows: List<Row> = emptyList()
    ): NotificationCompat.Builder {
        val collapsed = RemoteViews(context.packageName, R.layout.notification_timer)
        collapsed.setTextViewText(R.id.timer_label, label)
        setClock(collapsed, R.id.timer_clock, R.id.timer_still, endsAtMs, stillText)

        val expanded = if (detail != null || rows.isNotEmpty()) {
            RemoteViews(context.packageName, R.layout.notification_timer_expanded).apply {
                setTextViewText(R.id.timer_label, label)
                setClock(this, R.id.timer_clock, R.id.timer_still, endsAtMs, stillText)
                if (detail != null) {
                    setTextViewText(R.id.timer_detail, detail)
                    setViewVisibility(R.id.timer_detail, View.VISIBLE)
                }
                rows.forEach { row ->
                    val rowViews = RemoteViews(context.packageName, R.layout.notification_timer_row)
                    rowViews.setTextViewText(R.id.row_label, row.label)
                    setClock(rowViews, R.id.row_clock, R.id.row_still, row.endsAtMs, row.stillText)
                    addView(R.id.timer_rows, rowViews)
                }
            }
        } else {
            null
        }

        // The clock is in the layout, so the small one in Android's header would only repeat it.
        return builder
            .setShowWhen(false)
            .setUsesChronometer(false)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(collapsed)
            .setCustomBigContentView(expanded)
    }

    private fun setClock(views: RemoteViews, clockId: Int, stillId: Int, endsAtMs: Long?, stillText: String?) {
        if (endsAtMs != null) {
            // A Chronometer counts against the time since boot, not the wall clock.
            val leftMs = (endsAtMs - System.currentTimeMillis()).coerceAtLeast(0L)
            views.setChronometer(clockId, SystemClock.elapsedRealtime() + leftMs, null, true)
            views.setChronometerCountDown(clockId, true)
            views.setViewVisibility(clockId, View.VISIBLE)
            views.setViewVisibility(stillId, View.GONE)
        } else {
            views.setTextViewText(stillId, stillText.orEmpty())
            views.setViewVisibility(stillId, View.VISIBLE)
            views.setViewVisibility(clockId, View.GONE)
        }
    }
}
