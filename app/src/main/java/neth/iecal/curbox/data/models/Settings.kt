package neth.iecal.curbox.data.models

data class Settings(
    val blockedAppGroups: List<AppGroup> = listOf(),
    val reelBlockerConfig: ReelBlocker = ReelBlocker(),
    val keywordBlockerConfig: KeywordBlocker = KeywordBlocker(),
    val isReelCounterOn: Boolean = true,
    val usageBackgroundArt: String = "",
    val usageTrackerIgnoredApps: List<String> = listOf(),
    val isAppUsageTrackingEnabled: Boolean = true,
    val isWebsiteUsageTrackingEnabled: Boolean = true,
    val mindfulMessageConfig: MindfulMessageConfig = MindfulMessageConfig(),
    val uiHiderConfig: UiHiderConfig = UiHiderConfig(),
    val reelCounterOverlayConfig: ReelCounterOverlayConfig = ReelCounterOverlayConfig(),
    val nextWebsiteRecheckTime: Long = 0L,
    val serviceProtectionConfig: ServiceProtectionConfig = ServiceProtectionConfig(),
    val antiUninstallConfig2: AntiUninstallConfig = AntiUninstallConfig(),
    val eyeBreakConfig: EyeBreakConfig = EyeBreakConfig(),
    val scrollBreakConfig: ScrollBreakConfig = ScrollBreakConfig(),
    val settingsChangeDelayConfig2: SettingsChangeDelayConfig = SettingsChangeDelayConfig(),
    /** Apps that a payment pause can open, such as banking or UPI apps. */
    val paymentPauseApps: List<String> = listOf()
)
