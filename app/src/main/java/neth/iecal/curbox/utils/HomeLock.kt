package neth.iecal.curbox.utils

import neth.iecal.curbox.data.models.AppBlockerWarningScreenConfig
import neth.iecal.curbox.data.models.GatedSettingsField
import neth.iecal.curbox.data.models.Settings

/**
 * Guards the lock after unlock. Anything that would weaken a group using it (turning the lock
 * off or shorter, loosening, pausing or deleting the group) is always refused. Away mode only
 * pauses blocking, so nothing loosened while away could carry over to home; Emergency off is
 * the one way to loosen a locked group.
 */
object HomeLock {
    fun usesLock(config: AppBlockerWarningScreenConfig): Boolean =
        config.lockAfterUnlockMs > 0L && !config.isOnOpenConfig

    fun hasAnyLock(settings: Settings): Boolean =
        settings.blockedAppGroups.any { it.isActive && usesLock(it.warningScreenConfig) } ||
            (settings.keywordBlockerConfig.isActive &&
                settings.keywordBlockerConfig.keywordGroups.any { it.isActive && usesLock(it.warningScreenConfig) }) ||
            (settings.reelBlockerConfig.isActive && usesLock(settings.reelBlockerConfig.warningScreenConfig))

    /** True when [proposed] loosens something that is currently protected by a lock after unlock. */
    fun weakensLock(field: GatedSettingsField, current: Settings, proposed: Settings): Boolean {
        return try {
            when (field) {
                GatedSettingsField.APP_GROUPS ->
                    current.blockedAppGroups
                        .filter { it.isActive && usesLock(it.warningScreenConfig) }
                        .any { o ->
                            val n = proposed.blockedAppGroups.find { it.id == o.id }
                            n == null || !RestrictionComparator.appGroup(o, n)
                        }
                GatedSettingsField.KEYWORD_BLOCKER -> {
                    val old = current.keywordBlockerConfig
                    val new = proposed.keywordBlockerConfig
                    val locked = if (old.isActive) {
                        old.keywordGroups.filter { it.isActive && usesLock(it.warningScreenConfig) }
                    } else {
                        emptyList()
                    }
                    locked.isNotEmpty() && (!new.isActive || locked.any { o ->
                        val n = new.keywordGroups.find { it.id == o.id }
                        n == null || !RestrictionComparator.keywordGroup(o, n)
                    })
                }
                GatedSettingsField.REEL_BLOCKER ->
                    current.reelBlockerConfig.isActive &&
                        usesLock(current.reelBlockerConfig.warningScreenConfig) &&
                        !RestrictionComparator.reelBlocker(current.reelBlockerConfig, proposed.reelBlockerConfig)
                else -> false
            }
        } catch (e: Exception) {
            // Cannot prove it is safe, so treat it as weakening, like RestrictionComparator does.
            true
        }
    }
}
