package neth.iecal.curbox.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import neth.iecal.curbox.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*


class TimerNotification(
    private val context: Context,
    private val notificationId: Int,
    // False keeps only the timer and its callbacks; UnlockNotification shows unlocks for everyone.
    private val postsNotification: Boolean = true
) {

    enum class TimerState { IDLE, RUNNING, FINISHED }

    private val _timerState = MutableStateFlow(TimerState.IDLE)
    val timerState: StateFlow<TimerState> = _timerState.asStateFlow()

    private val _elapsedMillis = MutableStateFlow(0L)
    val elapsedMillis: StateFlow<Long> = _elapsedMillis.asStateFlow()

    companion object {
        private const val TAG = "NotificationTimerMgr"
        private const val CHANNEL_ID = "TimerNotificationChannel"
        // Notification.EXTRA_REQUEST_PROMOTED_ONGOING (Android 16). Asks for a Live Update, which
        // shows the countdown as a chip in the status bar. Older versions ignore it.
        private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
    }

    private val notificationManager: NotificationManager by lazy {
        context.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    private val notificationBuilder by lazy {
        NotificationCompat.Builder(context.applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.icon)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
    }

    private val timerDispatcher = Dispatchers.Default
    private val scope = CoroutineScope(SupervisorJob() + timerDispatcher)
    private var timerJob: Job? = null

    private var currentTimerId = ""
    // Label -> wall clock end time, listed under the main countdown. A negative value is a
    // paused unlock: minus the time it still has left.
    @Volatile private var breakdown: Map<String, Long> = emptyMap()

    init {
        createNotificationChannel()
    }

    fun startTimer(
        totalMillis: Long,
        isCountdown: Boolean = true,
        title: String = "Timer",
        timerId: String = "focusMode",
        breakdown: Map<String, Long> = emptyMap(),
        // False keeps it a plain notification, without the status bar chip.
        promoted: Boolean = true,
        onTickCallback: ((Long) -> Unit)? = null,
        onFinishCallback: (() -> Unit)? = null,
    ) {
        require(totalMillis > 0) { "totalMillis must be > 0" }

        // Prevent duplicate timers, but ONLY if the ID is the same AND it's actively running
        if (currentTimerId == timerId && timerJob?.isActive == true) {
            return
        }

        stopTimer() // Cleans up any old state safely

        currentTimerId = timerId
        this.breakdown = breakdown
        _timerState.value = TimerState.RUNNING

        // Setup the cached notification UI title
        notificationBuilder.setContentTitle(title)
        notificationBuilder.extras.putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, promoted)
        // A running clock in the notification, and in the status bar chip of a Live Update.
        val now = System.currentTimeMillis()
        notificationBuilder
            .setWhen(if (isCountdown) now + totalMillis else now)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(isCountdown)

        timerJob = scope.launch {
            try {
                runTimer(totalMillis, isCountdown, onTickCallback)

                // If it finishes naturally (not cancelled):
                _timerState.value = TimerState.FINISHED
                _elapsedMillis.value = if (isCountdown) 0L else totalMillis

                withContext(Dispatchers.Main) {
                    onFinishCallback?.invoke()
                }

            } catch (e: CancellationException) {
                // Normal cancellation via stopTimer() — ignore
            } catch (e: Exception) {
                Log.e(TAG, "Timer '$timerId' crashed", e)
            } finally {
                if (currentTimerId == timerId) {
                    currentTimerId = ""
                    dismissNotification()
                }
            }
        }
    }

    /** A plain notification with no countdown and no status bar chip, listing [breakdown]. */
    fun showStill(title: String, timerId: String, breakdown: Map<String, Long>) {
        if (currentTimerId == timerId && timerJob == null) return
        stopTimer()
        currentTimerId = timerId
        this.breakdown = breakdown
        notificationBuilder
            .setContentTitle(title)
            .setShowWhen(false)
            .setUsesChronometer(false)
        notificationBuilder.extras.putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, false)
        updateNotificationUI(0L)
    }

    fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
        currentTimerId = ""
        dismissNotification()
        _timerState.value = TimerState.IDLE
        _elapsedMillis.value = 0L
    }

    fun release() {
        stopTimer()
        scope.cancel()
    }

    private suspend fun runTimer(
        totalMillis: Long,
        isCountdown: Boolean,
        onTickCallback: ((Long) -> Unit)?
    ) {
        val startTimeRealtime = SystemClock.elapsedRealtime()
        val endTimeRealtime = startTimeRealtime + totalMillis

        while (currentCoroutineContext().isActive) {
            val now = SystemClock.elapsedRealtime()
            val remainingMillis = endTimeRealtime - now

            if (remainingMillis <= 0) break // Timer finished!

            val displayMillis = if (isCountdown) remainingMillis else (totalMillis - remainingMillis)
            _elapsedMillis.value = displayMillis

            updateNotificationUI(displayMillis)

            onTickCallback?.let { cb ->
                withContext(Dispatchers.Main) { cb(displayMillis) }
            }

            val sleepTime = remainingMillis % 1000
            delay(if (sleepTime > 0) sleepTime else 1000L)
        }
    }

    private fun updateNotificationUI(remainingMillis: Long) {
        if (!postsNotification) return
        runCatching {
            val timeString = formatDuration(remainingMillis)

            val now = System.currentTimeMillis()
            // Running unlocks first, soonest end first, then paused ones.
            val lines = breakdown.entries
                .sortedWith(compareBy({ it.value < 0L }, { if (it.value < 0L) -it.value else it.value }))
                .map { (label, endMs) ->
                    if (endMs < 0L) {
                        context.getString(R.string.notification_unlock_paused_row, label, formatDuration(-endMs))
                    } else {
                        "$label  ${formatDuration((endMs - now).coerceAtLeast(0L))}"
                    }
                }
            // InboxStyle cannot be a Live Update, so the list uses BigTextStyle.
            val style = if (lines.size > 1) {
                NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n"))
            } else {
                null
            }
            val contentText = if (lines.isNotEmpty()) lines.joinToString(" · ") else timeString

            val notification = notificationBuilder
                .setContentText(contentText)
                .setStyle(style)
                .build()

            notificationManager.notify(notificationId, notification)
        }.onFailure { exception ->
            Log.e(TAG, "Failed to show notification: ${exception.message}", exception)
        }
    }

    private fun formatDuration(millis: Long): String {
        val hours = millis / 3_600_000
        val minutes = (millis % 3_600_000) / 60_000
        val seconds = (millis % 60_000) / 1_000
        return if (hours > 0) {
            "%02d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%02d:%02d".format(minutes, seconds)
        }
    }

    private fun dismissNotification() {
        if (!postsNotification) return
        runCatching { notificationManager.cancel(notificationId) }
    }

    private fun createNotificationChannel() {
        runCatching {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "App Blocker Timers",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active countdowns for blocked apps"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }
}
