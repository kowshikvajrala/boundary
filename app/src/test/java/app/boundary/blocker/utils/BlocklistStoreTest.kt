package app.boundary.blocker.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlocklistStoreTest {

    @Test
    fun parsesEveryCommonListFormat() {
        assertEquals("site.com", BlocklistStore.parseLine("0.0.0.0 site.com"))
        assertEquals("site.com", BlocklistStore.parseLine("127.0.0.1\tsite.com # tracker"))
        assertEquals("site.com", BlocklistStore.parseLine("site.com"))
        assertEquals("site.com", BlocklistStore.parseLine("*.site.com"))
        assertEquals("site.com", BlocklistStore.parseLine("||site.com^"))
        assertEquals("site.com", BlocklistStore.parseLine("||site.com^\$important"))
        assertEquals("site.com", BlocklistStore.parseLine("www.SITE.com."))
        assertEquals("sub.site.co.uk", BlocklistStore.parseLine("sub.site.co.uk"))
    }

    @Test
    fun skipsCommentsHeadersAndJunk() {
        assertNull(BlocklistStore.parseLine("# comment"))
        assertNull(BlocklistStore.parseLine("! adblock comment"))
        assertNull(BlocklistStore.parseLine("[Adblock Plus 2.0]"))
        assertNull(BlocklistStore.parseLine("@@||allowed.com^"))
        assertNull(BlocklistStore.parseLine("127.0.0.1 localhost"))
        assertNull(BlocklistStore.parseLine("0.0.0.0"))
        assertNull(BlocklistStore.parseLine("   "))
    }

    @Test
    fun matchesHostAndParentDomains() {
        val domains = setOf("eporner.com", "bad.example.org")

        assertTrue(BlocklistStore.matchesHost(domains, "eporner.com"))
        assertTrue(BlocklistStore.matchesHost(domains, "www.eporner.com"))
        assertTrue(BlocklistStore.matchesHost(domains, "m.eporner.com"))
        assertTrue(BlocklistStore.matchesHost(domains, "x.bad.example.org"))

        assertFalse(BlocklistStore.matchesHost(domains, "example.org"))
        assertFalse(BlocklistStore.matchesHost(domains, "noteporner.com"))
        assertFalse(BlocklistStore.matchesHost(domains, "eporner.xyz"))
    }

    @Test
    fun readsListIdOnlyFromSafeListKeywords() {
        assertEquals("adult", BlocklistStore.idFromKeyword("list:adult"))
        assertEquals("adult", BlocklistStore.idFromKeyword("LIST: Adult"))
        assertNull(BlocklistStore.idFromKeyword("adult"))
        assertNull(BlocklistStore.idFromKeyword("list:../secrets"))
        assertNull(BlocklistStore.idFromKeyword("list:"))
    }
}
