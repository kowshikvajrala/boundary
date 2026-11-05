package neth.iecal.curbox.data.models

/**
 * After [scrollMinutes] minutes spent mostly scrolling, or [watchMinutes] minutes watching
 * videos, the phone shows a break screen for [breakMinutes] minutes. Minutes spent typing or away
 * from the screen do not count.
 */
data class ScrollBreakConfig(
    val isEnabled: Boolean = false,
    val scrollMinutes: Int = 20,
    val watchMinutes: Int = 60,
    val breakMinutes: Int = 5
)
