package neth.iecal.curbox.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KenmeiImportTest {

    @Test
    fun realKenmeiExport() {
        val csv = listOf(
            "title,status,score,last_volume_read,last_chapter_read,last_chapter_title_read,last_read_at,migratable,source_to_be_removed_at,notes,tracked_site,series_url,tags",
            "Mo Shi Wei Wang,plan_to_read,,,,,,,,,MangaDex,https://mangadex.org/title/c4c48989-e9df-4bd2-8f4e-03b71c889124,",
            "Against the Gods,on_hold,,,118.0,Chapter 118,2021-11-01 13:11:30 UTC,,,,MangaDex,https://mangadex.org/title/a9eb39e6-b0ae-46fb-9323-d88af37e4bcd,",
            "Gong Heon Ja,plan_to_read,,,,,,,,,MangaDex,https://mangadex.org/title/3b3fd6eb-b46d-4ba7-a57e-67e1899b206d,"
        ).joinToString("\n")
        val entries = KenmeiImport.parse(csv)
        assertEquals(3, entries.size)
        assertEquals("Mo Shi Wei Wang", entries[0].title)
        assertEquals(SeriesStatus.PLAN_TO_READ, entries[0].status)
        assertEquals(0.0, entries[0].chapter, 0.0)
        assertEquals(0L, entries[0].lastReadAt)
        val gods = entries[1]
        assertEquals(SeriesStatus.ON_HOLD, gods.status)
        assertEquals(118.0, gods.chapter, 0.0)
        assertEquals(java.time.Instant.parse("2021-11-01T13:11:30Z").toEpochMilli(), gods.lastReadAt)
        assertEquals("https://mangadex.org/title/a9eb39e6-b0ae-46fb-9323-d88af37e4bcd", gods.url)
        assertEquals("mangadex.org", KenmeiImport.siteOf(gods.url))
    }

    @Test
    fun readsTheUsualColumns() {
        val csv = """
            title,status,last_chapter_read,series_url,last_read_at
            Star-Embracing Swordmaster,reading,144,https://asurascans.com/comics/star-embracing-swordmaster-3ebcc5a9,2025-01-02T10:00:00Z
            "Solo Leveling, Ragnarok",plan_to_read,0,,
            The Novel's Extra,completed,410.5,https://example.com/series/the-novels-extra,2024-05-01
        """.trimIndent()
        val entries = KenmeiImport.parse(csv)
        assertEquals(3, entries.size)
        assertEquals("Star-Embracing Swordmaster", entries[0].title)
        assertEquals(SeriesStatus.READING, entries[0].status)
        assertEquals(144.0, entries[0].chapter, 0.0)
        assertEquals("asurascans.com", KenmeiImport.siteOf(entries[0].url))
        assertTrue(entries[0].lastReadAt > 0L)
        assertEquals("Solo Leveling, Ragnarok", entries[1].title)
        assertEquals(SeriesStatus.PLAN_TO_READ, entries[1].status)
        assertEquals(410.5, entries[2].chapter, 0.0)
        assertEquals(SeriesStatus.COMPLETED, entries[2].status)
    }

    @Test
    fun acceptsOtherColumnNamesAndStatusSpelling() {
        val csv = "Title,Reading Status,Chapters Read,URL\nEleceed,On Hold,Ch. 310,https://x.com/eleceed\n"
        val entry = KenmeiImport.parse(csv).single()
        assertEquals(SeriesStatus.ON_HOLD, entry.status)
        assertEquals(310.0, entry.chapter, 0.0)
        assertEquals("https://x.com/eleceed", entry.url)
    }

    @Test(expected = IllegalArgumentException::class)
    fun needsATitleColumn() {
        KenmeiImport.parse("name,status\nA,reading\n")
    }

    @Test
    fun importedTitlesMeetDetectedSeries() {
        val detected = ChapterParser.parse("asurascans.com/comics/star-embracing-swordmaster-3ebcc5a9/chapter/144")!!
        assertEquals(detected.seriesKey, SeriesKeys.of("Star-Embracing Swordmaster"))
        val extra = ChapterParser.parse("example.com/series/the-novels-extra/chapter-411")!!
        assertEquals(extra.seriesKey, SeriesKeys.of("The Novel's Extra"))
    }

    @Test
    fun quotedFieldsWithLineBreaksAndQuotes() {
        val rows = KenmeiImport.readCsv("a,b\n\"x \"\"y\"\"\",\"line1\nline2\"\n")
        assertEquals(listOf("x \"y\"", "line1\nline2"), rows[1])
    }
}
