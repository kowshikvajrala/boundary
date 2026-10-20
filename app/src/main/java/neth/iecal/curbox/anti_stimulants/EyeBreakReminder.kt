package neth.iecal.curbox.anti_stimulants

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.EyeBreakConfig
import neth.iecal.curbox.services.BaseBlockingService

/**
 * The 20 20 20 rule. Counts how long the screen stays on and, after every interval, shows a
 * small countdown pill asking the user to look at something far away.
 *
 * A screen off of at least [BREAK_RESET_MS] already rests the eyes, so it restarts the count.
 */
class EyeBreakReminder {

    companion object {
        private const val BREAK_RESET_MS = 20_000L
        private const val BREAK_SECONDS = 20

        // The settings screen lives in another process, so it asks for the schedule by broadcast.
        const val ACTION_STATUS_REQUEST = "app.boundary.blocker.eye_break.STATUS_REQUEST"
        const val ACTION_STATUS = "app.boundary.blocker.eye_break.STATUS"
        /** [SystemClock.elapsedRealtime] of the next reminder, or 0 when none is scheduled. */
        const val EXTRA_NEXT_AT = "next_at_elapsed"
        /** [SystemClock.elapsedRealtime] when the running break ends, or 0 when none is running. */
        const val EXTRA_BREAK_ENDS_AT = "break_ends_at_elapsed"
    }

    private lateinit var service: BaseBlockingService
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var settingsJob: Job? = null

    // Every field below is only touched on the main thread.
    private val handler = Handler(Looper.getMainLooper())
    private var config = EyeBreakConfig()
    private var screenOnSinceMs = 0L
    private var screenOnBeforeMs = 0L
    private var screenOffAtMs = 0L
    private var secondsLeft = 0
    private var nextReminderAtMs = 0L
    private var breakEndsAtMs = 0L

    private val remindRunnable = Runnable { startBreak() }
    private val countdownRunnable = object : Runnable {
        override fun run() {
            secondsLeft--
            if (secondsLeft <= 0) {
                endBreak()
            } else {
                service.blockedPill.updateMessage(breakMessage())
                handler.postDelayed(this, 1_000L)
            }
        }
    }

    private val statusRequestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_STATUS_REQUEST) handler.post { broadcastStatus() }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> onScreenOn()
                Intent.ACTION_SCREEN_OFF -> onScreenOff()
            }
        }
    }

    fun setup(service: BaseBlockingService) {
        this.service = service
        val powerManager = service.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (powerManager.isInteractive) screenOnSinceMs = SystemClock.elapsedRealtime()

        service.registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        })

        ContextCompat.registerReceiver(
            service,
            statusRequestReceiver,
            IntentFilter(ACTION_STATUS_REQUEST),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        settingsJob?.cancel()
        settingsJob = scope.launch {
            service.dataStoreManager.settings.collect { settings ->
                val newConfig = settings.eyeBreakConfig
                handler.post {
                    if (newConfig == config) return@post
                    config = newConfig
                    if (!config.isEnabled) cancelBreak()
                    scheduleNext()
                }
            }
        }
    }

    private fun onScreenOn() {
        val now = SystemClock.elapsedRealtime()
        if (screenOffAtMs > 0L && now - screenOffAtMs >= BREAK_RESET_MS) screenOnBeforeMs = 0L
        screenOffAtMs = 0L
        screenOnSinceMs = now
        scheduleNext()
    }

    private fun onScreenOff() {
        val now = SystemClock.elapsedRealtime()
        if (screenOnSinceMs > 0L) screenOnBeforeMs += now - screenOnSinceMs
        screenOnSinceMs = 0L
        screenOffAtMs = now
        handler.removeCallbacks(remindRunnable)
        nextReminderAtMs = 0L
        // Looking at a dark screen counts as the break, so an unfinished reminder is done.
        if (secondsLeft > 0) {
            service.blockedPill.hideNow()
            endBreak()
        }
    }

    private fun scheduleNext() {
        handler.removeCallbacks(remindRunnable)
        nextReminderAtMs = 0L
        if (config.isEnabled && screenOnSinceMs != 0L && secondsLeft == 0) {
            val now = SystemClock.elapsedRealtime()
            val intervalMs = config.intervalMinutes.coerceAtLeast(1) * 60_000L
            val usedMs = screenOnBeforeMs + (now - screenOnSinceMs)
            val delayMs = (intervalMs - usedMs).coerceAtLeast(0L)
            nextReminderAtMs = now + delayMs
            handler.postDelayed(remindRunnable, delayMs)
        }
        broadcastStatus()
    }

    private fun broadcastStatus() {
        service.sendBroadcast(
            Intent(ACTION_STATUS)
                .setPackage(service.packageName)
                .putExtra(EXTRA_NEXT_AT, nextReminderAtMs)
                .putExtra(EXTRA_BREAK_ENDS_AT, breakEndsAtMs)
        )
    }

    private fun startBreak() {
        if (!config.isEnabled || screenOnSinceMs == 0L) return
        secondsLeft = BREAK_SECONDS
        nextReminderAtMs = 0L
        breakEndsAtMs = SystemClock.elapsedRealtime() + BREAK_SECONDS * 1_000L
        broadcastStatus()
        service.blockedPill.show(
            packageName = null,
            message = breakMessage(),
            visibleMs = BREAK_SECONDS * 1_000L,
            iconRes = R.drawable.ic_eye_24
        )
        handler.postDelayed(countdownRunnable, 1_000L)
    }

    private fun endBreak() {
        handler.removeCallbacks(countdownRunnable)
        secondsLeft = 0
        breakEndsAtMs = 0L
        screenOnBeforeMs = 0L
        if (screenOnSinceMs > 0L) screenOnSinceMs = SystemClock.elapsedRealtime()
        scheduleNext()
    }

    private fun cancelBreak() {
        handler.removeCallbacks(countdownRunnable)
        handler.removeCallbacks(remindRunnable)
        if (secondsLeft > 0) service.blockedPill.hideNow()
        secondsLeft = 0
        breakEndsAtMs = 0L
    }

    private fun breakMessage(): String = service.getString(R.string.eye_break_message, secondsLeft)

    fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        settingsJob?.cancel()
        scope.cancel()
        runCatching { service.unregisterReceiver(screenReceiver) }
        runCatching { service.unregisterReceiver(statusRequestReceiver) }
    }
}
