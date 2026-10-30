package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.appBlocker

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import neth.iecal.curbox.CrashLogger
import kotlinx.coroutines.flow.collectLatest
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.AppBlockerWarningScreenConfig
import neth.iecal.curbox.data.models.AppGroup
import neth.iecal.curbox.databinding.FragmentCreateAppGroupBinding
import neth.iecal.curbox.hardcoded.AppWebsites
import neth.iecal.curbox.ui.activity.SelectAppsActivity
import java.util.UUID

class CreateAppGroupFragment : Fragment() {

    companion object {
        const val FRAGMENT_ID = "create_app_group"
    }

    private var _binding: FragmentCreateAppGroupBinding? = null
    private val binding get() = _binding!!

    private var selectedApps: ArrayList<String> = arrayListOf()
    private var selectedWebsites = linkedSetOf<String>()
    private var isPrefilled = false
    private val viewModel: AppBlockerSettingViewModel by activityViewModels()
    private val websiteAdapter by lazy { WebsiteAdapter() }

    private val selectAppsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == AppCompatActivity.RESULT_OK) {
            val apps = result.data?.getStringArrayListExtra("SELECTED_APPS")
            if (apps != null) {
                selectedApps = apps
                binding.btnSelectApps.text = getString(R.string.select_apps_count, selectedApps.size)
                updateWebsitesList()
            }
        }
    }

    private var isDeleting = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCreateAppGroupBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        var isEditing = false
        val groupId = requireActivity().intent.getStringExtra("group_id") ?: arguments?.getString("group_id")
        val prefillPackage = requireActivity().intent.getStringExtra("prefill_package")
        binding.btnDeleteGroup.visibility = View.GONE
        binding.rvWebsites.adapter = websiteAdapter

        if (groupId == null && !isPrefilled && prefillPackage != null) {
            isPrefilled = true
            selectedApps = arrayListOf(prefillPackage)
            binding.btnSelectApps.text = getString(R.string.select_apps_count, selectedApps.size)
        }
        updateWebsitesList()

        if (groupId != null) {
            viewLifecycleOwner.lifecycleScope.launch {
                viewModel.groups.collectLatest { groups ->
                    val group = groups.find { it.id == groupId }
                    if (group != null && !isEditing) {
                        isEditing = true
                        binding.textView2.text = getString(R.string.app_group_edit_title)
                        binding.etGroupName.setText(group.name)
                        selectedApps = ArrayList(group.selectedPackages)
                        binding.btnSelectApps.text = getString(R.string.select_apps_count, selectedApps.size)
                        selectedWebsites = group.websites.toCollection(LinkedHashSet())
                        updateWebsitesList()

                        binding.btnDeleteGroup.visibility = View.VISIBLE
                        binding.btnDeleteGroup.setOnClickListener {
                            isDeleting = true
                            viewLifecycleOwner.lifecycleScope.launch {
                                viewModel.deleteGroup(group.id)
                                Toast.makeText(requireContext(), getString(R.string.group_deleted), Toast.LENGTH_SHORT).show()
                                requireActivity().finish()
                            }
                        }

                        viewModel.warningScrnConfig = group.warningScreenConfig
                    }
                }
            }
        }

        binding.btnSelectApps.setOnClickListener { openAppSelector() }
        binding.btnAddWebsite.setOnClickListener { addWebsite() }
        binding.etWebsite.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) addWebsite()
            actionId == EditorInfo.IME_ACTION_DONE
        }

        binding.configureWarningScreen.setOnClickListener {
            val configFragment = neth.iecal.curbox.ui.fragments.main.reducers.blockertools.shared.WarningConfigFragment.newInstance(
                viewModel.warningScrnConfig, 
                "result_warning_config",
                isNew = groupId == null,
                supportsOnEachOpen = true
            )
            parentFragmentManager.beginTransaction()
                .hide(this)
                .add(R.id.fragment_holder, configFragment)
                .addToBackStack(null)
                .commit()
        }

        parentFragmentManager.setFragmentResultListener("result_warning_config", viewLifecycleOwner) { _, bundle ->
            val configStr = bundle.getString("result_config")
            if (configStr != null) {
                viewModel.warningScrnConfig =
                    Gson().fromJson(configStr, AppBlockerWarningScreenConfig::class.java)
            }
        }
        binding.fabSaveGroup.setOnClickListener {
            saveGroup()
        }
    }

    private fun saveGroup() {
        if (_binding == null || isDeleting) return
        val name = binding.etGroupName.text.toString().trim()
        if (name.isEmpty()) {
            binding.etGroupName.error = getString(R.string.group_name_required)
            return
        }

        if (selectedApps.isEmpty() && selectedWebsites.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.please_select_at_least_one_app), Toast.LENGTH_SHORT).show()
            return
        }

        val savedGroupId =
            requireActivity().intent.getStringExtra("group_id") ?: arguments?.getString("group_id")
        val isEditingRecord = savedGroupId != null
        val targetExistingGroup = viewModel.groups.value.find { it.id == savedGroupId }

        val newGroupId = if (isEditingRecord && targetExistingGroup != null) {
            targetExistingGroup.id
        } else {
            UUID.randomUUID().toString()
        }

        val newGroup = AppGroup(
            id = newGroupId,
            name = name,
            selectedPackages = selectedApps.toList(),
            websites = selectedWebsites.toList(),
            isActive =
                if (isEditingRecord && targetExistingGroup != null) targetExistingGroup.isActive
                else true,
            temporarilyDisabledUntilMs = targetExistingGroup?.temporarilyDisabledUntilMs ?: 0L,
            warningScreenConfig = viewModel.warningScrnConfig
        )

        binding.fabSaveGroup.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                if (isEditingRecord && targetExistingGroup != null) {
                    viewModel.updateGroupById(newGroup)
                } else {
                    viewModel.addGroupAndWait(newGroup)
                    if (arguments == null) {
                        arguments = Bundle()
                    }
                    arguments?.putString("group_id", newGroupId)
                }

                Toast.makeText(requireContext(), getString(R.string.group_saved_successfully), Toast.LENGTH_SHORT).show()
                requireActivity().finish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CrashLogger(requireContext().applicationContext).logNonFatalError(e)
                Toast.makeText(requireContext(), R.string.settings_save_failed, Toast.LENGTH_SHORT).show()
            } finally {
                _binding?.fabSaveGroup?.isEnabled = true
            }
        }
    }

    private fun addWebsite() {
        val site = AppWebsites.normalize(binding.etWebsite.text.toString())
        if (site == null) {
            binding.tilWebsite.error = getString(R.string.app_group_website_invalid)
            return
        }
        binding.tilWebsite.error = null
        binding.etWebsite.setText("")
        if (selectedWebsites.add(site)) updateWebsitesList()
    }

    private fun updateWebsitesList() {
        // Websites of the chosen apps are blocked with them already; shown here without a remove button.
        val included = selectedApps.flatMap { pkg ->
            val appName = runCatching {
                requireContext().packageManager.run { getApplicationLabel(getApplicationInfo(pkg, 0)).toString() }
            }.getOrDefault(pkg)
            AppWebsites.sitesFor(pkg).map { site ->
                WebsiteRow(getString(R.string.app_group_website_included, site, appName), removableSite = null)
            }
        }
        websiteAdapter.submitList(included + selectedWebsites.map { WebsiteRow(it, removableSite = it) })
    }

    private data class WebsiteRow(val text: String, val removableSite: String?)

    private inner class WebsiteAdapter : RecyclerView.Adapter<WebsiteAdapter.ViewHolder>() {
        private var items = listOf<WebsiteRow>()

        fun submitList(newItems: List<WebsiteRow>) {
            items = newItems
            notifyDataSetChanged()
        }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvWebsite: TextView = view.findViewById(R.id.tv_keyword)
            val btnRemove: ImageButton = view.findViewById(R.id.btn_remove)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_keyword, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val row = items[position]
            holder.tvWebsite.text = row.text
            holder.btnRemove.visibility = if (row.removableSite == null) View.GONE else View.VISIBLE
            holder.btnRemove.setOnClickListener {
                val site = items.getOrNull(holder.adapterPosition)?.removableSite ?: return@setOnClickListener
                selectedWebsites.remove(site)
                updateWebsitesList()
            }
        }

        override fun getItemCount() = items.size
    }

    private fun openAppSelector() {
        val intent = Intent(requireContext(), SelectAppsActivity::class.java).apply {
            putStringArrayListExtra("PRE_SELECTED_APPS", selectedApps)
            putExtra(SelectAppsActivity.EXTRA_ALLOW_CURBOX, true)
        }
        selectAppsLauncher.launch(intent)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
