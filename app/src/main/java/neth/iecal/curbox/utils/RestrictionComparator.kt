package neth.iecal.curbox.utils

import neth.iecal.curbox.data.models.AppBlockerWarningScreenConfig
import neth.iecal.curbox.data.models.AppGroup
import neth.iecal.curbox.data.models.GatedSettingsField
import neth.iecal.curbox.data.models.KeywordBlocker
import neth.iecal.curbox.data.models.KeywordGroup
import neth.iecal.curbox.data.models.MindfulMessageConfig
import neth.iecal.curbox.data.models.ReelBlocker
import neth.iecal.curbox.data.models.Settings
import neth.iecal.curbox.data.models.SettingsChangeDelayConfig
import neth.iecal.curbox.data.models.UiHiderConfig
import neth.iecal.curbox.hardcoded.allScripts

/**
 * Decides whether a proposed settings value keeps every restriction at least as strong as
 * the current one. The settings change delay applies "same or stricter" changes right away
 * and holds everything else for the countdown.
 *
 * The comparison is deliberately conservative: whenever a change cannot be proven to be
 * same or stricter (unknown fields, unexpected values), it is
 * treated as a reduction and delayed. A restriction that is currently switched off imposes
 * nothing, so any change to it passes.
 */
object RestrictionComparator {

    fun isSameOrStricter(field: GatedSettingsField, current: Settings, proposed: Settings): Boolean {
        return try {
            when (field) {
                GatedSettingsField.APP_GROUPS ->
                    appGroups(current.blockedAppGroups, proposed.blockedAppGroups)
                GatedSettingsField.REEL_BLOCKER ->
                    reelBlocker(current.reelBlockerConfig, proposed.reelBlockerConfig)
                GatedSettingsField.KEYWORD_BLOCKER ->
                    keywordBlocker(current.keywordBlockerConfig, proposed.keywordBlockerConfig)
                GatedSettingsField.REEL_COUNTER ->
                    !current.isReelCounterOn || proposed.isReelCounterOn
                GatedSettingsField.MINDFUL_MESSAGES ->
                    mindfulMessages(current.mindfulMessageConfig, proposed.mindfulMessageConfig)
                GatedSettingsField.UI_HIDER ->
                    uiHider(current.uiHiderConfig, proposed.uiHiderConfig)
                GatedSettingsField.APP_USAGE_TRACKING ->
                    !current.isAppUsageTrackingEnabled || proposed.isAppUsageTrackingEnabled
                GatedSettingsField.WEBSITE_USAGE_TRACKING ->
                    !current.isWebsiteUsageTrackingEnabled || proposed.isWebsiteUsageTrackingEnabled
                GatedSettingsField.CHANGE_DELAY ->
                    changeDelay(current.settingsChangeDelayConfig2, proposed.settingsChangeDelayConfig2)
            }
        } catch (e: Exception) {
            false
        }
    }

    fun changeDelay(old: SettingsChangeDelayConfig, new: SettingsChangeDelayConfig): Boolean {
        // Off or set to no wait means the timer holds nothing, so any change to it passes
        val timeOk = !old.isEnabled || old.delayMinutes <= 0 ||
            (new.isEnabled && new.delayMinutes >= old.delayMinutes)
        // Turning this off is itself a reduction, so it has to earn its own way past the gate
        val tamperGateOk = !old.requireTamperProtectionOff || new.requireTamperProtectionOff
        return timeOk && tamperGateOk
    }

    fun appGroups(old: List<AppGroup>, new: List<AppGroup>): Boolean {
        return old.filter { it.isActive || it.temporarilyDisabledUntilMs > 0L }.all { o ->
            val n = new.find { it.id == o.id } ?: return@all false
            appGroup(o, n)
        }
    }

    fun appGroup(o: AppGroup, n: AppGroup): Boolean {
        if (!temporaryDisableSameOrShorter(o.isActive, o.temporarilyDisabledUntilMs,
                n.isActive, n.temporarilyDisabledUntilMs)) return false
        if (!n.selectedPackages.containsAll(o.selectedPackages)) return false
        if (!n.websites.containsAll(o.websites)) return false
        return warningConfig(o.warningScreenConfig, n.warningScreenConfig)
    }

    fun keywordBlocker(old: KeywordBlocker, new: KeywordBlocker): Boolean {
        if (!old.isActive) return true
        if (!new.isActive) return false
        if (old.blockAllExceptSupported && !new.blockAllExceptSupported) return false
        return old.keywordGroups.filter { it.isActive || it.temporarilyDisabledUntilMs > 0L }.all { o ->
            val n = new.keywordGroups.find { it.id == o.id } ?: return@all false
            keywordGroup(o, n)
        }
    }

