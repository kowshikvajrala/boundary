package neth.iecal.curbox.data.models

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Test

class ReelCounterOverlayConfigTest {

    @Test
    fun legacyJsonUsesNewDefaults() {
        val config = Gson().fromJson(
            """{"bgColor":1193046,"textOpacity":75}""",
            ReelCounterOverlayConfig::class.java
        )

        assertEquals(0xFFFFFF, config.textColor)
        assertEquals(0x123456, config.bgColor)
        assertEquals(75, config.textOpacity)
        assertEquals(false, config.checkpointsEnabled)
        assertEquals(10, config.checkpointInterval)
    }

    @Test
    fun counterAlwaysShowsWhenCheckpointsAreOff() {
        val config = ReelCounterOverlayConfig(checkpointsEnabled = false)

        assertEquals(true, config.shouldShowAtCount(0))
        assertEquals(true, config.shouldShowAtCount(7))
    }

    @Test
    fun counterOnlyShowsAtMatchingCheckpoints() {
        val config = ReelCounterOverlayConfig(
            checkpointsEnabled = true,
            checkpointInterval = 5
        )

        assertEquals(false, config.shouldShowAtCount(0))
        assertEquals(false, config.shouldShowAtCount(4))
        assertEquals(true, config.shouldShowAtCount(5))
        assertEquals(false, config.shouldShowAtCount(6))
        assertEquals(true, config.shouldShowAtCount(10))
    }
}
