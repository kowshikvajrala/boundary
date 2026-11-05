package neth.iecal.curbox.utils

import neth.iecal.curbox.data.models.ScrollBreakConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollBreakRestrictionComparatorTest {

    private val on = ScrollBreakConfig(isEnabled = true, scrollMinutes = 20, breakMinutes = 5)

    @Test
    fun anyChangePassesWhileOff() {
        assertTrue(RestrictionComparator.scrollBreak(on.copy(isEnabled = false), ScrollBreakConfig(isEnabled = false, scrollMinutes = 60, breakMinutes = 2)))
    }

    @Test
    fun turningOnIsStricter() {
        assertTrue(RestrictionComparator.scrollBreak(ScrollBreakConfig(), on))
    }

    @Test
    fun turningOffIsWeaker() {
        assertFalse(RestrictionComparator.scrollBreak(on, on.copy(isEnabled = false)))
    }

    @Test
    fun earlierOrLongerBreakIsStricter() {
        assertTrue(RestrictionComparator.scrollBreak(on, on.copy(scrollMinutes = 10)))
        assertTrue(RestrictionComparator.scrollBreak(on, on.copy(breakMinutes = 15)))
        assertTrue(RestrictionComparator.scrollBreak(on, on.copy(watchMinutes = 30)))
    }

    @Test
    fun laterOrShorterBreakIsWeaker() {
        assertFalse(RestrictionComparator.scrollBreak(on, on.copy(scrollMinutes = 30)))
        assertFalse(RestrictionComparator.scrollBreak(on, on.copy(breakMinutes = 2)))
        assertFalse(RestrictionComparator.scrollBreak(on, on.copy(watchMinutes = 90)))
        assertFalse(RestrictionComparator.scrollBreak(on, on.copy(scrollMinutes = 10, breakMinutes = 2)))
    }
}
