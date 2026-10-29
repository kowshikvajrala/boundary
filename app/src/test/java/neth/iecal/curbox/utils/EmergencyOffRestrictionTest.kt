package neth.iecal.curbox.utils

import neth.iecal.curbox.data.models.GatedSettingsField
import neth.iecal.curbox.data.models.Settings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmergencyOffRestrictionTest {
    private val field = GatedSettingsField.EMERGENCY_OFF_WAIT

    @Test
    fun raisingTheWaitIsStricter() {
        assertTrue(RestrictionComparator.isSameOrStricter(field, Settings(emergencyOffWaitDays = 30), Settings(emergencyOffWaitDays = 60)))
    }

    @Test
    fun loweringTheWaitIsWeaker() {
        assertFalse(RestrictionComparator.isSameOrStricter(field, Settings(emergencyOffWaitDays = 30), Settings(emergencyOffWaitDays = 1)))
    }
}
