package neth.iecal.curbox.data.models

/**
 * After [scrollMinutes] minutes spent mostly scrolling, or [watchMinutes] minutes watching
 * videos, the phone shows a break screen for [breakMinutes] minutes. Minutes spent typing or away
 * from the screen do not count, and [resetMinutes] away from one start its count again.
 */
data class ScrollBreakConfig(
    val isEnabled: Boolean = false,
    val countScrolling: Boolean = true,
    val scrollMinutes: Int = 20,
    val countWatching: Boolean = true,
    val watchMinutes: Int = 60,
    val breakMinutes: Int = 5,
    val resetMinutes: Int = 5,
    /** How long before a break the warning shows. 0 shows none. */
    val warnMinutes: Int = 1,
    /** Shows the counts, and the break countdown, in a notification. */
    val showNotification: Boolean = true
)
