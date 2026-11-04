package neth.iecal.curbox.utils

import neth.iecal.curbox.data.models.AppBlockerWarningScreenConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestrictionComparatorStepsTest {
    private val base = AppBlockerWarningScreenConfig()
    private val withSteps = base.copy(isStepsRequirementEnabled = true, requiredSteps = 200)

    @Test
    fun enablingStepsIsStricter() {
        assertTrue(RestrictionComparator.warningConfig(base, withSteps))
    }

    @Test
    fun disablingStepsIsWeaker() {
        assertFalse(
            RestrictionComparator.warningConfig(
                withSteps,
                withSteps.copy(isStepsRequirementEnabled = false)
            )
        )
    }

    @Test
    fun moreStepsIsStricter() {
        assertTrue(RestrictionComparator.warningConfig(withSteps, withSteps.copy(requiredSteps = 500)))
    }

    @Test
    fun fewerStepsIsWeaker() {
        assertFalse(RestrictionComparator.warningConfig(withSteps, withSteps.copy(requiredSteps = 50)))
    }

    @Test
    fun switchingFromMathToStepsIsWeaker() {
        val math = base.copy(isAdaptiveMathRequirementEnabled = true)
        assertFalse(
            RestrictionComparator.warningConfig(
                math,
                math.copy(isAdaptiveMathRequirementEnabled = false, isStepsRequirementEnabled = true)
            )
        )
    }
}
