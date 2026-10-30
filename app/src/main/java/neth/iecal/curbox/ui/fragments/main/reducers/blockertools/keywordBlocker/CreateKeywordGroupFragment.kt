package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.keywordBlocker

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import neth.iecal.curbox.CrashLogger
import kotlinx.coroutines.withContext
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.AppBlockerWarningScreenConfig
import neth.iecal.curbox.data.models.KeywordGroup
import neth.iecal.curbox.databinding.FragmentCreateKeywordGroupBinding
import neth.iecal.curbox.utils.BlocklistStore
import neth.iecal.curbox.utils.KeywordFileCodec
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.text.NumberFormat
import java.util.*

class CreateKeywordGroupFragment : Fragment() {

    companion object {
        const val FRAGMENT_ID = "create_keyword_group"
        private const val FILE_BUFFER_SIZE = 64 * 1024
        private const val KEYWORD_LIST_URL = "https://github.com/curbox-app/website_packs"
        private const val ADULT_LIST_ID = "adult"
    }

    private var _binding: FragmentCreateKeywordGroupBinding? = null
    private val binding get() = _binding!!

    private val viewModel: KeywordBlockerViewModel by activityViewModels()
    private var selectedKeywords = linkedSetOf<String>()
    private val keywordAdapter by lazy { KeywordAdapter() }
    private var isEditing = false
    private var isSyncingAdultSwitch = false
    private var existingGroupId: String? = null

