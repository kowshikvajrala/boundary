package neth.iecal.curbox.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Lives in the blocker service. When a banking app comes to the front it hands over to
 * [BankingPauseService] and switches this accessibility service off.
 */
class BankingPauseTrigger {

    companion object {
        /** Sent by the banking shortcut to switch the service off before the bank app opens. */
        const val ACTION_PAUSE_NOW = "app.boundary.blocker.banking_pause.PAUSE_NOW"
    }

    private lateinit var service: BaseBlockingService
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var settingsJob: Job? = null
    @Volatile private var bankingPackages: Set<String> = emptySet()
    @Volatile private var isPausing = false

    private val pauseReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_PAUSE_NOW) pause(null)
        }
    }

    fun setup(service: BaseBlockingService) {
        this.service = service
        settingsJob?.cancel()
        settingsJob = scope.launch {
            service.dataStoreManager.settings.collect { settings ->
                val config = settings.bankingPauseConfig
                bankingPackages = if (config.isEnabled) config.packages.toSet() else emptySet()
            }
        }
    }

    fun setupReceivers() {
        ContextCompat.registerReceiver(
            service,
            pauseReceiver,
            IntentFilter(ACTION_PAUSE_NOW),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    /** Cheap enough to run inline: a set lookup on window changes only. */
    fun onEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val packageName = event.packageName?.toString() ?: return
        if (packageName in bankingPackages) pause(packageName)
    }

    private fun pause(bankingPackage: String?) {
        if (isPausing) return
        isPausing = true
        Log.i("BankingPauseTrigger", "Pausing blocker for $bankingPackage")
        bankingPackage?.let { BankingPauseService.start(service, it) }
        service.disableSelf()
    }

    fun onDestroy() {
        runCatching { service.unregisterReceiver(pauseReceiver) }
        scope.cancel()
    }
}
