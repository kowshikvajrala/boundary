package neth.iecal.curbox.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReleaseSourcesTest {

    @Test
    fun mangadexIdFromKenmeiAddress() {
        assertEquals(
            "a9eb39e6-b0ae-46fb-9323-d88af37e4bcd",
            MangaDex.idFromUrl("https://mangadex.org/title/a9eb39e6-b0ae-46fb-9323-d88af37e4bcd")
        )
        assertNull(MangaDex.idFromUrl("https://asurascans.com/comics/x"))
    }

    @Test
    fun mangadexSearchMatchesByAnyTitle() {
        val json = """{"data":[
            {"id":"aaa","attributes":{"title":{"en":"Something Else"},"altTitles":[]}},
            {"id":"bbb","attributes":{"title":{"ko-ro":"Ma-eul"},"altTitles":[{"en":"Against the Gods"},{"ja":"x"}]}}
        ]}"""
        val match = MangaDex.parseSearch(json, SeriesKeys.of("Against the Gods"))!!
        assertEquals("bbb", match.id)
        assertNull(MangaDex.parseSearch(json, SeriesKeys.of("Nano Machine")))
    }

    @Test
    fun mangadexFeedKeepsNumberedChapters() {
        val json = """{"data":[
            {"id":"c1","attributes":{"chapter":"120","externalUrl":null}},
            {"id":"c2","attributes":{"chapter":"119.5","externalUrl":"https://mangaplus.example/viewer/1"}},
            {"id":"c3","attributes":{"chapter":null,"externalUrl":null}}
        ]}"""
        val releases = MangaDex.parseFeed(json)
        assertEquals(2, releases.size)
        assertEquals(Release(120.0, "https://mangadex.org/chapter/c1"), releases[0])
        assertEquals(Release(119.5, "https://mangaplus.example/viewer/1"), releases[1])
    }

    @Test
    fun seriesPageLinksForTheSameSeriesOnly() {
        val html = """
            <a href="/comics/star-embracing-swordmaster-3ebcc5a9/chapter/146">Chapter 146</a>
            <a href='https://asurascans.com/comics/star-embracing-swordmaster-3ebcc5a9/chapter/145'>145</a>
            <a href="/comics/star-embracing-swordmaster-3ebcc5a9/chapter/146">dup</a>
            <a href="/comics/another-series-11aa22bb/chapter/9">other</a>
            <a href="/comics/star-embracing-swordmaster-3ebcc5a9">series</a>
        """
        val releases = SitePage.parseLinks(
            html,
            "https://asurascans.com/comics/star-embracing-swordmaster-3ebcc5a9",
            SeriesKeys.of("Star Embracing Swordmaster")
        )
        assertEquals(
            listOf(
                Release(146.0, "https://asurascans.com/comics/star-embracing-swordmaster-3ebcc5a9/chapter/146"),
                Release(145.0, "https://asurascans.com/comics/star-embracing-swordmaster-3ebcc5a9/chapter/145")
            ),
            releases
        )
    }
}
