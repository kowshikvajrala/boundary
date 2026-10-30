package neth.iecal.curbox.utils

import neth.iecal.curbox.data.models.AppBlockerWarningScreenConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestrictionComparatorPauseUnlockTest {
    private val base = AppBlockerWarningScreenConfig()

    @Test
    fun turningPauseOffIsStricter() {
        assertTrue(RestrictionComparator.warningConfig(base, base.copy(pauseUnlockWhenAway = false)))
    }

    @Test
    fun turningPauseOnIsWeaker() {
        val old = base.copy(pauseUnlockWhenAway = false)
        assertFalse(RestrictionComparator.warningConfig(old, old.copy(pauseUnlockWhenAway = true)))
    }

    @Test
    fun keepingLeftoverShorterIsStricter() {
        assertTrue(RestrictionComparator.warningConfig(base, base.copy(keepLeftoverMs = 10 * 60_000L)))
    }

    @Test
    fun keepingLeftoverLongerIsWeaker() {
        assertFalse(RestrictionComparator.warningConfig(base, base.copy(keepLeftoverMs = 2 * 60 * 60_000L)))
    }

    @Test
    fun keepTimeDoesNotMatterWhilePauseIsOff() {
        val old = base.copy(pauseUnlockWhenAway = false)
        assertTrue(RestrictionComparator.warningConfig(old, old.copy(keepLeftoverMs = 5 * 60 * 60_000L)))
    }
}
