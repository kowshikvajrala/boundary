package neth.iecal.curbox.reader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import neth.iecal.curbox.services.BaseBlockingService
import neth.iecal.curbox.trackers.WebsiteObservation

/**
 * Remembers which chapter of which series is open in the browser, from the pages Boundary
 * already reads (see [neth.iecal.curbox.trackers.WebsiteUsageTracker]). Reading time counts only
 * while a chapter page is in front and the screen is on. Runs in the service process; every
 * failure is logged and swallowed so blocking is never affected.
 */
class ReadingTracker {

    private sealed interface Event {
        data class Page(val observation: WebsiteObservation?) : Event
        object ScreenOff : Event
        object UserPresent : Event
        object Flush : Event
    }

    private data class Reading(val seriesKey: String, val chapter: Double, val sinceMs: Long)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val events = Channel<Event>(Channel.UNLIMITED)
    private lateinit var service: BaseBlockingService
    private lateinit var dao: ReadingDao
    @Volatile private var isEnabled = false
    // Only touched by the event loop.
    private var lastObservation: WebsiteObservation? = null
    private var current: Reading? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> events.trySend(Event.ScreenOff)
                Intent.ACTION_USER_PRESENT -> events.trySend(Event.UserPresent)
            }
        }
    }

    fun setup(service: BaseBlockingService) {
        this.service = service
        dao = ReadingDatabase.getInstance(service).readingDao()
        scope.launch {
            service.dataStoreManager.settings.collectLatest { settings ->
                // Keeps the new chapter check scheduled even if the Reading tab is never opened.
                runCatching { neth.iecal.curbox.services.ReleaseCheckJob.ensureScheduled(service, settings.releaseCheckHours) }
                isEnabled = settings.isReadingTrackerEnabled
                if (!isEnabled) events.trySend(Event.Page(null))
            }
        }
        scope.launch {
            for (event in events) {
                try {
                    handle(event)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Reading tracker failed", e)
                }
            }
        }
        // Saves the time read so far now and then, so little is lost if the service stops.
        scope.launch {
            while (isActive) {
                delay(FLUSH_INTERVAL_MS)
                events.trySend(Event.Flush)
            }
        }
        service.registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        })
    }

    fun onWebsiteObserved(observation: WebsiteObservation?) {
        events.trySend(Event.Page(observation))
    }

    fun onDestroy() {
        runCatching { service.unregisterReceiver(screenReceiver) }
        events.trySend(Event.Page(null))
        events.close()
        // The loop finishes the open chapter before the scope goes; give it a moment.
        scope.launch {
            delay(1_000)
            scope.cancel()
        }
    }

    private suspend fun handle(event: Event) {
        when (event) {
            is Event.Page -> {
                lastObservation = event.observation
                onPage(event.observation)
            }
            Event.ScreenOff -> finishCurrent()
            // The tracker does not report a page again if it did not change, so pick the
            // reading back up when the same page is still in front after unlocking the phone.
            Event.UserPresent -> {
                val page = lastObservation ?: return
                val front = runCatching { service.rootInActiveWindow?.packageName?.toString() }.getOrNull()
                if (front == page.packageName) onPage(page)
            }
            Event.Flush -> current?.let { reading ->
                val now = SystemClock.elapsedRealtime()
                addTime(reading, now)
                current = reading.copy(sinceMs = now)
            }
        }
    }

    private suspend fun onPage(observation: WebsiteObservation?) {
        val chapter = observation?.takeIf { isEnabled }?.let { ChapterParser.parse(it.urlIdentifier) }
        if (chapter == null) {
            finishCurrent()
            return
        }
        val key = dao.aliasOf(chapter.seriesKey) ?: chapter.seriesKey
        val reading = current
        if (reading != null && reading.seriesKey == key && reading.chapter == chapter.number) return

        finishCurrent()
        val now = System.currentTimeMillis()
        val read = dao.chapterRead(key, chapter.number)
        dao.putChapterRead(
            ChapterReadEntity(
                seriesKey = key,
                chapter = chapter.number,
                url = chapter.url,
                site = chapter.site,
                firstOpenedAt = read?.firstOpenedAt ?: now,
                lastOpenedAt = now,
                readingTimeMs = read?.readingTimeMs ?: 0L
            )
        )
        val series = dao.series(key)
        dao.putSeries(
            // A name set by hand, a MangaDex link and check results stay.
            series?.copy(
                lastChapter = chapter.number,
                lastUrl = chapter.url,
                lastSite = chapter.site,
                lastReadAt = now,
                status = readingStatus(series.status)
            ) ?: SeriesEntity(
                key = key,
                name = chapter.seriesName,
                lastChapter = chapter.number,
                lastUrl = chapter.url,
                lastSite = chapter.site,
                lastReadAt = now,
                readingTimeMs = 0L,
                // A series seen for the first time waits for the owner in "To review".
                status = SeriesStatus.DETECTED.id
            )
        )
        current = Reading(key, chapter.number, SystemClock.elapsedRealtime())
    }

    // Opening a chapter of a series that was planned, on hold or dropped means reading it again.
    // A completed one stays completed (a reread); one waiting for review or ignored stays so.
    private fun readingStatus(status: String?): String =
        if (status in KEEPS_STATUS) status!! else SeriesStatus.READING.id

    private suspend fun finishCurrent() {
        val reading = current ?: return
        current = null
        addTime(reading, SystemClock.elapsedRealtime())
    }

    private suspend fun addTime(reading: Reading, nowMs: Long) {
        // A long gap means the phone slept without telling us; do not count it.
        val ms = (nowMs - reading.sinceMs).takeIf { it in 1..MAX_SEGMENT_MS } ?: return
        dao.chapterRead(reading.seriesKey, reading.chapter)?.let {
            dao.putChapterRead(it.copy(readingTimeMs = it.readingTimeMs + ms))
        }
        dao.series(reading.seriesKey)?.let {
            dao.putSeries(it.copy(readingTimeMs = it.readingTimeMs + ms))
        }
    }

    private companion object {
        const val TAG = "ReadingTracker"
        const val FLUSH_INTERVAL_MS = 60_000L
        const val MAX_SEGMENT_MS = 10 * 60_000L
        val KEEPS_STATUS = setOf(SeriesStatus.COMPLETED.id, SeriesStatus.DETECTED.id, SeriesStatus.IGNORED.id)
    }
}
