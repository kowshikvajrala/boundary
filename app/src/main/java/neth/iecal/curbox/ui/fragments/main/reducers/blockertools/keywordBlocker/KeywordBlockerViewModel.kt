package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.keywordBlocker

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import neth.iecal.curbox.data.models.KeywordBlocker
import neth.iecal.curbox.data.models.KeywordGroup
import neth.iecal.curbox.utils.DataStoreManager
import neth.iecal.curbox.data.models.AppBlockerWarningScreenConfig

class KeywordBlockerViewModel(application: Application) : AndroidViewModel(application) {
    private val dataStoreManager = DataStoreManager(application)

    private val _keywordBlockerConfig = MutableStateFlow(KeywordBlocker())
    val keywordBlockerConfig: StateFlow<KeywordBlocker> = _keywordBlockerConfig
    private val _temporaryDisableAvailable = MutableStateFlow(true)
    val temporaryDisableAvailable: StateFlow<Boolean> = _temporaryDisableAvailable

    var warningScrnConfig = AppBlockerWarningScreenConfig()

    init {
        viewModelScope.launch {
            dataStoreManager.settingsForEditing.collectLatest { settings ->
                _keywordBlockerConfig.value = settings.keywordBlockerConfig
            }
        }
        viewModelScope.launch {
            dataStoreManager.settings.collectLatest { settings ->
                _temporaryDisableAvailable.value = !settings.settingsChangeDelayConfig2.isEnabled &&
                    !settings.settingsChangeDelayConfig2.requireTamperProtectionOff
            }
        }
    }


    private fun requestKeywordBlockerRefresh() {
        val intent = Intent(neth.iecal.curbox.blockers.KeywordBlocker.INTENT_ACTION_REFRESH_CONFIG)
        getApplication<Application>().sendBroadcast(intent)
    }
    private fun updateConfig(transform: (neth.iecal.curbox.data.models.KeywordBlocker) -> neth.iecal.curbox.data.models.KeywordBlocker) {
        viewModelScope.launch {
            persistConfig(transform)
        }
    }

    private suspend fun persistConfig(transform: (KeywordBlocker) -> KeywordBlocker) {
        dataStoreManager.updateKeywordBlockerConfig(transform)
        requestKeywordBlockerRefresh()
    }

    fun activateIfNeeded() {
        viewModelScope.launch {
            if (dataStoreManager.settings.first().keywordBlockerConfig.isActive) return@launch
            dataStoreManager.updateKeywordBlockerConfig { it.copy(isActive = true) }
            requestKeywordBlockerRefresh()
        }
    }

    fun setBlockAllExceptSupported(enabled: Boolean) {
        updateConfig { it.copy(blockAllExceptSupported = enabled) }
    }

    fun addGroup(group: KeywordGroup) = viewModelScope.launch {
        addGroupAndWait(group)
    }

    suspend fun addGroupAndWait(group: KeywordGroup) {
        persistConfig { config ->
            val groups = config.keywordGroups.toMutableList()
            groups.add(group)
            config.copy(keywordGroups = groups)
        }
    }

    fun updateGroupById(group: KeywordGroup) = viewModelScope.launch {
        updateGroupByIdAndWait(group)
    }

    suspend fun updateGroupByIdAndWait(group: KeywordGroup) {
        persistConfig { config ->
            val groups = config.keywordGroups.toMutableList()
            val index = groups.indexOfFirst { it.id == group.id }
            if (index != -1) {
                groups[index] = group
            }
            config.copy(keywordGroups = groups)
        }
    }

    fun deleteGroup(groupId: String) = viewModelScope.launch {
        deleteGroupAndWait(groupId)
    }

    suspend fun deleteGroupAndWait(groupId: String) {
        persistConfig { config ->
            val groups = config.keywordGroups.toMutableList()
            groups.removeAll { it.id == groupId }
            config.copy(keywordGroups = groups)
        }
    }

    fun updateGroupActiveState(groupId: String, isActive: Boolean) {
        updateConfig { config ->
            val groups = config.keywordGroups.toMutableList()
            val index = groups.indexOfFirst { it.id == groupId }
            if (index != -1) {
                groups[index] = groups[index].copy(
                    isActive = isActive,
                    temporarilyDisabledUntilMs = 0L
                )
            }
            config.copy(keywordGroups = groups)
        }
    }

    fun temporarilyDisableGroup(groupId: String, durationMinutes: Long) {
        viewModelScope.launch {
            if (dataStoreManager.temporarilyDisableKeywordGroup(groupId, durationMinutes)) {
                requestKeywordBlockerRefresh()
            }
        }
    }
}
