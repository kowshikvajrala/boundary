package neth.iecal.curbox.blockers

import android.app.ActivityManager
import neth.iecal.curbox.ui.activity.PaymentPauseStartActivity
import neth.iecal.curbox.utils.PermissionUtils
import android.os.SystemClock
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import neth.iecal.curbox.Constants
import neth.iecal.curbox.R
import neth.iecal.curbox.services.LockedAppNotificationListener
import neth.iecal.curbox.data.models.AppBlockerWarningScreenConfig
import neth.iecal.curbox.data.models.AppGroupConfig
import neth.iecal.curbox.data.models.AppUsageConfig
import neth.iecal.curbox.data.models.upgradeLegacyAppGroupConfigs
import neth.iecal.curbox.services.BaseBlockingService
import neth.iecal.curbox.ui.activity.WarningActivity
import neth.iecal.curbox.utils.AppSuspendHelper
import neth.iecal.curbox.utils.ShizukuRunner
import neth.iecal.curbox.utils.TimerNotification
import neth.iecal.curbox.utils.UsageStatsHelper
import neth.iecal.curbox.utils.activeWindow
import neth.iecal.curbox.utils.getCurrentKeyboardPackageName
import neth.iecal.curbox.utils.nextChangeAfter
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

class AppBlocker : BaseBlocker() {

    companion object {
        private const val TIMER_NOTIFICATION_ID = 1001
        /**
         * Refreshes information about warning screen, cheat hours and blocked app list
         */
        const val INTENT_ACTION_REFRESH_APP_BLOCKER = "app.boundary.blocker.refresh.appblocker"

        /**
         * Add cooldown to an app group.
         * This broadcast should always be sent together with the following keys:
         * selected_time: Long -> Duration of cooldown in millis
         * result_id : String -> ID of the app group to be put into cooldown
         */
        const val INTENT_ACTION_REFRESH_APP_BLOCKER_COOLDOWN = "app.boundary.blocker.refresh.appblocker.cooldown"
        const val INTENT_ACTION_WARNING_SCREEN_VISIBILITY =
            "app.boundary.blocker.appblocker.warning_screen_visibility"
        const val EXTRA_WARNING_SCREEN_VISIBLE = "warning_screen_visible"
        /** A frozen unlock is dropped if its apps are not opened again within this time. */
        private const val MAX_PAUSED_UNLOCK_MS = 60 * 60_000L
        private const val CLOSE_AFTER_HOME_MS = 1_500L
        private const val PAYMENT_PAUSE_DEBOUNCE_MS = 10_000L

        // Package -> when its lock ends. Read by LockedAppNotificationListener in this process.
        @Volatile
        private var lockedPackagesUntil: Map<String, Long> = emptyMap()

        fun lockedUntil(packageName: String): Long = lockedPackagesUntil[packageName] ?: 0L
        private const val LOCK_CLOSE_INTERVAL_MS = 60_000L
        private const val TARGET_EVENTS_MASK = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
    }

    private lateinit var prefs: SharedPreferences

    /**
     * Stores which blocked groups have been allowed by the user and until when.
     * group-id -> end-time-in-real-time-millis
     */
    private var cooldownGroupsList = ConcurrentHashMap<String, Long>()

    private data class AppGroupEntry(
        val groupId: String,
        val config: AppGroupConfig,
        val groupPackages: List<String>,
        val warningConfig: AppBlockerWarningScreenConfig
    )

    private val blockedAppsList = ConcurrentHashMap<String, MutableList<AppGroupEntry>>()
    private val groupNames = ConcurrentHashMap<String, String>()

