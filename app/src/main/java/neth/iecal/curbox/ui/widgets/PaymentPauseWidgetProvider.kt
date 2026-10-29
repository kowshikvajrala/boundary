package neth.iecal.curbox.ui.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import neth.iecal.curbox.R
import neth.iecal.curbox.ui.activity.PaymentPauseChooserActivity

/** A one tap button that asks which payment app to open, then starts a payment pause. */
class PaymentPauseWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val views = RemoteViews(context.packageName, R.layout.widget_payment_pause)
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, PaymentPauseChooserActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.payment_widget_root, open)
        appWidgetManager.updateAppWidget(appWidgetIds, views)
    }
}
