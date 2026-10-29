package neth.iecal.curbox.data.models

data class Settings(
    val blockedAppGroups: List<AppGroup> = listOf(),
    val manualFocusGroups: List<ManualFocusGroup> = listOf(),
    val autoDndGroups: List<AutoDndGroup> = listOf(),
    /**
     * Stores info about active manual focus mode.
     * Format Pair<GroupId?, system ms when it ends>.
     * Set group id as null when no active focus mode is running
     */
    val activeManualFocusGroupId: Pair<String?, Long> = Pair(null, 0),
    val activePomodoroState: PomodoroState = PomodoroState(),

    val reelBlockerConfig: ReelBlocker = ReelBlocker(),
    val keywordBlockerConfig: KeywordBlocker = KeywordBlocker(),
    val isReelCounterOn: Boolean = true,
    val grayscaleGroups: List<GrayscaleGroup> = listOf(),
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
    val settingsChangeDelayConfig2: SettingsChangeDelayConfig = SettingsChangeDelayConfig(),
    val homeLocation: HomeLocation = HomeLocation(),
    /** Apps that a payment pause can open, such as banking or UPI apps. */
    val paymentPauseApps: List<String> = listOf(),
    /**
     * Away mode: started from Home lock after an away check. App groups, the Reels blocker and
     * locks stop; keyword blocking stays on, and the next blocked site checks if the owner is
     * home again, which ends it.
     */
    val isAwayModeActive: Boolean = false,
    /** Days Emergency off stays unavailable after its last use. Chosen when it is used, then fixed. */
    val emergencyOffWaitDays: Int = 30,
    /** When Emergency off was last used, 0 for never. Written only by the emergency itself. */
    val emergencyOffLastUsedAtMs: Long = 0L
)
