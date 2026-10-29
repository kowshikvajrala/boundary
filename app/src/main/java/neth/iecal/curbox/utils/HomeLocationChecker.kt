package neth.iecal.curbox.utils

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import neth.iecal.curbox.data.models.HomeLocation

/**
 * Reads one fresh location while the app is open. Never runs in the background and never keeps
 * a history. Fake locations from mock location apps are rejected.
 */
object HomeLocationChecker {

    sealed class Result {
        data class Found(val location: Location) : Result()
        object NoPermission : Result()
        object LocationOff : Result()
        object NotFound : Result()
        object Fake : Result()
    }

    @SuppressLint("MissingPermission")
    fun currentLocation(context: Context, cancel: CancellationSignal, onResult: (Result) -> Unit) {
        if (!PermissionUtils.hasLocationPermission(context)) {
            onResult(Result.NoPermission)
            return
        }
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!LocationManagerCompat.isLocationEnabled(manager)) {
            onResult(Result.LocationOff)
            return
        }
        val provider = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                manager.hasProvider(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> LocationManager.NETWORK_PROVIDER
        }
        LocationManagerCompat.getCurrentLocation(
            manager,
            provider,
            cancel,
            ContextCompat.getMainExecutor(context)
        ) { location ->
            onResult(
                when {
                    location == null -> Result.NotFound
                    LocationCompat.isMock(location) -> Result.Fake
                    else -> Result.Found(location)
                }
            )
        }
    }

    /**
     * Away only when even the far edge of the reading's accuracy circle is outside home, so a
     * blurry reading taken at home never counts as away.
     */
    fun isAway(home: HomeLocation, location: Location): Boolean {
        val distance = FloatArray(1)
        Location.distanceBetween(home.latitude, home.longitude, location.latitude, location.longitude, distance)
        val accuracy = if (location.hasAccuracy()) location.accuracy else Float.MAX_VALUE
        return distance[0] - accuracy > home.radiusMeters
    }
}
