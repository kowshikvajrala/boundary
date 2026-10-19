package app.boundary.blocker.blockers.uihider

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.RECEIVER_EXPORTED
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import app.boundary.blocker.blockers.BaseBlocker
import app.boundary.blocker.blockers.uihider.script.Budget
import app.boundary.blocker.blockers.uihider.script.Interpreter
import app.boundary.blocker.blockers.uihider.script.Parser
import app.boundary.blocker.blockers.uihider.script.ScriptError
import app.boundary.blocker.blockers.uihider.script.Stmt
import app.boundary.blocker.data.models.UiHiderConfig
import app.boundary.blocker.hardcoded.allScripts
import app.boundary.blocker.services.BaseBlockingService

/**
 * Advanced, scriptable view hider. Each user script is bound to a package and runs in the
 * background only while that app is foreground. Scripts read the accessibility tree, compute
 * geometry, and draw overlays / press back / press home.
 *
 * Robustness: every run is sandboxed with a [Budget] and wrapped in try/catch so a faulty or
 * runaway script can never crash or hang the accessibility service.
 */
class UiHider : BaseBlocker() {

    companion object {
        const val INTENT_ACTION_REFRESH_UI_HIDER = "app.boundary.blocker.refresh.uihider"
        private const val MIN_RUN_INTERVAL_MS = 80L
        private val SETTLE_RETRY_DELAYS_MS = longArrayOf(200L, 500L, 1_000L, 2_000L)
    }

    private lateinit var service: BaseBlockingService
    private lateinit var overlay: UiHiderOverlayManager
    private var store: ScriptStore? = null

    private var config = UiHiderConfig()
    private var blockerScope: CoroutineScope? = null
    private var settingsJob: Job? = null
    private var settleRetryJob: Job? = null

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenMap: Map<String, Any?> = emptyMap()

    @Volatile private var scriptsByPackage: Map<String, List<CompiledScript>> = emptyMap()

    private var lastPackage = ""
    private var lastRunAt = 0L

    // Last overlay set handed to the manager; lets us skip re-posting an identical frame.
    @Volatile private var lastCommands: List<DrawCommand> = emptyList()

    private class CompiledScript(val id: String, val program: List<Stmt>)

    private data class EventContext(
        val type: String,
        val packageName: String,
        val text: String?,
        val className: String?
    )

