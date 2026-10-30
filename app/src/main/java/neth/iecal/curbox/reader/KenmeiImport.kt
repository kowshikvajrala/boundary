package neth.iecal.curbox.reader

import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Locale

/**
 * Reads the list exported from Kenmei (Settings, export as CSV). Column names vary between
 * exports, so each field accepts several names; only `title` is required.
 */
object KenmeiImport {

    data class Entry(
        val title: String,
        val status: SeriesStatus,
        val chapter: Double,
        val url: String,
        val lastReadAt: Long
    )

    private val CHAPTER_COLUMNS = listOf("last_chapter_read", "chapters_read", "chapter", "current_chapter", "last_read_chapter")
    private val STATUS_COLUMNS = listOf("status", "reading_status")
    private val URL_COLUMNS = listOf("series_url", "url", "link", "source_url")
    private val DATE_COLUMNS = listOf("last_read_at", "last_read", "date_last_read", "updated_at", "date")
    private val NUMBER = Regex("""\d+(?:\.\d+)?""")

    /** Throws [IllegalArgumentException] when the file has no title column. */
    fun parse(csv: String): List<Entry> {
        val rows = readCsv(csv.removePrefix("﻿"))
        if (rows.isEmpty()) return emptyList()
        val headers = rows.first().map { it.trim().lowercase(Locale.ROOT).replace(' ', '_') }
        val titleAt = headers.indexOf("title")
        require(titleAt >= 0) { "No title column" }
        fun at(names: List<String>) = names.firstNotNullOfOrNull { name -> headers.indexOf(name).takeIf { it >= 0 } }
        val chapterAt = at(CHAPTER_COLUMNS)
        val statusAt = at(STATUS_COLUMNS)
        val urlAt = at(URL_COLUMNS)
        val dateAt = at(DATE_COLUMNS)

        return rows.drop(1).mapNotNull { row ->
            fun cell(index: Int?) = index?.let(row::getOrNull)?.trim().orEmpty()
            val title = cell(titleAt).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Entry(
                title = title,
                status = SeriesStatus.of(cell(statusAt)) ?: SeriesStatus.READING,
                chapter = NUMBER.find(cell(chapterAt))?.value?.toDoubleOrNull() ?: 0.0,
                url = cell(urlAt).takeIf { it.startsWith("http://") || it.startsWith("https://") }.orEmpty(),
                lastReadAt = parseDate(cell(dateAt)) ?: 0L
            )
        }
    }

    /** The site of an address, like "asurascans.com", or empty. */
    fun siteOf(url: String): String =
        runCatching { URI(url).host.orEmpty().removePrefix("www.") }.getOrDefault("")

    private fun parseDate(text: String): Long? {
        if (text.isEmpty()) return null
        // Kenmei writes "2021-11-01 13:11:30 UTC".
        val iso = text.removeSuffix(" UTC").replace(' ', 'T') + if (text.endsWith(" UTC")) "Z" else ""
        return runCatching { Instant.parse(iso).toEpochMilli() }.getOrNull()
            ?: runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(text.replace(' ', 'T')).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { LocalDate.parse(text.take(10)).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
    }

    /** CSV with quoted fields, doubled quotes and line breaks inside quotes. */
    internal fun readCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        fun endField() { row += field.toString(); field.clear() }
        fun endRow() {
            endField()
            if (row.any { it.isNotBlank() }) rows += row.toList()
            row.clear()
        }
        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes && c == '"' && text.getOrNull(i + 1) == '"' -> { field.append('"'); i++ }
                c == '"' -> inQuotes = !inQuotes
                !inQuotes && c == ',' -> endField()
                !inQuotes && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && text.getOrNull(i + 1) == '\n') i++
                    endRow()
                }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }
}
