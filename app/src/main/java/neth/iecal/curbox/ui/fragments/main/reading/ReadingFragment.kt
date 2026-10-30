package neth.iecal.curbox.ui.fragments.main.reading

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import neth.iecal.curbox.R
import neth.iecal.curbox.databinding.FragmentReadingBinding
import neth.iecal.curbox.databinding.ItemReadingSeriesBinding
import neth.iecal.curbox.reader.ChapterParser
import neth.iecal.curbox.reader.KenmeiImport
import neth.iecal.curbox.reader.ReadingDatabase
import neth.iecal.curbox.reader.SeriesEntity
import neth.iecal.curbox.reader.SeriesStatus
import neth.iecal.curbox.utils.DataStoreManager
import neth.iecal.curbox.utils.TimeTools
import java.util.Locale

/**
 * The Reading tab: the owner's series in status lists (as in Kenmei), with the last chapter read,
 * search, and the option to import a Kenmei list.
 */
class ReadingFragment : Fragment() {

    private var _binding: FragmentReadingBinding? = null
    private val binding get() = _binding!!
    private val adapter = SeriesAdapter()
    private val dao by lazy { ReadingDatabase.getInstance(requireContext()).readingDao() }
    private val dataStore by lazy { DataStoreManager(requireContext()) }

    private var allSeries = listOf<SeriesEntity>()
    private var selectedStatus = SeriesStatus.READING
    private var query = ""
    private var sortByTitle = false
    private var isTrackingOn = true
    private val statusChips = mutableMapOf<SeriesStatus, Chip>()

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::importKenmei)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentReadingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.rvSeries.adapter = adapter
        setupStatusChips()
        binding.etReadingSearch.doAfterTextChanged {
            query = it?.toString().orEmpty().trim()
            render()
        }
        binding.btnReadingImport.setOnClickListener { pickKenmeiFile() }
        binding.btnReadingMenu.setOnClickListener(::showMenu)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    dataStore.settings.collect { settings ->
                        isTrackingOn = settings.isReadingTrackerEnabled
                        binding.tvReadingTrackingOff.isVisible = !isTrackingOn
                    }
                }
                dao.observeSeries().collect { series ->
                    allSeries = series
                    render()
                }
            }
        }
    }

    override fun onDestroyView() {
        statusChips.clear()
        _binding = null
        super.onDestroyView()
    }

    private fun setupStatusChips() {
        SeriesStatus.values().forEach { status ->
            val chip = Chip(requireContext(), null, com.google.android.material.R.attr.chipStyle).apply {
                id = View.generateViewId()
                isCheckable = true
                isCheckedIconVisible = false
                isChecked = status == selectedStatus
                setOnClickListener {
                    selectedStatus = status
                    render()
                }
            }
            statusChips[status] = chip
            binding.chipsStatus.addView(chip)
        }
    }

    private fun render() {
        val binding = _binding ?: return
        val counts = allSeries.groupingBy { it.status }.eachCount()
        statusChips.forEach { (status, chip) ->
            chip.text = getString(R.string.reading_status_chip, statusLabel(status), counts[status.id] ?: 0)
            chip.isChecked = status == selectedStatus
        }
        val needle = query.lowercase(Locale.ROOT)
        val shown = allSeries
            .filter { it.status == selectedStatus.id }
            .filter { needle.isEmpty() || it.name.lowercase(Locale.ROOT).contains(needle) }
            .let { list -> if (sortByTitle) list.sortedBy { it.name.lowercase(Locale.ROOT) } else list }
        adapter.submit(shown)
        binding.tvReadingEmpty.isVisible = shown.isEmpty()
        binding.tvReadingEmpty.setText(
            if (allSeries.isNotEmpty() && query.isNotEmpty()) R.string.reading_empty_search else R.string.reading_empty
        )
    }

    private fun statusLabel(status: SeriesStatus): String = getString(
        when (status) {
            SeriesStatus.READING -> R.string.reading_status_reading
            SeriesStatus.PLAN_TO_READ -> R.string.reading_status_plan_to_read
            SeriesStatus.COMPLETED -> R.string.reading_status_completed
            SeriesStatus.ON_HOLD -> R.string.reading_status_on_hold
            SeriesStatus.DROPPED -> R.string.reading_status_dropped
        }
    )

    private fun showMenu(anchor: View) {
        PopupMenu(requireContext(), anchor).apply {
            menu.add(0, MENU_TRACKING, 0, R.string.reading_track_switch).apply {
                isCheckable = true
                isChecked = isTrackingOn
            }
            menu.add(0, MENU_SORT, 1, if (sortByTitle) R.string.reading_sort_last_read else R.string.reading_sort_title)
            menu.add(0, MENU_IMPORT, 2, R.string.reading_import_kenmei)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_TRACKING -> viewLifecycleOwner.lifecycleScope.launch {
                        dataStore.updateReadingTrackerEnabled(!isTrackingOn)
                    }
                    MENU_SORT -> {
                        sortByTitle = !sortByTitle
                        render()
                    }
                    MENU_IMPORT -> pickKenmeiFile()
                }
                true
            }
            show()
        }
    }

    private fun pickKenmeiFile() {
        runCatching { importLauncher.launch(arrayOf("text/*", "application/vnd.ms-excel", "application/octet-stream")) }
    }

    private fun importKenmei(uri: Uri) {
        val context = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val message = withContext(Dispatchers.IO) {
                try {
                    val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                        ?: return@withContext context.getString(R.string.reading_import_failed)
                    val entries = KenmeiImport.parse(text)
                    val added = dao.importEntries(entries, System.currentTimeMillis())
                    context.getString(R.string.reading_import_done, entries.size, added)
                } catch (_: Exception) {
                    context.getString(R.string.reading_import_failed)
                }
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun openChapter(series: SeriesEntity) {
        if (series.lastUrl.isEmpty()) {
            Toast.makeText(requireContext(), R.string.reading_no_address, Toast.LENGTH_LONG).show()
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(series.lastUrl)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(requireContext(), R.string.reading_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showActions(series: SeriesEntity) {
        val actions = arrayOf(
            getString(R.string.reading_action_status),
            getString(R.string.reading_action_chapter),
            getString(R.string.reading_action_rename),
            getString(R.string.reading_action_merge),
            getString(R.string.delete)
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(series.name)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> setStatus(series)
                    1 -> setChapter(series)
                    2 -> rename(series)
                    3 -> merge(series)
                    4 -> delete(series)
                }
            }
            .show()
    }

    private fun setStatus(series: SeriesEntity) {
        val statuses = SeriesStatus.values()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(series.name)
            .setSingleChoiceItems(
                statuses.map(::statusLabel).toTypedArray(),
                statuses.indexOfFirst { it.id == series.status }
            ) { dialog, which ->
                viewLifecycleOwner.lifecycleScope.launch { dao.setStatus(series.key, statuses[which].id) }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun setChapter(series: SeriesEntity) {
        val input = dialogInput(ChapterParser.format(series.lastChapter)).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.reading_chapter_title)
            .setView(wrap(input))
            .setPositiveButton(R.string.save) { _, _ ->
                val chapter = input.text.toString().trim().toDoubleOrNull()
                if (chapter != null && chapter >= 0) {
                    viewLifecycleOwner.lifecycleScope.launch { dao.setChapter(series.key, chapter) }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun rename(series: SeriesEntity) {
        val input = dialogInput(series.name)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.reading_rename_title)
            .setView(wrap(input))
            .setPositiveButton(R.string.save) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) viewLifecycleOwner.lifecycleScope.launch { dao.rename(series.key, name) }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun merge(series: SeriesEntity) {
        val others = allSeries.filter { it.key != series.key }.sortedBy { it.name.lowercase(Locale.ROOT) }
        if (others.isEmpty()) {
            Toast.makeText(requireContext(), R.string.reading_merge_none, Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.reading_merge_title, series.name))
            .setItems(others.map { it.name }.toTypedArray()) { _, which ->
                viewLifecycleOwner.lifecycleScope.launch { dao.merge(series.key, others[which].key) }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun delete(series: SeriesEntity) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.reading_delete_title, series.name))
            .setMessage(R.string.reading_delete_message)
            .setPositiveButton(R.string.delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch { dao.delete(series.key) }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun dialogInput(text: String) = EditText(requireContext()).apply {
        setText(text)
        setSelection(this.text.length)
    }

    private fun wrap(input: EditText) = FrameLayout(requireContext()).apply {
        val padding = (20 * resources.displayMetrics.density).toInt()
        setPadding(padding, padding / 2, padding, 0)
        addView(input)
    }

    private inner class SeriesAdapter : RecyclerView.Adapter<SeriesAdapter.ViewHolder>() {
        private var items = listOf<SeriesEntity>()

        fun submit(newItems: List<SeriesEntity>) {
            items = newItems
            notifyDataSetChanged()
        }

        inner class ViewHolder(val binding: ItemReadingSeriesBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            ViewHolder(ItemReadingSeriesBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val series = items[position]
            val chapter = ChapterParser.format(series.lastChapter)
            holder.binding.tvSeriesLetter.text = series.name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"
            holder.binding.tvSeriesName.text = series.name
            holder.binding.tvSeriesDetail.text = if (series.lastSite.isNotEmpty()) {
                getString(R.string.reading_series_detail, chapter, series.lastSite)
            } else {
                getString(R.string.reading_series_detail_no_site, chapter)
            }
            val lastRead = DateUtils.getRelativeTimeSpanString(
                series.lastReadAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
            )
            holder.binding.tvSeriesTime.text = if (series.readingTimeMs > 0L) {
                getString(R.string.reading_series_time, lastRead, TimeTools.formatTimeForWidget(series.readingTimeMs))
            } else {
                lastRead.ifEmpty { getString(R.string.reading_never_read) }
            }
            holder.binding.root.setOnClickListener { openChapter(series) }
            holder.binding.root.setOnLongClickListener {
                showActions(series)
                true
            }
        }

        override fun getItemCount() = items.size
    }

    private companion object {
        const val MENU_TRACKING = 1
        const val MENU_SORT = 2
        const val MENU_IMPORT = 3
    }
}
