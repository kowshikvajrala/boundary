package neth.iecal.curbox.data.models

data class ReelCounterOverlayConfig(
    val textSize: Float = 96f,
    val textColor: Int = 0xFFFFFF,
    val textOpacity: Int = 80,
    val bgColor: Int = 0x000000,
    val bgOpacity: Int = 0,
    val checkpointsEnabled: Boolean = false,
    val checkpointInterval: Int = 10,
    val positionX: Float = 0.5f,
    val positionY: Float = 0.3f
) {
    fun shouldShowAtCount(reelCount: Int): Boolean {
        if (!checkpointsEnabled) return true
        val interval = checkpointInterval.coerceIn(2, 100)
        return reelCount > 0 && reelCount % interval == 0
    }
}
