package neth.iecal.curbox.data.models

/** Where the owner lives. Weakening a lock after unlock is only allowed away from here. */
data class HomeLocation(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val radiusMeters: Int = DEFAULT_RADIUS_METERS,
    val isSet: Boolean = false
) {
    companion object {
        const val DEFAULT_RADIUS_METERS = 150
    }
}
