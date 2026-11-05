package neth.iecal.curbox.anti_stimulants

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.telecom.TelecomManager
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.ScrollBreakConfig
import neth.iecal.curbox.services.BaseBlockingService
import neth.iecal.curbox.services.LockedAppNotificationListener
import neth.iecal.curbox.ui.activity.FragmentActivity
import neth.iecal.curbox.ui.activity.ScrollBreakActivity
import neth.iecal.curbox.ui.fragments.main.reducers.anti_stimulants.ScrollBreakFragment
import neth.iecal.curbox.utils.TimerNotificationLayout

/**
 * Notices long stretches of scrolling or watching and then shows a break screen.
 *
 * Every minute of screen time is split into twelve 5 second slots. A minute counts as scrolling
 * when the app in front scrolled in at least [MIN_SCROLL_SLOTS] slots and typed in at most
 * [MAX_TYPING_SLOTS]. So chatting or reading a page slowly does not count, while flicking through
 * a feed or short videos does. A minute that is not scrolling counts as watching when the app in
 * front was playing media in at least [MIN_WATCH_SAMPLES] of the 6 checks made every 10 seconds.
 * Seeing what plays needs notification access. Each count has its own limit and starts again
 * after [ScrollBreakConfig.resetMinutes] without it, or with the screen off that long.
 */
class ScrollBreak {

    companion object {
        private const val MINUTE_MS = 60_000L
        private const val SLOT_MS = 5_000L
        private const val MIN_SCROLL_SLOTS = 3
        private const val MAX_TYPING_SLOTS = 1
        private const val WATCH_SAMPLE_MS = 10_000L
        private const val MIN_WATCH_SAMPLES = 4
        private const val SCROLL_NOTIFICATION_ID = 1006
        private const val WATCH_NOTIFICATION_ID = 1007
        private const val CHANNEL_ID = "ScrollBreakChannel"
        private const val RELAUNCH_GAP_MS = 1_500L
        private const val KEEP_PAUSED_MS = 2_000L
        private const val ACTIVITY_CHECK_MS = 5_000L
        // Scrolling counts as going on for this long after the last scroll.
        private const val SCROLLING_NOW_MS = 10_000L
        // Gives the app time to pause before the break screen covers it. Apps like YouTube only
        // move a video into a popup window when they are left while it is still playing.
        private const val PAUSE_BEFORE_SCREEN_MS = 400L

        // The settings screen lives in another process, so it asks for the status by broadcast.
        const val ACTION_STATUS_REQUEST = "app.boundary.blocker.scroll_break.STATUS_REQUEST"
        const val ACTION_STATUS = "app.boundary.blocker.scroll_break.STATUS"
        const val EXTRA_SCROLLED_MINUTES = "scrolled_minutes"
        const val EXTRA_WATCHED_MINUTES = "watched_minutes"
        /** Wall clock time the running break ends, or 0 when none is running. */
        const val EXTRA_BREAK_ENDS_AT = "break_ends_at"
    }

    private lateinit var service: BaseBlockingService
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var settingsJob: Job? = null

    // Every field below is only touched on the main thread.
    private val handler = Handler(Looper.getMainLooper())
    private var config = ScrollBreakConfig()
    // Keyboards and the status bar send window events while an app stays in front.
    private var passThrough = emptySet<String>()
    private var homePackage: String? = null
    private var allowedDuringBreak = emptySet<String>()
    private var frontPackage: String? = null
    private var screenOffAtMs = 0L
    private var minuteStartMs = 0L
    private var scrollSlots = 0
    private var typingSlots = 0
    private var watchSamples = 0
    private var scrolledMinutes = 0
    private var watchedMinutes = 0
    private var minutesWithoutScrolling = 0
    private var minutesWithoutWatching = 0
    private var breakEndsAtMs = 0L
    private var breakForWatching = false
    private var breakAfterMinutes = 0
    private val pausedDuringBreak = mutableSetOf<String>()
    private var lastScrollAtMs = 0L
    private var isWatchingNow = false
    private var shownScrolling = false
    private var shownWatching = false
    private var lastLaunchMs = 0L

