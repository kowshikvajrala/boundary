package neth.iecal.curbox.data.models

/**
 * Apps that refuse to run while an accessibility service is on, usually banking apps.
 * While one of them is open, the blocker service turns itself off and comes back afterwards.
 */
data class BankingPauseConfig(
    val isEnabled: Boolean = false,
    val packages: List<String> = emptyList()
)
