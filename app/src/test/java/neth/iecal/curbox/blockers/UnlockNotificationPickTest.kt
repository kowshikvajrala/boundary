package neth.iecal.curbox.blockers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UnlockNotificationPickTest {
    private val running = mapOf("Instagram" to 5_000L, "YouTube" to 9_000L)

    @Test
    fun showsTheUnlockOnScreenEvenWhenAnotherEndsFirst() {
        assertEquals("YouTube", UnlockNotification.pickShown(running, setOf("YouTube"))?.key)
    }

    @Test
    fun showsTheOneEndingFirstWhenNothingUnlockedIsOnScreen() {
        assertEquals("Instagram", UnlockNotification.pickShown(running, setOf("Chrome"))?.key)
        assertEquals("Instagram", UnlockNotification.pickShown(running, emptySet())?.key)
    }

    @Test
    fun nothingRunningShowsNothing() {
        assertNull(UnlockNotification.pickShown(emptyMap(), setOf("YouTube")))
    }
}
