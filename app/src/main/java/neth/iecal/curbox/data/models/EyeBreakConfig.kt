package neth.iecal.curbox.data.models

/** The 20 20 20 rule: after every [intervalMinutes] of screen time, look far away for a moment. */
data class EyeBreakConfig(
    val isEnabled: Boolean = false,
    val intervalMinutes: Int = 20
)
