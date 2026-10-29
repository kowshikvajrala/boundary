package neth.iecal.curbox.hardcoded

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppWebsitesTest {

    @Test
    fun typedWebsiteIsReducedToItsSite() {
        assertEquals("example.com", AppWebsites.normalize("  https://www.Example.com/page?x=1 "))
        assertEquals("news.example.com", AppWebsites.normalize("news.example.com:443"))
        assertNull(AppWebsites.normalize("example"))
        assertNull(AppWebsites.normalize(""))
    }

    @Test
    fun siteMatchesItselfAndSubdomainsOnly() {
        assertTrue(AppWebsites.matches("example.com", "example.com"))
        assertTrue(AppWebsites.matches("m.example.com", "example.com"))
        assertTrue(AppWebsites.matches("www.example.com", "example.com"))
        assertFalse(AppWebsites.matches("notexample.com", "example.com"))
    }

    @Test
    fun knownAppWebsites() {
        assertEquals(listOf("com.instagram.android"), AppWebsites.packagesFor("instagram.com"))
        assertEquals(listOf("com.facebook.katana"), AppWebsites.packagesFor("m.facebook.com"))
        assertTrue(AppWebsites.packagesFor("example.com").isEmpty())
    }

    @Test
    fun sitesOfAppsAndTypedWebsites() {
        assertEquals(listOf("instagram.com"), AppWebsites.sitesFor("com.instagram.android"))
        assertEquals(listOf("example.com"), AppWebsites.sitesFor(AppWebsites.siteKey("example.com")))
        assertTrue(AppWebsites.sitesFor("com.example.unknown").isEmpty())
    }
}
