package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.shared

import android.transition.AutoTransition
import android.transition.TransitionManager
import android.content.Intent
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.widget.PopupMenu
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.AppBlockerWarningScreenConfig
import neth.iecal.curbox.databinding.FragmentWarningConfigBinding
import neth.iecal.curbox.ui.activity.StepCounting
import neth.iecal.curbox.databinding.ItemWarningMessageBinding

internal class WarningConfigFormController(
    private val fragment: Fragment,
    private val binding: FragmentWarningConfigBinding,
    private val onEachOpenChanged: () -> Unit
) {
    private var supportsOnEachOpen = false
    // App and keyword groups; their unlocks can pause while their apps or pages are not open.
    private var supportsPauseUnlock = false
    private val warningChallengeOptions by lazy {
        createWarningChallengeOptions(fragment.requireContext())
    }
    private val warningEffortOptions by lazy {
        createWarningEffortOptions(fragment.requireContext())
    }
    private val warningNoEffortOptions by lazy {
        createWarningNoEffortOptions(fragment.requireContext())
    }
    private var focusGroupOptions: List<FocusGroupUnlockOption> = emptyList()
    private var selectedFocusGroupId = ""
    private val messageBoxes = mutableListOf<ItemWarningMessageBinding>()
    private val proceedDelayField by lazy {
        SecondsField(
            binding.proceedDelayInput,
            binding.proceedDelayInputLayout,
            binding.proceedDelayUnitBtn,
            minimumSeconds = 0L
        )
    }
    private val lockAfterField by lazy {
        SecondsField(
            binding.lockAfterInput,
            binding.lockAfterInputLayout,
            binding.lockAfterUnitBtn,
            minimumSeconds = 1L
        )
    }

    private val keepLeftoverField by lazy {
        SecondsField(
            binding.keepLeftoverInput,
            binding.keepLeftoverInputLayout,
            binding.keepLeftoverUnitBtn,
            minimumSeconds = 1L
        )
    }

    fun bind(
        config: AppBlockerWarningScreenConfig,
        isNew: Boolean,
        supportsOnEachOpen: Boolean,
        supportsPauseUnlock: Boolean
    ) {
        this.supportsOnEachOpen = supportsOnEachOpen
        this.supportsPauseUnlock = supportsPauseUnlock
        val isOnEachOpenEnabled = supportsOnEachOpen && config.isOnOpenConfig
        binding.switchOnEachOpen.isVisible = supportsOnEachOpen
        binding.switchOnEachOpen.isChecked = isOnEachOpenEnabled
        binding.unlockChallengeDropdown.setAdapter(
            WarningUnlockOptionAdapter(fragment.requireContext(), warningChallengeOptions)
        )

        if (isNew) {
            hideUnlockConfiguration()
        } else {
            val selection = WarningUnlockSelectionMapper.fromConfig(
                config,
                isOnEachOpenEnabled
            )
            binding.unlockChallengeDropdown.setText(
                warningChallengeOptions[selection.challengeIndex].title,
                false
            )
            updateSecondaryDropdown(selection.challengeIndex)
            if (selection.secondaryIndex != -1) {
                binding.secondaryBehaviorDropdown.setText(
                    secondaryOptions(selection.challengeIndex)[selection.secondaryIndex].title,
                    false
                )
            }
            updateUiVisibility(
                selection.challengeIndex,
                selection.secondaryIndex,
                animate = isOnEachOpenEnabled
            )
        }

        binding.typingSentenceEdit.setText(config.typingSentence)
        val intentMinLength = config.minIntentLength.coerceAtLeast(1)
        updateIntentMinLengthInput(intentMinLength.toLong())

        val mathQuestionCount = config.adaptiveMathQuestionCount.coerceAtLeast(MIN_MATH_QUESTIONS)
        updateMathQuestionCountInput(mathQuestionCount.toLong())
        val mathStartingLevel = config.adaptiveMathStartingLevel.coerceAtLeast(MIN_MATH_LEVEL)
        updateMathStartingLevelInput(mathStartingLevel.toLong())
        updateStepsCountInput(config.requiredSteps.coerceAtLeast(MIN_STEPS).toLong())

        val focusGoalMinutes = config.focusGoalRequiredMinutes.coerceAtLeast(MIN_FOCUS_MINUTES)
        updateFocusGoalMinutesInput(focusGoalMinutes.toLong())
        selectedFocusGroupId = config.focusGoalGroupId
        binding.focusGoalRequirementSwitch.isChecked = config.isFocusGoalRequirementEnabled
        // The Focus tab was removed, so a focus goal can no longer be met. Only show the switch
        // to groups that already use it, so they can turn it off.
        binding.focusGoalRequirementSwitch.isVisible = config.isFocusGoalRequirementEnabled
        binding.focusGoalSetupContainer.isVisible = config.isFocusGoalRequirementEnabled

        val fixedTimeMinutes = (config.timeInterval / 60_000L).coerceAtLeast(1L)
        updateFixedTimeInput(fixedTimeMinutes)

        proceedDelayField.bind(config.proceedDelayInSecs.coerceAtLeast(0).toLong())

        val lockAfterSeconds = config.lockAfterUnlockMs / 1000L
        binding.lockAfterSwitch.isChecked = lockAfterSeconds > 0
        binding.lockAfterInputs.isVisible = lockAfterSeconds > 0
        binding.lockAfterCloseSwitch.isChecked = config.closeAppsWhileLocked
        // Closing apps only applies to app groups, the only form that supports "each open".
        binding.lockAfterCloseSwitch.isVisible = supportsOnEachOpen
        binding.lockAfterCloseDesc.isVisible = supportsOnEachOpen
        lockAfterField.bind(if (lockAfterSeconds > 0) lockAfterSeconds else DEFAULT_LOCK_AFTER_SECONDS)
        refreshNotificationAccess()

        binding.pauseUnlockSection.isVisible = supportsPauseUnlock && !isOnEachOpenEnabled
        binding.pauseUnlockSwitch.isChecked = config.pauseUnlockWhenAway
        binding.keepLeftoverInputs.isVisible = config.pauseUnlockWhenAway
        keepLeftoverField.bind((config.keepLeftoverMs / 1000L).coerceAtLeast(1L))

        binding.proceedLimitSwitch.isChecked = config.proceedLimitEnabled
        binding.proceedLimitContainer.isVisible = config.proceedLimitEnabled

        val allowedProceeds = config.allowedProceeds.coerceAtLeast(1)
        updateAllowedProceedsInput(allowedProceeds.toLong())

        val totalMinutes = config.proceedsTimeWindowMn
        val (initialUnitIndex, initialInputValue) = when {
            totalMinutes > 0 && totalMinutes % MINUTES_PER_DAY == 0L ->
                DAYS_INDEX to (totalMinutes / MINUTES_PER_DAY)
            totalMinutes > 0 && totalMinutes % MINUTES_PER_HOUR == 0L ->
                HOURS_INDEX to (totalMinutes / MINUTES_PER_HOUR)
            else -> MINUTES_INDEX to totalMinutes.coerceAtLeast(1L)
        }
        binding.proceedWindowUnitBtn.text = unitOptions()[initialUnitIndex]
        updateProceedWindowInput(initialInputValue)

        binding.warningMsgContainer.removeAllViews()
        messageBoxes.clear()
        config.message.ifEmpty { listOf("") }.forEach(::addMessageBox)
        binding.switchVibrateBrightness.isChecked = config.vibrateAndIncBrightness
    }

    fun setupListeners(onSave: () -> Unit) {
        binding.btnAddWarningMsg.setOnClickListener {
            addMessageBox("")
            messageBoxes.last().warningMsgEdit.requestFocus()
        }
        binding.unlockChallengeDropdown.setOnItemClickListener { _, _, position, _ ->
            updateSecondaryDropdown(position)
            val isOnEachOpenEnabled = isOnEachOpenEnabled()
            if (!(isOnEachOpenEnabled &&
                    position == WarningUnlockSelectionMapper.NO_EFFORT_INDEX)) {
                binding.secondaryBehaviorDropdown.setText("", false)
            }
            val secondaryIndex =
                if (isOnEachOpenEnabled &&
                    position == WarningUnlockSelectionMapper.NO_EFFORT_INDEX) {
                    WarningUnlockSelectionMapper.FIXED_TIME_INDEX
                } else {
                    -1
                }
            updateUiVisibility(position, secondaryIndex, animate = true)
        }

        binding.secondaryBehaviorDropdown.setOnItemClickListener { _, _, position, _ ->
            updateUiVisibility(selectedChallengeIndex(), position, animate = true)
        }

        setupNumericInput(
            minimumValue = { 1L },
            binding.fixedTimeInput,
            binding.fixedTimeInputLayout,
            maximumValue = { Long.MAX_VALUE / 60_000L },
            updateInput = ::updateFixedTimeInput
        )
        proceedDelayField.setupListeners()
        lockAfterField.setupListeners()
        keepLeftoverField.setupListeners()
        binding.pauseUnlockSwitch.setOnCheckedChangeListener { _, isChecked ->
            TransitionManager.beginDelayedTransition(binding.mainContentContainer, AutoTransition())
            binding.keepLeftoverInputs.isVisible = isChecked
        }
        binding.btnLockAfterNotificationAccess.setOnClickListener {
            runCatching {
                fragment.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }
        binding.lockAfterSwitch.setOnCheckedChangeListener { _, isChecked ->
            TransitionManager.beginDelayedTransition(binding.mainContentContainer, AutoTransition())
            binding.lockAfterInputs.isVisible = isChecked
            refreshNotificationAccess()
        }
        binding.proceedLimitSwitch.setOnCheckedChangeListener { _, isChecked ->
            binding.proceedLimitContainer.isVisible = isChecked
        }
        binding.switchOnEachOpen.setOnCheckedChangeListener { _, isChecked ->
            val challengeIndex = selectedChallengeIndex()
            if (isChecked && challengeIndex == WarningUnlockSelectionMapper.NO_EFFORT_INDEX) {
                binding.secondaryBehaviorDropdown.setText(
                    warningNoEffortOptions[WarningUnlockSelectionMapper.FIXED_TIME_INDEX].title,
                    false
                )
            }
            updateSecondaryDropdown(challengeIndex)
            updateUiVisibility(
                challengeIndex,
                selectedSecondaryIndex(challengeIndex),
                animate = true
            )
            onEachOpenChanged()
        }
        setupNumericInput(
            minimumValue = { 1L },
            binding.allowedProceedsInput,
            binding.allowedProceedsInputLayout,
            updateInput = ::updateAllowedProceedsInput
        )
        setupNumericInput(
            minimumValue = { 1L },
            binding.proceedWindowInput,
            binding.proceedWindowInputLayout,
            maximumValue = {
                Long.MAX_VALUE / 60_000L / minutesForUnit(selectedUnitIndex())
            },
            updateInput = ::updateProceedWindowInput
        )
        binding.proceedWindowUnitBtn.setOnClickListener { button ->
            showProceedWindowUnitMenu(button)
        }
        setupNumericInput(
            minimumValue = { 1L },
            binding.intentMinLengthInput,
            binding.intentMinLengthInputLayout,
            updateInput = ::updateIntentMinLengthInput
        )
        setupNumericInput(
            minimumValue = { MIN_MATH_QUESTIONS.toLong() },
            binding.mathQuestionCountInput,
            binding.mathQuestionCountInputLayout,
            updateInput = ::updateMathQuestionCountInput
        )
        setupNumericInput(
            minimumValue = { MIN_MATH_LEVEL.toLong() },
            binding.mathStartingLevelInput,
            binding.mathStartingLevelInputLayout,
            updateInput = ::updateMathStartingLevelInput
        )
        setupNumericInput(
            minimumValue = { MIN_STEPS.toLong() },
            binding.stepsCountInput,
            binding.stepsCountInputLayout,
            updateInput = ::updateStepsCountInput
        )
        setupNumericInput(
            minimumValue = { MIN_FOCUS_MINUTES.toLong() },
            binding.focusGoalMinutesInput,
            binding.focusGoalMinutesInputLayout,
            updateInput = ::updateFocusGoalMinutesInput
        )
        binding.focusGoalGroupDropdown.setOnItemClickListener { _, _, position, _ ->
            selectedFocusGroupId = focusGroupOptions[position].groupId
            binding.focusGoalGroupLayout.error = null
        }
        binding.focusGoalRequirementSwitch.setOnCheckedChangeListener { _, isChecked ->
            binding.focusGoalSetupContainer.isVisible = isChecked
            if (!isChecked) {
                binding.focusGoalGroupLayout.error = null
            }
        }
        binding.advancedSettingsHeader.setOnClickListener {
            val isCurrentlyVisible = binding.advancedSettingsContent.isVisible
            TransitionManager.beginDelayedTransition(
                binding.mainContentContainer,
                AutoTransition()
            )
            binding.advancedSettingsContent.isVisible = !isCurrentlyVisible
            binding.advancedSettingsArrow.animate()
                .rotation(if (isCurrentlyVisible) 0f else 90f)
                .start()
        }
        binding.saveconfigs.setOnClickListener { onSave() }
    }

    private fun addMessageBox(text: String) {
        val box = ItemWarningMessageBinding.inflate(
            LayoutInflater.from(fragment.requireContext()),
            binding.warningMsgContainer,
            false
        )
        // Every box shares one view id, so Android's own state restore would mix up their text.
        box.warningMsgEdit.isSaveEnabled = false
        box.warningMsgEdit.setText(text)
        box.root.setEndIconOnClickListener {
            binding.warningMsgContainer.removeView(box.root)
            messageBoxes.remove(box)
            updateMessageBoxes()
        }
        binding.warningMsgContainer.addView(box.root)
        messageBoxes.add(box)
        updateMessageBoxes()
    }

    private fun updateMessageBoxes() {
        val hasSeveral = messageBoxes.size > 1
        messageBoxes.forEachIndexed { index, box ->
            box.root.hint = if (hasSeveral) {
                fragment.getString(R.string.warning_message_number, index + 1)
            } else {
                fragment.getString(R.string.hint_custom_warning)
            }
            // The last box stays so there is always somewhere to type.
            box.root.isEndIconVisible = hasSeveral
        }
        binding.warningMsgRandomNote.isVisible = hasSeveral
    }

    /** The lock after an unlock can only be turned on once a home is set, because home is what lets it be turned off. */
    /** Shows the notification access prompt while the lock is on and the access is missing. */
    fun refreshNotificationAccess() {
        val context = fragment.context ?: return
        val hasAccess = NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)
        binding.lockAfterNotificationSetup.isVisible = binding.lockAfterSwitch.isChecked && !hasAccess
    }

    fun createConfig(
        qrKeys: Map<String, Long>,
        nfcKeys: Map<String, Long>
    ): AppBlockerWarningScreenConfig {
        val flags = WarningUnlockSelectionMapper.toFlags(
            selectedChallengeIndex(),
            selectedSecondaryIndex(selectedChallengeIndex())
        )
        return AppBlockerWarningScreenConfig(
            message = messageBoxes
                .map { it.warningMsgEdit.text.toString().trim() }
                .filter { it.isNotEmpty() },

            timeInterval = numericInputValue(binding.fixedTimeInput) * 60_000L,
            isDynamicIntervalSettingAllowed = flags.isDynamicIntervalSettingAllowed,
            isProceedDisabled = flags.isProceedDisabled,
            isWarningDialogHidden = false,
            isQrUnlockRequirementEnabled = flags.isQrUnlockRequirementEnabled,
            qrKeys = if (flags.isQrUnlockRequirementEnabled) qrKeys else emptyMap(),
            isNfcUnlockRequirementEnabled = flags.isNfcUnlockRequirementEnabled,
            nfcKeys = if (flags.isNfcUnlockRequirementEnabled) nfcKeys else emptyMap(),
            isTypingRequirementEnabled = flags.isTypingRequirementEnabled,
            typingSentence = binding.typingSentenceEdit.text.toString(),
            isIntentRequirementEnabled = flags.isIntentRequirementEnabled,
            minIntentLength = numericInputValue(binding.intentMinLengthInput).toInt(),
            isAdaptiveMathRequirementEnabled = flags.isAdaptiveMathRequirementEnabled,
            adaptiveMathQuestionCount = numericInputValue(binding.mathQuestionCountInput).toInt(),
            adaptiveMathStartingLevel = numericInputValue(binding.mathStartingLevelInput).toInt(),
            isStepsRequirementEnabled = flags.isStepsRequirementEnabled,
            requiredSteps = numericInputValue(binding.stepsCountInput).toInt(),
            isFocusGoalRequirementEnabled = binding.focusGoalRequirementSwitch.isChecked,
            focusGoalGroupId = if (binding.focusGoalRequirementSwitch.isChecked) {
                selectedFocusGroupId
            } else {
                ""
            },
            focusGoalRequiredMinutes = numericInputValue(binding.focusGoalMinutesInput).toInt(),
            proceedDelayInSecs = proceedDelayField.seconds().toInt(),
            lockAfterUnlockMs = if (binding.lockAfterSwitch.isChecked) {
                lockAfterField.seconds() * 1000L
            } else {
                0L
            },
            closeAppsWhileLocked = binding.lockAfterCloseSwitch.isChecked,
            pauseUnlockWhenAway = binding.pauseUnlockSwitch.isChecked,
            keepLeftoverMs = keepLeftoverField.seconds() * 1000L,
            vibrateAndIncBrightness = binding.switchVibrateBrightness.isChecked,
            proceedLimitEnabled = binding.proceedLimitSwitch.isChecked,
            allowedProceeds = numericInputValue(binding.allowedProceedsInput).toInt(),
            proceedsTimeWindowMn = selectedProceedWindowMinutes(),
            isOnOpenConfig = isOnEachOpenEnabled()
        )
    }

    fun isOnEachOpenEnabled(): Boolean {
        return supportsOnEachOpen && binding.switchOnEachOpen.isChecked
    }

    fun bindFocusGroups() {
        // Focus mode was removed, so there are no focus groups to pick.
        focusGroupOptions = emptyList()
        binding.focusGoalGroupDropdown.setAdapter(
            android.widget.ArrayAdapter(
                fragment.requireContext(),
                android.R.layout.simple_dropdown_item_1line,
                focusGroupOptions
            )
        )
        val selectedGroup = focusGroupOptions.firstOrNull {
            it.groupId == selectedFocusGroupId
        }
        binding.focusGoalGroupDropdown.setText(selectedGroup?.name.orEmpty(), false)
    }

    fun validate(): Boolean {
        if (!numericInputs().all { validateNumericInput(it) }) {
            return false
        }
        if (isStepsSelected() && !StepCounting.isAvailable(fragment.requireContext())) {
            binding.stepsNoSensor.isVisible = true
            return false
        }
        if (binding.focusGoalRequirementSwitch.isChecked &&
            focusGroupOptions.none { it.groupId == selectedFocusGroupId }
        ) {
            binding.focusGoalGroupLayout.error = fragment.getString(
                R.string.warning_focus_goal_group_required
            )
            return false
        }
        return true
    }

    private fun setupNumericInput(
        minimumValue: () -> Long,
        input: TextInputEditText,
        inputLayout: TextInputLayout,
        maximumValue: () -> Long = { Int.MAX_VALUE.toLong() },
        updateInput: (Long) -> Unit
    ) {
        input.doAfterTextChanged { editable ->
            val value = editable?.toString()?.toLongOrNull()
            inputLayout.error = when {
                value == null -> null
                value < minimumValue() -> minimumError(minimumValue())
                value > maximumValue() -> fragment.getString(R.string.warning_numeric_value_too_large)
                else -> null
            }
        }
        input.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus && !isNumericInputValid(minimumValue(), input, maximumValue())) {
                inputLayout.error = null
                val value = input.text?.toString()?.toLongOrNull() ?: minimumValue()
                updateInput(value.coerceIn(minimumValue(), maximumValue()))
            }
        }
    }

    private fun numericInputs(): List<NumericInput> {
        return listOf(
            NumericInput(
                1L,
                binding.fixedTimeInput,
                binding.fixedTimeInputLayout,
                Long.MAX_VALUE / 60_000L
            ),
            proceedDelayField.numericInput(),
            lockAfterField.numericInput(),
            keepLeftoverField.numericInput(),
            NumericInput(
                1L,
                binding.allowedProceedsInput,
                binding.allowedProceedsInputLayout
            ),
            NumericInput(
                1L,
                binding.proceedWindowInput,
                binding.proceedWindowInputLayout,
                Long.MAX_VALUE / 60_000L / minutesForUnit(selectedUnitIndex())
            ),
            NumericInput(
                1L,
                binding.intentMinLengthInput,
                binding.intentMinLengthInputLayout
            ),
            NumericInput(
                MIN_MATH_QUESTIONS.toLong(),
                binding.mathQuestionCountInput,
                binding.mathQuestionCountInputLayout
            ),
            NumericInput(
                MIN_MATH_LEVEL.toLong(),
                binding.mathStartingLevelInput,
                binding.mathStartingLevelInputLayout
            ),
            NumericInput(
                MIN_STEPS.toLong(),
                binding.stepsCountInput,
                binding.stepsCountInputLayout
            ),
            NumericInput(
                MIN_FOCUS_MINUTES.toLong(),
                binding.focusGoalMinutesInput,
                binding.focusGoalMinutesInputLayout
            )
        )
    }

    private fun validateNumericInput(numericInput: NumericInput): Boolean {
        if (isNumericInputValid(
                numericInput.minimumValue,
                numericInput.input,
                numericInput.maximumValue
            )
        ) {
            numericInput.inputLayout.error = null
            return true
        }
        val value = numericInput.input.text?.toString()?.toLongOrNull()
        numericInput.inputLayout.error = if (value != null && value > numericInput.maximumValue) {
            fragment.getString(R.string.warning_numeric_value_too_large)
        } else {
            minimumError(numericInput.minimumValue)
        }
        numericInput.input.requestFocus()
        return false
    }

    private fun isNumericInputValid(
        minimumValue: Long,
        input: TextInputEditText,
        maximumValue: Long
    ): Boolean {
        val value = input.text?.toString()?.toLongOrNull() ?: return false
        return value in minimumValue..maximumValue
    }

    private fun minimumError(minimumValue: Long): String =
        fragment.getString(R.string.warning_numeric_value_minimum, minimumValue.toInt())

    private fun hideUnlockConfiguration() {
        binding.secondaryBehaviorLayout.isVisible = false
        binding.timingContainer.isVisible = false
        binding.proceedDelayContainer.isVisible = false
        binding.qrSetupContainer.isVisible = false
        binding.nfcSetupContainer.isVisible = false
        binding.typingSetupContainer.isVisible = false
        binding.mathSetupContainer.isVisible = false
        binding.stepsSetupContainer.isVisible = false
    }

    private fun updateSecondaryDropdown(challengeIndex: Int) {
        if (isOnEachOpenEnabled() &&
            challengeIndex == WarningUnlockSelectionMapper.NO_EFFORT_INDEX) {
            binding.secondaryBehaviorLayout.isVisible = false
            binding.secondaryBehaviorDropdown.setText(
                warningNoEffortOptions[WarningUnlockSelectionMapper.FIXED_TIME_INDEX].title,
                false
            )
            return
        }
        val options = when (challengeIndex) {
            WarningUnlockSelectionMapper.EFFORT_INDEX -> warningEffortOptions
            WarningUnlockSelectionMapper.NO_EFFORT_INDEX -> warningNoEffortOptions
            else -> null
        }
        if (options == null) {
            binding.secondaryBehaviorLayout.isVisible = false
        } else {
            binding.secondaryBehaviorDropdown.setAdapter(
                WarningUnlockOptionAdapter(fragment.requireContext(), options)
            )
            binding.secondaryBehaviorLayout.isVisible = true
        }
    }

    private fun updateUiVisibility(
        challengeIndex: Int,
        secondaryIndex: Int,
        animate: Boolean
    ) {
        if (animate) {
            TransitionManager.beginDelayedTransition(
                binding.mainContentContainer,
                AutoTransition()
            )
        }
        val usesSharedTiming = when (challengeIndex) {
            WarningUnlockSelectionMapper.NO_EFFORT_INDEX ->
                secondaryIndex == WarningUnlockSelectionMapper.FIXED_TIME_INDEX
            WarningUnlockSelectionMapper.EFFORT_INDEX ->
                secondaryIndex == 1 || secondaryIndex == 2 || secondaryIndex == 4 ||
                    secondaryIndex == STEPS_INDEX
            else -> false
        }
        binding.timingContainer.isVisible = !isOnEachOpenEnabled() && usesSharedTiming
        // Walking replaces the wait, so its box is hidden. The stored wait is kept, so switching
        // back to another method still needs it.
        binding.proceedDelayContainer.isVisible =
            challengeIndex != WarningUnlockSelectionMapper.NEVER_UNLOCK_INDEX &&
                challengeIndex != -1 &&
                !(challengeIndex == WarningUnlockSelectionMapper.EFFORT_INDEX &&
                    secondaryIndex == STEPS_INDEX)
        // An each open unlock never runs out on a timer, so there is nothing to lock after.
        binding.lockAfterSection.isVisible = !isOnEachOpenEnabled()
        binding.pauseUnlockSection.isVisible = supportsPauseUnlock && !isOnEachOpenEnabled()
        binding.qrSetupContainer.isVisible =
            challengeIndex == WarningUnlockSelectionMapper.EFFORT_INDEX &&
                secondaryIndex == 0
        binding.typingSetupContainer.isVisible =
            challengeIndex == WarningUnlockSelectionMapper.EFFORT_INDEX &&
                secondaryIndex == 1
        binding.intentSetupContainer.isVisible =
            challengeIndex == WarningUnlockSelectionMapper.EFFORT_INDEX &&
                secondaryIndex == 2
        binding.nfcSetupContainer.isVisible =
            challengeIndex == WarningUnlockSelectionMapper.EFFORT_INDEX &&
                secondaryIndex == 3
        binding.mathSetupContainer.isVisible =
            challengeIndex == WarningUnlockSelectionMapper.EFFORT_INDEX &&
                secondaryIndex == 4
        val isSteps = challengeIndex == WarningUnlockSelectionMapper.EFFORT_INDEX &&
            secondaryIndex == STEPS_INDEX
        binding.stepsSetupContainer.isVisible = isSteps
        binding.stepsNoSensor.isVisible =
            isSteps && !StepCounting.isAvailable(fragment.requireContext())
    }

    private fun isStepsSelected(): Boolean {
        val challengeIndex = selectedChallengeIndex()
        return challengeIndex == WarningUnlockSelectionMapper.EFFORT_INDEX &&
            selectedSecondaryIndex(challengeIndex) == STEPS_INDEX
    }

    private fun selectedChallengeIndex(): Int {
        val selectedTitle = binding.unlockChallengeDropdown.text.toString()
        return warningChallengeOptions.indexOfFirst { it.title == selectedTitle }
    }

    private fun selectedSecondaryIndex(challengeIndex: Int): Int {
        val selectedTitle = binding.secondaryBehaviorDropdown.text.toString()
        return secondaryOptions(challengeIndex).indexOfFirst { it.title == selectedTitle }
    }

    private fun secondaryOptions(challengeIndex: Int): List<WarningUnlockOption> {
        return when (challengeIndex) {
            WarningUnlockSelectionMapper.EFFORT_INDEX -> warningEffortOptions
            WarningUnlockSelectionMapper.NO_EFFORT_INDEX -> warningNoEffortOptions
            else -> emptyList()
        }
    }

    private fun showProceedWindowUnitMenu(anchor: View) {
        val options = unitOptions()
        PopupMenu(fragment.requireContext(), anchor).apply {
            options.forEachIndexed { index, option ->
                menu.add(0, index, index, option)
            }
            setOnMenuItemClickListener { item ->
                binding.proceedWindowUnitBtn.text = item.title
                true
            }
            show()
        }
    }

    private fun selectedProceedWindowMinutes(): Long {
        return numericInputValue(binding.proceedWindowInput) *
            minutesForUnit(selectedUnitIndex())
    }

    private fun selectedUnitIndex(): Int {
        return unitOptions().indexOf(binding.proceedWindowUnitBtn.text.toString())
            .coerceAtLeast(MINUTES_INDEX)
    }

    private fun minutesForUnit(unitIndex: Int): Long {
        return when (unitIndex) {
            HOURS_INDEX -> MINUTES_PER_HOUR
            DAYS_INDEX -> MINUTES_PER_DAY
            else -> 1L
        }
    }

    private fun updateProceedWindowInput(value: Long) {
        setNumericInputValue(binding.proceedWindowInput, value)
    }

    private fun updateFixedTimeInput(value: Long) {
        setNumericInputValue(binding.fixedTimeInput, value)
    }

    private fun updateAllowedProceedsInput(value: Long) {
        setNumericInputValue(binding.allowedProceedsInput, value)
    }

    private fun updateIntentMinLengthInput(value: Long) {
        setNumericInputValue(binding.intentMinLengthInput, value)
    }

    private fun updateMathQuestionCountInput(value: Long) {
        setNumericInputValue(binding.mathQuestionCountInput, value)
    }

    private fun updateMathStartingLevelInput(value: Long) {
        setNumericInputValue(binding.mathStartingLevelInput, value)
    }

    private fun updateStepsCountInput(value: Long) {
        setNumericInputValue(binding.stepsCountInput, value)
    }

    private fun updateFocusGoalMinutesInput(value: Long) {
        setNumericInputValue(binding.focusGoalMinutesInput, value)
    }

    private fun unitOptions(): List<String> {
        return listOf(
            fragment.getString(R.string.unit_minutes),
            fragment.getString(R.string.unit_hours),
            fragment.getString(R.string.unit_days)
        )
    }

    private fun numericInputValue(input: TextInputEditText): Long {
        return input.text?.toString()?.toLongOrNull() ?: 0L
    }

    private fun setNumericInputValue(input: TextInputEditText, value: Long) {
        val text = value.toString()
        if (input.text?.toString() != text) {
            input.setText(text)
        }
    }

    /** A number with a Seconds, Minutes or Hours button. The value is kept in seconds. */
    private inner class SecondsField(
        private val input: TextInputEditText,
        private val inputLayout: TextInputLayout,
        private val unitButton: MaterialButton,
        private val minimumSeconds: Long
    ) {
        fun bind(totalSeconds: Long) {
            val (unitIndex, value) = when {
                totalSeconds > 0 && totalSeconds % SECONDS_PER_HOUR == 0L ->
                    HOURS_UNIT_INDEX to (totalSeconds / SECONDS_PER_HOUR)
                totalSeconds > 0 && totalSeconds % SECONDS_PER_MINUTE == 0L ->
                    MINUTES_UNIT_INDEX to (totalSeconds / SECONDS_PER_MINUTE)
                else -> SECONDS_UNIT_INDEX to totalSeconds
            }
            unitButton.text = unitOptions()[unitIndex]
            setNumericInputValue(input, value)
        }

        fun setupListeners() {
            setupNumericInput(
                ::minimumValue,
                input,
                inputLayout,
                maximumValue = ::maximumValue,
                updateInput = { setNumericInputValue(input, it) }
            )
            unitButton.setOnClickListener { showUnitMenu(it) }
        }

        fun seconds(): Long = numericInputValue(input) * secondsForUnit(unitIndex())

        fun numericInput() = NumericInput(minimumValue(), input, inputLayout, maximumValue())

        private fun minimumValue(): Long = if (unitIndex() == SECONDS_UNIT_INDEX) minimumSeconds else 1L

        private fun maximumValue(): Long = Int.MAX_VALUE.toLong() / secondsForUnit(unitIndex())

        private fun showUnitMenu(anchor: View) {
            PopupMenu(fragment.requireContext(), anchor).apply {
                unitOptions().forEachIndexed { index, option ->
                    menu.add(0, index, index, option)
                }
                setOnMenuItemClickListener { item ->
                    unitButton.text = item.title
                    true
                }
                show()
            }
        }

        private fun unitIndex(): Int =
            unitOptions().indexOf(unitButton.text.toString()).coerceAtLeast(SECONDS_UNIT_INDEX)

        private fun secondsForUnit(unitIndex: Int): Long = when (unitIndex) {
            MINUTES_UNIT_INDEX -> SECONDS_PER_MINUTE
            HOURS_UNIT_INDEX -> SECONDS_PER_HOUR
            else -> 1L
        }

        private fun unitOptions(): List<String> = listOf(
            fragment.getString(R.string.unit_seconds),
            fragment.getString(R.string.unit_minutes),
            fragment.getString(R.string.unit_hours)
        )
    }

    private companion object {
        const val MINUTES_INDEX = 0
        const val HOURS_INDEX = 1
        const val DAYS_INDEX = 2
        const val MINUTES_PER_HOUR = 60L
        const val MINUTES_PER_DAY = 1_440L
        const val SECONDS_UNIT_INDEX = 0
        const val MINUTES_UNIT_INDEX = 1
        const val HOURS_UNIT_INDEX = 2
        const val SECONDS_PER_MINUTE = 60L
        const val SECONDS_PER_HOUR = 3_600L
        const val DEFAULT_LOCK_AFTER_SECONDS = 30 * SECONDS_PER_MINUTE
        const val MIN_MATH_QUESTIONS = 1
        const val MIN_STEPS = 100
        const val STEPS_INDEX = 5
        const val MIN_MATH_LEVEL = 1
        const val MIN_FOCUS_MINUTES = 15
    }
}

private data class FocusGroupUnlockOption(
    val groupId: String,
    val name: String
) {
    override fun toString(): String = name
}

private data class NumericInput(
    val minimumValue: Long,
    val input: TextInputEditText,
    val inputLayout: TextInputLayout,
    val maximumValue: Long = Int.MAX_VALUE.toLong()
)