    private val minuteRunnable = object : Runnable {
        override fun run() {
            endMinute()
            handler.postDelayed(this, MINUTE_MS)
        }
    }
    private val watchSampleRunnable = object : Runnable {
        override fun run() {
            isWatchingNow = isFrontAppPlaying()
            if (isWatchingNow) watchSamples++
            handler.postDelayed(this, WATCH_SAMPLE_MS)
        }
    }
    private val endBreakRunnable = Runnable { endBreak() }
    // The notification countdowns run only while their kind is going on, so this checks often
    // and redraws them when one starts or stops.
    private val activityCheckRunnable = object : Runnable {
        override fun run() {
            if (isScrollingNow() != shownScrolling || isWatchingNow != shownWatching) updateNotification()
            handler.postDelayed(this, ACTIVITY_CHECK_MS)
        }
    }
    // Pressing play in the popup window during a break is undone within a moment.
    private val keepPausedRunnable = object : Runnable {
        override fun run() {
            if (breakEndsAtMs == 0L) return
            pauseBreakApps()
            handler.postDelayed(this, KEEP_PAUSED_MS)
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
        refreshSystemApps()
        val powerManager = service.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (powerManager.isInteractive) onScreenOn()

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
                val newConfig = settings.scrollBreakConfig
                handler.post {
                    if (newConfig == config) return@post
                    config = newConfig
                    if (!config.isEnabled) {
                        scrolledMinutes = 0
                        watchedMinutes = 0
                        endBreak()
                    }
                    if (!config.countScrolling) scrolledMinutes = 0
                    if (!config.countWatching) watchedMinutes = 0
                    broadcastStatus()
                }
            }
        }
    }

    /** Cheap, runs inline on every accessibility event. */
    fun onEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // Boundary's own overlays send events too, so its own package never counts as in front.
                if (packageName == service.packageName || packageName in passThrough) return
                frontPackage = packageName
                if (breakEndsAtMs > System.currentTimeMillis() && packageName !in allowedDuringBreak) {
                    pausedDuringBreak += packageName
                    pauseBreakApps()
                    launchBreakScreen()
                }
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED ->
                if (packageName == frontPackage) {
                    scrollSlots = scrollSlots or currentSlotBit()
                    lastScrollAtMs = SystemClock.elapsedRealtime()
                }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ->
                if (packageName == frontPackage) typingSlots = typingSlots or currentSlotBit()
        }
    }

    private fun refreshSystemApps() {
        homePackage = runCatching {
            service.packageManager.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0
            )?.activityInfo?.packageName
        }.getOrNull()
        val keyboards = runCatching {
            service.getSystemService(InputMethodManager::class.java)
                .enabledInputMethodList.map { it.packageName }
        }.getOrDefault(emptyList())
        val dialer = runCatching {
            service.getSystemService(TelecomManager::class.java).defaultDialerPackage
        }.getOrNull()
        passThrough = (keyboards + "com.android.systemui").toSet()
        allowedDuringBreak = passThrough +
            listOfNotNull(homePackage, dialer, "com.android.server.telecom", service.packageName)
    }

    private fun currentSlotBit(): Int {
        if (minuteStartMs == 0L) return 0
        val slot = ((SystemClock.elapsedRealtime() - minuteStartMs) / SLOT_MS).toInt().coerceIn(0, 11)
        return 1 shl slot
    }

    /** True when the app in front has a media session that is playing, such as a video. */
    private fun isFrontAppPlaying(): Boolean {
        val front = frontPackage ?: return false
        if (!config.isEnabled || !config.countWatching || breakEndsAtMs != 0L) return false
        return playingSessions().any { it.packageName == front }
    }

    /** Media that is playing now. Empty without notification access. */
    private fun playingSessions() = runCatching {
        service.getSystemService(MediaSessionManager::class.java)
            .getActiveSessions(ComponentName(service, LockedAppNotificationListener::class.java))
            .filter { it.playbackState?.state == PlaybackState.STATE_PLAYING }
    }.getOrDefault(emptyList())

    /** Pauses what [pausedDuringBreak] apps play, so a video does not go on in a popup window. */
    private fun pauseBreakApps() {
        playingSessions()
            .filter { it.packageName in pausedDuringBreak }
            .forEach { runCatching { it.transportControls.pause() } }
    }

    private fun startMinute() {
        minuteStartMs = SystemClock.elapsedRealtime()
        scrollSlots = 0
        typingSlots = 0
        watchSamples = 0
        handler.removeCallbacks(minuteRunnable)
        handler.postDelayed(minuteRunnable, MINUTE_MS)
        handler.removeCallbacks(watchSampleRunnable)
        handler.postDelayed(watchSampleRunnable, WATCH_SAMPLE_MS)
        handler.removeCallbacks(activityCheckRunnable)
        handler.postDelayed(activityCheckRunnable, ACTIVITY_CHECK_MS)
    }

    private fun isScrollingNow() =
        minuteStartMs != 0L && SystemClock.elapsedRealtime() - lastScrollAtMs < SCROLLING_NOW_MS

    /**
     * Time left before a break of one kind, if it went on without a stop. A minute that is going
     * on counts as used, so the countdown keeps moving inside it.
     */
    private fun remainingMs(minutes: Int, limit: Int, goingOn: Boolean): Long {
        val inMinute = if (goingOn && minuteStartMs != 0L) SystemClock.elapsedRealtime() - minuteStartMs else 0L
        return ((limit.coerceAtLeast(1) - minutes) * MINUTE_MS - inMinute).coerceAtLeast(0L)
    }

    private fun endMinute() {
        val front = frontPackage
        val counting = config.isEnabled && breakEndsAtMs == 0L && front != null && front != homePackage
        val scrolled = counting && config.countScrolling &&
            Integer.bitCount(scrollSlots) >= MIN_SCROLL_SLOTS &&
            Integer.bitCount(typingSlots) <= MAX_TYPING_SLOTS
        // Short videos with sound would match both, so a minute counts once, as scrolling.
        val watched = counting && config.countWatching && !scrolled && watchSamples >= MIN_WATCH_SAMPLES
        minuteStartMs = SystemClock.elapsedRealtime()
        scrollSlots = 0
        typingSlots = 0
        watchSamples = 0

        if (scrolled) {
            scrolledMinutes++
            minutesWithoutScrolling = 0
        } else if (++minutesWithoutScrolling >= resetMinutes()) {
            scrolledMinutes = 0
        }
        if (watched) {
            watchedMinutes++
            minutesWithoutWatching = 0
        } else if (++minutesWithoutWatching >= resetMinutes()) {
            watchedMinutes = 0
        }

        if (scrolled) checkLimit(scrolledMinutes, config.scrollMinutes, isWatching = false)
        if (watched) checkLimit(watchedMinutes, config.watchMinutes, isWatching = true)
        broadcastStatus()
    }

    private fun checkLimit(minutes: Int, limitMinutes: Int, isWatching: Boolean) {
        val limit = limitMinutes.coerceAtLeast(1)
        if (minutes >= limit) {
            startBreak(isWatching, limit)
        } else if (config.warnMinutes > 0 && minutes == limit - config.warnMinutes) {
            service.blockedPill.show(
                packageName = null,
                message = service.getString(
                    if (isWatching) R.string.watch_break_warning else R.string.scroll_break_warning,
                    config.warnMinutes,
                    config.breakMinutes
                ),
                visibleMs = 5_000L,
                iconRes = R.drawable.ic_timer_aesthetic
            )
        }
    }

    private fun onScreenOn() {
        if (screenOffAtMs > 0L && SystemClock.elapsedRealtime() - screenOffAtMs >= resetMinutes() * MINUTE_MS) {
            scrolledMinutes = 0
            watchedMinutes = 0
        }
        screenOffAtMs = 0L
        // The launcher may have changed since the service started.
        refreshSystemApps()
        startMinute()
        broadcastStatus()
    }

    private fun onScreenOff() {
        screenOffAtMs = SystemClock.elapsedRealtime()
        // A part minute is dropped, so a quick look at the lock screen never counts.
        handler.removeCallbacks(minuteRunnable)
        handler.removeCallbacks(watchSampleRunnable)
        handler.removeCallbacks(activityCheckRunnable)
        minuteStartMs = 0L
        isWatchingNow = false
        updateNotification()
    }

    private fun startBreak(isWatching: Boolean, minutes: Int) {
        val breakMs = config.breakMinutes.coerceAtLeast(1) * MINUTE_MS
        breakEndsAtMs = System.currentTimeMillis() + breakMs
        breakForWatching = isWatching
        breakAfterMinutes = minutes
        scrolledMinutes = 0
        watchedMinutes = 0
        handler.removeCallbacks(endBreakRunnable)
        handler.postDelayed(endBreakRunnable, breakMs)
        pausedDuringBreak.clear()
        frontPackage?.let { pausedDuringBreak += it }
        val wasPlaying = playingSessions().any { it.packageName in pausedDuringBreak }
        pauseBreakApps()
        handler.removeCallbacks(keepPausedRunnable)
        handler.postDelayed(keepPausedRunnable, KEEP_PAUSED_MS)
        if (wasPlaying) handler.postDelayed({ launchBreakScreen() }, PAUSE_BEFORE_SCREEN_MS) else launchBreakScreen()
        broadcastStatus()
    }

    private fun launchBreakScreen() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastLaunchMs < RELAUNCH_GAP_MS) return
        lastLaunchMs = now
        runCatching {
            service.startActivity(
                Intent(service, ScrollBreakActivity::class.java)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                    .putExtra(ScrollBreakActivity.EXTRA_ENDS_AT, breakEndsAtMs)
                    .putExtra(ScrollBreakActivity.EXTRA_MINUTES, breakAfterMinutes)
                    .putExtra(ScrollBreakActivity.EXTRA_WATCHING, breakForWatching)
            )
        }
    }

    private fun endBreak() {
        handler.removeCallbacks(endBreakRunnable)
        handler.removeCallbacks(keepPausedRunnable)
        pausedDuringBreak.clear()
        if (breakEndsAtMs == 0L) return
        breakEndsAtMs = 0L
        broadcastStatus()
    }

    private fun resetMinutes() = config.resetMinutes.coerceAtLeast(1)

    /** Tells the settings screen and refreshes the notification. */
    private fun broadcastStatus() {
        service.sendBroadcast(
            Intent(ACTION_STATUS)
                .setPackage(service.packageName)
                .putExtra(EXTRA_SCROLLED_MINUTES, scrolledMinutes)
                .putExtra(EXTRA_WATCHED_MINUTES, watchedMinutes)
                .putExtra(EXTRA_BREAK_ENDS_AT, breakEndsAtMs)
        )
        updateNotification()
    }

    private val notificationManager by lazy { service.getSystemService(NotificationManager::class.java) }

    /**
     * One notification for scrolling and one for watching, each shown only while that kind goes
     * on, with a countdown to its break. During a break, the one that caused it counts down to
     * its end and the other hides.
     */
    private fun updateNotification() {
        runCatching {
            val shows = config.isEnabled && config.showNotification
            val onBreak = breakEndsAtMs > System.currentTimeMillis()
            if (!shows) {
                notificationManager.cancel(SCROLL_NOTIFICATION_ID)
                notificationManager.cancel(WATCH_NOTIFICATION_ID)
                return
            }
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    service.getString(R.string.scroll_break_title),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                }
            )
            shownScrolling = isScrollingNow()
            shownWatching = isWatchingNow
            postKind(
                SCROLL_NOTIFICATION_ID,
                counts = config.countScrolling,
                onBreak = onBreak,
                isBreakKind = !breakForWatching,
                title = R.string.scroll_break_notification_scroll_title,
                icon = R.drawable.ic_notif_scroll,
                goingOn = shownScrolling,
                remainingMs = remainingMs(scrolledMinutes, config.scrollMinutes, shownScrolling)
            )
            postKind(
                WATCH_NOTIFICATION_ID,
                counts = config.countWatching,
                onBreak = onBreak,
                isBreakKind = breakForWatching,
                title = R.string.scroll_break_notification_watch_title,
                icon = R.drawable.ic_notif_watch,
                goingOn = shownWatching,
                remainingMs = remainingMs(watchedMinutes, config.watchMinutes, shownWatching)
            )
        }
    }

    private fun postKind(
        id: Int,
        counts: Boolean,
        onBreak: Boolean,
        isBreakKind: Boolean,
        title: Int,
        icon: Int,
        goingOn: Boolean,
        remainingMs: Long
    ) {
        if (!counts || (onBreak && !isBreakKind) || (!onBreak && !goingOn)) {
            notificationManager.cancel(id)
            return
        }
        val openSettings = PendingIntent.getActivity(
            service,
            id,
            Intent(service, FragmentActivity::class.java)
                .putExtra("fragment", ScrollBreakFragment.FRAGMENT_ID)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(service, CHANNEL_ID)
            .setSmallIcon(if (onBreak) R.drawable.ic_notif_break else icon)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openSettings)
        val label = service.getString(if (onBreak) R.string.scroll_break_notification_break else title)
        // Title and text for the lock screen and screen readers, which do not read the layout.
        builder.setContentTitle(label)
        if (!onBreak) builder.setContentText(service.getString(R.string.scroll_break_notification_running))
        TimerNotificationLayout.apply(
            builder,
            service,
            label = label,
            endsAtMs = if (onBreak) breakEndsAtMs else System.currentTimeMillis() + remainingMs,
            detail = if (onBreak) service.getString(R.string.scroll_break_notification_break_detail) else null
        )
        notificationManager.notify(id, builder.build())
    }

    fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching {
            notificationManager.cancel(SCROLL_NOTIFICATION_ID)
            notificationManager.cancel(WATCH_NOTIFICATION_ID)
        }
        settingsJob?.cancel()
        scope.cancel()
        runCatching { service.unregisterReceiver(screenReceiver) }
        runCatching { service.unregisterReceiver(statusRequestReceiver) }
    }
}
