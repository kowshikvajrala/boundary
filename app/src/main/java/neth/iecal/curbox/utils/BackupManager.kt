package neth.iecal.curbox.utils

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.IOException

class BackupManager(context: Context) {
    private val app = context.applicationContext
    private val dataStore = DataStoreManager(app)

    suspend fun export(uri: Uri) = withContext(Dispatchers.IO) {
        BlocklistStore.init(app)
        val bytes = SettingsBackupCodec.encode(dataStore.settings.first(), BlocklistStore.sources())
        val output = app.contentResolver.openOutputStream(uri, "wt")
            ?: throw IOException("Unable to open backup file")
        output.use { it.write(bytes) }
    }

    suspend fun restore(uri: Uri) = withContext(Dispatchers.IO) {
        check(OnboardingState.canRestore(app)) { "Restore is only available during onboarding" }
        val input = app.contentResolver.openInputStream(uri)
            ?: throw IOException("Unable to open backup file")
        val restored = input.use(SettingsBackupCodec::decodeFull)
        val settings = restored.settings
        dataStore.restoreSettingsDuringOnboarding(settings)
        // Bring back the blocklist links and download them again, so groups that use them work.
        BlocklistStore.init(app)
        restored.blocklists.forEach { source ->
            runCatching {
                BlocklistStore.ensureSource(source)
                BlocklistStore.download(source.id)
            }
        }
        if (settings.serviceProtectionConfig.isEnabled) {
            neth.iecal.curbox.services.ServiceWatchdogJob.schedule(app)
        }
    }
}
