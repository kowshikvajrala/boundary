package neth.iecal.curbox.blockers

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.core.os.CancellationSignal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.HomeLocation
import neth.iecal.curbox.utils.DataStoreManager
import neth.iecal.curbox.utils.HomeLocationChecker

/**
 * During Away mode, the moment the owner reaches for something Boundary would block (a blocked
 * app, short videos or a blocked site) is when it checks whether they are home again. At home,
 * Away mode ends and every blocker comes back. Shared by all blockers, so it reads at most one
 * location a minute; that read needs "Allow all the time" location access.
 */
object AwayModeHomeCheck {
    private const val CHECK_INTERVAL_MS = 60_000L

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var lastCheckMs = 0L

    fun checkIfBackHome(context: Context, home: HomeLocation) {
        if (!home.isSet) return
        val now = SystemClock.elapsedRealtime()
        synchronized(this) {
            if (lastCheckMs != 0L && now - lastCheckMs < CHECK_INTERVAL_MS) return
            lastCheckMs = now
        }
        val appContext = context.applicationContext
        Handler(Looper.getMainLooper()).post {
            try {
                HomeLocationChecker.currentLocation(appContext, CancellationSignal()) { result ->
                    if (result !is HomeLocationChecker.Result.Found) return@currentLocation
                    if (HomeLocationChecker.isAway(home, result.location)) return@currentLocation
                    scope.launch { DataStoreManager(appContext).endAwayMode() }
                    Toast.makeText(appContext, R.string.away_mode_ended_home, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e("AwayModeHomeCheck", "Home check failed", e)
            }
        }
    }
}
