package neth.iecal.curbox.utils

import neth.iecal.curbox.data.models.AntiUninstallMode
import neth.iecal.curbox.data.models.AppBlockerWarningScreenConfig
import neth.iecal.curbox.data.models.GatedSettingsField
import neth.iecal.curbox.data.models.Settings

/**
 * Guards the lock after unlock. Anything that would weaken a group using it (turning the lock
 * off or shorter, loosening, pausing or deleting the group) is refused unless the user proved,
 * moments ago, that they are away from home.
 *
 * The pass lives in memory only, so it never survives a restart and only works in the process
 * that checked the location (the app's own screens).
 */
object HomeLock {
    const val AWAY_PASS_MS = 10 * 60_000L
    @Volatile
    private var awayPassUntilMs = 0L

    fun grantAwayPass() {
        awayPassUntilMs = System.currentTimeMillis() + AWAY_PASS_MS
    }

    fun awayPassEndsAt(): Long = awayPassUntilMs.takeIf { it > System.currentTimeMillis() } ?: 0L

    fun isAwayPassActive(): Boolean = awayPassEndsAt() > 0L

    fun usesLock(config: AppBlockerWarningScreenConfig): Boolean =
        config.lockAfterUnlockMs > 0L && !config.isOnOpenConfig

    fun hasAnyLock(settings: Settings): Boolean =
        settings.blockedAppGroups.any { it.isActive && usesLock(it.warningScreenConfig) } ||
            (settings.keywordBlockerConfig.isActive &&
                settings.keywordBlockerConfig.keywordGroups.any { it.isActive && usesLock(it.warningScreenConfig) }) ||
            (settings.reelBlockerConfig.isActive && usesLock(settings.reelBlockerConfig.warningScreenConfig)) ||
            (settings.antiUninstallConfig2.isEnabled && settings.antiUninstallConfig2.mode == AntiUninstallMode.AWAY_FROM_HOME)

    /** True when [proposed] loosens something that is currently protected by a lock after unlock. */
    fun weakensLock(field: GatedSettingsField, current: Settings, proposed: Settings): Boolean {
        if (isAwayPassActive()) return false
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
