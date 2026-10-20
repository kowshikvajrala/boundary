package neth.iecal.curbox.ui.fragments.main.reducers.advanced

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.BankingPauseConfig
import neth.iecal.curbox.data.models.GatedSettingsField
import neth.iecal.curbox.databinding.FragmentBankingPauseBinding
import neth.iecal.curbox.databinding.ItemBankingAppBinding
import neth.iecal.curbox.ui.activity.BankingLaunchActivity
import neth.iecal.curbox.ui.activity.SelectAppsActivity
import neth.iecal.curbox.utils.BankingPause
import neth.iecal.curbox.utils.DataStoreManager
import neth.iecal.curbox.utils.SettingsChangeDelayUtils

class BankingPauseFragment : Fragment() {

    companion object {
        const val FRAGMENT_ID = "banking_pause"
    }

    private var _binding: FragmentBankingPauseBinding? = null
    private val binding get() = _binding!!

    private val dataStore by lazy { DataStoreManager(requireContext()) }
    private val adapter = BankingAppAdapter()
    private var config = BankingPauseConfig()
    private var isRendering = false

    private val selectAppsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val selected = result.data?.getStringArrayListExtra("SELECTED_APPS") ?: return@registerForActivityResult
            updateConfig { it.copy(packages = selected.distinct()) }
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentBankingPauseBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.rvApps.layoutManager = LinearLayoutManager(requireContext())
        binding.rvApps.adapter = adapter

        binding.btnBack.setOnClickListener { requireActivity().onBackPressedDispatcher.onBackPressed() }
        binding.switchEnabled.setOnCheckedChangeListener { _, isChecked ->
            if (!isRendering) updateConfig { it.copy(isEnabled = isChecked) }
        }
        binding.btnAddApps.setOnClickListener {
            val intent = android.content.Intent(requireContext(), SelectAppsActivity::class.java)
            intent.putStringArrayListExtra("PRE_SELECTED_APPS", ArrayList(config.packages))
            selectAppsLauncher.launch(intent)
        }
        binding.tvAdbCommand.text = BankingPause.adbGrantCommand(requireContext())
        binding.btnCopyCommand.setOnClickListener {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("adb", BankingPause.adbGrantCommand(requireContext())))
            Toast.makeText(requireContext(), R.string.banking_pause_command_copied, Toast.LENGTH_SHORT).show()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            dataStore.settings.collectLatest { settings ->
                config = settings.bankingPauseConfig
                val pending = settings.settingsChangeDelayConfig2.pendingChanges
                    .find { it.field == GatedSettingsField.BANKING_PAUSE.name }
                render(pending?.appliesAtMs)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) renderPermission()
    }

    private fun render(pendingAppliesAtMs: Long?) {
        isRendering = true
        binding.switchEnabled.isChecked = config.isEnabled
        isRendering = false

        binding.tvPending.isVisible = pendingAppliesAtMs != null
        if (pendingAppliesAtMs != null) {
            binding.tvPending.text = getString(
                R.string.banking_pause_pending,
                SettingsChangeDelayUtils.formatRemaining(requireContext(), pendingAppliesAtMs - System.currentTimeMillis())
            )
        }

        binding.tvEmpty.isVisible = config.packages.isEmpty()
        adapter.submitList(config.packages)
        renderPermission()
    }

    private fun renderPermission() {
        val granted = BankingPause.canTurnServiceBackOn(requireContext())
        binding.tvPermissionTitle.setText(
            if (granted) R.string.banking_pause_permission_ready_title else R.string.banking_pause_permission_needed_title
        )
        binding.tvPermissionBody.setText(
            if (granted) R.string.banking_pause_permission_ready_body else R.string.banking_pause_permission_needed_body
        )
        binding.commandGroup.isVisible = !granted
    }

    private fun updateConfig(transform: (BankingPauseConfig) -> BankingPauseConfig) {
        viewLifecycleOwner.lifecycleScope.launch {
            dataStore.updateBankingPauseConfig(transform)
        }
    }

    private fun addShortcut(packageName: String) {
        val context = requireContext()
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
            Toast.makeText(context, R.string.banking_pause_shortcut_unsupported, Toast.LENGTH_LONG).show()
            return
        }
        val pm = context.packageManager
        val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString() }
            .getOrDefault(packageName)
        val icon = runCatching { IconCompat.createWithBitmap(pm.getApplicationIcon(packageName).toBitmap()) }
            .getOrElse { IconCompat.createWithResource(context, R.drawable.logo) }
        val shortcut = ShortcutInfoCompat.Builder(context, "banking_$packageName")
            .setShortLabel(label)
            .setIcon(icon)
            .setIntent(BankingLaunchActivity.intentFor(context, packageName))
            .build()
        ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
    }

    inner class BankingAppAdapter : RecyclerView.Adapter<BankingAppAdapter.ViewHolder>() {
        private var items = listOf<String>()

        fun submitList(newItems: List<String>) {
            items = newItems
            notifyDataSetChanged()
        }

        inner class ViewHolder(val itemBinding: ItemBankingAppBinding) : RecyclerView.ViewHolder(itemBinding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
            ViewHolder(ItemBankingAppBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val packageName = items[position]
            val pm = holder.itemView.context.packageManager
            with(holder.itemBinding) {
                tvName.text = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)) }
                    .getOrDefault(packageName)
                runCatching { pm.getApplicationIcon(packageName) }.getOrNull()?.let(ivIcon::setImageDrawable)
                btnShortcut.setOnClickListener { addShortcut(packageName) }
                btnRemove.setOnClickListener {
                    updateConfig { it.copy(packages = it.packages - packageName) }
                }
            }
        }

        override fun getItemCount() = items.size
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
