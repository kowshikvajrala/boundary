package neth.iecal.curbox.ui.activity

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import neth.iecal.curbox.R
import neth.iecal.curbox.services.BankingPauseService
import neth.iecal.curbox.services.BankingPauseTrigger
import neth.iecal.curbox.utils.BankingPause
import neth.iecal.curbox.utils.DataStoreManager

/**
 * Opened by a banking shortcut. Turns the blocker service off first and only then opens the
 * banking app, so apps that check for accessibility services the moment they start see none.
 */
class BankingLaunchActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PACKAGE = "banking_package"
        private const val STOP_WAIT_MS = 2_000L
        private const val STOP_POLL_MS = 100L

        fun intentFor(context: Context, bankingPackage: String): Intent =
            Intent(context, BankingLaunchActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .putExtra(EXTRA_PACKAGE, bankingPackage)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bankingPackage = intent.getStringExtra(EXTRA_PACKAGE)
        lifecycleScope.launch {
            val config = DataStoreManager(this@BankingLaunchActivity).settings.first().bankingPauseConfig
            val launchIntent = bankingPackage?.let { packageManager.getLaunchIntentForPackage(it) }
            if (!config.isEnabled || bankingPackage !in config.packages || launchIntent == null) {
                Toast.makeText(this@BankingLaunchActivity, R.string.banking_pause_shortcut_invalid, Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }

            BankingPauseService.start(this@BankingLaunchActivity, bankingPackage!!)
            if (isBlockerRunning()) {
                if (!BankingPause.setServiceEnabled(this@BankingLaunchActivity, false)) {
                    sendBroadcast(Intent(BankingPauseTrigger.ACTION_PAUSE_NOW).setPackage(packageName))
                }
                var waited = 0L
                while (isBlockerRunning() && waited < STOP_WAIT_MS) {
                    delay(STOP_POLL_MS)
                    waited += STOP_POLL_MS
                }
            }

            startActivity(launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
        }
    }

    private fun isBlockerRunning(): Boolean {
        val manager = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        return manager.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == packageName }
    }
}