    // Unlocks only count down while their apps are in front. Leaving freezes what is left.
    private data class PausedUnlock(val remainingMs: Long, val pausedAtMs: Long)
    private val pausedUnlocks = ConcurrentHashMap<String, PausedUnlock>()
    private val groupPackagesById = ConcurrentHashMap<String, Set<String>>()
    private val onEachOpenGroupIds = ConcurrentHashMap.newKeySet<String>()
    private val lockAfterMsById = ConcurrentHashMap<String, Long>()
    private val closeAppsWhileLockedIds = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var paymentPauseApps: Set<String> = emptySet()
    private var lastPaymentPauseStartMs = 0L
    // The app in front before the current one. Null until the first switch after the service
    // starts, so a pause that ends while the payment app is open does not start a new one.
    private var lastWindowPackage: String? = null
    private lateinit var unlockLocks: UnlockLocks
    // Windows that can sit over an app without the user leaving it, such as the keyboard.
    private val passThroughPackages = ConcurrentHashMap.newKeySet<String>()

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_SCREEN_OFF) return
            handler.post {
                pauseAllRunningUnlocks()
                // Re check the app that shows after unlock so its unlock resumes.
                lastPackage = ""
            }
        }
    }
    // Holds the warning config last shown per group, used for the cooldown intent's default duration.
    private val appBlockerWarningScrnConfgs = ConcurrentHashMap<String, AppBlockerWarningScreenConfig>()

    private lateinit var usageStats : UsageStatsHelper
    private var lastPackage = ""
    private lateinit var service: BaseBlockingService
    private var settingsJob: kotlinx.coroutines.Job? = null
    private var isWarningScreenVisible = false


    // responsible to trigger a recheck for what app user is currently using even when no event is received. Used in putting the usage recheck logic into
    // cooldown for an app and later when the cooldown duration is over, trigger a recheck
    private val handler = Handler(Looper.getMainLooper())

    private val activeRunnables = ConcurrentHashMap<String, Runnable>()

    private lateinit var notificationManager: TimerNotification

    private val ignoredApps = mutableListOf("com.android.systemui")

    fun doAppBlockerCheck(event: AccessibilityEvent?) {
        if (event == null || (event.eventType and TARGET_EVENTS_MASK) == 0) return

        val packageName = event.packageName?.toString() ?: return

        if (packageName == service.packageName &&
            (isWarningScreenVisible || event.className?.toString() == WarningActivity::class.java.name)
        ) {
            isWarningScreenVisible = true
            return
        }
        // Our own overlays (reel counter, pill) send events while another app stays in front.
        // Treating those as leaving the app stopped the usage countdown, so skip them.
        if (packageName == service.packageName && !isOwnAppInFront()) return

        if (lastPackage == packageName || ignoredApps.contains(packageName)) {
            return
        }

        clearFinishedOnEachOpenSessions(packageName)
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            packageName !in passThroughPackages
        ) {
            syncUnlocksWithForegroundApp(packageName)
            val previousWindowPackage = lastWindowPackage
            lastWindowPackage = packageName
            if (previousWindowPackage != null && previousWindowPackage != packageName &&
                maybeStartPaymentPause(packageName)
            ) return
        }

        val now = System.currentTimeMillis()

        blockedAppsList[packageName]?.let { entries ->
            for (entry in entries) {
                val lockEndsAt = lockEndsAt(entry, now)
                if (lockEndsAt > 0L) {
                    showLockedPill(packageName, entry.groupId, lockEndsAt)
                    return
                }
            }
            for (entry in entries) {
                if (!entry.warningConfig.isOnOpenConfig ||
                    isGroupInCooldown(entry.groupId, now)
                ) {
                    continue
                }
                val activeWindow = entry.config.schedule.activeWindow(now)
                if (activeWindow == null) {
                    entry.config.schedule.nextChangeAfter(now)?.let { nextChange ->
                        setUpForcedRefreshChecker(
                            "schedule:${entry.groupId}:$packageName",
                            nextChange
                        )
                    }
                    continue
                }

                notificationManager.stopTimer()
                showWarningScreen(packageName, entry.groupId, entry.warningConfig)
                return
            }

            var minRemaining = Long.MAX_VALUE
            for (entry in entries) {
                if (entry.warningConfig.isOnOpenConfig) continue
                if (isGroupInCooldown(entry.groupId, now)) continue
                val activeWindow = entry.config.schedule.activeWindow(now)
                if (activeWindow == null) {
                    entry.config.schedule.nextChangeAfter(now)?.let { nextChange ->
                        setUpForcedRefreshChecker(
                            "schedule:${entry.groupId}:$packageName",
                            nextChange
                        )
                    }
                    continue
                }

                val currentUsage = runBlocking {
                    usageStats.getForegroundUsageBetween(
                        entry.groupPackages.toSet(),
                        activeWindow.startMs,
                        minOf(now, activeWindow.endMs)
                    )
                }
                val usageLimitMillis = getUsageLimitForToday(entry.config.usage) * 60_000L
                val remainingUsage = usageLimitMillis - currentUsage

                if (remainingUsage <= 0) {
                    notificationManager.stopTimer()
                    showWarningScreen(packageName, entry.groupId, entry.warningConfig)
                    return
                }
                if (remainingUsage < minRemaining) minRemaining = remainingUsage
                setUpForcedRefreshChecker(
                    "schedule:${entry.groupId}:$packageName",
                    activeWindow.endMs
                )
            }

            if (minRemaining != Long.MAX_VALUE) {
                notificationManager.startTimer(
                    totalMillis = minRemaining,
                    timerId = packageName,
                    title = service.getString(R.string.notification_title_remaining_usage)
                )
                setUpForcedRefreshChecker("usage:$packageName", System.currentTimeMillis() + minRemaining)
            } else {
                showNextCooldownNotification()
            }
            lastPackage = packageName
            return
        }

        showNextCooldownNotification()

        lastPackage = packageName
    }

    /**
     * An on-each-open unlock lasts only while the user stays within that group's selected apps.
     */
    private fun clearFinishedOnEachOpenSessions(currentPackage: String) {
        blockedAppsList[lastPackage]
            .orEmpty()
            .asSequence()
            .filter { it.warningConfig.isOnOpenConfig }
            .filter { currentPackage !in it.groupPackages }
            .map(AppGroupEntry::groupId)
            .distinct()
            .filter(cooldownGroupsList::containsKey)
            .forEach(::removeCooldownFrom)
    }

    // THIS IS EXPORTED HERE INTENTIONALLY. THE APPBLOCKER SERVICE RUNS IN A DIFFERENT PROCESS THAN
    // THE MAIN UI.
    fun setupReceivers() {
        val filter = IntentFilter().apply {
            addAction(INTENT_ACTION_REFRESH_APP_BLOCKER)
            addAction(INTENT_ACTION_REFRESH_APP_BLOCKER_COOLDOWN)
            addAction(INTENT_ACTION_WARNING_SCREEN_VISIBILITY)
        }
        ContextCompat.registerReceiver(
            service,
            refreshReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
        service.registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
    }

    fun onDestroy() {
        service.unregisterReceiver(refreshReceiver)
        runCatching { service.unregisterReceiver(screenOffReceiver) }
        notificationManager.release()
        handler.removeCallbacksAndMessages(null)
        activeRunnables.clear()
        settingsJob?.cancel()
    }

    fun setupAppBlocker(service: BaseBlockingService) {
        this.service = service
        if (!this::notificationManager.isInitialized) {
            notificationManager = TimerNotification(service, TIMER_NOTIFICATION_ID)
        }
        prefs = service.getSharedPreferences("app_blocker_prefs", Context.MODE_PRIVATE)
        unlockLocks = UnlockLocks(service, "app_blocker_unlock_locks")
        usageStats = UsageStatsHelper(service)
        loadPersistedData()
        passThroughPackages.clear()
        passThroughPackages.add("com.android.systemui")
        runCatching {
            val imm = service.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.inputMethodList.forEach { passThroughPackages.add(it.packageName) }
        }

        ignoredApps.add(getCurrentKeyboardPackageName(service)?:"com.google.android.inputmethod.latin")
        ignoredApps.add("com.google.android.apps.wellbeing")

        settingsJob?.cancel()
        settingsJob = CoroutineScope(Dispatchers.IO).launch {
            service.dataStoreManager.settings.collectLatest { settings ->
                Log.d("AppBlocker", "Settings updated, groups count: ${settings.blockedAppGroups.size}")

                val newBlockedAppsList = ConcurrentHashMap<String, MutableList<AppGroupEntry>>()
                settings.blockedAppGroups.upgradeLegacyAppGroupConfigs().forEach { group ->
                    groupNames[group.id] = group.name
                    groupPackagesById[group.id] = group.selectedPackages.map(String::trim).toSet()
                    if (group.warningScreenConfig.isOnOpenConfig) onEachOpenGroupIds.add(group.id) else onEachOpenGroupIds.remove(group.id)
                    lockAfterMsById[group.id] = group.warningScreenConfig.lockAfterUnlockMs
                    if (group.warningScreenConfig.closeAppsWhileLocked) closeAppsWhileLockedIds.add(group.id) else closeAppsWhileLockedIds.remove(group.id)
                    if (!group.isActive) return@forEach

                    try {
                        val config = group.config ?: return@forEach
                        val groupPackages = group.selectedPackages.map(String::trim)
                        val entry = AppGroupEntry(
                            group.id,
                            config,
                            groupPackages,
                            group.warningScreenConfig
                        )
                        groupPackages.forEach { pkg ->
                            newBlockedAppsList.getOrPut(pkg) { mutableListOf() }.add(entry)
                        }
                    } catch (e: Exception) {
                        Log.e("AppBlocker", "Error loading group ${group.name}", e)
                    }
                }

                paymentPauseApps = settings.paymentPauseApps.toSet()

                // Atomic-like update of the maps
                blockedAppsList.clear()
                blockedAppsList.putAll(newBlockedAppsList)

                Log.d("AppBlocker", "Loaded ${blockedAppsList.size} scheduled usage apps")

                // Locks that were running before a restart keep their apps closed too.
                handler.post {
                    refreshLockedPackages()
                    lockAfterMsById.keys
                        .filter { lockEndsAt(it) > 0L && !activeRunnables.containsKey("lockclose:$it") }
                        .forEach(::keepLockedAppsClosed)
                }
                
                // Force a check for the currently open app after settings change
                handler.post {
                    try {
                        val currentPackage = service.rootInActiveWindow?.packageName?.toString()
                        if (currentPackage != null) {
                            Log.d("AppBlocker", "Forcing re-check for current package: $currentPackage")
                            lastPackage = "" // Reset lastPackage to ensure doAppBlockerCheck doesn't return early
                            // Construct a dummy event to trigger the check
                            val dummyEvent = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
                            dummyEvent.packageName = currentPackage
                            doAppBlockerCheck(dummyEvent)
                            dummyEvent.recycle()
                        }
                    } catch (e: Exception) {
                        Log.e("AppBlocker", "Error in forced re-check", e)
                    }
                }
            }
        }
    }

    private fun handlePutCooldownIntentBroadcast(intent: Intent) {
        val groupId = intent.getStringExtra("result_id") ?: return
        if (lockEndsAt(groupId) > 0L) return

        val durationMillis = intent.getLongExtra(
            "selected_time",
            appBlockerWarningScrnConfgs[groupId]?.timeInterval ?: 10L
        )
        if (durationMillis <= 0) return
        Log.d("cooldown for ", durationMillis.toString())
        val currentTimeMillis = System.currentTimeMillis()
        val realTimeEndMillis = if (durationMillis > Long.MAX_VALUE - currentTimeMillis) {
            Long.MAX_VALUE
        } else {
            currentTimeMillis + durationMillis
        }

        pausedUnlocks.remove(groupId)
        putCooldownTo(groupId, realTimeEndMillis)
        showNextCooldownNotification()
        setUpForcedRefreshChecker("cooldown:$groupId", realTimeEndMillis, groupId)
    }

    private fun getUsageLimitForToday(config: AppUsageConfig): Long {
        return if (config.isDailyUniform) {
            config.uniformLimit
        } else {
            val calendar = Calendar.getInstance()
            val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK) - 1
            config.dailyLimits[dayOfWeek]
        }
    }

    private fun loadPersistedData() {
        val pausedKeys = prefs.getStringSet("paused_unlock_keys", setOf()) ?: setOf()
        pausedKeys.forEach { groupId ->
            val remaining = prefs.getLong("paused_unlock_left_$groupId", 0L)
            val pausedAt = prefs.getLong("paused_unlock_at_$groupId", 0L)
            if (remaining > 0L) pausedUnlocks[groupId] = PausedUnlock(remaining, pausedAt)
        }
        val cooldownKeys = prefs.getStringSet("cooldown_keys", setOf()) ?: setOf()
        cooldownKeys.forEach { groupId ->
            val endTime = prefs.getLong("cooldown_$groupId", 0L)
            if (endTime > System.currentTimeMillis()) {
                cooldownGroupsList[groupId] = endTime
                setUpForcedRefreshChecker("cooldown:$groupId", endTime, groupId)
            }
        }
    }

    private fun persistCooldownData() {
        prefs.edit {
            putStringSet("cooldown_keys", cooldownGroupsList.keys.toSet())
            cooldownGroupsList.forEach { (groupId, endTime) ->
                putLong("cooldown_$groupId", endTime)
            }
            putStringSet("paused_unlock_keys", pausedUnlocks.keys.toSet())
            pausedUnlocks.forEach { (groupId, paused) ->
                putLong("paused_unlock_left_$groupId", paused.remainingMs)
                putLong("paused_unlock_at_$groupId", paused.pausedAtMs)
            }
        }
    }

    private fun putCooldownTo(groupId: String, realTimeEnd: Long) {
        cooldownGroupsList[groupId] = realTimeEnd
        persistCooldownData()
    }

    private fun removeCooldownFrom(groupId: String) {
        cooldownGroupsList.remove(groupId)
        pausedUnlocks.remove(groupId)
        prefs.edit {
            remove("cooldown_$groupId")
            putStringSet("cooldown_keys", cooldownGroupsList.keys.toSet())
        }
    }

    /**
     * Freezes the unlock of every group whose apps are not in front, and lets a frozen unlock run
     * again when one of its apps comes back. Unlocks for "each open" groups end on leaving instead.
     */
    private fun syncUnlocksWithForegroundApp(packageName: String) {
        val now = System.currentTimeMillis()
        var changed = false
        cooldownGroupsList.forEach { (groupId, endMs) ->
            if (groupId in onEachOpenGroupIds || endMs <= now) return@forEach
            val groupPackages = groupPackagesById[groupId] ?: return@forEach
            if (packageName !in groupPackages) {
                pauseUnlock(groupId, endMs - now, now)
                changed = true
            }
        }
        pausedUnlocks.forEach { (groupId, paused) ->
            if (packageName !in groupPackagesById[groupId].orEmpty()) return@forEach
            pausedUnlocks.remove(groupId)
            // Time left over from long ago is not saved up for later.
            if (now - paused.pausedAtMs <= MAX_PAUSED_UNLOCK_MS) {
                val endMs = now + paused.remainingMs
                cooldownGroupsList[groupId] = endMs
                setUpForcedRefreshChecker("cooldown:$groupId", endMs, groupId)
            }
            changed = true
        }
        if (changed) {
            persistCooldownData()
            showNextCooldownNotification()
        }
    }

    private fun pauseUnlock(groupId: String, remainingMs: Long, now: Long) {
        cooldownGroupsList.remove(groupId)
        prefs.edit { remove("cooldown_$groupId") }
        activeRunnables.remove("cooldown:$groupId")?.let(handler::removeCallbacks)
        pausedUnlocks[groupId] = PausedUnlock(remainingMs, now)
    }

    private fun pauseAllRunningUnlocks() {
        val now = System.currentTimeMillis()
        val running = cooldownGroupsList.filter { (groupId, endMs) -> groupId !in onEachOpenGroupIds && endMs > now }
        if (running.isEmpty()) return
        running.forEach { (groupId, endMs) -> pauseUnlock(groupId, endMs - now, now) }
        persistCooldownData()
        showNextCooldownNotification()
    }

    private fun isGroupInCooldown(groupId: String, now: Long = System.currentTimeMillis()): Boolean {
        // A frozen unlock still has time left; it starts running again once its app is in front.
        pausedUnlocks[groupId]?.let { if (now - it.pausedAtMs <= MAX_PAUSED_UNLOCK_MS) return true }
        val endTime = cooldownGroupsList[groupId] ?: return false
        if (endTime > now) return true
        endUnlock(groupId)
        return false
    }

    private fun isOwnAppInFront(): Boolean = try {
        service.rootInActiveWindow?.packageName?.toString() == service.packageName
    } catch (_: Exception) {
        false
    }

    /** A timed unlock ran out. Remembers when, so a lock after unlock can start from that moment. */
    private fun endUnlock(groupId: String) {
        val endedAt = cooldownGroupsList[groupId]
        removeCooldownFrom(groupId)
        if (endedAt != null && groupId !in onEachOpenGroupIds) unlockLocks.unlockEnded(groupId, endedAt)
        refreshLockedPackages()
        if (lockEndsAt(groupId) > 0L) {
            // Give the forced re check a moment to send the user home, then close the apps.
            handler.postDelayed({ closeLockedApps(groupId) }, CLOSE_AFTER_HOME_MS)
            keepLockedAppsClosed(groupId)
        }
    }

    /**
     * Closes the group's apps while its lock runs, so they do not keep running in the background.
     * Android only lets a normal app close apps that are in the background, and never one that
     * is playing media with a notification.
     */
    /**
     * Opening a payment app starts a payment pause on its own. The app has already seen this
     * service, so it is sent home and closed, and the pause reopens it fresh once the service
     * is off. Blocked apps are refused by [PaymentPause.start] itself.
     */
    private fun maybeStartPaymentPause(packageName: String): Boolean {
        if (packageName !in paymentPauseApps || blockedAppsList.containsKey(packageName)) return false
        if (!PermissionUtils.hasWriteSecureSettings(service)) return false
        val now = SystemClock.elapsedRealtime()
        if (now - lastPaymentPauseStartMs < PAYMENT_PAUSE_DEBOUNCE_MS) return true
        lastPaymentPauseStartMs = now
        service.pressHome()
        lastPackage = ""
        handler.postDelayed({
            try {
                val activityManager = service.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                activityManager.killBackgroundProcesses(packageName)
                service.startActivity(
                    Intent(service, PaymentPauseStartActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(PaymentPauseStartActivity.EXTRA_PACKAGE, packageName)
                )
            } catch (e: Exception) {
                Log.e("AppBlocker", "Could not start a payment pause", e)
            }
        }, CLOSE_AFTER_HOME_MS)
        return true
    }

    /** Works out which apps are locked right now and hides their notifications. */
    private fun refreshLockedPackages() {
        val now = System.currentTimeMillis()
        val locked = HashMap<String, Long>()
        lockAfterMsById.keys.forEach { groupId ->
            val endsAt = lockEndsAt(groupId, now)
            if (endsAt <= 0L) return@forEach
            groupPackagesById[groupId].orEmpty().forEach { pkg ->
                if (pkg != service.packageName && endsAt > (locked[pkg] ?: 0L)) locked[pkg] = endsAt
            }
        }
        lockedPackagesUntil = locked
        if (locked.isNotEmpty()) LockedAppNotificationListener.instance?.snoozeLockedNow()
    }

    private fun closeLockedApps(groupId: String) {
        if (groupId !in closeAppsWhileLockedIds) return
        val activityManager = service.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        groupPackagesById[groupId].orEmpty()
            .filter { it != service.packageName }
            .forEach { pkg ->
                try {
                    activityManager.killBackgroundProcesses(pkg)
                } catch (e: Exception) {
                    Log.e("AppBlocker", "Could not close $pkg", e)
                }
            }
    }

    /** Closes the group's apps again every minute until its lock ends, in case they restart. */
    private fun keepLockedAppsClosed(groupId: String) {
        val key = "lockclose:$groupId"
        activeRunnables[key]?.let(handler::removeCallbacks)
        val runnable = object : Runnable {
            override fun run() {
                try {
                    refreshLockedPackages()
                    if (lockEndsAt(groupId) <= 0L) {
                        activeRunnables.remove(key)
                        return
                    }
                    closeLockedApps(groupId)
                } catch (e: Exception) {
                    Log.e("AppBlocker", "Lock close check failed", e)
                }
                handler.postDelayed(this, LOCK_CLOSE_INTERVAL_MS)
            }
        }
        activeRunnables[key] = runnable
        handler.postDelayed(runnable, LOCK_CLOSE_INTERVAL_MS)
    }

    private fun lockEndsAt(groupId: String, now: Long = System.currentTimeMillis()): Long {
        if (groupId in onEachOpenGroupIds) return 0L
        return unlockLocks.lockEndsAt(groupId, lockAfterMsById[groupId] ?: 0L, now)
    }

    /** Locked only while the group is enforcing, that is, inside its schedule and not unlocked. */
    private fun lockEndsAt(entry: AppGroupEntry, now: Long): Long {
        if (isGroupInCooldown(entry.groupId, now)) return 0L
        if (entry.config.schedule.activeWindow(now) == null) return 0L
        return lockEndsAt(entry.groupId, now)
    }

    private fun showLockedPill(packageName: String, groupId: String, lockEndsAt: Long) {
        if (!service.isDelayOver(1000)) return
        notificationManager.stopTimer()
        service.pressHome()
        lastPackage = ""
        service.blockedPill.show(packageName, UnlockLocks.pillText(service, lockEndsAt))
        handler.postDelayed({ closeLockedApps(groupId) }, CLOSE_AFTER_HOME_MS)
        if (!activeRunnables.containsKey("lockclose:$groupId")) keepLockedAppsClosed(groupId)
    }

    // Counts down to the soonest unlock ending and, when several groups are unlocked, lists each one.
    private fun showNextCooldownNotification() {
        val now = System.currentTimeMillis()
        val active = cooldownGroupsList.filterValues { it > now }
        val next = active.minByOrNull { it.value }
        if (next == null) {
            notificationManager.stopTimer()
            return
        }
        val breakdown = active.entries.associate { (groupId, endMs) ->
            (groupNames[groupId] ?: groupId) to endMs
        }
        notificationManager.startTimer(
            totalMillis = next.value - now,
            timerId = "app_cooldown:" + active.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" },
            title = service.getString(R.string.notification_remaining_usage_lockdown),
            breakdown = breakdown,
            onFinishCallback = { showNextCooldownNotification() }
        )
    }

    private fun setUpForcedRefreshChecker(
        checkId: String,
        realTimeEndMillis: Long,
        cooldownGroupId: String? = null
    ) {
        activeRunnables[checkId]?.let { handler.removeCallbacks(it) }

        val delayMillis = realTimeEndMillis - System.currentTimeMillis()
        if (delayMillis <= 0) return // Time is already up

        val runnable = Runnable {
            try {
                cooldownGroupId?.let(::endUnlock)
                val currentPackage = service.rootInActiveWindow?.packageName?.toString()
                if (currentPackage != null) {
                    lastPackage = ""
                    val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
                    event.packageName = currentPackage
                    doAppBlockerCheck(event)
                    event.recycle()
                }
            } catch (e: Exception) {
                Log.e("AppBlocker", "Recheck error: $e")
                // Retry in 1 minute if UI check failed
                setUpForcedRefreshChecker(
                    checkId,
                    System.currentTimeMillis() + 60_000L,
                    cooldownGroupId
                )
            } finally {
                activeRunnables.remove(checkId)
            }
        }

        activeRunnables[checkId] = runnable
        handler.postDelayed(runnable, delayMillis)
    }

    private fun showWarningScreen(packageName: String, groupId: String, warningConfig: AppBlockerWarningScreenConfig) {
        if (service.isDelayOver(1000)) {

            // Remember the warning that was shown so the cooldown intent can read its default duration
            appBlockerWarningScrnConfgs[groupId] = warningConfig

            Log.d("AppBlocker", "Showing warning screen for $packageName")
            notificationManager.stopTimer()
            service.pressHome()
            lastPackage = ""

            try {
                if (packageName != service.packageName && AppSuspendHelper.isShizukuAvailable()) {
                    ShizukuRunner.executeCommand(
                        "am force-stop $packageName",
                        object : ShizukuRunner.CommandResultListener {})
                }
            } catch (e: Exception) {
                Log.e("AppBlocker", "Shizuku force-stop failed", e)
            }

            if (warningConfig.isWarningDialogHidden) return
            if (warningConfig.isProceedDisabled) {
                service.blockedPill.show(packageName, warningConfig.message.filter { it.isNotBlank() }.randomOrNull() ?: service.getString(R.string.blocked_pill_fallback))
                return
            }
            isWarningScreenVisible = true

            handler.postDelayed({
                val dialogIntent = Intent(service, WarningActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    putExtra("mode", Constants.WARNING_SCREEN_MODE_APP_BLOCKER)
                    putExtra("result_id", groupId)
                    putExtra("launch_package", packageName)
                    putExtra(
                        "warning_config",
                        Gson().toJson(warningConfig)
                    )
                }
                service.startActivity(dialogIntent)
            }, 100)
        }
    }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            when (intent.action) {
                INTENT_ACTION_REFRESH_APP_BLOCKER -> setupAppBlocker(service)
                INTENT_ACTION_REFRESH_APP_BLOCKER_COOLDOWN -> handlePutCooldownIntentBroadcast(intent)
                INTENT_ACTION_WARNING_SCREEN_VISIBILITY -> {
                    isWarningScreenVisible = intent.getBooleanExtra(
                        EXTRA_WARNING_SCREEN_VISIBLE,
                        false
                    )
                }
            }
        }
    }
}
