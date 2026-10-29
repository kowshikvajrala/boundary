package neth.iecal.curbox.ui.fragments.main.reducers.advanced

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.Settings as BoundarySettings
import neth.iecal.curbox.databinding.FragmentPaymentPauseBinding
import neth.iecal.curbox.ui.activity.SelectAppsActivity
import neth.iecal.curbox.utils.DataStoreManager
import neth.iecal.curbox.utils.PaymentPause
import neth.iecal.curbox.utils.SettingsChangeDelayUtils

/** Lists the owner's payment apps, each with a button that pauses Boundary and opens the app. */
class PaymentPauseFragment : Fragment() {

    private var _binding: FragmentPaymentPauseBinding? = null
    private val binding get() = _binding!!
    private lateinit var dataStore: DataStoreManager
    private var apps: List<String> = emptyList()
    private var settings: BoundarySettings? = null
    // Apps Boundary blocks. They can never be payment apps, or a pause would open them unblocked.
    private var blockedApps: Set<String> = emptySet()

    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            renderPause()
            ticker.postDelayed(this, 1000)
        }
    }

    private val selectAppsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != AppCompatActivity.RESULT_OK) return@registerForActivityResult
        val selected = result.data?.getStringArrayListExtra("SELECTED_APPS") ?: return@registerForActivityResult
        viewLifecycleOwner.lifecycleScope.launch { dataStore.updatePaymentPauseApps(selected.toList()) }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPaymentPauseBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        dataStore = DataStoreManager(requireContext())

        val command = "adb shell pm grant ${requireContext().packageName} android.permission.WRITE_SECURE_SETTINGS"
        binding.textAdbCommand.text = command
        binding.btnCopyCommand.setOnClickListener {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("adb", command))
            Toast.makeText(requireContext(), R.string.payment_pause_copied, Toast.LENGTH_SHORT).show()
        }
        binding.textUsageCommand.text =
            "adb shell appops set ${requireContext().packageName} GET_USAGE_STATS allow"
        binding.btnUsageAccess.setOnClickListener {
            runCatching { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        }
        binding.btnEndPause.setOnClickListener {
            PaymentPause.end(requireContext())
            renderPause()
        }
        binding.btnChooseApps.setOnClickListener {
            selectAppsLauncher.launch(
                Intent(requireContext(), SelectAppsActivity::class.java)
                    .putStringArrayListExtra("PRE_SELECTED_APPS", ArrayList(apps - blockedApps))
                    .putStringArrayListExtra("IGNORED_APPS", ArrayList(blockedApps))
            )
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                dataStore.settings.collect { latest ->
                    settings = latest
                    blockedApps = PaymentPause.watchedPackages(requireContext(), latest)
                    apps = latest.paymentPauseApps
                    renderApps()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        PaymentPause.endIfDue(requireContext())
        binding.cardPermission.isVisible = !PaymentPause.canPause(requireContext())
        binding.cardUsageAccess.isVisible = !PaymentPause.hasUsageAccess(requireContext())
        renderApps()
        ticker.post(tick)
    }

    override fun onPause() {
        ticker.removeCallbacks(tick)
        super.onPause()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun renderApps() {
        val binding = _binding ?: return
        val context = requireContext()
        val canPause = PaymentPause.canPause(context) && !PaymentPause.isActive(context)
        binding.textNoApps.isVisible = apps.isEmpty()
        binding.appsContainer.removeAllViews()
        apps.forEach { packageName ->
            val label = runCatching {
                context.packageManager.getApplicationLabel(
                    context.packageManager.getApplicationInfo(packageName, 0)
                ).toString()
            }.getOrDefault(packageName)
            val isBlocked = packageName in blockedApps
            val button = MaterialButton(context).apply {
                text = if (isBlocked) {
                    getString(R.string.payment_pause_app_blocked, label)
                } else {
                    getString(R.string.payment_pause_open) + ": " + label
                }
                isEnabled = canPause && !isBlocked
                setOnClickListener {
                    val latest = settings ?: return@setOnClickListener
                    if (!PaymentPause.start(context, packageName, latest)) {
                        Toast.makeText(context, R.string.payment_pause_failed, Toast.LENGTH_LONG).show()
                    }
                    renderPause()
                }
            }
            binding.appsContainer.addView(
                button,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
        }
    }

    private fun renderPause() {
        val binding = _binding ?: return
        val context = requireContext()
        PaymentPause.endIfDue(context)
        val endsAt = PaymentPause.endsAt(context)
        val wasActive = binding.cardActive.isVisible
        binding.cardActive.isVisible = endsAt > 0L
        if (endsAt > 0L) {
            binding.textActive.text = getString(
                R.string.payment_pause_active,
                SettingsChangeDelayUtils.formatRemaining(context, endsAt - System.currentTimeMillis())
            )
        }
        if (wasActive != binding.cardActive.isVisible) renderApps()
    }
}
