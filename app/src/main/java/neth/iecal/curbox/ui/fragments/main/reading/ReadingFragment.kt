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
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
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
import neth.iecal.curbox.reader.SeriesWithReleases
import neth.iecal.curbox.reader.SeriesMatching
import neth.iecal.curbox.reader.NotDuplicateEntity
import neth.iecal.curbox.services.ReleaseCheckJob
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

    private var allSeries = listOf<SeriesWithReleases>()
    // Null shows the New filter: series with unread chapters out.
    private var selectedStatus: SeriesStatus? = SeriesStatus.READING
    private var query = ""
    private var sortByTitle = false
    private var isTrackingOn = true
    private var checkHours = 24
    private var notDuplicates = emptySet<String>()
    private var duplicates = listOf<SeriesMatching.Pair>()
    private val statusChips = mutableMapOf<SeriesStatus?, Chip>()

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
        binding.btnMangadexMatches.setOnClickListener { reviewMangadexMatches() }
        binding.btnPossibleDuplicates.setOnClickListener { reviewDuplicates() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    dataStore.settings.collect { settings ->
                        isTrackingOn = settings.isReadingTrackerEnabled
                        checkHours = settings.releaseCheckHours
                        binding.tvReadingTrackingOff.isVisible = !isTrackingOn
                        runCatching { ReleaseCheckJob.ensureScheduled(requireContext(), checkHours) }
                    }
                }
                launch {
                    dao.observeNotDuplicates().collect { pairs ->
                        notDuplicates = pairs.map { SeriesMatching.pairId(it.keyA, it.keyB) }.toSet()
                        render()
                    }
                }
                dao.observeSeriesWithReleases().collect { series ->
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
        (listOf<SeriesStatus?>(null) + SeriesStatus.SHOWN).forEach { status ->
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
        val counts = allSeries.groupingBy { it.series.status }.eachCount()
        statusChips.forEach { (status, chip) ->
            val count = if (status == null) allSeries.count(::hasNew) else counts[status.id] ?: 0
            val label = status?.let(::statusLabel) ?: getString(R.string.reading_status_new)
            chip.text = getString(R.string.reading_status_chip, label, count)
            chip.isChecked = status == selectedStatus
        }
        val needle = query.lowercase(Locale.ROOT)
        val shown = allSeries
            .filter { item -> selectedStatus?.let { item.series.status == it.id } ?: hasNew(item) }
            .filter { needle.isEmpty() || it.series.name.lowercase(Locale.ROOT).contains(needle) }
            .let { list -> if (sortByTitle) list.sortedBy { it.series.name.lowercase(Locale.ROOT) } else list }
        adapter.submit(shown)
        binding.tvReadingEmpty.isVisible = shown.isEmpty()
        binding.tvReadingEmpty.setText(
            if (allSeries.isNotEmpty() && query.isNotEmpty()) R.string.reading_empty_search else R.string.reading_empty
        )
        duplicates = SeriesMatching.possibleDuplicates(allSeries.map { it.series }, notDuplicates)
        binding.btnPossibleDuplicates.isVisible = duplicates.isNotEmpty()
        binding.btnPossibleDuplicates.text = getString(R.string.reading_duplicates, duplicates.size)
        val matches = allSeries.count { it.series.mangadexCandidateId != null }
        binding.btnMangadexMatches.isVisible = matches > 0
        binding.btnMangadexMatches.text = getString(R.string.reading_mangadex_matches, matches)
    }

    // Unread chapters count for series being read or on hold; a planned one has not started.
    private fun hasNew(item: SeriesWithReleases): Boolean =
        item.newCount > 0 && item.series.status in NEW_STATUSES

    private fun reviewDuplicates() {
        val pair = duplicates.firstOrNull() ?: return
        // Keep the entry linked to MangaDex, or else the one read longer.
        val (from, into) = when {
            pair.first.mangadexId != null && pair.second.mangadexId == null -> pair.second to pair.first
            pair.second.mangadexId != null && pair.first.mangadexId == null -> pair.first to pair.second
            pair.first.readingTimeMs >= pair.second.readingTimeMs -> pair.second to pair.first
            else -> pair.first to pair.second
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.reading_duplicate_title)
            .setMessage(getString(R.string.reading_duplicate_message, from.name, into.name))
            .setPositiveButton(R.string.reading_duplicate_merge) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch { dao.merge(from.key, into.key) }
            }
            .setNegativeButton(R.string.reading_duplicate_not_same) { _, _ ->
                val (a, b) = listOf(from.key, into.key).sorted()
                viewLifecycleOwner.lifecycleScope.launch { dao.putNotDuplicate(NotDuplicateEntity(a, b)) }
            }
            .setNeutralButton(R.string.reading_mangadex_later, null)
            .show()
    }

    private fun reviewMangadexMatches() {
        val next = allSeries.firstOrNull { it.series.mangadexCandidateId != null }?.series ?: return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.reading_mangadex_confirm_title)
            .setMessage(getString(R.string.reading_mangadex_confirm_message, next.name, next.mangadexCandidateTitle.orEmpty()))
            .setPositiveButton(R.string.reading_mangadex_yes) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    dao.linkMangadex(next.key, next.mangadexCandidateId)
                    ReleaseCheckJob.checkNow(requireContext())
                }
            }
            .setNegativeButton(R.string.reading_mangadex_no) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch { dao.declineMangadex(next.key) }
            }
            .setNeutralButton(R.string.reading_mangadex_later, null)
            .show()
    }

    private fun statusLabel(status: SeriesStatus): String = getString(
        when (status) {
            SeriesStatus.READING -> R.string.reading_status_reading
            SeriesStatus.PLAN_TO_READ -> R.string.reading_status_plan_to_read
            SeriesStatus.COMPLETED -> R.string.reading_status_completed
            SeriesStatus.ON_HOLD -> R.string.reading_status_on_hold
            SeriesStatus.DROPPED -> R.string.reading_status_dropped
            SeriesStatus.DETECTED -> R.string.reading_status_detected
            SeriesStatus.IGNORED -> R.string.reading_status_ignored
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
            menu.add(0, MENU_CHECK_NOW, 3, R.string.reading_check_now)
            menu.add(0, MENU_CHECK_EVERY, 4, R.string.reading_check_every)
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
                    MENU_CHECK_NOW -> {
                        ReleaseCheckJob.checkNow(requireContext())
                        Toast.makeText(requireContext(), R.string.reading_check_now_started, Toast.LENGTH_LONG).show()
                    }
                    MENU_CHECK_EVERY -> chooseCheckInterval()
                }
                true
            }
            show()
        }
    }

    private fun chooseCheckInterval() {
        val options = listOf(0, 6, 12, 24)
        val labels = options.map { if (it == 0) getString(R.string.reading_check_off) else getString(R.string.reading_check_hours, it) }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.reading_check_every_title)
            .setSingleChoiceItems(labels.toTypedArray(), options.indexOf(checkHours).coerceAtLeast(0)) { dialog, which ->
                viewLifecycleOwner.lifecycleScope.launch { dataStore.updateReleaseCheckHours(options[which]) }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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
                    ReleaseCheckJob.checkNow(context)
                    context.getString(R.string.reading_import_done, entries.size, added)
                } catch (_: Exception) {
                    context.getString(R.string.reading_import_failed)
                }
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun openChapter(item: SeriesWithReleases) {
        val series = item.series
        val url = item.nextUrl?.takeIf { item.newCount > 0 && series.status != SeriesStatus.COMPLETED.id } ?: series.lastUrl
        if (url.isEmpty()) {
            Toast.makeText(requireContext(), R.string.reading_no_address, Toast.LENGTH_LONG).show()
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(requireContext(), R.string.reading_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showActions(series: SeriesEntity) {
        val actions = buildList<Pair<Int, () -> Unit>> {
            add(R.string.reading_action_status to { setStatus(series) })
            add(R.string.reading_action_chapter to { setChapter(series) })
            add(R.string.reading_action_rename to { rename(series) })
            add(R.string.reading_action_merge to { merge(series) })
            if (series.mangadexId != null) add(R.string.reading_action_unlink_mangadex to {
                viewLifecycleOwner.lifecycleScope.launch { dao.linkMangadex(series.key, null) }
                Unit
            })
            add(R.string.reading_action_ignore to { setStatusTo(series, SeriesStatus.IGNORED) })
            add(R.string.delete to { delete(series) })
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(series.name)
            .setItems(actions.map { getString(it.first) }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    /** A series found in the browser: the owner decides where it belongs. */
    private fun review(item: SeriesWithReleases) {
        val series = item.series
        val actions = listOf<kotlin.Pair<Int, () -> Unit>>(
            R.string.reading_review_reading to { setStatusTo(series, SeriesStatus.READING) },
            R.string.reading_review_plan to { setStatusTo(series, SeriesStatus.PLAN_TO_READ) },
            R.string.reading_action_merge to { merge(series) },
            R.string.reading_review_open to { openChapter(item) },
            R.string.reading_action_ignore to { setStatusTo(series, SeriesStatus.IGNORED) }
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(series.name)
            .setItems(actions.map { getString(it.first) }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    private fun setStatusTo(series: SeriesEntity, status: SeriesStatus) {
        viewLifecycleOwner.lifecycleScope.launch { dao.setStatus(series.key, status.id) }
    }

    private fun setStatus(series: SeriesEntity) {
        val statuses = SeriesStatus.SHOWN
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
        // Series sharing words with this one first, as the right one is usually among them.
        val words = series.key.split(' ').filter { it.length > 2 }.toSet()
        val others = allSeries.map { it.series }
            .filter { it.key != series.key && it.status != SeriesStatus.IGNORED.id }
            .sortedWith(
                compareByDescending<SeriesEntity> { other -> other.key.split(' ').count { it in words } }
                    .thenBy { it.name.lowercase(Locale.ROOT) }
            )
        if (others.isEmpty()) {
            Toast.makeText(requireContext(), R.string.reading_merge_none, Toast.LENGTH_SHORT).show()
            return
        }
        val context = requireContext()
        val density = resources.displayMetrics.density
        val search = EditText(context).apply {
            hint = getString(R.string.reading_search_hint)
            setSingleLine()
        }
        var shown = others
        val listAdapter = ArrayAdapter(context, android.R.layout.simple_list_item_1, others.map { it.name }.toMutableList())
        val list = ListView(context).apply {
            adapter = listAdapter
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (360 * density).toInt())
        }
        search.doAfterTextChanged { text ->
            val needle = text?.toString().orEmpty().trim().lowercase(Locale.ROOT)
            shown = if (needle.isEmpty()) others else others.filter { it.name.lowercase(Locale.ROOT).contains(needle) }
            listAdapter.clear()
            listAdapter.addAll(shown.map { it.name })
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * density).toInt()
            setPadding(padding, padding / 2, padding, 0)
            addView(search)
            addView(list)
        }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(getString(R.string.reading_merge_title, series.name))
            .setView(content)
            .setNegativeButton(R.string.cancel, null)
            .show()
        list.setOnItemClickListener { _, _, position, _ ->
            val target = shown.getOrNull(position) ?: return@setOnItemClickListener
            viewLifecycleOwner.lifecycleScope.launch { dao.merge(series.key, target.key) }
            dialog.dismiss()
        }
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
        private var items = listOf<SeriesWithReleases>()

        fun submit(newItems: List<SeriesWithReleases>) {
            items = newItems
            notifyDataSetChanged()
        }

        inner class ViewHolder(val binding: ItemReadingSeriesBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            ViewHolder(ItemReadingSeriesBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            val series = item.series
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
            val latest = item.latestChapter?.let(ChapterParser::format)
            val newText = when {
                latest != null && hasNew(item) -> getString(R.string.reading_new_badge, item.newCount, latest)
                latest != null && item.newCount > 0 && series.status == SeriesStatus.PLAN_TO_READ.id ->
                    getString(R.string.reading_latest_badge, latest)
                series.checkFailed -> getString(R.string.reading_check_failed)
                else -> null
            }
            holder.binding.tvSeriesNew.isVisible = newText != null
            holder.binding.tvSeriesNew.text = newText
            holder.binding.tvSeriesNew.setTextColor(
                com.google.android.material.color.MaterialColors.getColor(
                    holder.binding.root,
                    if (series.checkFailed && !hasNew(item)) com.google.android.material.R.attr.colorOnSurfaceVariant
                    else com.google.android.material.R.attr.colorTertiary
                )
            )
            holder.binding.root.setOnClickListener {
                if (series.status == SeriesStatus.DETECTED.id) review(item) else openChapter(item)
            }
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
        const val MENU_CHECK_NOW = 4
        const val MENU_CHECK_EVERY = 5
        val NEW_STATUSES = setOf(SeriesStatus.READING.id, SeriesStatus.ON_HOLD.id)
    }
}
