package neth.iecal.curbox.data.models

data class KeywordBlocker(
    val isActive: Boolean = false,
    val keywordGroups: List<KeywordGroup> = emptyList(),
    val blockAllExceptSupported: Boolean = false
)

data class KeywordGroup(
    val id: String = "",
    val name: String = "name",
    val selectedKeywords: List<String> = listOf(),
    val isActive: Boolean = false,
    val temporarilyDisabledUntilMs: Long = 0L,
    val warningScreenConfig: AppBlockerWarningScreenConfig = AppBlockerWarningScreenConfig(),
    /**
     * While this group is active, browsers Boundary cannot read URLs from
     * are sent home, so its keywords cannot be searched there. Part of the group, so turning it
     * off is gated like any other loosening of the group.
     */
    val blockUnsupportedBrowsers: Boolean = false,
)
