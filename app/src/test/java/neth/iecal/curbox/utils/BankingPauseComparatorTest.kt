package neth.iecal.curbox.utils

import neth.iecal.curbox.data.models.BankingPauseConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BankingPauseComparatorTest {

    private val bank = "com.example.bank"
    private val otherBank = "com.example.otherbank"

    @Test
    fun turningOnOrAddingAppsWaitsForTheDelay() {
        assertFalse(RestrictionComparator.bankingPause(BankingPauseConfig(), BankingPauseConfig(true, listOf(bank))))
        assertFalse(
            RestrictionComparator.bankingPause(
                BankingPauseConfig(true, listOf(bank)),
                BankingPauseConfig(true, listOf(bank, otherBank))
            )
        )
    }

    @Test
    fun turningOffOrRemovingAppsAppliesRightAway() {
        assertTrue(RestrictionComparator.bankingPause(BankingPauseConfig(true, listOf(bank)), BankingPauseConfig(false, listOf(bank))))
        assertTrue(
            RestrictionComparator.bankingPause(
                BankingPauseConfig(true, listOf(bank, otherBank)),
                BankingPauseConfig(true, listOf(bank))
            )
        )
    }
}
