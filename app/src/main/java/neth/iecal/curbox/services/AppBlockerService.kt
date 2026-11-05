package neth.iecal.curbox.services

import android.annotation.SuppressLint
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import neth.iecal.curbox.BuildConfig
import neth.iecal.curbox.CrashLogger
import neth.iecal.curbox.R
import neth.iecal.curbox.anti_stimulants.MindfulMessage
import neth.iecal.curbox.blockers.AntiUninstallBlocker
import neth.iecal.curbox.blockers.AppBlocker
import neth.iecal.curbox.blockers.KeywordBlocker
import neth.iecal.curbox.blockers.ReelBlocker
import neth.iecal.curbox.blockers.ReelScriptRunner
import neth.iecal.curbox.blockers.uihider.NodePicker
import neth.iecal.curbox.blockers.uihider.UiHider
import neth.iecal.curbox.trackers.AppUsageTracker
import neth.iecal.curbox.trackers.ReelsCountTracker
import neth.iecal.curbox.trackers.ReelUsageTracker
import neth.iecal.curbox.trackers.WebsiteObservation
import neth.iecal.curbox.trackers.WebsiteUsageTracker
import neth.iecal.curbox.ui.overlay.ReelsOverlayManager

@Suppress("DEPRECATION")
class AppBlockerService : BaseBlockingService() {

    private val appBlocker: AppBlocker = AppBlocker()
    private val reelBlocker = ReelBlocker()
    private val reelScriptRunner = ReelScriptRunner()
    private var keywordBlocker = KeywordBlocker()
    private val uiHider = UiHider()
    private val nodePicker = NodePicker()
    private val antiUninstallBlocker = AntiUninstallBlocker()


    // Usage tracking, which used to live in its own accessibility service, now
    // runs here so the user only has to grant one service.
    private val reelsOverlayManager by lazy { ReelsOverlayManager(this) }
    private val reelsCountTracker = ReelsCountTracker()
    private val reelUsageTracker = ReelUsageTracker()
    private val mindfulMessage = MindfulMessage()
    private val websiteUsageTracker = WebsiteUsageTracker()
    private val appUsageTracker = AppUsageTracker()
    private val eyeBreakReminder = neth.iecal.curbox.anti_stimulants.EyeBreakReminder()
    private val scrollBreak = neth.iecal.curbox.anti_stimulants.ScrollBreak()

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val eventChannel = Channel<AccessibilityEvent>(Channel.CONFLATED) { droppedEvent ->
        droppedEvent.recycle()
    }
    private val websiteObservationChannel = Channel<WebsiteObservation?>(Channel.CONFLATED)

    private lateinit var crashLogger: CrashLogger

