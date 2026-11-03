package neth.iecal.curbox.utils

import neth.iecal.curbox.data.models.AppBlockerWarningScreenConfig
import neth.iecal.curbox.data.models.AppGroup
import neth.iecal.curbox.data.models.GatedSettingsField
import neth.iecal.curbox.data.models.Settings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLockTest {
    private val lockedGroup = group(lockAfterMs = 30 * 60_000L)
    private val current = Settings(blockedAppGroups = listOf(lockedGroup))

    @Test
    fun turningTheLockOffWeakensIt() {
        assertTrue(weakens(lockedGroup.copy(warningScreenConfig = lockedGroup.warningScreenConfig.copy(lockAfterUnlockMs = 0L))))
    }

    @Test
    fun shorterLockWeakensIt() {
        assertTrue(weakens(lockedGroup.copy(warningScreenConfig = lockedGroup.warningScreenConfig.copy(lockAfterUnlockMs = 60_000L))))
    }

    @Test
    fun pausingOrDeletingTheGroupWeakensIt() {
        assertTrue(weakens(lockedGroup.copy(isActive = false)))
        assertTrue(HomeLock.weakensLock(GatedSettingsField.APP_GROUPS, current, Settings()))
    }

    @Test
    fun longerLockOrNewMessageIsFine() {
        assertFalse(weakens(lockedGroup.copy(warningScreenConfig = lockedGroup.warningScreenConfig.copy(lockAfterUnlockMs = 60 * 60_000L))))
        assertFalse(weakens(lockedGroup.copy(warningScreenConfig = lockedGroup.warningScreenConfig.copy(message = listOf("Go outside")))))
    }

    @Test
    fun groupsWithoutALockAreNotGuarded() {
        val free = group(lockAfterMs = 0L)
        assertFalse(
            HomeLock.weakensLock(
                GatedSettingsField.APP_GROUPS,
                Settings(blockedAppGroups = listOf(free)),
                Settings(blockedAppGroups = listOf(free.copy(isActive = false)))
            )
        )
    }

    @Test
    fun lockOnAnEachOpenGroupIsIgnored() {
        assertFalse(HomeLock.usesLock(AppBlockerWarningScreenConfig(lockAfterUnlockMs = 60_000L, isOnOpenConfig = true)))
    }

    private fun weakens(proposedGroup: AppGroup) = HomeLock.weakensLock(
        GatedSettingsField.APP_GROUPS,
        current,
        Settings(blockedAppGroups = listOf(proposedGroup))
    )

    private fun group(lockAfterMs: Long) = AppGroup(
        id = "group",
        selectedPackages = listOf("com.example"),
        isActive = true,
        warningScreenConfig = AppBlockerWarningScreenConfig(lockAfterUnlockMs = lockAfterMs)
    )
}
