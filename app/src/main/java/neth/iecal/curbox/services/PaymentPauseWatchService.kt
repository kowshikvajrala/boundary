package neth.iecal.curbox.services

import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import neth.iecal.curbox.utils.DataStoreManager
import neth.iecal.curbox.utils.PaymentPause

/**
 * Runs only during a payment pause, while the accessibility service is off. Every second it looks
 * at which app is in front (through usage access) and ends the pause the moment a blocked app,
 * a short video app or a browser opens, so the blocker takes over at once. It also ends the pause
 * when its time is up.
 */
class PaymentPauseWatchService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var watched: Set<String> = emptySet()
    private var lastForeground: String? = null

    private val check = object : Runnable {
        override fun run() {
            try {
                PaymentPause.endIfDue(this@PaymentPauseWatchService)
                if (!PaymentPause.isActive(this@PaymentPauseWatchService)) {
                    stopSelf()
                    return
                }
                val front = foregroundPackage()
                if (front != null && front in watched) {
                    Log.i(TAG, "$front opened during a payment pause, ending it")
                    PaymentPause.end(this@PaymentPauseWatchService)
                    stopSelf()
                    return
                }
            } catch (e: Exception) {
                Log.e(TAG, "check failed", e)
            }
            handler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val endsAt = PaymentPause.endsAt(this)
        val notification = PaymentPause.buildNotification(this, endsAt)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(PaymentPause.NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(PaymentPause.NOTIFICATION_ID, notification)
        }
        if (endsAt <= 0L) {
            stopSelf()
            return START_NOT_STICKY
        }
        scope.launch {
            try {
                watched = PaymentPause.watchedPackages(
                    this@PaymentPauseWatchService,
                    DataStoreManager(applicationContext).settings.first()
                )
            } catch (e: Exception) {
                Log.e(TAG, "could not load blocked apps", e)
            }
        }
        handler.removeCallbacks(check)
        handler.post(check)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(check)
        scope.cancel()
        super.onDestroy()
    }

    /** The app most recently brought to the front, or the last one seen if nothing changed. */
    private fun foregroundPackage(): String? {
        if (!PaymentPause.hasUsageAccess(this)) return null
        val manager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = manager.queryEvents(now - LOOKBACK_MS, now)
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) lastForeground = event.packageName
        }
        return lastForeground
    }

    private companion object {
        const val TAG = "PaymentPauseWatch"
        const val CHECK_INTERVAL_MS = 1_000L
        const val LOOKBACK_MS = 10_000L
    }
}