    fun setupBlocker(service: BaseBlockingService) {
        this.service = service
        overlay = UiHiderOverlayManager(service)
        if (store == null) store = ScriptStore(java.io.File(service.filesDir, "uihider_store.json"))
        val metrics = service.resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenMap = mapOf("width" to screenWidth.toDouble(), "height" to screenHeight.toDouble())

        blockerScope?.cancel()
        blockerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        settingsJob = blockerScope?.launch(Dispatchers.IO) {
            service.dataStoreManager.settings.collectLatest { settings ->
                config = settings.uiHiderConfig
                recompile()
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun setupReceivers() {
        val filter = IntentFilter(INTENT_ACTION_REFRESH_UI_HIDER)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            service.registerReceiver(refreshReceiver, filter, RECEIVER_EXPORTED)
        } else {
            service.registerReceiver(refreshReceiver, filter)
        }
    }

    fun removeReceivers() {
        try { service.unregisterReceiver(refreshReceiver) } catch (_: Exception) {}
        settingsJob?.cancel()
        settleRetryJob?.cancel()
        blockerScope?.cancel()
        blockerScope = null
        clearOverlays()
        store?.close()
        store = null
    }

    private fun recompile() {
        val newMap = HashMap<String, MutableList<CompiledScript>>()
        if (config.isActive) {
            for (script in config.allScripts()) {
                if (!script.isEnabled || script.packageName.isBlank() || script.source.isBlank()) continue
                try {
                    val program = Parser.parse(script.source)
                    newMap.getOrPut(script.packageName) { ArrayList() }
                        .add(CompiledScript(script.id.ifEmpty { script.packageName }, program))
                } catch (e: ScriptError) {
                    Log.w("UiHider", "Compile error in '${script.label}': ${e.message}")
                }
            }
        }
        scriptsByPackage = newMap
        if (!config.isActive) {
            settleRetryJob?.cancel()
            clearOverlays()
        } else {
            scheduleCurrentWindowRetries()
        }
    }

    fun doUiHiderCheck(event: AccessibilityEvent?) {
        if (event == null || !config.isActive) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == service.packageName) return

        val scripts = scriptsByPackage[pkg]
        if (scripts.isNullOrEmpty()) {
            if (lastPackage != pkg) {
                settleRetryJob?.cancel()
                clearOverlays()
                lastPackage = pkg
            }
            return
        }
        val packageChanged = lastPackage != pkg
        lastPackage = pkg

        val now = SystemClock.uptimeMillis()
        val isWindowChange = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        if (!isWindowChange && now - lastRunAt < MIN_RUN_INTERVAL_MS) return
        lastRunAt = now

        val eventContext = EventContext(
            type = eventTypeName(event.eventType),
            packageName = pkg,
            text = event.text.joinToString(" ").takeIf { it.isNotEmpty() },
            className = event.className?.toString()
        )
        runScripts(pkg, scripts, eventContext)
        if (packageChanged || isWindowChange) {
            scheduleSettleRetries(pkg, eventContext)
        }
    }

    @Synchronized
    private fun runScripts(pkg: String, scripts: List<CompiledScript>, event: EventContext) {
        val root = service.rootInActiveWindow ?: return
        try {
            if (root.packageName?.toString() != pkg) return

            val commands = ArrayList<DrawCommand>()
            val globals = buildGlobals(event)
            for (compiled in scripts) {
                val budget = Budget()
                val runtime = UiHiderRuntime(service, root, budget, globals, compiled.id, store!!)
                try {
                    Interpreter(runtime, budget).run(compiled.program)
                    for (cmd in runtime.drawCommands) {
                        commands.add(cmd.copy(key = "${compiled.id}::${cmd.key}"))
                    }
                } catch (e: ScriptError) {
                    Log.w("UiHider", "Runtime error in script '${compiled.id}': ${e.message}")
                } finally {
                    if (runtime.output.isNotEmpty()) {
                        Log.i("UiHider", "[${compiled.id}] ${runtime.output.toString().trimEnd()}")
                    }
                    runtime.finish()
                }
            }
            if (commands != lastCommands) {
                overlay.apply(commands)
                lastCommands = commands
            }
        } catch (t: Throwable) {
            Log.e("UiHider", "Error running scripts for $pkg", t)
        } finally {
            @Suppress("DEPRECATION") root.recycle()
        }
    }

    private fun scheduleCurrentWindowRetries() {
        val pkg = try {
            val root = service.rootInActiveWindow ?: return
            try {
                root.packageName?.toString()
            } finally {
                @Suppress("DEPRECATION") root.recycle()
            }
        } catch (t: Throwable) {
            Log.e("UiHider", "Error reading the active window", t)
            return
        } ?: return

        if (!scriptsByPackage[pkg].isNullOrEmpty()) {
            scheduleSettleRetries(
                pkg,
                EventContext("content", pkg, text = null, className = null)
            )
        }
    }

    private fun scheduleSettleRetries(pkg: String, event: EventContext) {
        settleRetryJob?.cancel()
        settleRetryJob = blockerScope?.launch {
            for (delayMs in SETTLE_RETRY_DELAYS_MS) {
                delay(delayMs)
                val scripts = scriptsByPackage[pkg] ?: return@launch
                runScripts(pkg, scripts, event)
            }
        }
    }

    /** Remove all overlays and invalidate the dedupe cache so the next run re-applies cleanly. */
    private fun clearOverlays() {
        overlay.clearAll()
        lastCommands = emptyList()
    }

    private fun buildGlobals(event: EventContext): Map<String, Any?> = mapOf(
        "app" to event.packageName,
        "screen" to screenMap,
        "event" to mapOf(
            "type" to event.type,
            "package" to event.packageName,
            "text" to event.text,
            "class" to event.className
        )
    )

    private fun eventTypeName(type: Int): String = when (type) {
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "window_state"
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "content"
        AccessibilityEvent.TYPE_VIEW_SCROLLED -> "scrolled"
        AccessibilityEvent.TYPE_VIEW_CLICKED -> "clicked"
        AccessibilityEvent.TYPE_VIEW_SELECTED -> "selected"
        else -> "other"
    }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == INTENT_ACTION_REFRESH_UI_HIDER) {
                clearOverlays()
            }
        }
    }
}
