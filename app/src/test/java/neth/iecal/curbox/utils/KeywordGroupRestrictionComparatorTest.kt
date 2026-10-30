package neth.iecal.curbox.utils

import neth.iecal.curbox.data.models.KeywordBlocker
import neth.iecal.curbox.data.models.KeywordGroup
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeywordGroupRestrictionComparatorTest {
    private val oldBlocker = blocker()

    @Test
    fun turningOffUnsupportedBrowserBlockingIsWeaker() {
        val group = oldBlocker.keywordGroups.single()
        val on = oldBlocker.copy(keywordGroups = listOf(group.copy(blockUnsupportedBrowsers = true)))
        val off = oldBlocker.copy(keywordGroups = listOf(group.copy(blockUnsupportedBrowsers = false)))
        assertFalse(RestrictionComparator.keywordBlocker(on, off))
        assertTrue(RestrictionComparator.keywordBlocker(off, on))
    }

    @Test
    fun keywordOrderAndDuplicatesDoNotWeakenTheGroup() {
        val group = oldBlocker.keywordGroups.single().copy(selectedKeywords = listOf("first", "second"))
        val old = oldBlocker.copy(keywordGroups = listOf(group))
        val proposed = old.copy(keywordGroups = listOf(group.copy(
            selectedKeywords = listOf("second", "first", "first")
        )))
        assertTrue(RestrictionComparator.keywordBlocker(old, proposed))
    }

    @Test(timeout = 5000)
    fun handlesLargeWordListUpdates() {
        val keywords = List(100_000) { "domain$it.example" }
        val group = oldBlocker.keywordGroups.single().copy(selectedKeywords = keywords)
        val old = oldBlocker.copy(keywordGroups = listOf(group))
        assertTrue(RestrictionComparator.keywordBlocker(old, old))
        val expanded = old.copy(keywordGroups = listOf(group.copy(
            selectedKeywords = keywords + "additional.example"
        )))
        assertTrue(RestrictionComparator.keywordBlocker(old, expanded))
        val removed = expanded.copy(keywordGroups = listOf(group.copy(
            selectedKeywords = keywords.drop(1) + "additional.example"
        )))
        assertFalse(RestrictionComparator.keywordBlocker(old, removed))
    }

    private fun blocker() = KeywordBlocker(
        isActive = true,
        keywordGroups = listOf(
            KeywordGroup(
                id = "group",
                selectedKeywords = listOf("example"),
                isActive = true
            )
        )
    )
}
