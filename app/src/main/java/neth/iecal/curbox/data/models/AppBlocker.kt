package neth.iecal.curbox.data.models

data class AppGroup(
    val id: String = "",
    val name: String = "name",
    val selectedPackages: List<String> = listOf(),
    /** Extra websites blocked with the group's apps, such as "example.com" (see AppWebsites). */
    val websites: List<String> = listOf(),
    val isActive: Boolean = false,
    /** Non-zero only while the group is using the opt-in temporary-disable escape hatch. */
    val temporarilyDisabledUntilMs: Long = 0L,
    val warningScreenConfig : AppBlockerWarningScreenConfig = AppBlockerWarningScreenConfig(),
)
