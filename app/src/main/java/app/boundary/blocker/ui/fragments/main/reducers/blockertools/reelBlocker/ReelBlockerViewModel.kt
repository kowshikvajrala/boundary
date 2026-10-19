package app.boundary.blocker.ui.fragments.main.reducers.blockertools.reelBlocker

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.application
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import app.boundary.blocker.data.models.ReelBlocker
import app.boundary.blocker.data.models.ReelCountConfig
import app.boundary.blocker.data.models.ReelTimeConfig
import app.boundary.blocker.data.models.ReelUsageConfig
import app.boundary.blocker.data.models.ReelBlockerConfig
import app.boundary.blocker.data.models.upgradeLegacyConfig
import app.boundary.blocker.data.models.AppBlockerWarningScreenConfig
import app.boundary.blocker.utils.DataStoreManager

class ReelBlockerViewModel(application: Application) : AndroidViewModel(application) {
    private val dataStoreManager = DataStoreManager(application)
    private val _reelBlockerConfig = MutableStateFlow(ReelBlocker())
    val reelBlockerConfig: StateFlow<ReelBlocker> = _reelBlockerConfig
    private val _temporaryDisableAvailable = MutableStateFlow(true)
    val temporaryDisableAvailable: StateFlow<Boolean> = _temporaryDisableAvailable

    init {
        viewModelScope.launch {
            dataStoreManager.settings.collectLatest { settings ->
                _temporaryDisableAvailable.value = !settings.settingsChangeDelayConfig2.isEnabled &&
                    !settings.settingsChangeDelayConfig2.requireTamperProtectionOff
            }
        }
        viewModelScope.launch {
            dataStoreManager.settingsForEditing.collectLatest { settings ->
                _reelBlockerConfig.value = settings.reelBlockerConfig.upgradeLegacyConfig()
            }
        }
    }


    private fun requestReelBlockerRefresh() {
        val intent = Intent(app.boundary.blocker.blockers.ReelBlocker.INTENT_ACTION_REFRESH_REEL_BLOCKER)
        application.sendBroadcast(intent)
    }
    private fun updateConfig(newConfig: ReelBlocker) {
        viewModelScope.launch {
            dataStoreManager.updateReelBlockerConfig(newConfig)
            requestReelBlockerRefresh()
        }
    }

    fun setIsActive(isActive: Boolean) {
        updateConfig(
            _reelBlockerConfig.value.copy(
                isActive = isActive,
                temporarilyDisabledUntilMs = 0L
            )
        )
    }

    fun temporarilyDisable(durationMinutes: Long) {
        viewModelScope.launch {
            if (dataStoreManager.temporarilyDisableReelBlocker(durationMinutes)) {
                requestReelBlockerRefresh()
            }
        }
    }

    fun updateWarningConfig(config: AppBlockerWarningScreenConfig) {
        updateConfig(_reelBlockerConfig.value.copy(warningScreenConfig = config))
    }

    fun updateExcludedPackages(packages: List<String>) {
        updateConfig(_reelBlockerConfig.value.copy(excludedPackages = packages.distinct()))
    }

    fun setAllowInstagramReelsFromDmInbox(allow: Boolean) {
        viewModelScope.launch {
            dataStoreManager.updateAllowInstagramReelsFromDmInbox(allow)
            requestReelBlockerRefresh()
        }
    }

    fun getReelTimeConfig(): ReelTimeConfig {
        return _reelBlockerConfig.value.config?.schedule ?: ReelTimeConfig()
    }

    fun saveReelTimeConfig(config: ReelTimeConfig) {
        val current = _reelBlockerConfig.value.config ?: ReelBlockerConfig()
        updateConfig(_reelBlockerConfig.value.copy(config = current.copy(schedule = config)))
    }

    fun getReelUsageConfig(): ReelUsageConfig {
        return _reelBlockerConfig.value.config?.usage ?: ReelUsageConfig(uniformLimit = 0)
    }

    fun saveReelUsageConfig(config: ReelUsageConfig) {
        val current = _reelBlockerConfig.value.config ?: ReelBlockerConfig()
        updateConfig(_reelBlockerConfig.value.copy(config = current.copy(usage = config)))
    }

    fun getReelCountConfig(): ReelCountConfig {
        return _reelBlockerConfig.value.config?.reelCount ?: ReelCountConfig(uniformLimit = 0)
    }

    fun saveReelCountConfig(config: ReelCountConfig) {
        val current = _reelBlockerConfig.value.config ?: ReelBlockerConfig()
        updateConfig(_reelBlockerConfig.value.copy(config = current.copy(reelCount = config)))
    }
}