    fun keywordGroup(o: KeywordGroup, n: KeywordGroup): Boolean {
        if (!temporaryDisableSameOrShorter(o.isActive, o.temporarilyDisabledUntilMs,
                n.isActive, n.temporarilyDisabledUntilMs)) return false
        if (!n.selectedKeywords.toHashSet().containsAll(o.selectedKeywords)) return false
        if (o.blockUnsupportedBrowsers && !n.blockUnsupportedBrowsers) return false
        return warningConfig(o.warningScreenConfig, n.warningScreenConfig)
    }

    fun reelBlocker(o: ReelBlocker, n: ReelBlocker): Boolean {
        if (!o.isActive && o.temporarilyDisabledUntilMs == 0L) return true
        if (!temporaryDisableSameOrShorter(o.isActive, o.temporarilyDisabledUntilMs,
                n.isActive, n.temporarilyDisabledUntilMs)) return false
        if (!o.excludedPackages.containsAll(n.excludedPackages)) return false
        if (!o.allowInstagramReelsFromDmInbox && n.allowInstagramReelsFromDmInbox) return false
        if (!warningConfig(o.warningScreenConfig, n.warningScreenConfig)) return false
        val oldCount = dailyReelCounts(o)
        val newCount = dailyReelCounts(n)
        return oldCount.size == 7 && newCount.size == 7 &&
            newCount.indices.all { reelAllowanceSameOrLower(oldCount[it], newCount[it]) }
    }

    // Zero means no allowance, so short videos are blocked all day; that is the strictest.
    private fun reelAllowanceSameOrLower(old: Int, new: Int): Boolean =
        new == 0 || (old > 0 && new in 1..old)

    private fun dailyReelCounts(blocker: ReelBlocker): List<Int> {
        val count = blocker.config?.reelCount ?: return List(7) { 0 }
        return if (count.isDailyUniform) List(7) { count.uniformLimit } else count.dailyLimits.toList()
    }

    fun mindfulMessages(old: MindfulMessageConfig, new: MindfulMessageConfig): Boolean {
        if (!old.isActive) return true
        return new.isActive && new.selectedApps.containsAll(old.selectedApps)
    }

    fun uiHider(old: UiHiderConfig, new: UiHiderConfig): Boolean {
        if (!old.isActive) return true
        if (!new.isActive) return false
        return old.allScripts().filter { it.isEnabled }.all { o ->
            val n = new.allScripts().find { it.id == o.id } ?: return@all false
            n.isEnabled && n.packageName == o.packageName && n.source == o.source
        }
    }

    private fun temporaryDisableSameOrShorter(
        oldActive: Boolean, oldUntil: Long, newActive: Boolean, newUntil: Long
    ): Boolean = if (newActive) newUntil == 0L
        else !oldActive && oldUntil > 0L && newUntil in 1..oldUntil

