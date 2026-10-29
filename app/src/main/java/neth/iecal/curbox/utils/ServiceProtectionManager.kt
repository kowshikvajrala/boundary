package neth.iecal.curbox.utils

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import kotlinx.coroutines.flow.first
import neth.iecal.curbox.BuildConfig
import neth.iecal.curbox.receivers.AdminReceiver
import neth.iecal.curbox.services.AppBlockerService

/**
 * The brain behind the Service Protection screen and the watchdog. It checks how well protected the
 * service is and, with the adb granted secure settings permission, turns the accessibility service
 * back on when an OEM battery manager or a system update switched it off.
 *
 * Nothing here can make the app truly un killable. A force stop still wins. The goal is only to make
 * the service hard to kill and fast to bring back.
 */
object ServiceProtectionManager {

    private const val TAG = "ServiceProtection"

    fun isAppBlockerEnabled(context: Context): Boolean =
        PermissionUtils.isAccessibilityServiceEnabled(context, AppBlockerService::class.java)

    /** The adb granted secure settings permission is what lets Boundary turn its own service back on. */
    fun canSelfHeal(context: Context): Boolean = PermissionUtils.hasWriteSecureSettings(context)

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Intent that lets the user drop battery optimization for the app. When we hold the
     * REQUEST_IGNORE_BATTERY_OPTIMIZATIONS permission (full and fdroid flavors) we can show the one
     * tap allow dialog. The playstore flavor ships without that permission, so we open the battery
     * optimization list instead, which needs no permission.
     */
    fun ignoreBatteryOptimizationsIntent(context: Context): Intent =
        if (canRequestIgnoreBatteryOptimizations(context)) {
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}")
            )
        } else {
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        }

    /** True only when the permission is declared in the merged manifest (install time grant). */
    private fun canRequestIgnoreBatteryOptimizations(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Turns the accessibility service back on if it is off, unless a payment pause switched it
     * off on purpose. Safe to call from anywhere.
     */
    fun healNow(context: Context) {
        try {
            if (!canSelfHeal(context) || PaymentPause.isActive(context)) return
            if (!isAppBlockerEnabled(context)) {
                PaymentPause.setServiceEnabled(context, true)
                Log.i(TAG, "Re enabled the accessibility service")
            }
        } catch (e: Exception) {
            Log.e(TAG, "healNow failed", e)
        }
    }

    fun isDeviceOwner(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isDeviceOwnerApp(context.packageName)
    }

    /** Returns false when tamper protection still requires the device admin. */
    @Suppress("DEPRECATION")
    suspend fun removeDeviceAdmin(context: Context): Boolean {
        if (!BuildConfig.SUPPORTS_ANTI_UNINSTALL) return false
        if (DataStoreManager(context).settings.first().antiUninstallConfig2.isEnabled) return false

        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (dpm.isDeviceOwnerApp(context.packageName)) {
            dpm.clearDeviceOwnerApp(context.packageName)
        } else {
            AntiUninstallManager.removeProtection(context)
        }
        return true
    }
}
