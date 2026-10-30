package neth.iecal.curbox.utils

import neth.iecal.curbox.data.models.AppGroup
import neth.iecal.curbox.data.models.GatedSettingsField
import neth.iecal.curbox.data.models.Settings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppGroupRestrictionComparatorTest {
    private val oldGroup = AppGroup(
        id = "group",
        selectedPackages = listOf("example.app"),
        isActive = true
    )

    @Test
    fun addingAppIsStricter() {
        val proposed = oldGroup.copy(
            selectedPackages = oldGroup.selectedPackages + "another.app"
        )
        assertTrue(RestrictionComparator.appGroups(listOf(oldGroup), listOf(proposed)))
    }

    @Test
    fun addingWebsiteIsStricterAndRemovingItIsWeaker() {
        val withSite = oldGroup.copy(websites = listOf("example.com"))
        assertTrue(RestrictionComparator.appGroups(listOf(oldGroup), listOf(withSite)))
        assertFalse(RestrictionComparator.appGroups(listOf(withSite), listOf(oldGroup)))
    }

    @Test
    fun removingAppIsWeaker() {
        val current = oldGroup.copy(
            selectedPackages = listOf("example.app", "another.app")
        )
        assertFalse(
            RestrictionComparator.isSameOrStricter(
                GatedSettingsField.APP_GROUPS,
                Settings(blockedAppGroups = listOf(current)),
                Settings(blockedAppGroups = listOf(oldGroup))
            )
        )
    }

    @Test
    fun deletingActiveGroupIsWeaker() {
        assertFalse(RestrictionComparator.appGroups(listOf(oldGroup), emptyList()))
    }
}
