package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.appBlocker

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.application
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import neth.iecal.curbox.blockers.AppBlocker
import neth.iecal.curbox.data.models.AppBlockerWarningScreenConfig
import neth.iecal.curbox.data.models.AppGroup
import neth.iecal.curbox.utils.DataStoreManager

class AppBlockerSettingViewModel(application: Application) : AndroidViewModel(application) {
    var warningScrnConfig: AppBlockerWarningScreenConfig = AppBlockerWarningScreenConfig()

    private val dataStoreManager = DataStoreManager(application)

    private val _groups = MutableStateFlow<List<AppGroup>>(emptyList())
    val groups: StateFlow<List<AppGroup>> = _groups
    private val _temporaryDisableAvailable = MutableStateFlow(true)
    val temporaryDisableAvailable: StateFlow<Boolean> = _temporaryDisableAvailable
    init {
        viewModelScope.launch {
            dataStoreManager.settingsForEditing.collectLatest { settings ->
                _groups.value = settings.blockedAppGroups
            }
        }
        viewModelScope.launch {
            // This escape hatch must not become usable while enabling it is still pending.
            dataStoreManager.settings.collectLatest { settings ->
                _temporaryDisableAvailable.value =
                    !settings.settingsChangeDelayConfig2.isEnabled &&
                    !settings.settingsChangeDelayConfig2.requireTamperProtectionOff
            }
        }
    }

    private fun requestAppBlockerRefresh() {
        val intent = Intent(AppBlocker.INTENT_ACTION_REFRESH_APP_BLOCKER)
        application.sendBroadcast(intent)
    }

    private suspend fun persistGroups(newGroups: List<AppGroup>) {
        dataStoreManager.updateAppGroups(newGroups)
        requestAppBlockerRefresh()
    }

    fun updateGroups(newGroups: List<AppGroup>) = viewModelScope.launch {
        persistGroups(newGroups)
    }
    
    fun addGroup(group: AppGroup) = viewModelScope.launch {
        addGroupAndWait(group)
    }

    suspend fun addGroupAndWait(group: AppGroup) {
        val currentSettings = dataStoreManager.settingsForEditing.first()
        val updatedGroups = currentSettings.blockedAppGroups.toMutableList().apply { add(group) }
        persistGroups(updatedGroups)
    }

    suspend fun updateGroupById(updatedGroup: AppGroup) {
        val currentSettings = dataStoreManager.settingsForEditing.first()
        val updatedGroups = currentSettings.blockedAppGroups.toMutableList()
        val index = updatedGroups.indexOfFirst { it.id == updatedGroup.id }
        if (index != -1) {
            updatedGroups[index] = updatedGroup
            persistGroups(updatedGroups)
        }
    }

    suspend fun deleteGroup(groupId: String) {
        val currentSettings = dataStoreManager.settingsForEditing.first()
        val updatedGroups = currentSettings.blockedAppGroups.filterNot { it.id == groupId }
        persistGroups(updatedGroups)
    }

    fun updateGroupActiveState(index: Int, isActive: Boolean) {
        viewModelScope.launch {
            val currentSettings = dataStoreManager.settingsForEditing.first()
            val updatedGroups = currentSettings.blockedAppGroups.toMutableList()
            if (index in updatedGroups.indices) {
                updatedGroups[index] = updatedGroups[index].copy(
                    isActive = isActive,
                    temporarilyDisabledUntilMs = 0L
                )
                updateGroups(updatedGroups)
            }
        }
    }

    fun temporarilyDisableGroup(groupId: String, durationMinutes: Long) {
        viewModelScope.launch {
            if (dataStoreManager.temporarilyDisableAppGroup(groupId, durationMinutes)) {
                requestAppBlockerRefresh()
            }
        }
    }
}
