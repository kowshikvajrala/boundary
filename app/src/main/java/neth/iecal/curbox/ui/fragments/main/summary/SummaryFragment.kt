package neth.iecal.curbox.ui.fragments.main.summary

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import neth.iecal.curbox.R
import neth.iecal.curbox.data.db.AppDatabase
import neth.iecal.curbox.databinding.FragmentSummaryBinding
import neth.iecal.curbox.databinding.ItemSummaryRowBinding
import neth.iecal.curbox.databinding.ItemSummaryStatBinding
import neth.iecal.curbox.ui.views.WeeklyBarGraphView
import neth.iecal.curbox.utils.DailyCounters
import neth.iecal.curbox.utils.DataStoreManager
import neth.iecal.curbox.utils.TimeTools
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/**
 * A look back over the last 7 or 30 days: screen time and how it changed from the period
 * before, app opens, unlocks and locks, when in the day the phone is used most, and the top
 * apps, short video apps and websites. Everything is read from what Boundary already records.
 */
class SummaryFragment : Fragment() {

    companion object {
        private const val TOP_COUNT = 5
        private const val MINUTE_MS = 60_000L
    }

    private var _binding: FragmentSummaryBinding? = null
    private val binding get() = _binding!!
    private var days = 7

    private class Period(val days: List<LocalDate>) {
        val keys = days.map(TimeTools::dayKey)
    }

    private class Summary(
        val totalMs: Long,
        val previousMs: Long,
        val perDayMs: List<Long>,
        val perHourMs: LongArray,
        val opens: Int,
        val unlocks: Int,
        val locks: Int,
        val topApps: List<Pair<String, Long>>,
        val reelsCount: Int,
        val reelsMs: Long,
        val topReelApps: List<Triple<String, Long, Int>>,
        val topSites: List<Pair<String, Long>>
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSummaryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.summaryPeriod.check(R.id.btn_period_week)
        binding.summaryPeriod.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            days = if (checkedId == R.id.btn_period_month) 30 else 7
            load()
        }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun load() {
        val context = requireContext().applicationContext
        val count = days
        viewLifecycleOwner.lifecycleScope.launch {
            val summary = withContext(Dispatchers.IO) { buildSummary(context, count) }
            if (_binding != null) render(summary, count)
        }
    }

    private suspend fun buildSummary(context: Context, count: Int): Summary {
        val today = LocalDate.now()
        val current = Period((count - 1 downTo 0).map { today.minusDays(it.toLong()) })
        val previous = Period((2 * count - 1 downTo count).map { today.minusDays(it.toLong()) })
        val db = AppDatabase.getInstance(context)
        val ignored = DataStoreManager(context).settings.first().usageTrackerIgnoredApps.toSet() +
            context.packageName + "com.android.systemui"

        val usage = db.appUsageDao().getForDates(current.keys).filter { it.packageName !in ignored }
        val previousMs = db.appUsageDao().getForDates(previous.keys)
            .filter { it.packageName !in ignored }.sumOf { it.totalTime }

        val perDay = current.keys.map { key -> usage.filter { it.date == key }.sumOf { it.totalTime } }
        val perHour = LongArray(24)
        usage.forEach { row ->
            row.hourlyUsage.split(',').forEachIndexed { hour, value ->
                if (hour < 24) perHour[hour] += value.toLongOrNull() ?: 0L
            }
        }
        val topApps = usage.groupBy { it.packageName }
            .mapValues { (_, rows) -> rows.sumOf { it.totalTime } }
            .entries.sortedByDescending { it.value }.take(TOP_COUNT)
            .map { appLabel(context, it.key) to it.value }

        val reelsCount = current.keys.sumOf { db.reelStatsDao().getCount(it) ?: 0 }
        val reelRows = db.reelUsageStatsDao().getForDates(current.keys)
        val topReelApps = reelRows.groupBy { it.packageName }
            .map { (pkg, rows) -> Triple(appLabel(context, pkg), rows.sumOf { it.totalTime }, rows.sumOf { it.reelCount }) }
            .sortedByDescending { it.second }.take(TOP_COUNT)

        val topSites = db.websiteStatsDao().getStatsForDates(current.keys)
            .groupBy { it.domain.ifBlank { it.urlIdentifier } }
            .mapValues { (_, rows) -> rows.sumOf { it.totalTime } }
            .entries.sortedByDescending { it.value }.take(TOP_COUNT)
            .map { it.key to it.value }

        return Summary(
            totalMs = perDay.sum(),
            previousMs = previousMs,
            perDayMs = perDay,
            perHourMs = perHour,
            opens = usage.sumOf { it.launchCount },
            unlocks = current.days.sumOf { DailyCounters.count(context, DailyCounters.Kind.UNLOCKS, it) },
            locks = current.days.sumOf { DailyCounters.count(context, DailyCounters.Kind.LOCKS, it) },
            topApps = topApps,
            reelsCount = reelsCount,
            reelsMs = reelRows.sumOf { it.totalTime },
            topReelApps = topReelApps,
            topSites = topSites
        )
    }

