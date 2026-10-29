package neth.iecal.curbox.ui.activity

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.Settings
import neth.iecal.curbox.utils.DataStoreManager
import neth.iecal.curbox.utils.PaymentPause
import neth.iecal.curbox.utils.SettingsChangeDelayUtils

/**
 * Opened from the payment pause widget. Lists the payment apps that can be paused for and
 * starts the pause for the one picked. Sends the user to the Payment pause screen when setup
 * is missing.
 */
class PaymentPauseChooserActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            val settings = DataStoreManager(applicationContext).settings.first()
            PaymentPause.endIfDue(this@PaymentPauseChooserActivity)
            when {
                PaymentPause.isActive(this@PaymentPauseChooserActivity) -> showRunningPause()
                !PaymentPause.canPause(this@PaymentPauseChooserActivity) -> openSetup()
                else -> {
                    val blocked = PaymentPause.watchedPackages(this@PaymentPauseChooserActivity, settings)
                    val apps = settings.paymentPauseApps.filter { it !in blocked }
                    if (apps.isEmpty()) openSetup() else showChooser(apps, settings)
                }
            }
        }
    }

    private fun showChooser(apps: List<String>, settings: Settings) {
        val labels = apps.map { pkg ->
            runCatching {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
            }.getOrDefault(pkg)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.payment_pause_widget_choose)
            .setItems(labels.toTypedArray()) { _, which ->
                if (!PaymentPause.start(applicationContext, apps[which], settings)) {
                    Toast.makeText(this, R.string.payment_pause_failed, Toast.LENGTH_LONG).show()
                }
                finish()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun showRunningPause() {
        val remaining = SettingsChangeDelayUtils.formatRemaining(
            this,
            PaymentPause.endsAt(this) - System.currentTimeMillis()
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.payment_pause_title)
            .setMessage(getString(R.string.payment_pause_active, remaining))
            .setPositiveButton(R.string.payment_pause_end_now) { _, _ ->
                PaymentPause.end(applicationContext)
                finish()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun openSetup() {
        startActivity(
            Intent(this, FragmentActivity::class.java)
                .putExtra("fragment", PaymentPause.PAGE_FRAGMENT_ID)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
    }
}