    private val importLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { importKeywordsFromFile(it) }
    }

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
        uri?.let { exportKeywordsToFile(it) }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCreateKeywordGroupBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        binding.rvKeywords.adapter = keywordAdapter
        showAdultListSize()
        
        existingGroupId = requireActivity().intent.getStringExtra("group_id") ?: arguments?.getString("group_id")
        
        if (existingGroupId != null) {
            loadExistingGroup(existingGroupId!!)
        }

        setupListeners()
    }

    private fun loadExistingGroup(groupId: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.keywordBlockerConfig.collectLatest { config ->
                val group = config.keywordGroups.find { it.id == groupId }
                if (group != null && !isEditing) {
                    val (keywords, keywordItems) = withContext(Dispatchers.Default) {
                        val uniqueKeywords = group.selectedKeywords.toCollection(LinkedHashSet())
                        uniqueKeywords to uniqueKeywords.toList()
                    }
                    isEditing = true
                    binding.tvTitle.text = getString(R.string.keyword_group_edit_title)
                    binding.etGroupName.setText(group.name)
                    selectedKeywords = keywords
                    keywordAdapter.submitList(keywordItems)
                    syncAdultSwitch()
                    binding.switchBlockUnsupportedBrowsers.isChecked = group.blockUnsupportedBrowsers

                    viewModel.warningScrnConfig = group.warningScreenConfig
                }
            }
        }
    }

    private fun setupListeners() {
        binding.switchBlockAdult.setOnCheckedChangeListener { _, isChecked ->
            if (isSyncingAdultSwitch) return@setOnCheckedChangeListener
            if (isChecked) {
                viewLifecycleOwner.lifecycleScope.launch {
                    val adult = withContext(Dispatchers.IO) {
                        BlocklistStore.ensureSource(BlocklistStore.DEFAULT_SOURCES.first { it.id == ADULT_LIST_ID })
                    }
                    addBlocklists(listOf(adult))
                }
            } else {
                selectedKeywords.remove(BlocklistStore.keywordFor(ADULT_LIST_ID))
                updateKeywordsList()
            }
        }
        binding.btnMoreBlocklists.setOnClickListener { showBlocklistPicker() }

        binding.btnAddKeyword.setOnClickListener {
            val kw = binding.etKeyword.text.toString().trim()
            if (kw.isNotEmpty() && selectedKeywords.add(kw)) {
                updateKeywordsList()
                binding.etKeyword.setText("")
            }
        }


        binding.btnConfigureWarningScreen.setOnClickListener {
            val configFragment = neth.iecal.curbox.ui.fragments.main.reducers.blockertools.shared.WarningConfigFragment.newInstance(
                viewModel.warningScrnConfig, 
                "result_warning_config",
                isNew = existingGroupId == null,
                supportsPauseUnlock = true
            )
            parentFragmentManager.beginTransaction()
                .hide(this)
                .add(R.id.fragment_holder, configFragment)
                .addToBackStack(null)
                .commit()
        }

        parentFragmentManager.setFragmentResultListener("result_warning_config", viewLifecycleOwner) { _, bundle ->
            bundle.getString("result_config")?.let {
                viewModel.warningScrnConfig = Gson().fromJson(it, AppBlockerWarningScreenConfig::class.java)
            }
        }

        binding.btnMoreOptions.setOnClickListener {
            showMoreOptions(it)
        }

        binding.fabSaveGroup.setOnClickListener { saveGroup() }
    }

    private fun showMoreOptions(view: View) {
        val popup = PopupMenu(requireContext(), view)
        popup.menuInflater.inflate(R.menu.menu_keyword_group_options, popup.menu)
        
        if (existingGroupId == null) {
            popup.menu.findItem(R.id.action_delete)?.isVisible = false
        }

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_import -> {
                    importLauncher.launch("text/plain")
                    true
                }
                R.id.action_add_blocklist -> {
                    showBlocklistPicker()
                    true
                }
                R.id.action_export -> {
                    val fileName = "keywords_${binding.etGroupName.text.toString().ifEmpty { "group" }}.txt"
                    exportLauncher.launch(fileName)
                    true
                }
                R.id.action_download -> {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(KEYWORD_LIST_URL)))
                    true
                }
                R.id.action_delete -> {
                    viewLifecycleOwner.lifecycleScope.launch {
                        existingGroupId?.let { viewModel.deleteGroupAndWait(it) }
                        requireActivity().finish()
                    }
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun showBlocklistPicker() {
        viewLifecycleOwner.lifecycleScope.launch {
            val sources = withContext(Dispatchers.IO) { BlocklistStore.sources() }
            val openManager = { _: android.content.DialogInterface, _: Int ->
                startActivity(Intent(requireContext(), neth.iecal.curbox.ui.activity.FragmentActivity::class.java).apply {
                    putExtra("fragment", BlocklistsFragment.FRAGMENT_ID)
                })
            }
            val builder = MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.blocklists_pick_title)
                .setNeutralButton(R.string.blocklists_manage, openManager)
            if (sources.isEmpty()) {
                builder.setMessage(R.string.blocklists_none_downloaded)
                    .setNegativeButton(R.string.cancel, null)
                    .show()
                return@launch
            }
            val checked = BooleanArray(sources.size) { BlocklistStore.keywordFor(sources[it].id) in selectedKeywords }
            builder.setMultiChoiceItems(sources.map { it.name }.toTypedArray(), checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
                .setPositiveButton(R.string.add) { _, _ ->
                    addBlocklists(sources.filterIndexed { index, _ -> checked[index] })
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    /** Adds each list's keyword to the group and downloads any list that is not on the phone yet. */
    private fun addBlocklists(sources: List<BlocklistStore.Source>) {
        sources.forEach { selectedKeywords.add(BlocklistStore.keywordFor(it.id)) }
        updateKeywordsList()

        val appContext = requireContext().applicationContext
        sources.filter { it.updatedAtMs == 0L }.forEach { source ->
            Toast.makeText(appContext, getString(R.string.blocklists_downloading, source.name), Toast.LENGTH_SHORT).show()
            // Not tied to this screen, so the download finishes even after the group is saved.
            CoroutineScope(Dispatchers.IO).launch {
                val message = try {
                    val count = BlocklistStore.download(source.id)
                    BlocklistsFragment.requestBlockerRefresh(appContext)
                    appContext.getString(R.string.blocklists_update_done, NumberFormat.getIntegerInstance().format(count))
                } catch (e: Exception) {
                    appContext.getString(R.string.blocklists_update_failed, e.message.orEmpty())
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
                    if (_binding != null) showAdultListSize()
                }
            }
        }
    }

    private fun importKeywordsFromFile(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val existingKeywords = selectedKeywords.toHashSet()
                val newKeywords = withContext(Dispatchers.IO) {
                    requireContext().contentResolver.openInputStream(uri)?.use { inputStream ->
                        BufferedReader(
                            InputStreamReader(inputStream, Charsets.UTF_8),
                            FILE_BUFFER_SIZE
                        ).use { reader ->
                            KeywordFileCodec.readNewKeywords(reader, existingKeywords)
                        }
                    } ?: throw IllegalStateException("Unable to open keyword file")
                }

                val addedCount = newKeywords.count { selectedKeywords.add(it) }
                if (addedCount > 0) {
                    updateKeywordsList()
                    Toast.makeText(requireContext(), getString(R.string.keyword_imported_count, addedCount), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), R.string.keyword_no_new_import, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), R.string.keyword_import_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun exportKeywordsToFile(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val keywords = selectedKeywords.toList()
                withContext(Dispatchers.IO) {
                    requireContext().contentResolver.openOutputStream(uri)?.use { outputStream ->
                        BufferedWriter(
                            OutputStreamWriter(outputStream, Charsets.UTF_8),
                            FILE_BUFFER_SIZE
                        ).use { writer ->
                            KeywordFileCodec.writeKeywords(writer, keywords)
                        }
                    } ?: throw IllegalStateException("Unable to open keyword file")
                }
                Toast.makeText(requireContext(), R.string.keyword_export_success, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), R.string.keyword_export_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateKeywordsList() {
        keywordAdapter.submitList(selectedKeywords.toList())
        syncAdultSwitch()
    }

    override fun onResume() {
        super.onResume()
        // Manage lists may have updated the adult list while this screen was hidden.
        showAdultListSize()
    }

    /** The adult list grows each time it updates, so its size is read from the saved list. */
    private fun showAdultListSize() {
        viewLifecycleOwner.lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) {
                BlocklistStore.sources().find { it.id == ADULT_LIST_ID }?.domainCount ?: 0
            }
            val view = _binding?.tvBlockAdultDesc ?: return@launch
            view.text = if (count > 0) {
                getString(R.string.blocklists_adult_desc_count, NumberFormat.getIntegerInstance().format(count))
            } else {
                getString(R.string.blocklists_adult_desc_not_downloaded)
            }
        }
    }

    private fun syncAdultSwitch() {
        isSyncingAdultSwitch = true
        binding.switchBlockAdult.isChecked = BlocklistStore.keywordFor(ADULT_LIST_ID) in selectedKeywords
        isSyncingAdultSwitch = false
    }

    inner class KeywordAdapter : RecyclerView.Adapter<KeywordAdapter.ViewHolder>() {
        private var items = listOf<String>()

        fun submitList(newItems: List<String>) {
            items = newItems
            notifyDataSetChanged()
        }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvKeyword: android.widget.TextView = view.findViewById(R.id.tv_keyword)
            val btnRemove: android.widget.ImageButton = view.findViewById(R.id.btn_remove)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_keyword, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val keyword = items[position]
            holder.tvKeyword.text = keyword
            holder.btnRemove.setOnClickListener {
                val currentPos = holder.adapterPosition
                if (currentPos != RecyclerView.NO_POSITION) {
                    selectedKeywords.remove(items[currentPos])
                    updateKeywordsList()
                }
            }
        }

        override fun getItemCount() = items.size
    }

    private fun saveGroup() {
        val name = binding.etGroupName.text.toString().trim()
        if (name.isEmpty()) {
            binding.etGroupName.error = getString(R.string.group_name_required)
            return
        }
        if (selectedKeywords.isEmpty()) {
            Toast.makeText(requireContext(), R.string.keyword_add_at_least_one, Toast.LENGTH_SHORT).show()
            return
        }

        val existing = viewModel.keywordBlockerConfig.value.keywordGroups.find { it.id == existingGroupId }

        val group = KeywordGroup(
            id = existingGroupId ?: UUID.randomUUID().toString(),
            name = name,
            selectedKeywords = selectedKeywords.toList(),
            isActive = existing?.isActive ?: true,
            temporarilyDisabledUntilMs = existing?.temporarilyDisabledUntilMs ?: 0L,
            warningScreenConfig = viewModel.warningScrnConfig.copy(isOnOpenConfig = false),
            blockUnsupportedBrowsers = binding.switchBlockUnsupportedBrowsers.isChecked
        )

        binding.fabSaveGroup.isEnabled = false
        binding.fabSaveGroup.setText(R.string.settings_saving)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                if (existingGroupId != null) viewModel.updateGroupByIdAndWait(group)
                else viewModel.addGroupAndWait(group)
                requireActivity().finish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CrashLogger(requireContext().applicationContext).logNonFatalError(e)
                Toast.makeText(requireContext(), R.string.settings_save_failed, Toast.LENGTH_SHORT).show()
            } finally {
                _binding?.fabSaveGroup?.apply {
                    isEnabled = true
                    setText(R.string.done)
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
