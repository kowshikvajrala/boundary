package neth.iecal.curbox.ui.activity

import android.app.Activity
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import neth.iecal.curbox.utils.DataStoreManager
import neth.iecal.curbox.utils.PaymentPause

/**
 * Invisible screen the blocker opens when a payment app is launched. It starts the pause from
 * the main process, where the pause's timer and safety nets live, then closes at once.
 */
class PaymentPauseStartActivity : Activity() {
    private val scope = MainScope()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val packageName = intent.getStringExtra(EXTRA_PACKAGE)
        if (packageName == null || PaymentPause.isActive(this)) {
            finish()
            return
        }
        scope.launch {
            try {
                val settings = DataStoreManager(applicationContext).settings.first()
                PaymentPause.start(applicationContext, packageName, settings)
            } catch (e: Exception) {
                Log.e("PaymentPauseStart", "could not start the pause", e)
            } finally {
                finish()
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PACKAGE = "package"
    }
}
