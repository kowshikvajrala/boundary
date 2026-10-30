package neth.iecal.curbox.blockers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.room.InvalidationTracker
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import neth.iecal.curbox.Constants
import neth.iecal.curbox.R
import android.os.SystemClock
import androidx.core.os.CancellationSignal
import neth.iecal.curbox.utils.HomeLocationChecker
import neth.iecal.curbox.data.models.HomeLocation
import neth.iecal.curbox.data.db.AppDatabase
import neth.iecal.curbox.data.db.WebsiteStatsEntity
import neth.iecal.curbox.data.models.KeywordGroup
import neth.iecal.curbox.hardcoded.URL_BAR_ID_LIST
import neth.iecal.curbox.services.BaseBlockingService
import neth.iecal.curbox.trackers.WebsiteObservation
import neth.iecal.curbox.ui.activity.WarningActivity
import neth.iecal.curbox.utils.AccessibilityHelper
import neth.iecal.curbox.utils.KeywordMatcher
import neth.iecal.curbox.utils.TimerNotification
import neth.iecal.curbox.utils.TimeTools
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class KeywordBlocker : BaseBlocker() {

    private val blockerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var destroyed = false
    companion object {
        const val INTENT_ACTION_REFRESH_CONFIG = "app.boundary.blocker.refresh.keywordblocker.config"
        const val INTENT_ACTION_REFRESH_KEYWORD_BLOCKER_COOLDOWN = "app.boundary.blocker.refresh.keywordblocker.cooldown"
        private const val TARGET_EVENTS_MASK =
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        private const val BLOCK_SUPPRESSION_MS = 5_000L
        private const val REPLACEMENT_PAGE = BlockedPage.REPLACEMENT_PAGE
        private const val COOLDOWN_NOTIFICATION_ID = 1004
        private const val DEFAULT_KEEP_LEFTOVER_MS = 60 * 60_000L
    }

    private lateinit var service: BaseBlockingService
    private lateinit var browserBlocker: BrowserBlocker
    private lateinit var prefs: SharedPreferences

    private var activeGroups = listOf<KeywordGroup>()
    // Maps group ID → (compiled regexes, lowercase literal keywords)
    private var groupPatternMap = mutableMapOf<String, Pair<List<Regex>, List<String>>>()

    private val detectionCache = LruCache<String, List<KeywordGroup>>(200)
    private var isTurnedOn = false
    private var isUnsupportedBrowserBlockingOn = false
    private var cooldownGroupsList = ConcurrentHashMap<String, Long>()
    // Unlocks only count down while a page of their group is open. Leaving freezes what is left.
    // firstLeftAtMs is the first time the pages were left; opening them again does not reset it.
    private data class PausedUnlock(val remainingMs: Long, val firstLeftAtMs: Long)
    private val pausedUnlocks = ConcurrentHashMap<String, PausedUnlock>()
    // Running unlocks that were paused before -> the first time their pages were left.
    private val firstLeftAtMs = ConcurrentHashMap<String, Long>()
    private val pauseGuard = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val leftoverExpiry = ConcurrentHashMap<String, Runnable>()
    private lateinit var unlockLocks: UnlockLocks
    @Volatile private var isAwayMode = false
    @Volatile private var homeLocation = HomeLocation()
    private lateinit var notificationManager: TimerNotification
    private var notifiedCooldownGroupId: String? = null
    private var observationJob: Job? = null
    private val observationGuard = Any()
    private var lastObservedSnapshot: WebsiteStatsEntity? = null
    private val blockGuard = Any()
    private var lastBlockedTarget = ""
    private var blockSuppressedUntil = 0L
    @Volatile private var lastWebsiteObservation: WebsiteObservation? = null

    fun compileKeywords(keywords: Collection<String>): Pair<List<Regex>, List<String>> =
        KeywordMatcher.compileKeywords(keywords)

    private fun matchesPatterns(patterns: Pair<List<Regex>, List<String>>, urlIdentifier: String): Boolean =
        KeywordMatcher.matchesPatterns(patterns, urlIdentifier)

    // Returns every active group whose keywords match, preserving activeGroups order so the
    // first group in the list wins the tie-break when more than one would block.
    private fun findMatchingGroups(urlIdentifier: String): List<KeywordGroup> {
        detectionCache.get(urlIdentifier)?.let { return it }   // empty list = cached "no match"

        val matches = activeGroups.filter { group ->
            groupPatternMap[group.id]?.let { matchesPatterns(it, urlIdentifier) } == true
        }
        detectionCache.put(urlIdentifier, matches)
        return matches
    }

    private fun isInternalBrowserPage(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        return lower.startsWith("chrome://") || lower.startsWith("about:") ||
               lower.contains("newtab") || lower.contains("bookmarks") ||
               lower.contains("history") || lower.startsWith("search") ||
               lower.endsWith("url") || lower.contains("Search Google or type URL") ||
               !lower.contains('.') || lower.contains("null")
    }

    fun checkIfUnsupportedBrowser(event: AccessibilityEvent?) {
        val ev = event ?: return
        if (ev.packageName == null) return
        if ((ev.eventType and TARGET_EVENTS_MASK) == 0) return
        val blockUnsupported = isUnsupportedBrowserBlockingOn || isGroupBlockingUnsupportedBrowsers()
        if (blockUnsupported && ::browserBlocker.isInitialized && browserBlocker.isAppBrowser(ev)) {
            if (!service.isDelayOver(1000)) return
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(service, service.getString(R.string.toast_unsupported_browser), Toast.LENGTH_LONG).show()
            }
            service.pressHome()
        }
    }

    /** True while any active group blocks unsupported browsers. */
    private fun isGroupBlockingUnsupportedBrowsers(): Boolean =
        activeGroups.any { it.blockUnsupportedBrowsers }

    private fun startObservingDatabase() {
        if (destroyed) return
        if (observationJob?.isActive == true) return

        observationJob = blockerScope.launch {
            val db = AppDatabase.getInstance(service)
            val dao = db.websiteStatsDao()
            callbackFlow {
                val observer = object : InvalidationTracker.Observer("website_stats") {
                    override fun onInvalidated(tables: Set<String>) { trySend(Unit) }
                }
                db.invalidationTracker.addObserver(observer)
                awaitClose { db.invalidationTracker.removeObserver(observer) }
            }.collect {
                val date = TimeTools.getCurrentDate()
                val latest = dao.getStatsForDate(date).maxByOrNull { it.lastVisited }
                if (latest != null &&
                    latest.lastVisited > (System.currentTimeMillis() - 2500) &&
                    isShowingNow(latest) &&
                    markSnapshotAsObserved(latest)
                ) {
                    evaluateAndBlock(latest.packageName, latest.urlIdentifier)
                    Log.d("KeywordBlocker", "Evaluated $latest")
                }
            }
        }
    }

    // Saved rows can outlive their page, so only act on one while it is on screen.
    private fun isShowingNow(snapshot: WebsiteStatsEntity): Boolean {
        val observation = lastWebsiteObservation ?: return false
        if (observation.packageName != snapshot.packageName ||
            observation.urlIdentifier != snapshot.urlIdentifier
        ) return false
        val foreground = runCatching {
            service.rootInActiveWindow?.packageName?.toString()
        }.getOrNull()
        return foreground == snapshot.packageName
    }

    private fun markSnapshotAsObserved(snapshot: WebsiteStatsEntity): Boolean =
        synchronized(observationGuard) {
            val previous = lastObservedSnapshot
            if (previous != null &&
                previous.date == snapshot.date &&
                previous.packageName == snapshot.packageName &&
                previous.urlIdentifier == snapshot.urlIdentifier &&
                previous.domain == snapshot.domain &&
                previous.totalTime == snapshot.totalTime &&
                previous.lastVisited == snapshot.lastVisited &&
                previous.hourlyUsage.contentEquals(snapshot.hourlyUsage)
            ) {
                false
            } else {
                lastObservedSnapshot = snapshot
                true
            }
        }

    fun onWebsiteObserved(observation: WebsiteObservation?) {
        if (destroyed) return
        lastWebsiteObservation = observation
        syncUnlocksWithPage(observation?.urlIdentifier)
        if (observation == null || !isTurnedOn) return
        evaluateAndBlock(observation.packageName, observation.urlIdentifier)
    }

    /**
     * Freezes the unlock of every group whose pages are not open, and lets a frozen unlock run
     * again when one of its pages comes back. Null means no page is open.
     */
    private fun syncUnlocksWithPage(urlIdentifier: String?) = synchronized(pauseGuard) {
        if (destroyed || !::prefs.isInitialized) return@synchronized
        val now = System.currentTimeMillis()
        val openIds = if (urlIdentifier == null || !isTurnedOn) emptySet()
            else findMatchingGroups(urlIdentifier).map(KeywordGroup::id).toSet()
        var changed = false
        cooldownGroupsList.forEach { (id, end) ->
            if (end <= now || id in openIds || !pausesUnlock(id)) return@forEach
            pauseUnlock(id, end - now, now)
            changed = true
        }
        pausedUnlocks.forEach { (id, paused) ->
            if (id !in openIds) return@forEach
            // Time left over from long ago is not saved up for later.
            if (isLeftoverKept(id, paused, now)) {
                pausedUnlocks.remove(id)
                leftoverExpiry.remove(id)?.let(mainHandler::removeCallbacks)
                cooldownGroupsList[id] = now + paused.remainingMs
                firstLeftAtMs[id] = paused.firstLeftAtMs
            } else {
                dropLeftover(id, leftoverExpiresAt(id, paused))
            }
            changed = true
        }
        if (changed) {
            persistCooldownData()
            if (::notificationManager.isInitialized) {
                notificationManager.stopTimer()
                notifiedCooldownGroupId = null
            }
            showNextCooldownNotification()
        }
    }

    private fun pausesUnlock(id: String): Boolean =
        activeGroups.find { it.id == id }?.warningScreenConfig?.pauseUnlockWhenAway == true

    private fun pauseUnlock(id: String, remainingMs: Long, now: Long) {
        cooldownGroupsList.remove(id)
        // The keep time counts from the first time the pages were left, not from this time, so a
        // quick visit now and then cannot keep the leftover time forever.
        val paused = PausedUnlock(remainingMs, firstLeftAtMs.remove(id) ?: now)
        if (isLeftoverKept(id, paused, now)) {
            pausedUnlocks[id] = paused
            scheduleLeftoverExpiry(id, paused)
        } else {
            // The keep time ran out while the pages were open: the unlock ends now.
            dropLeftover(id, now)
        }
    }

    private fun isLeftoverKept(id: String, paused: PausedUnlock, now: Long): Boolean =
        now < leftoverExpiresAt(id, paused)

    private fun leftoverExpiresAt(id: String, paused: PausedUnlock): Long {
        val keepMs = activeGroups.find { it.id == id }?.warningScreenConfig?.keepLeftoverMs
            ?: DEFAULT_KEEP_LEFTOVER_MS
        return paused.firstLeftAtMs + keepMs
    }

    /** Drops the leftover the moment its keep time runs out, even while no page is open. */
    private fun scheduleLeftoverExpiry(id: String, paused: PausedUnlock) {
        leftoverExpiry.remove(id)?.let(mainHandler::removeCallbacks)
        val runnable = Runnable {
            leftoverExpiry.remove(id)
            synchronized(pauseGuard) {
                if (destroyed || pausedUnlocks[id] != paused) return@synchronized
                dropLeftover(id, leftoverExpiresAt(id, paused))
                persistCooldownData()
                publishTimersToWidgets()
            }
        }
        leftoverExpiry[id] = runnable
        mainHandler.postDelayed(runnable, (leftoverExpiresAt(id, paused) - System.currentTimeMillis()).coerceAtLeast(0L) + 500L)
    }

    /**
     * Leftover time not used within its keep time is dropped. That ends the unlock like running
     * out does, so a lock after unlock starts from [endedAt].
     */
    private fun dropLeftover(id: String, endedAt: Long) {
        pausedUnlocks.remove(id)
        firstLeftAtMs.remove(id)
        leftoverExpiry.remove(id)?.let(mainHandler::removeCallbacks)
        unlockLocks.unlockEnded(id, endedAt)
    }

    private fun isUnlockPaused(id: String, now: Long): Boolean {
        val paused = pausedUnlocks[id] ?: return false
        if (isLeftoverKept(id, paused, now)) return true
        synchronized(pauseGuard) {
            if (pausedUnlocks[id] == paused) {
                dropLeftover(id, leftoverExpiresAt(id, paused))
                persistCooldownData()
                publishTimersToWidgets()
            }
        }
        return false
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            blockerScope.launch {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> syncUnlocksWithPage(null)
                    // The tracker does not report a page again if it did not change, so resume
                    // here when the same page is still in front after unlocking the phone.
                    Intent.ACTION_USER_PRESENT -> {
                        val page = lastWebsiteObservation ?: return@launch
                        val front = runCatching { service.rootInActiveWindow?.packageName?.toString() }.getOrNull()
                        if (front == page.packageName) syncUnlocksWithPage(page.urlIdentifier)
                    }
                }
            }
        }
    }

    private fun evaluateAndBlock(packageName: String, urlIdentifier: String) {
        if (destroyed) return
        val matched = findMatchingGroups(urlIdentifier)
        if (matched.isEmpty()) return
        val now = System.currentTimeMillis()

        val eligible = matched.filter { group ->
            if (isUnlockPaused(group.id, now)) return@filter false
            val cooldownEnd = cooldownGroupsList[group.id]
            if (cooldownEnd != null) {
                if (cooldownEnd > now) return@filter false
                removeCooldownFrom(group.id)
                unlockLocks.unlockEnded(group.id, cooldownEnd)
                publishTimersToWidgets()
            }
            true
        }

        // A group blocks all day; only an unlock lets its keywords through.
        eligible.firstOrNull()?.let { group ->
            val lockEndsAt = unlockLocks.lockEndsAt(group.id, group.warningScreenConfig.lockAfterUnlockMs, now)
            if (!claimBlock(packageName, urlIdentifier, group.id)) return
            handleBlocking(group, packageName, lockEndsAt)
            return
        }

        // Every matched group is unlocked → re-check when the soonest unlock ends
        val soonest = matched.mapNotNull { cooldownGroupsList[it.id] }.filter { it > now }.minOrNull()
        if (soonest != null) {
            blockerScope.launch {
                service.dataStoreManager.updateNextWebsiteRecheckTime(soonest + 500)
            }
        }
    }

    private fun claimBlock(
        packageName: String,
        urlIdentifier: String,
        groupId: String
    ): Boolean = synchronized(blockGuard) {
        val now = System.currentTimeMillis()
        val target = blockTarget(packageName, urlIdentifier, groupId)
        if (target == lastBlockedTarget && now < blockSuppressedUntil) {
            false
        } else {
            lastBlockedTarget = target
            blockSuppressedUntil = now + BLOCK_SUPPRESSION_MS
            true
        }
    }

    private fun blockTarget(packageName: String, urlIdentifier: String, groupId: String): String =
        "$groupId\u0000$packageName\u0000$urlIdentifier"

    private fun handleBlocking(group: KeywordGroup, packageName: String, lockEndsAt: Long = 0L) {
        if (isAwayMode) AwayModeHomeCheck.checkIfBackHome(service, homeLocation)
        Thread.sleep(250)
        if (destroyed) return
        // Back + Home can leave the tab on the blocked page, so leave it from the URL bar.
        if (!replaceBlockedPage(packageName)) {
            service.pressBack()
            service.pressHome()
        }
        service.forgetBrowserPage(packageName)
        Thread.sleep(1000)

        if (lockEndsAt > 0L) {
            service.blockedPill.show(packageName, UnlockLocks.pillText(service, lockEndsAt))
            return
        }
        if (group.warningScreenConfig.isWarningDialogHidden) return
        if (group.warningScreenConfig.isProceedDisabled) {
            service.blockedPill.show(packageName, group.warningScreenConfig.message.filter { it.isNotBlank() }.randomOrNull() ?: service.getString(R.string.blocked_pill_fallback))
            return
        }

        Handler(Looper.getMainLooper()).postDelayed({
            if (destroyed) return@postDelayed
            val intent = Intent(service, WarningActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra("mode", Constants.WARNING_SCREEN_MODE_KEYWORD_BLOCKER)
                putExtra("result_id", group.id)
                putExtra("warning_config", Gson().toJson(group.warningScreenConfig))
            }
            service.startActivity(intent)
        }, 300)
    }

    private fun replaceBlockedPage(packageName: String): Boolean {
        if (findMatchingGroups(REPLACEMENT_PAGE).isNotEmpty()) return false
        return BlockedPage.replace(service, packageName)
    }

    private var configJob: Job? = null

    fun setupBlocker(service: BaseBlockingService, watchSettings: Boolean = true) {
        if (destroyed) return
        this.service = service
        this.browserBlocker = BrowserBlocker(service)
        this.prefs = service.getSharedPreferences("keyword_blocker_prefs", Context.MODE_PRIVATE)
        this.unlockLocks = UnlockLocks(service, "keyword_blocker_unlock_locks")
        if (!::notificationManager.isInitialized) {
            notificationManager = TimerNotification(service, COOLDOWN_NOTIFICATION_ID, postsNotification = false)
        }
        loadPersistedData()

        if (!watchSettings) return

        configJob?.cancel()
        configJob = blockerScope.launch {
            service.dataStoreManager.settings.collectLatest { settings ->
                val keywordConfig = settings.keywordBlockerConfig
                isTurnedOn = keywordConfig.isActive
                isAwayMode = settings.isAwayModeActive
                homeLocation = settings.homeLocation
                isUnsupportedBrowserBlockingOn = keywordConfig.blockAllExceptSupported
                browserBlocker.isTurnedOn = isTurnedOn

                activeGroups = if (isTurnedOn) {
                    keywordConfig.keywordGroups.filter { it.isActive }
                } else {
                    emptyList()
                }

                groupPatternMap = activeGroups.associate { group ->
                    group.id to compileKeywords(group.selectedKeywords)
                }.toMutableMap()

                detectionCache.evictAll()
                // Leftover kept from before a restart still runs out on time.
                pausedUnlocks.forEach { (id, paused) -> scheduleLeftoverExpiry(id, paused) }
                publishTimersToWidgets()

                if (isTurnedOn) {
                    startObservingDatabase()
                    showNextCooldownNotification()
                    reevaluateCurrentWebsite()
                    Handler(Looper.getMainLooper()).post {
                        if (destroyed) return@post
                        val currentPackage =
                            service.rootInActiveWindow?.packageName?.toString() ?: return@post
                        val event = AccessibilityEvent.obtain(
                            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                        )
                        event.packageName = currentPackage
                        checkIfUnsupportedBrowser(event)
                        event.recycle()
                    }
                } else {
                    observationJob?.cancel()
                    observationJob = null
                    notificationManager.stopTimer()
                    notifiedCooldownGroupId = null
                }
            }
        }
    }

    private fun reevaluateCurrentWebsite() {
        if (destroyed) return
        val observation = lastWebsiteObservation ?: return
        val currentPackage = runCatching {
            service.rootInActiveWindow?.packageName?.toString()
        }.getOrNull()
        if (currentPackage != observation.packageName) return

        try {
            evaluateAndBlock(observation.packageName, observation.urlIdentifier)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            Log.e("KeywordBlocker", "Failed to recheck current website", error)
        }
    }

    private fun loadPersistedData() {
        val keys = prefs.getStringSet("cooldown_keys", setOf()) ?: setOf()
        keys.forEach { id ->
            val end = prefs.getLong("cooldown_$id", 0L)
            if (end > System.currentTimeMillis()) cooldownGroupsList[id] = end
        }
        (prefs.getStringSet("paused_unlock_keys", setOf()) ?: setOf()).forEach { id ->
            val remaining = prefs.getLong("paused_unlock_left_$id", 0L)
            val leftAt = prefs.getLong("paused_unlock_at_$id", 0L)
            if (remaining > 0L) pausedUnlocks[id] = PausedUnlock(remaining, leftAt)
        }
        (prefs.getStringSet("unlock_left_at_keys", setOf()) ?: setOf()).forEach { id ->
            val leftAt = prefs.getLong("unlock_left_at_$id", 0L)
            if (leftAt > 0L) firstLeftAtMs[id] = leftAt
        }
    }

    private fun persistCooldownData() {
        prefs.edit {
            putStringSet("cooldown_keys", cooldownGroupsList.keys.toSet())
            cooldownGroupsList.forEach { (id, end) -> putLong("cooldown_$id", end) }
            putStringSet("paused_unlock_keys", pausedUnlocks.keys.toSet())
            pausedUnlocks.forEach { (id, paused) ->
                putLong("paused_unlock_left_$id", paused.remainingMs)
                putLong("paused_unlock_at_$id", paused.firstLeftAtMs)
            }
            putStringSet("unlock_left_at_keys", firstLeftAtMs.keys.toSet())
            firstLeftAtMs.forEach { (id, leftAt) -> putLong("unlock_left_at_$id", leftAt) }
        }
    }

    private fun removeCooldownFrom(id: String) {
        cooldownGroupsList.remove(id)
        pausedUnlocks.remove(id)
        firstLeftAtMs.remove(id)
        publishTimersToWidgets()
        prefs.edit {
            remove("cooldown_$id")
            putStringSet("cooldown_keys", cooldownGroupsList.keys.toSet())
        }
        if (notifiedCooldownGroupId == id && ::notificationManager.isInitialized) {
            notifiedCooldownGroupId = null
            notificationManager.stopTimer()
            Handler(Looper.getMainLooper()).postDelayed(
                { showNextCooldownNotification() },
                100L
            )
        }
    }

    private fun handleCooldownIntent(intent: Intent) {
        val groupId = intent.getStringExtra("result_id") ?: return
        val duration = intent.getLongExtra("selected_time", 120000L)
        if (duration <= 0L) return
        if (unlockLocks.lockEndsAt(groupId, lockAfterFor(groupId)) > 0L) return

        val cooldownEnd = System.currentTimeMillis() + duration
        pausedUnlocks.remove(groupId)
        firstLeftAtMs.remove(groupId)
        cooldownGroupsList[groupId] = cooldownEnd
        persistCooldownData()
        publishTimersToWidgets()
        showCooldownNotification(groupId, cooldownEnd)

        val date = TimeTools.getCurrentDate()
        blockerScope.launch {
            val latest = AppDatabase.getInstance(service).websiteStatsDao()
                .getStatsForDate(date).maxByOrNull { it.lastVisited }
            if (latest != null && latest.lastVisited > (System.currentTimeMillis() - 5000)) {
                evaluateAndBlock(latest.packageName, latest.urlIdentifier)
            }
        }
    }

    private fun lockAfterFor(groupId: String): Long =
        activeGroups.find { it.id == groupId }?.warningScreenConfig?.lockAfterUnlockMs ?: 0L

    /** Sends this blocker's unlocks and locks to the home screen widgets. */
    private fun publishTimersToWidgets() {
        if (destroyed || !::service.isInitialized || !::unlockLocks.isInitialized) return
        val now = System.currentTimeMillis()
        val names = activeGroups.associate { it.id to it.name }
        val unlocks = cooldownGroupsList.filter { it.value > now && it.key in names }
            .entries.associate { names.getValue(it.key) to it.value } +
            pausedUnlocks.filter { (id, paused) -> id in names && isLeftoverKept(id, paused, now) }
                .entries.associate { names.getValue(it.key) to -it.value.remainingMs }
        val locks = activeGroups.mapNotNull { group ->
            val endsAt = unlockLocks.lockEndsAt(group.id, group.warningScreenConfig.lockAfterUnlockMs, now)
            if (endsAt > 0L) group.name to endsAt else null
        }.toMap()
        WidgetTimers.publish(service, WidgetTimers.Kind.UNLOCKS, "keywords", unlocks)
        WidgetTimers.publish(service, WidgetTimers.Kind.LOCKS, "keywords", locks)
    }

    private fun showNextCooldownNotification() {
        publishTimersToWidgets()
        if (destroyed || !isTurnedOn || !::notificationManager.isInitialized) return

        val now = System.currentTimeMillis()
        val nextCooldown = cooldownGroupsList
            .filterValues { it > now }
            .minByOrNull { it.value }

        if (nextCooldown == null) {
            notificationManager.stopTimer()
            notifiedCooldownGroupId = null
            return
        }

        showCooldownNotification(nextCooldown.key, nextCooldown.value)
    }

    private fun showCooldownNotification(groupId: String, cooldownEnd: Long) {
        if (destroyed) return
        if (!::notificationManager.isInitialized) return

        val remaining = cooldownEnd - System.currentTimeMillis()
        if (remaining <= 0L) {
            removeCooldownFrom(groupId)
            unlockLocks.unlockEnded(groupId, cooldownEnd)
            publishTimersToWidgets()
            return
        }

        notifiedCooldownGroupId = groupId
        notificationManager.startTimer(
            totalMillis = remaining,
            timerId = "keyword_cooldown:$groupId:$cooldownEnd",
            title = service.getString(R.string.notification_remaining_usage_lockdown),
            onFinishCallback = {
                if (cooldownGroupsList[groupId] == cooldownEnd) {
                    unlockLocks.unlockEnded(groupId, cooldownEnd)
                    publishTimersToWidgets()
                    cooldownGroupsList.remove(groupId)
                    prefs.edit {
                        remove("cooldown_$groupId")
                        putStringSet("cooldown_keys", cooldownGroupsList.keys.toSet())
                    }
                }
                if (notifiedCooldownGroupId == groupId) {
                    notifiedCooldownGroupId = null
                    Handler(Looper.getMainLooper()).postDelayed(
                        { showNextCooldownNotification() },
                        100L
                    )
                }
            }
        )
    }

    // THIS IS EXPORTED HERE INTENTIONALLY. THE APPBLOCKER SERVICE RUNS IN A DIFFERENT PROCESS THAN
    // THE MAIN UI.
    fun setupReceivers() {
        val filter = IntentFilter().apply {
            addAction(INTENT_ACTION_REFRESH_CONFIG)
            addAction(INTENT_ACTION_REFRESH_KEYWORD_BLOCKER_COOLDOWN)
        }
        ContextCompat.registerReceiver(
            service,
            refreshReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
        service.registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        })
    }

    fun removeReceivers() {
        destroyed = true
        leftoverExpiry.values.forEach(mainHandler::removeCallbacks)
        leftoverExpiry.clear()
        blockerScope.cancel()
        configJob?.cancel()
        observationJob?.cancel()
        observationJob = null
        lastWebsiteObservation = null
        if (::notificationManager.isInitialized) {
            notificationManager.release()
        }
        notifiedCooldownGroupId = null
        service.unregisterReceiver(refreshReceiver)
        runCatching { service.unregisterReceiver(screenReceiver) }
    }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                INTENT_ACTION_REFRESH_CONFIG -> setupBlocker(service)
                INTENT_ACTION_REFRESH_KEYWORD_BLOCKER_COOLDOWN -> handleCooldownIntent(intent)
            }
        }
    }
}
