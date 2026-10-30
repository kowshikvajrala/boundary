package neth.iecal.curbox.utils

import com.google.gson.Gson
import neth.iecal.curbox.data.models.ReelBlocker
import neth.iecal.curbox.data.models.ReelBlockerConfig
import neth.iecal.curbox.data.models.ReelCountConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReelBlockerRestrictionComparatorTest {

    @Test
    fun oldStoredConfigGetsEmptyExcludedPackages() {
        val config = Gson().fromJson("""{"isActive":true}""", ReelBlocker::class.java)

        assertTrue(config.excludedPackages.isEmpty())
        assertFalse(config.allowInstagramReelsFromDmInbox)
    }

    @Test
    fun addingExcludedPackageIsWeaker() {
        val old = ReelBlocker(isActive = true)
        val new = old.copy(excludedPackages = listOf("com.instagram.android"))

        assertFalse(RestrictionComparator.reelBlocker(old, new))
    }

    @Test
    fun removingExcludedPackageIsStricter() {
        val old = ReelBlocker(
            isActive = true,
            excludedPackages = listOf("com.instagram.android")
        )
        val new = old.copy(excludedPackages = emptyList())

        assertTrue(RestrictionComparator.reelBlocker(old, new))
    }

    @Test
    fun allowingInstagramReelsFromDmInboxIsWeaker() {
        val old = ReelBlocker(isActive = true)

        assertFalse(
            RestrictionComparator.reelBlocker(
                old,
                old.copy(allowInstagramReelsFromDmInbox = true)
            )
        )
    }

    @Test
    fun disallowingInstagramReelsFromDmInboxIsStricter() {
        val old = ReelBlocker(
            isActive = true,
            allowInstagramReelsFromDmInbox = true
        )

        assertTrue(
            RestrictionComparator.reelBlocker(
                old,
                old.copy(allowInstagramReelsFromDmInbox = false)
            )
        )
    }

    @Test
    fun noCountAllowanceBlocksAllDayAndIsStrictest() {
        val blocked = ReelBlocker(isActive = true)
        val allowance = reels(ReelCountConfig(uniformLimit = 10))
        assertTrue(RestrictionComparator.reelBlocker(allowance, blocked))
        assertFalse(RestrictionComparator.reelBlocker(blocked, allowance))
        assertTrue(RestrictionComparator.reelBlocker(allowance, reels(ReelCountConfig(uniformLimit = 0))))
    }

    @Test
    fun smallerCountAllowanceIsStricter() {
        val ten = reels(ReelCountConfig(uniformLimit = 10))
        val five = reels(ReelCountConfig(uniformLimit = 5))
        assertTrue(RestrictionComparator.reelBlocker(ten, five))
        assertFalse(RestrictionComparator.reelBlocker(five, ten))
    }

    @Test
    fun dailyCountAllowancesCompareEachDay() {
        val uniform = reels(ReelCountConfig(uniformLimit = 10))
        val daily = reels(ReelCountConfig(isDailyUniform = false, dailyLimits = IntArray(7) { if (it == 0) 20 else 5 }))
        assertFalse(RestrictionComparator.reelBlocker(uniform, daily))
        assertTrue(RestrictionComparator.reelBlocker(daily, reels(ReelCountConfig(isDailyUniform = false, dailyLimits = IntArray(7) { 5 }))))
    }

    private fun reels(count: ReelCountConfig) = ReelBlocker(
        isActive = true,
        config = ReelBlockerConfig(reelCount = count)
    )
}
