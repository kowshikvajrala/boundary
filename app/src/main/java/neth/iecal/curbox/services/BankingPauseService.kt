package neth.iecal.curbox.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import neth.iecal.curbox.R
import neth.iecal.curbox.utils.BankingPause
import neth.iecal.curbox.utils.DataStoreManager

/**
 * Runs in the main process while the blocker service is off for a banking app. It watches which
 * app is in front and turns the blocker back on as soon as the user leaves the banking apps.
 */
class BankingPauseService : Service() {

    companion object {
        private const val TAG = "BankingPauseService"
        private const val EXTRA_PACKAGE = "package_name"
        private const val CHANNEL_ID = "banking_pause_channel"
        private const val NOTIFICATION_ID = 7801
        private const val RESUME_NOTIFICATION_ID = 7802
        private const val POLL_MS = 1_000L
        private const val EVENT_WINDOW_MS = 10_000L

        // System screens that can sit on top of a banking app without the user leaving it.
        private val PASS_THROUGH_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.google.android.gms"
        )

        fun start(context: Context, bankingPackage: String) {
            val intent = Intent(context, BankingPauseService::class.java)
                .putExtra(EXTRA_PACKAGE, bankingPackage)
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Could not start banking pause", e)
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watchJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val bankingPackage = intent?.getStringExtra(EXTRA_PACKAGE)
        startAsForeground(bankingPackage)
        if (watchJob?.isActive != true) {
            BankingPause.markPaused(this, System.currentTimeMillis() + BankingPause.MAX_PAUSE_MS)
            watchJob = scope.launch { watch(bankingPackage) }
        }
        return START_NOT_STICKY
    }

    private suspend fun watch(startPackage: String?) {
        val startedAt = System.currentTimeMillis()
        var foreground = startPackage
        val dataStore = DataStoreManager(this)
        try {
            while (scope.isActive) {
                val bankingPackages = dataStore.settings.first().bankingPauseConfig
                    .takeIf { it.isEnabled }?.packages?.toSet().orEmpty()

                latestResumedPackage()?.let { foreground = it }
                val stillBanking = foreground != null &&
                    (foreground in bankingPackages || foreground in PASS_THROUGH_PACKAGES)
                val timedOut = System.currentTimeMillis() - startedAt > BankingPause.MAX_PAUSE_MS

                if (!stillBanking || timedOut) {
                    resumeBlocking()
                    return
                }
                delay(POLL_MS)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Banking pause watcher failed", e)
            resumeBlocking()
        }
    }

    private fun latestResumedPackage(): String? {
        val usageStats = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = usageStats.queryEvents(now - EVENT_WINDOW_MS, now)
        val event = UsageEvents.Event()
        var latest: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED &&
                event.packageName != packageName
            ) {
                latest = event.packageName
            }
        }
        return latest
    }

    private fun resumeBlocking() {
        BankingPause.clearPaused(this)
        val restored = BankingPause.isServiceEnabled(this) || BankingPause.setServiceEnabled(this, true)
        if (!restored) postTurnBackOnReminder()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startAsForeground(bankingPackage: String?) {
        ensureChannel()
        val appName = bankingPackage?.let {
            runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(it, 0)).toString() }.getOrNull()
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.icon)
            .setContentTitle(getString(R.string.banking_pause_active_title))
            .setContentText(
                if (appName != null) getString(R.string.banking_pause_active_text, appName)
                else getString(R.string.banking_pause_active_text_generic)
            )
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun postTurnBackOnReminder() {
        ensureChannel()
        val openSettings = PendingIntent.getActivity(
            this,
            0,
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.icon)
            .setContentTitle(getString(R.string.banking_pause_turn_on_title))
            .setContentText(getString(R.string.banking_pause_turn_on_text))
            .setContentIntent(openSettings)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        getSystemService(NotificationManager::class.java).notify(RESUME_NOTIFICATION_ID, notification)
    }

    private fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.banking_pause_title),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply { setSound(null, null) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