    private fun render(summary: Summary, count: Int) {
        val context = requireContext()
        val periodDays = (count - 1 downTo 0).map { LocalDate.now().minusDays(it.toLong()) }

        // Stat cards
        binding.summaryStats.removeAllViews()
        val change = if (summary.previousMs > 0L) {
            val percent = ((summary.totalMs - summary.previousMs) * 100.0 / summary.previousMs).roundToInt()
            getString(if (percent <= 0) R.string.summary_less_than_before else R.string.summary_more_than_before, kotlin.math.abs(percent), count)
        } else {
            getString(R.string.summary_no_earlier_data)
        }
        val busiestDayIndex = summary.perDayMs.indices.maxByOrNull { summary.perDayMs[it] }
        val busiestDay = busiestDayIndex?.takeIf { summary.perDayMs[it] > 0L }?.let {
            getString(R.string.summary_busiest_day, periodDays[it].dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault()))
        }.orEmpty()
        addStat(getString(R.string.summary_screen_time), duration(summary.totalMs), change)
        addStat(getString(R.string.summary_daily_average), duration(summary.totalMs / count), busiestDay)
        addStat(
            getString(R.string.summary_app_opens),
            summary.opens.toString(),
            getString(R.string.summary_per_day, (summary.opens.toFloat() / count).roundToInt())
        )
        addStat(
            getString(R.string.summary_unlocks),
            summary.unlocks.toString(),
            resources.getQuantityString(R.plurals.summary_locks_note, summary.locks, summary.locks)
        )

        // Daily chart, in minutes
        val dayLabel = if (count <= 7) DateTimeFormatter.ofPattern("EEE") else DateTimeFormatter.ofPattern("d")
        binding.summaryDailyChart.setData(periodDays.mapIndexed { i, day ->
            WeeklyBarGraphView.BarData(
                day.format(dayLabel),
                summary.perDayMs[i] / MINUTE_MS.toFloat(),
                day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            )
        })

        // Busiest hours, average minutes per day
        val busiestHour = summary.perHourMs.indices.maxByOrNull { summary.perHourMs[it] }
        binding.summaryBusiestHour.text = if (busiestHour != null && summary.perHourMs[busiestHour] > 0L) {
            getString(R.string.summary_busiest_hour, hourText(busiestHour), hourText((busiestHour + 1) % 24))
        } else {
            getString(R.string.summary_nothing_yet)
        }
        binding.summaryHourlyChart.setData((0 until 24).map { hour ->
            WeeklyBarGraphView.BarData(
                if (hour % 6 == 0) hourText(hour) else "",
                summary.perHourMs[hour] / count / MINUTE_MS.toFloat(),
                0L
            )
        })

        // Lists
        fillRows(binding.summaryTopApps, summary.topApps.map { Triple(it.first, duration(it.second), it.second) }, summary.totalMs)
        val reelRows = buildList {
            add(Triple(getString(R.string.summary_reels_total, summary.reelsCount), duration(summary.reelsMs), -1L))
            summary.topReelApps.forEach { (name, ms, reels) ->
                add(Triple(name, getString(R.string.summary_reels_app_value, duration(ms), reels), ms))
            }
        }
        fillRows(binding.summaryReels, reelRows, summary.reelsMs)
        val sitesTotal = summary.topSites.sumOf { it.second }
        fillRows(binding.summaryTopSites, summary.topSites.map { Triple(it.first, duration(it.second), it.second) }, sitesTotal)
    }

    private fun addStat(label: String, value: String, note: String) {
        val card = ItemSummaryStatBinding.inflate(layoutInflater, binding.summaryStats, false)
        card.statLabel.text = label
        card.statValue.text = value
        card.statNote.text = note
        card.statNote.isVisible = note.isNotEmpty()
        binding.summaryStats.addView(card.root)
    }

    /** Rows of name, value text and share of [total]; a share of -1 hides the bar. */
    private fun fillRows(container: LinearLayout, rows: List<Triple<String, String, Long>>, total: Long) {
        container.removeAllViews()
        if (rows.isEmpty()) {
            val row = ItemSummaryRowBinding.inflate(layoutInflater, container, false)
            row.rowName.text = getString(R.string.summary_nothing_yet)
            row.rowShare.isVisible = false
            container.addView(row.root)
            return
        }
        rows.forEach { (name, value, share) ->
            val row = ItemSummaryRowBinding.inflate(layoutInflater, container, false)
            row.rowName.text = name
            row.rowValue.text = value
            row.rowShare.isVisible = share >= 0L && total > 0L
            if (share >= 0L && total > 0L) row.rowShare.progress = (share * 100 / total).toInt()
            container.addView(row.root)
        }
    }

    private fun duration(ms: Long): String {
        val minutes = ms / MINUTE_MS
        return when {
            minutes < 1 -> getString(R.string.summary_under_a_minute)
            minutes < 60 -> getString(R.string.summary_minutes, minutes)
            else -> getString(R.string.summary_hours_minutes, minutes / 60, minutes % 60)
        }
    }

    private fun hourText(hour: Int): String = String.format(Locale.getDefault(), "%02d:00", hour)

    private fun appLabel(context: Context, packageName: String): String = runCatching {
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)
}
