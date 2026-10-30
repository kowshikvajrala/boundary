package neth.iecal.curbox.data.models

data class ReelBlocker(
    val warningScreenConfig: AppBlockerWarningScreenConfig = AppBlockerWarningScreenConfig(),
    val config: ReelBlockerConfig? = null,
    val isActive: Boolean = false,
    val temporarilyDisabledUntilMs: Long = 0L,
    val excludedPackages: List<String> = emptyList(),
    val allowInstagramReelsFromDmInbox: Boolean = false
)

/** Short videos are blocked all day; [reelCount] is an optional daily allowance. */
data class ReelBlockerConfig(
    val reelCount: ReelCountConfig = ReelCountConfig(uniformLimit = 0)
)

data class ReelCountConfig(
    var isDailyUniform: Boolean = true,
    var uniformLimit: Int = 10,
    val dailyLimits: IntArray = IntArray(7) { 0 } // 0=Sunday
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ReelCountConfig

        if (isDailyUniform != other.isDailyUniform) return false
        if (uniformLimit != other.uniformLimit) return false
        if (!dailyLimits.contentEquals(other.dailyLimits)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = isDailyUniform.hashCode()
        result = 31 * result + uniformLimit.hashCode()
        result = 31 * result + dailyLimits.contentHashCode()
        return result
    }
}