    /**
     * Focus mode and Auto DND were removed. Undo a Do Not Disturb they may have left on; it is
     * only turned off when Boundary turned it on, so the user's own setting is left alone.
     */
    private fun releaseRemovedFeatures() {
        serviceScope.launch {
            try {
                delay(5_000)
                // Focus mode and Auto DND are gone, so nothing needs Do Not Disturb any more.
                neth.iecal.curbox.utils.DndHelper.applyDndState(this@AppBlockerService, false)
                // The Reading tab's new chapter check job (ids 7720, 7721) was removed after 2.4.0.
                (getSystemService(JOB_SCHEDULER_SERVICE) as android.app.job.JobScheduler).apply {
                    cancel(7720)
                    cancel(7721)
                }
                // Its reading history and covers were deleted at the owner's request.
                deleteDatabase("reading_db")
                java.io.File(filesDir, "covers").deleteRecursively()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e("AppBlockerService", "Could not undo removed features", e)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        crashLogger = CrashLogger(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        super.onAccessibilityEvent(event)

        try {
            if (BuildConfig.SUPPORTS_ANTI_UNINSTALL) {
                antiUninstallBlocker.doAntiUninstallCheck(event)
            }
            appBlocker.doAppBlockerCheck(event)
        } catch (t: Throwable) {
            Log.e("error", t.message ?: "Unknown error")
            crashLogger.logNonFatalError(Exception(t))
        }

        try {
            appUsageTracker.onEvent(event)
            mindfulMessage.onEvent(event)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t

            crashLogger.logNonFatalError(Exception(t))
            Log.e("Usage Tracking", "Usage tracking or mindful message error", t)
        }

        try {
            scrollBreak.onEvent(event)
        } catch (t: Throwable) {
            crashLogger.logNonFatalError(Exception(t))
            Log.e("ScrollBreak", "Scrolling break error", t)
        }

        val eventCopy = AccessibilityEvent.obtain(event)
        val result = eventChannel.trySend(eventCopy)

        // If the channel is closed or rejected it, recycle immediately
        if (result.isFailure) {
            eventCopy.recycle()
        }
    }

    override fun onInterrupt() {
    }

    private fun startBackgroundWorker() {
        serviceScope.launch {
            for (event in eventChannel) {
                try {
                    websiteUsageTracker.onEvent(event)
                    val reelDetection = reelScriptRunner.detect(event)
                    val reelComparator = reelDetection?.comparator
                    reelUsageTracker.onEvent(event, reelComparator)
                    reelsCountTracker.onEvent(event, reelComparator)
                    reelBlocker.doViewBlockerCheck(
                        event,
                        reelComparator,
                        reelDetection?.isInstagramReelOpenedFromDmInbox == true
                    )
                    keywordBlocker.checkIfUnsupportedBrowser(event)
                    if (BuildConfig.SUPPORTS_UI_HIDER) {
                        uiHider.doUiHiderCheck(event)
                    }
                } catch (t: Throwable) {
                    // Don't log normal coroutine cancellations as crashes
                    if (t is CancellationException) throw t

                    crashLogger.logNonFatalError(Exception(t))
                    Log.e("Blocker", "Background worker error", t)
                } finally {
                    event.recycle()
                }
            }
        }
        serviceScope.launch(Dispatchers.IO) {
            for (observation in websiteObservationChannel) {
                try {
                    appBlocker.onWebsiteObserved(observation)
                    keywordBlocker.onWebsiteObserved(observation)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t

                    crashLogger.logNonFatalError(Exception(t))
                    Log.e("KeywordBlocker", "Website observation check failed", t)
                }
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onServiceConnected() {
        super.onServiceConnected()
        // Tamper protection first, so the device admin screen is guarded as early as possible.
        if (BuildConfig.SUPPORTS_ANTI_UNINSTALL) {
            antiUninstallBlocker.setupBlocker(this)
        }
        appBlocker.setupAppBlocker(this)
        reelBlocker.setupBlocker(this)
        reelScriptRunner.setup(this)
        keywordBlocker.setupBlocker(this)
        releaseRemovedFeatures()
        if (BuildConfig.SUPPORTS_UI_HIDER) {
            uiHider.setupBlocker(this)
            nodePicker.setupBlocker(this)
        }

        reelUsageTracker.setup(this)
        reelsCountTracker.setup(this, reelsOverlayManager, reelUsageTracker::onReelCounted)
        mindfulMessage.setup(this)
        eyeBreakReminder.setup(this)
        scrollBreak.setup(this)
        websiteUsageTracker.setup(this) { observation ->
            websiteObservationChannel.trySend(observation)
        }
        appUsageTracker.setup(this)
        neth.iecal.curbox.utils.UsageStatsCleaner.watch(this)

        appBlocker.setupReceivers()
        reelBlocker.setupReceivers()
        keywordBlocker.setupReceivers()
        if (BuildConfig.SUPPORTS_UI_HIDER) {
            uiHider.setupReceivers()
            nodePicker.setupReceivers()
        }
        reelsCountTracker.setupReceivers()

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                getString(R.string.please_provide_draw_over_other_apps),
                Toast.LENGTH_LONG
            ).show()

            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                "package:$packageName".toUri()
            ).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }

            startActivity(intent)
        }

        startBackgroundWorker()
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanupFeature("event worker") { serviceScope.cancel() }
        cleanupFeature("event channel") { eventChannel.cancel() }
        cleanupFeature("website observation channel") { websiteObservationChannel.cancel() }

        cleanupFeature("reel blocker") { reelBlocker.removeReceivers() }
        cleanupFeature("app blocker") { appBlocker.onDestroy() }
        cleanupFeature("keyword blocker") { keywordBlocker.removeReceivers() }
        if (BuildConfig.SUPPORTS_UI_HIDER) {
            cleanupFeature("UI hider") { uiHider.removeReceivers() }
            cleanupFeature("node picker") { nodePicker.removeReceivers() }
        }
        if (BuildConfig.SUPPORTS_ANTI_UNINSTALL) {
            cleanupFeature("anti uninstall") { antiUninstallBlocker.onDestroy() }
        }
        cleanupFeature("mindful messages") { mindfulMessage.onDestroy() }
        cleanupFeature("eye breaks") { eyeBreakReminder.onDestroy() }
        cleanupFeature("scrolling breaks") { scrollBreak.onDestroy() }
        cleanupFeature("reel counter") { reelsCountTracker.onDestroy() }
        cleanupFeature("reel usage") { reelUsageTracker.onDestroy() }
        cleanupFeature("website usage") { websiteUsageTracker.onDestroy() }
        cleanupFeature("app usage") { appUsageTracker.onDestroy() }
    }

    private inline fun cleanupFeature(name: String, cleanup: () -> Unit) {
        try {
            cleanup()
        } catch (error: Throwable) {
            Log.e("AppBlockerService", "Failed to clean up $name", error)
            crashLogger.logNonFatalError(Exception("Failed to clean up $name", error))
        }
    }

    override fun forgetBrowserPage(browserPackage: String) {
        websiteUsageTracker.forgetLastSite(browserPackage)
    }
}
