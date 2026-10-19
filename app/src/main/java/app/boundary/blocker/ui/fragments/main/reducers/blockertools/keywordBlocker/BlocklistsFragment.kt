package app.boundary.blocker.ui.fragments.main.reducers.blockertools.keywordBlocker

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.boundary.blocker.R
import app.boundary.blocker.blockers.FocusModeBlocker
import app.boundary.blocker.blockers.KeywordBlocker
import app.boundary.blocker.databinding.DialogAddBlocklistBinding
import app.boundary.blocker.databinding.FragmentBlocklistsBinding
import app.boundary.blocker.databinding.ItemBlocklistBinding
import app.boundary.blocker.utils.BlocklistStore
import app.boundary.blocker.utils.DataStoreManager
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

class BlocklistsFragment : Fragment() {

    companion object {
        const val FRAGMENT_ID = "blocklists"

        /** Tells the blocking service to reload lists after one changes on disk. */
        fun requestBlockerRefresh(context: Context) {
            context.sendBroadcast(Intent(KeywordBlocker.INTENT_ACTION_REFRESH_CONFIG).setPackage(context.packageName))
            context.sendBroadcast(Intent(FocusModeBlocker.INTENT_ACTION_REFRESH_FOCUS_MODE).setPackage(context.packageName))
        }
    }

    private var _binding: FragmentBlocklistsBinding? = null
    private val binding get() = _binding!!

    private val adapter = BlocklistAdapter()
    private val updatingIds = mutableSetOf<String>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentBlocklistsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.rvBlocklists.layoutManager = LinearLayoutManager(requireContext())
        binding.rvBlocklists.adapter = adapter
        binding.fabAddList.setOnClickListener { showAddDialog() }
        refreshList()
    }

    private fun refreshList() {
        viewLifecycleOwner.lifecycleScope.launch {
            val sources = withContext(Dispatchers.IO) { BlocklistStore.sources() }
            adapter.submitList(sources)
        }
    }

    private fun showAddDialog() {
        val dialogBinding = DialogAddBlocklistBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.blocklists_add)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.add, null)
            .setNegativeButton(R.string.cancel, null)
            .show()

        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val name = dialogBinding.etName.text.toString().trim()
            val url = dialogBinding.etUrl.text.toString().trim()
            dialogBinding.nameLayout.error =
                if (name.isEmpty()) getString(R.string.blocklists_name_required) else null
            dialogBinding.urlLayout.error =
                if (!url.startsWith("https://", ignoreCase = true)) getString(R.string.blocklists_url_invalid) else null
            if (dialogBinding.nameLayout.error != null || dialogBinding.urlLayout.error != null) {
                return@setOnClickListener
            }
            dialog.dismiss()
            viewLifecycleOwner.lifecycleScope.launch {
                val source = withContext(Dispatchers.IO) { BlocklistStore.addSource(name, url) }
                refreshList()
                updateList(source.id)
            }
        }
    }

    private fun updateList(id: String) {
        if (!updatingIds.add(id)) return
        adapter.notifyDataSetChanged()
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val count = withContext(Dispatchers.IO) { BlocklistStore.download(id) }
                requestBlockerRefresh(appContext)
                Toast.makeText(
                    appContext,
                    getString(R.string.blocklists_update_done, NumberFormat.getIntegerInstance().format(count)),
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(
                    appContext,
                    getString(R.string.blocklists_update_failed, e.message.orEmpty()),
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                updatingIds.remove(id)
                if (_binding != null) refreshList()
            }
        }
    }

    private fun confirmDelete(source: BlocklistStore.Source) {
        viewLifecycleOwner.lifecycleScope.launch {
            val keyword = BlocklistStore.keywordFor(source.id)
            // Deleting a list in use would weaken a group without the settings change delay.
            if (isListInUse(source.id)) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.blocklists_in_use_title)
                    .setMessage(getString(R.string.blocklists_in_use_message, keyword))
                    .setPositiveButton(R.string.okay, null)
                    .show()
                return@launch
            }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.blocklists_delete_title)
                .setMessage(R.string.blocklists_delete_message)
                .setPositiveButton(R.string.delete) { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        withContext(Dispatchers.IO) { BlocklistStore.removeSource(source.id) }
                        requestBlockerRefresh(requireContext().applicationContext)
                        refreshList()
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private suspend fun isListInUse(id: String): Boolean {
        val settings = DataStoreManager(requireContext()).settings.first()
        val usesList = { keyword: String -> BlocklistStore.idFromKeyword(keyword) == id }
        return settings.keywordBlockerConfig.keywordGroups.any { group -> group.selectedKeywords.any(usesList) } ||
            settings.manualFocusGroups.any { group -> group.keywords.any(usesList) }
    }

    inner class BlocklistAdapter : RecyclerView.Adapter<BlocklistAdapter.ViewHolder>() {
        private var items = listOf<BlocklistStore.Source>()

        fun submitList(newItems: List<BlocklistStore.Source>) {
            items = newItems
            notifyDataSetChanged()
        }

        inner class ViewHolder(val itemBinding: ItemBlocklistBinding) : RecyclerView.ViewHolder(itemBinding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
            ViewHolder(ItemBlocklistBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val source = items[position]
            val isDownloaded = source.updatedAtMs > 0L
            val isUpdating = source.id in updatingIds
            with(holder.itemBinding) {
                tvName.text = source.name
                tvStatus.text = if (isDownloaded) {
                    getString(
                        R.string.blocklists_status,
                        NumberFormat.getIntegerInstance().format(source.domainCount),
                        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(source.updatedAtMs))
                    )
                } else {
                    getString(R.string.blocklists_not_downloaded)
                }
                tvKeyword.text = getString(R.string.blocklists_keyword_label, BlocklistStore.keywordFor(source.id))
                tvUrl.text = source.url
                btnUpdate.isEnabled = !isUpdating
                btnUpdate.setText(
                    when {
                        isUpdating -> R.string.blocklists_updating
                        isDownloaded -> R.string.blocklists_update
                        else -> R.string.blocklists_download
                    }
                )
                btnUpdate.setOnClickListener { updateList(source.id) }
                btnDelete.isEnabled = !isUpdating
                btnDelete.setOnClickListener { confirmDelete(source) }
            }
        }

        override fun getItemCount() = items.size
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
