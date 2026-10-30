package neth.iecal.curbox.reader

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Looks up new chapters for the series being read, planned or on hold. MangaDex first for
 * linked series, then the series page of the site the owner reads on; chapters found on that
 * site win, so "next unread" opens where the owner reads. Unlinked series are searched on
 * MangaDex by title once a week, and a match waits for the owner to confirm it.
 */
object ReleaseChecker {
    private const val TAG = "ReleaseChecker"
    private const val SEARCH_AGAIN_MS = 7 * 24 * 60 * 60_000L
    private const val TITLES_AGAIN_MS = 30 * 24 * 60 * 60_000L
    // MangaDex allows about five requests a second; stay well under it.
    private const val PAUSE_MS = 400L
    // Series waiting for review are checked too, so a MangaDex search can merge them into one
    // already in the list under another name.
    private val CHECKED = setOf(
        SeriesStatus.READING.id, SeriesStatus.PLAN_TO_READ.id, SeriesStatus.ON_HOLD.id, SeriesStatus.DETECTED.id
    )

    suspend fun checkAll(context: Context) {
        val dao = ReadingDatabase.getInstance(context).readingDao()
        mergeSameMangadexSeries(dao)
        learnOtherNames(dao)
        dao.allSeries().filter { it.status in CHECKED }.forEach { listed ->
            // An earlier step may have merged this one away.
            val series = dao.series(listed.key) ?: return@forEach
            try {
                check(dao, series)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Check failed for ${series.key}", e)
                dao.setChecked(series.key, System.currentTimeMillis(), failed = true)
            }
        }
    }

    private suspend fun check(dao: ReadingDao, series: SeriesEntity) {
        val now = System.currentTimeMillis()
        if (series.mangadexId == null && series.mangadexCandidateId == null && !series.mangadexDeclined &&
            now - series.mangadexSearchedAt > SEARCH_AGAIN_MS
        ) {
            val match = attempt { MangaDex.search(series.name) }
            delay(PAUSE_MS)
            val owner = match?.let { dao.seriesByMangadexId(it.id) }
            if (owner != null && owner.key != series.key) {
                // Already in the list under another name: the same series, proven by MangaDex.
                dao.merge(series.key, owner.key)
                return
            }
            dao.setMangadexCandidate(series.key, match?.id, match?.title, now)
        }

        val releases = mutableListOf<Release>()
        var triedAny = false
        var anyWorked = false
        series.mangadexId?.let { id ->
            triedAny = true
            attempt { MangaDex.releases(id) }?.let { releases += it; anyWorked = true }
            delay(PAUSE_MS)
        }
        val page = series.lastUrl.takeIf { series.lastSite != "mangadex.org" }?.let(ChapterParser::seriesPage)
        if (page != null) {
            triedAny = true
            attempt { SitePage.releases(page, series.key) }?.takeIf { it.isNotEmpty() }?.let { releases += it; anyWorked = true }
        }
        if (!triedAny) return
        if (anyWorked) {
            dao.replaceAvailableChapters(series.key, releases.map { AvailableChapterEntity(series.key, it.chapter, it.url) })
        }
        dao.setChecked(series.key, now, failed = !anyWorked)
    }

    /** Two entries linked to the same MangaDex series are one series. */
    private suspend fun mergeSameMangadexSeries(dao: ReadingDao) {
        dao.allSeries().filter { it.mangadexId != null }.groupBy { it.mangadexId }.values
            .filter { it.size > 1 }
            .forEach { group ->
                val target = group.maxWith(compareBy<SeriesEntity>({ it.lastChapter }, { it.lastReadAt }))
                group.filter { it.key != target.key }.forEach { dao.merge(it.key, target.key) }
            }
    }

    /**
     * Saves every other MangaDex name of linked series as an alias, so a site using another name
     * counts for the same series, and merges entries already listed under one of those names.
     * Refreshed monthly; up to 100 series per request.
     */
    private suspend fun learnOtherNames(dao: ReadingDao) {
        val now = System.currentTimeMillis()
        val due = dao.allSeries().filter { it.mangadexId != null && now - it.mangadexTitlesAt > TITLES_AGAIN_MS }
        due.chunked(100).forEach { chunk ->
            val titles = attempt { MangaDex.titles(chunk.mapNotNull { it.mangadexId }) } ?: return@forEach
            delay(PAUSE_MS)
            chunk.forEach { listed ->
                val series = dao.series(listed.key) ?: return@forEach
                val names = titles[series.mangadexId] ?: return@forEach
                names.map(SeriesKeys::of).filter { it.isNotEmpty() && it != series.key }.distinct().forEach { otherKey ->
                    val other = dao.series(otherKey)
                    if (other != null && (other.mangadexId == null || other.mangadexId == series.mangadexId)) {
                        dao.merge(otherKey, series.key)
                    } else {
                        dao.putAliasIfAbsent(SeriesAliasEntity(otherKey, series.key))
                    }
                }
                dao.setMangadexTitlesAt(series.key, now)
            }
        }
    }

    private inline fun <T> attempt(block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Source failed: ${e.message}")
        null
    }
}
