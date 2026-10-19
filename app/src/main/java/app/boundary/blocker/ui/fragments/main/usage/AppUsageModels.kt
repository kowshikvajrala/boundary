package app.boundary.blocker.ui.fragments.main.usage

import java.time.LocalDate
import app.boundary.blocker.data.db.ReelUsageStatsEntity
import app.boundary.blocker.data.db.WebsiteStatsEntity

data class AppUsageStat(
    val packageName: String,
    val totalTime: Long,
    val sessions: Int = 0,
    val hourlyUsage: LongArray = LongArray(24)
)

enum class AppUsageChartScale {
    DAYS,
    WEEKS
}

data class AppUsagePeriod(
    val start: LocalDate,
    val endInclusive: LocalDate,
    val label: String
)

data class AppUsageAnalytics(
    val apps: List<AppUsageStat>,
    val websites: List<WebsiteStatsEntity>,
    val reels: List<ReelUsageStatsEntity>,
    val totalTime: Long,
    val totalLabel: String
)
