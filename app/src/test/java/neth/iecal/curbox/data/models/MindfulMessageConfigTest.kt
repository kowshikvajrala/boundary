package neth.iecal.curbox.data.models

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Test

class MindfulMessageConfigTest {

    @Test
    fun legacyJsonUsesDefaultTextColor() {
        val config = Gson().fromJson(
            """{"bgColor":1193046,"textOpacity":75}""",
            MindfulMessageConfig::class.java
        )

        assertEquals(0xFFFFFF, config.textColor)
        assertEquals(0x123456, config.bgColor)
        assertEquals(75, config.textOpacity)
    }
}
