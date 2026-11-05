package neth.iecal.curbox.ui.fragments.main.reducers

import android.os.Bundle
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import neth.iecal.curbox.anti_stimulants.EyeBreakReminder
import neth.iecal.curbox.anti_stimulants.ScrollBreak
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import neth.iecal.curbox.data.models.EyeBreakConfig
import neth.iecal.curbox.data.models.ScrollBreakConfig
import neth.iecal.curbox.utils.DataStoreManager
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import neth.iecal.curbox.BuildConfig
import neth.iecal.curbox.R

import android.content.Intent
import neth.iecal.curbox.ui.activity.FragmentActivity
import neth.iecal.curbox.utils.ViewUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import neth.iecal.curbox.ui.fragments.main.reducers.blockertools.appBlocker.AppBlockerGroupsFragment

class ReducersFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_reducers, container, false)
        val appBlockerCard = view.findViewById<MaterialCardView>(R.id.card_app_blocker)
        val reelBlockerCard = view.findViewById<MaterialCardView>(R.id.card_reels_blocker)
        val keywordBlockerCard = view.findViewById<MaterialCardView>(R.id.card_keyword_blocker)
        
        view.findViewById<MaterialButton>(R.id.btn_help).setOnClickListener {
            ViewUtils.showHelpPopup(it, "Blocker tools and stimulation reducers help you regain focus.", "https://curbox.app/docs/reducers/overview/")
        }

        appBlockerCard.setOnClickListener {
            val intent = Intent(requireContext(), FragmentActivity::class.java).apply {
                putExtra("fragment", AppBlockerGroupsFragment.FRAGMENT_ID)
            }
            startActivity(intent)
        }
        
        reelBlockerCard.setOnClickListener {
            val intent = Intent(requireContext(), FragmentActivity::class.java).apply {
                putExtra("fragment", neth.iecal.curbox.ui.fragments.main.reducers.blockertools.reelBlocker.ReelBlockerFragment.FRAGMENT_ID)
            }
            startActivity(intent)
        }

        keywordBlockerCard.setOnClickListener {
            val intent = Intent(requireContext(), FragmentActivity::class.java).apply {
                putExtra("fragment", neth.iecal.curbox.ui.fragments.main.reducers.blockertools.keywordBlocker.KeywordBlockerFragment.FRAGMENT_ID)
            }
            startActivity(intent)
        }

        val reelCounterCard = view.findViewById<MaterialCardView>(R.id.card_reel_counter)
        reelCounterCard.setOnClickListener {
            val intent = Intent(requireContext(), FragmentActivity::class.java).apply {
                putExtra("fragment", neth.iecal.curbox.ui.fragments.main.reducers.anti_stimulants.reel_counter.ReelCounterFragment.FRAGMENT_ID)
            }
            startActivity(intent)
        }
        
        
        val intentsCard = view.findViewById<MaterialCardView>(R.id.card_intents)
        intentsCard.setOnClickListener {
            val intent = Intent(requireContext(), FragmentActivity::class.java).apply {
                putExtra("fragment", neth.iecal.curbox.ui.fragments.main.reducers.anti_stimulants.mindful_messages.MindfulMessagesFragment.FRAGMENT_ID)
            }
            startActivity(intent)
        }

        val uiHiderCard = view.findViewById<MaterialCardView>(R.id.card_ui_hider)
        uiHiderCard.isVisible = BuildConfig.SUPPORTS_UI_HIDER
        uiHiderCard.setOnClickListener {
            val intent = Intent(requireContext(), FragmentActivity::class.java).apply {
                putExtra("fragment", neth.iecal.curbox.ui.fragments.main.reducers.blockertools.uiHider.UiHiderFragment.FRAGMENT_ID)
            }
            startActivity(intent)
        }

        val intentsLogCard = view.findViewById<MaterialCardView>(R.id.card_logged_intents)
        intentsLogCard.setOnClickListener {
            val intent = Intent(requireContext(), FragmentActivity::class.java).apply {
                putExtra("fragment", neth.iecal.curbox.ui.fragments.main.reducers.analytics.IntentsLogFragment.FRAGMENT_ID)
            }
            startActivity(intent)
        }

        val antiUninstallCard = view.findViewById<MaterialCardView>(R.id.card_anti_uninstall)
        antiUninstallCard.isVisible = BuildConfig.SUPPORTS_ANTI_UNINSTALL
        antiUninstallCard.setOnClickListener {
            val intent = Intent(requireContext(), FragmentActivity::class.java).apply {
                putExtra("fragment", neth.iecal.curbox.ui.fragments.main.reducers.advanced.AntiUninstallFragment.FRAGMENT_ID)
            }
            startActivity(intent)
        }

        val settingsChangeDelayCard = view.findViewById<MaterialCardView>(R.id.card_settings_change_delay)
        settingsChangeDelayCard.setOnClickListener {
            val intent = Intent(requireContext(), FragmentActivity::class.java).apply {
                putExtra("fragment", neth.iecal.curbox.ui.fragments.main.reducers.advanced.SettingsChangeDelayFragment.FRAGMENT_ID)
            }
            startActivity(intent)
        }

        val paymentPauseCard = view.findViewById<MaterialCardView>(R.id.card_payment_pause)
        paymentPauseCard.setOnClickListener {
            val intent = Intent(requireContext(), FragmentActivity::class.java).apply {
                putExtra("fragment", neth.iecal.curbox.utils.PaymentPause.PAGE_FRAGMENT_ID)
            }
            startActivity(intent)
        }

        val permissionsCard = view.findViewById<MaterialCardView>(R.id.card_permissions)
        permissionsCard.setOnClickListener {
            val intent = Intent(requireContext(), FragmentActivity::class.java).apply {
                putExtra("fragment", neth.iecal.curbox.ui.fragments.main.reducers.advanced.PermissionsFragment.FRAGMENT_ID)
            }
            startActivity(intent)
        }

        val serviceProtectionCard = view.findViewById<MaterialCardView>(R.id.card_service_protection)
        serviceProtectionCard.setOnClickListener {
            val intent = Intent(requireContext(), FragmentActivity::class.java).apply {
                putExtra("fragment", neth.iecal.curbox.ui.fragments.main.reducers.advanced.ServiceProtectionFragment.FRAGMENT_ID)
            }
            startActivity(intent)
        }

        val reducerLogsHeader = view.findViewById<View>(R.id.reducer_logs_header)
        val reducerLogsContent = view.findViewById<View>(R.id.reducer_logs_content)
        val reducerLogsArrow = view.findViewById<ImageView>(R.id.reducer_logs_arrow)
        reducerLogsHeader.setOnClickListener {
            val isVisible = reducerLogsContent.isVisible
            TransitionManager.beginDelayedTransition(view.findViewById(R.id.reducers_root), AutoTransition())
            reducerLogsContent.isVisible = !isVisible
            reducerLogsArrow.animate().rotation(if (isVisible) 0f else 90f).start()
        }

        val advancedHeader = view.findViewById<View>(R.id.advanced_header)
        val advancedContent = view.findViewById<View>(R.id.advanced_content)
        val advancedArrow = view.findViewById<ImageView>(R.id.advanced_arrow)
        advancedHeader.setOnClickListener {
            val isVisible = advancedContent.isVisible
            TransitionManager.beginDelayedTransition(view.findViewById(R.id.reducers_root), AutoTransition())
            advancedContent.isVisible = !isVisible
            advancedArrow.animate().rotation(if (isVisible) 0f else 90f).start()
        }

        return view
    }

    // Eye break card. The schedule lives in the blocker service process and arrives by broadcast.
    private var eyeBreakConfig = EyeBreakConfig()
    private var eyeBreakNextAtMs = 0L
    private var eyeBreakEndsAtMs = 0L
    private var eyeBreakSubtitle: TextView? = null
    private var eyeBreakTicker: Job? = null

    private val eyeBreakStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != EyeBreakReminder.ACTION_STATUS) return
            eyeBreakNextAtMs = intent.getLongExtra(EyeBreakReminder.EXTRA_NEXT_AT, 0L)
            eyeBreakEndsAtMs = intent.getLongExtra(EyeBreakReminder.EXTRA_BREAK_ENDS_AT, 0L)
            renderEyeBreakSubtitle()
        }
    }

    // Scrolling break card. Its count also lives in the blocker service process.
    private var scrollBreakConfig = ScrollBreakConfig()
    private var scrolledMinutes = 0
    private var watchedMinutes = 0
    private var scrollBreakEndsAtMs = 0L
    private var scrollBreakSubtitle: TextView? = null

    private val scrollBreakStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ScrollBreak.ACTION_STATUS) return
            scrolledMinutes = intent.getIntExtra(ScrollBreak.EXTRA_SCROLLED_MINUTES, 0)
            watchedMinutes = intent.getIntExtra(ScrollBreak.EXTRA_WATCHED_MINUTES, 0)
            scrollBreakEndsAtMs = intent.getLongExtra(ScrollBreak.EXTRA_BREAK_ENDS_AT, 0L)
            renderScrollBreakSubtitle()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupEyeBreakCard(view)
        setupScrollBreakCard(view)
    }

    override fun onResume() {
        super.onResume()
        val context = requireContext()
        ContextCompat.registerReceiver(
            context,
            eyeBreakStatusReceiver,
            IntentFilter(EyeBreakReminder.ACTION_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        context.sendBroadcast(Intent(EyeBreakReminder.ACTION_STATUS_REQUEST).setPackage(context.packageName))
        ContextCompat.registerReceiver(
            context,
            scrollBreakStatusReceiver,
            IntentFilter(ScrollBreak.ACTION_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        context.sendBroadcast(Intent(ScrollBreak.ACTION_STATUS_REQUEST).setPackage(context.packageName))
        eyeBreakTicker = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                renderEyeBreakSubtitle()
                renderScrollBreakSubtitle()
                delay(1_000L)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        eyeBreakTicker?.cancel()
        runCatching { requireContext().unregisterReceiver(eyeBreakStatusReceiver) }
        runCatching { requireContext().unregisterReceiver(scrollBreakStatusReceiver) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        eyeBreakSubtitle = null
        scrollBreakSubtitle = null
    }

    private fun setupEyeBreakCard(view: View) {
        val card = view.findViewById<MaterialCardView>(R.id.card_eye_break)
        val switch = view.findViewById<MaterialSwitch>(R.id.switch_eye_break)
        eyeBreakSubtitle = view.findViewById(R.id.tv_eye_break_subtitle)
        val dataStore = DataStoreManager(requireContext())

        fun save(transform: (EyeBreakConfig) -> EyeBreakConfig) {
            viewLifecycleOwner.lifecycleScope.launch { dataStore.updateEyeBreakConfig(transform) }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            dataStore.settings.collectLatest { settings ->
                eyeBreakConfig = settings.eyeBreakConfig
                switch.setOnCheckedChangeListener(null)
                switch.isChecked = eyeBreakConfig.isEnabled
                switch.setOnCheckedChangeListener { _, isChecked -> save { it.copy(isEnabled = isChecked) } }
                renderEyeBreakSubtitle()
            }
        }

        card.setOnClickListener {
            val options = listOf(20, 30, 45, 60)
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.eye_break_interval_title)
                .setSingleChoiceItems(
                    options.map { getString(R.string.eye_break_minutes, it) }.toTypedArray(),
                    options.indexOf(eyeBreakConfig.intervalMinutes)
                ) { dialog, which ->
                    save { it.copy(isEnabled = true, intervalMinutes = options[which]) }
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun renderEyeBreakSubtitle() {
        val subtitle = eyeBreakSubtitle ?: return
        val now = SystemClock.elapsedRealtime()
        subtitle.text = when {
            !eyeBreakConfig.isEnabled -> getString(R.string.eye_break_subtitle_off)
            eyeBreakEndsAtMs > now ->
                getString(R.string.eye_break_status_break, ((eyeBreakEndsAtMs - now + 999) / 1_000L).toInt())
            eyeBreakNextAtMs > now -> {
                val totalSeconds = (eyeBreakNextAtMs - now + 999) / 1_000L
                getString(R.string.eye_break_status_next, "%d:%02d".format(totalSeconds / 60, totalSeconds % 60))
            }
            // No schedule heard yet, for example while the blocker service is off.
            else -> getString(R.string.eye_break_subtitle_on, eyeBreakConfig.intervalMinutes)
        }
    }

    private fun setupScrollBreakCard(view: View) {
        val card = view.findViewById<MaterialCardView>(R.id.card_scroll_break)
        val switch = view.findViewById<MaterialSwitch>(R.id.switch_scroll_break)
        scrollBreakSubtitle = view.findViewById(R.id.tv_scroll_break_subtitle)
        val dataStore = DataStoreManager(requireContext())

        // Weakening changes wait out the settings change delay, like other restrictions.
        fun save(transform: (ScrollBreakConfig) -> ScrollBreakConfig) {
            viewLifecycleOwner.lifecycleScope.launch { dataStore.updateScrollBreakConfig(transform) }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            dataStore.settings.collectLatest { settings ->
                scrollBreakConfig = settings.scrollBreakConfig
                switch.setOnCheckedChangeListener(null)
                switch.isChecked = scrollBreakConfig.isEnabled
                switch.setOnCheckedChangeListener { _, isChecked -> save { it.copy(isEnabled = isChecked) } }
                renderScrollBreakSubtitle()
            }
        }

        // Asks for the scrolling limit, then the watching limit, then the break length.
        fun pick(title: Int, options: List<Int>, current: Int, onPicked: (Int) -> Unit) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(title)
                .setSingleChoiceItems(
                    options.map { getString(R.string.eye_break_minutes, it) }.toTypedArray(),
                    options.indexOf(current)
                ) { dialog, which ->
                    dialog.dismiss()
                    onPicked(options[which])
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        card.setOnClickListener {
            val config = scrollBreakConfig
            pick(R.string.scroll_break_scroll_title, listOf(10, 15, 20, 30, 45, 60), config.scrollMinutes) { scroll ->
                pick(R.string.scroll_break_watch_title, listOf(30, 45, 60, 90, 120), config.watchMinutes) { watch ->
                    pick(R.string.scroll_break_length_title, listOf(2, 5, 10, 15, 30), config.breakMinutes) { length ->
                        save {
                            it.copy(isEnabled = true, scrollMinutes = scroll, watchMinutes = watch, breakMinutes = length)
                        }
                    }
                }
            }
        }
    }

    private fun renderScrollBreakSubtitle() {
        val subtitle = scrollBreakSubtitle ?: return
        val config = scrollBreakConfig
        val breakLeftMs = scrollBreakEndsAtMs - System.currentTimeMillis()
        subtitle.text = when {
            !config.isEnabled -> getString(R.string.scroll_break_subtitle_off)
            breakLeftMs > 0L -> {
                val totalSeconds = (breakLeftMs + 999) / 1_000L
                getString(R.string.scroll_break_status_break, "%d:%02d".format(totalSeconds / 60, totalSeconds % 60))
            }
            scrolledMinutes > 0 || watchedMinutes > 0 -> getString(
                R.string.scroll_break_status,
                scrolledMinutes, config.scrollMinutes, watchedMinutes, config.watchMinutes
            )
            else -> getString(
                R.string.scroll_break_subtitle_on,
                config.scrollMinutes, config.watchMinutes, config.breakMinutes
            )
        }
    }
}