    fun warningConfig(o: AppBlockerWarningScreenConfig, n: AppBlockerWarningScreenConfig): Boolean {
        // Message and typing sentence wording never change how strong the block is
        if (o.copy(message = listOf(), typingSentence = "") == n.copy(message = listOf(), typingSentence = "")) return true

        val onEachOpenOk = !o.isOnOpenConfig || n.isOnOpenConfig
        val cooldownOk = n.isOnOpenConfig || n.timeInterval <= o.timeInterval
        val dynamicIntervalOk = n.isOnOpenConfig ||
            !n.isDynamicIntervalSettingAllowed ||
            o.isDynamicIntervalSettingAllowed
        val proceedDisabledOk = !o.isProceedDisabled || n.isProceedDisabled
        val dialogHiddenOk = !o.isWarningDialogHidden || n.isWarningDialogHidden
        val proceedDelayOk = n.proceedDelayInSecs >= o.proceedDelayInSecs
        val lockAfterOk = n.lockAfterUnlockMs >= o.lockAfterUnlockMs
        val closeAppsOk = o.lockAfterUnlockMs <= 0L || !o.closeAppsWhileLocked || n.closeAppsWhileLocked
        // Pausing the unlock while away gives more time in total, so turning it on or keeping
        // leftover time longer is weaker.
        val pauseUnlockOk = o.pauseUnlockWhenAway || !n.pauseUnlockWhenAway
        val keepLeftoverOk = !o.pauseUnlockWhenAway || !n.pauseUnlockWhenAway ||
            n.keepLeftoverMs <= o.keepLeftoverMs
        val vibrateOk = !o.vibrateAndIncBrightness || n.vibrateAndIncBrightness
        val proceedLimitOk = when {
            o.proceedLimitEnabled && !n.proceedLimitEnabled -> false
            o.proceedLimitEnabled && n.proceedLimitEnabled ->
                n.allowedProceeds <= o.allowedProceeds && n.proceedsTimeWindowMn >= o.proceedsTimeWindowMn
            else -> true
        }
        // QR and NFC share the same rule: a key/tag is a new way to unlock, so keys may only be
        // kept or removed, and a kept key may not unlock for longer than before.
        val qrOk = unlockKeysOk(o.isQrUnlockRequirementEnabled, n.isQrUnlockRequirementEnabled, o.qrKeys, n.qrKeys)
        val nfcOk = unlockKeysOk(o.isNfcUnlockRequirementEnabled, n.isNfcUnlockRequirementEnabled, o.nfcKeys, n.nfcKeys)
        val typingOk = !o.isTypingRequirementEnabled || n.isTypingRequirementEnabled
        val intentOk = !o.isIntentRequirementEnabled || n.isIntentRequirementEnabled
        val adaptiveMathOk = !o.isAdaptiveMathRequirementEnabled ||
            n.isAdaptiveMathRequirementEnabled
        val adaptiveMathQuestionCountOk = when {
            !o.isAdaptiveMathRequirementEnabled || !n.isAdaptiveMathRequirementEnabled -> true
            else -> n.adaptiveMathQuestionCount.coerceAtLeast(1) >=
                o.adaptiveMathQuestionCount.coerceAtLeast(1)
        }
        val adaptiveMathStartingLevelOk = when {
            !o.isAdaptiveMathRequirementEnabled || !n.isAdaptiveMathRequirementEnabled -> true
            else -> n.adaptiveMathStartingLevel.coerceIn(1, 10) >=
                o.adaptiveMathStartingLevel.coerceIn(1, 10)
        }
        val stepsOk = !o.isStepsRequirementEnabled || n.isStepsRequirementEnabled
        val requiredStepsOk = when {
            !o.isStepsRequirementEnabled || !n.isStepsRequirementEnabled -> true
            else -> n.requiredSteps.coerceAtLeast(1) >= o.requiredSteps.coerceAtLeast(1)
        }
        val focusGoalOk = when {
            o.isFocusGoalRequirementEnabled && !n.isFocusGoalRequirementEnabled -> false
            o.isFocusGoalRequirementEnabled && n.isFocusGoalRequirementEnabled ->
                n.focusGoalGroupId == o.focusGoalGroupId &&
                    n.focusGoalRequiredMinutes.coerceAtLeast(1) >=
                    o.focusGoalRequiredMinutes.coerceAtLeast(1)
            else -> true
        }
        val intentMinLengthOk = when {
            !o.isIntentRequirementEnabled || !n.isIntentRequirementEnabled -> true
            else -> n.minIntentLength.coerceAtLeast(1) >= o.minIntentLength.coerceAtLeast(1)
        }

        return onEachOpenOk && cooldownOk && dynamicIntervalOk &&
            proceedDisabledOk && dialogHiddenOk &&
            proceedDelayOk && lockAfterOk && closeAppsOk && pauseUnlockOk && keepLeftoverOk && vibrateOk && proceedLimitOk && qrOk && nfcOk && typingOk && intentOk &&
            adaptiveMathOk && adaptiveMathQuestionCountOk && adaptiveMathStartingLevelOk &&
            stepsOk && requiredStepsOk && focusGoalOk && intentMinLengthOk
    }

    /**
     * Shared "unlock keys may only get stricter" rule for QR and NFC. Disabling the requirement or
     * adding a new key/tag weakens the block; a kept key may not unlock for longer than before, and
     * dynamic timing (-1) is only same-or-stricter when it is unchanged.
     */
    private fun unlockKeysOk(
        oldEnabled: Boolean,
        newEnabled: Boolean,
        oldKeys: Map<String, Long>,
        newKeys: Map<String, Long>
    ): Boolean = when {
        oldEnabled && !newEnabled -> false
        oldEnabled && newEnabled -> newKeys.all { (key, duration) ->
            val oldDuration = oldKeys[key] ?: return@all false
            if (oldDuration == -1L || duration == -1L) duration == oldDuration
            else duration <= oldDuration
        }
        else -> true
    }
}
