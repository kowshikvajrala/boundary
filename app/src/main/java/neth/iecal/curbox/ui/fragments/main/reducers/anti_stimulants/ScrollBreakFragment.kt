package neth.iecal.curbox.ui.fragments.main.reducers.anti_stimulants

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import neth.iecal.curbox.R
import neth.iecal.curbox.anti_stimulants.ScrollBreak
import neth.iecal.curbox.data.models.ScrollBreakConfig
import neth.iecal.curbox.databinding.FragmentScrollBreakBinding
import neth.iecal.curbox.utils.DataStoreManager

/**
 * Every setting of scrolling and watching breaks. Changes are saved together with the Save
 * button, so a change that makes breaks easier waits out the settings change delay only once.
 */
class ScrollBreakFragment : Fragment() {

    companion object {
        const val FRAGMENT_ID = "scroll_break"
        private const val MAX_MINUTES = 24 * 60
    }

    private var _binding: FragmentScrollBreakBinding? = null
    private val binding get() = _binding!!
    private var config = ScrollBreakConfig()
    private var scrolledMinutes = 0
    private var watchedMinutes = 0
    private var breakEndsAtMs = 0L

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ScrollBreak.ACTION_STATUS) return
            scrolledMinutes = intent.getIntExtra(ScrollBreak.EXTRA_SCROLLED_MINUTES, 0)
            watchedMinutes = intent.getIntExtra(ScrollBreak.EXTRA_WATCHED_MINUTES, 0)
            breakEndsAtMs = intent.getLongExtra(ScrollBreak.EXTRA_BREAK_ENDS_AT, 0L)
            renderStatus()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentScrollBreakBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnNotificationAccess.setOnClickListener {
            runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        }
        binding.btnSave.setOnClickListener { save() }
        viewLifecycleOwner.lifecycleScope.launch {
            config = DataStoreManager(requireContext()).settings.first().scrollBreakConfig
            fill(config)
        }
    }

    override fun onResume() {
        super.onResume()
        val context = requireContext()
        ContextCompat.registerReceiver(
            context,
            statusReceiver,
            IntentFilter(ScrollBreak.ACTION_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        context.sendBroadcast(Intent(ScrollBreak.ACTION_STATUS_REQUEST).setPackage(context.packageName))
        val hasAccess = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        binding.textNeedsAccess.isVisible = !hasAccess
        binding.btnNotificationAccess.isVisible = !hasAccess
    }

    override fun onPause() {
        super.onPause()
        runCatching { requireContext().unregisterReceiver(statusReceiver) }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun fill(config: ScrollBreakConfig) {
        binding.switchEnabled.isChecked = config.isEnabled
        binding.switchScrolling.isChecked = config.countScrolling
        binding.switchWatching.isChecked = config.countWatching
        binding.switchNotification.isChecked = config.showNotification
        binding.inputScrollMinutes.setText(config.scrollMinutes.toString())
        binding.inputWatchMinutes.setText(config.watchMinutes.toString())
        binding.inputBreakMinutes.setText(config.breakMinutes.toString())
        binding.inputResetMinutes.setText(config.resetMinutes.toString())
        binding.inputWarnMinutes.setText(config.warnMinutes.toString())
        renderStatus()
    }

    /** The number in [input], or null after showing an error when it is outside [min]..[MAX_MINUTES]. */
    private fun readMinutes(input: TextInputEditText, layout: TextInputLayout, min: Int): Int? {
        val value = input.text?.toString()?.toIntOrNull()
        return if (value == null || value < min || value > MAX_MINUTES) {
            layout.error = getString(R.string.scroll_break_value_error, min, MAX_MINUTES)
            null
        } else {
            layout.error = null
            value
        }
    }

    private fun save() {
        val values = listOf(
            readMinutes(binding.inputScrollMinutes, binding.inputScrollMinutesLayout, 1),
            readMinutes(binding.inputWatchMinutes, binding.inputWatchMinutesLayout, 1),
            readMinutes(binding.inputBreakMinutes, binding.inputBreakMinutesLayout, 1),
            readMinutes(binding.inputResetMinutes, binding.inputResetMinutesLayout, 1),
            readMinutes(binding.inputWarnMinutes, binding.inputWarnMinutesLayout, 0)
        )
        if (values.any { it == null }) return
        val (scroll, watch, length, reset, warn) = values.map { it!! }
        val newConfig = ScrollBreakConfig(
            isEnabled = binding.switchEnabled.isChecked,
            countScrolling = binding.switchScrolling.isChecked,
            scrollMinutes = scroll,
            countWatching = binding.switchWatching.isChecked,
            watchMinutes = watch,
            breakMinutes = length,
            resetMinutes = reset,
            warnMinutes = warn,
            showNotification = binding.switchNotification.isChecked
        )
        val context = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            // Easier settings may be held back by the settings change delay, which says so itself.
            DataStoreManager(context).updateScrollBreakConfig { newConfig }
            config = DataStoreManager(context).settings.first().scrollBreakConfig
            fill(config)
            Toast.makeText(context, R.string.scroll_break_saved, Toast.LENGTH_SHORT).show()
        }
    }

    private fun renderStatus() {
        val binding = _binding ?: return
        val breakLeftMs = breakEndsAtMs - System.currentTimeMillis()
        binding.scrollBreakStatus.text = when {
            !config.isEnabled -> getString(R.string.scroll_break_subtitle_off)
            breakLeftMs > 0L -> {
                val totalSeconds = (breakLeftMs + 999) / 1_000L
                getString(R.string.scroll_break_status_break, "%d:%02d".format(totalSeconds / 60, totalSeconds % 60))
            }
            else -> getString(
                R.string.scroll_break_status,
                scrolledMinutes, config.scrollMinutes, watchedMinutes, config.watchMinutes
            )
        }
    }
}
