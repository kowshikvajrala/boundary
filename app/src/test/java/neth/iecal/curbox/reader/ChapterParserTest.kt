package neth.iecal.curbox.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChapterParserTest {

    private fun assertChapter(url: String, seriesKey: String, number: Double) {
        val chapter = ChapterParser.parse(url) ?: throw AssertionError("No chapter found in $url")
        assertEquals(url, seriesKey, chapter.seriesKey)
        assertEquals(url, number, chapter.number, 0.0001)
    }

    @Test
    fun chapterAsItsOwnPathPart() {
        assertChapter("asuracomic.net/series/solo-leveling-abc123de/chapter/179", "solo leveling", 179.0)
    }

    @Test
    fun asuraScansComics() {
        assertChapter("asurascans.com/comics/star-embracing-swordmaster-3ebcc5a9/chapter/144", "star embracing swordmaster", 144.0)
        assertEquals(
            "Star Embracing Swordmaster",
            ChapterParser.parse("asurascans.com/comics/star-embracing-swordmaster-3ebcc5a9/chapter/144")!!.seriesName
        )
    }

    @Test
    fun chapterAfterSeriesInOnePart() {
        assertChapter("manhwaclan.com/manga/omniscient-reader/chapter-201/", "omniscient reader", 201.0)
        assertChapter("example.net/omniscient-reader-chapter-201", "omniscient reader", 201.0)
    }

    @Test
    fun webtoonsEpisodes() {
        assertChapter(
            "www.webtoons.com/en/action/tower-of-god/season-3-ep-120/viewer?title_no=95&episode_no=520",
            "tower of god",
            120.0
        )
        assertChapter("www.webtoons.com/en/fantasy/the-boxer/episode-12/viewer?title_no=2027&episode_no=12", "the boxer", 12.0)
    }

    @Test
    fun halfChapters() {
        assertChapter("reaperscans.com/series/the-greatest-estate-developer/chapter-120-5", "the greatest estate developer", 120.5)
        assertChapter("site.org/manga/nano-machine/ch.45.5", "nano machine", 45.5)
    }

    @Test
    fun chapterInQuery() {
        assertChapter("reader.example.com/read/eleceed?chapter=310", "eleceed", 310.0)
    }

    @Test
    fun sameSeriesOnTwoSitesGetsTheSameKey() {
        val a = ChapterParser.parse("asuracomic.net/series/solo-leveling-9f2c1a7b/chapter/12")!!
        val b = ChapterParser.parse("flamecomics.xyz/series/solo-leveling/chapter-13")!!
        assertEquals(a.seriesKey, b.seriesKey)
    }

    @Test
    fun keepsTheAddressToOpenAgain() {
        val chapter = ChapterParser.parse("asuracomic.net/series/Solo-Leveling/chapter/179")!!
        assertEquals("https://asuracomic.net/series/Solo-Leveling/chapter/179", chapter.url)
        assertEquals("Solo Leveling", chapter.seriesName)
        assertEquals("asuracomic.net", chapter.site)
    }

    @Test
    fun pagesThatAreNotChapters() {
        assertNull(ChapterParser.parse("google.com/search?q=solo+leveling"))
        assertNull(ChapterParser.parse("asuracomic.net/series/solo-leveling"))
        assertNull(ChapterParser.parse("youtube.com/watch?v=abc123"))
        assertNull(ChapterParser.parse("mangadex.org/chapter/0c8f3a2e-1b2c-4d5e-9f00-123456789abc"))
        assertNull(ChapterParser.parse("example.com/chapter/12"))
    }

    @Test
    fun findsTheSeriesPage() {
        assertEquals(
            "https://asurascans.com/comics/star-embracing-swordmaster-3ebcc5a9",
            ChapterParser.seriesPage("asurascans.com/comics/star-embracing-swordmaster-3ebcc5a9/chapter/144")
        )
        assertEquals(
            "https://manhwaclan.com/manga/omniscient-reader",
            ChapterParser.seriesPage("manhwaclan.com/manga/omniscient-reader/chapter-201/")
        )
        assertNull(ChapterParser.seriesPage("example.net/omniscient-reader-chapter-201"))
        assertNull(ChapterParser.seriesPage("reader.example.com/read/eleceed?chapter=310"))
    }

    @Test
    fun formatsNumbers() {
        assertEquals("179", ChapterParser.format(179.0))
        assertEquals("120.5", ChapterParser.format(120.5))
    }
}
