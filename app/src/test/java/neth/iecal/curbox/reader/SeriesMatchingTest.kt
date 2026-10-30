package neth.iecal.curbox.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesMatchingTest {

    private fun series(name: String, mangadexId: String? = null) = SeriesEntity(
        key = SeriesKeys.of(name), name = name, lastChapter = 1.0, lastUrl = "", lastSite = "",
        lastReadAt = 0L, readingTimeMs = 0L, mangadexId = mangadexId
    )

    @Test
    fun smallWordsDoNotMatter() {
        assertTrue(SeriesMatching.isSimilar(SeriesKeys.of("The Greatest Estate Developer"), SeriesKeys.of("Greatest Estate Developer")))
    }

    @Test
    fun nameWithAnAddedSubtitle() {
        assertTrue(SeriesMatching.isSimilar(SeriesKeys.of("Nano Machine"), SeriesKeys.of("Nano Machine (Official)")))
    }

    @Test
    fun oneSharedWordIsNotEnough() {
        assertFalse(SeriesMatching.isSimilar(SeriesKeys.of("Eleceed"), SeriesKeys.of("Eleceed Side Story Collection Vol")))
        assertFalse(SeriesMatching.isSimilar(SeriesKeys.of("Solo Leveling"), SeriesKeys.of("Leveling With The Gods")))
    }

    @Test
    fun skipsAnsweredPairsAndDifferentMangadexSeries() {
        val a = series("The Greatest Estate Developer")
        val b = series("Greatest Estate Developer")
        val c = series("Nano Machine", "md1")
        val d = series("Nano Machine Official", "md2")
        assertEquals(1, SeriesMatching.possibleDuplicates(listOf(a, b, c, d), emptySet()).size)
        assertEquals(0, SeriesMatching.possibleDuplicates(listOf(a, b), setOf(SeriesMatching.pairId(a.key, b.key))).size)
    }

    @Test
    fun mangadexBatchTitles() {
        val json = """{"data":[{"id":"x1","attributes":{"title":{"en":"Return of the Mount Hua Sect"},
            "altTitles":[{"en":"Return of the Blossoming Blade"},{"ko-ro":"Hwasan Gwihwan"}]}}]}"""
        val titles = MangaDex.parseTitles(json).getValue("x1")
        assertTrue(SeriesKeys.of("Return of the Blossoming Blade") in titles.map(SeriesKeys::of))
        assertEquals(3, titles.size)
    }
}
