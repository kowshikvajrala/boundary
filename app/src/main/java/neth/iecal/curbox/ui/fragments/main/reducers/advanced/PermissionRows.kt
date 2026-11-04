package neth.iecal.curbox.ui.fragments.main.reducers.advanced

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.nfc.NfcAdapter
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.widget.LinearLayout
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import neth.iecal.curbox.R
import neth.iecal.curbox.databinding.ItemPermissionStatusBinding
import neth.iecal.curbox.services.AppBlockerService
import neth.iecal.curbox.ui.activity.FragmentActivity
import neth.iecal.curbox.ui.activity.StepCounting
import neth.iecal.curbox.utils.AntiUninstallManager
import neth.iecal.curbox.utils.PaymentPause
import neth.iecal.curbox.utils.PermissionUtils

/**
 * Every permission Boundary uses, shared by the Permissions screen and the setup screen.
 * Add a row here whenever a feature needs a new permission.
 */
internal object PermissionRows {
    // Settings.ACTION_MANAGE_APP_PROMOTED_NOTIFICATIONS (Android 16).
    private const val ACTION_MANAGE_APP_PROMOTED_NOTIFICATIONS =
        "android.settings.MANAGE_APP_PROMOTED_NOTIFICATIONS"

    class Entry(
        val titleRes: Int,
        val descRes: Int,
        val isRequired: Boolean,
        val isOn: (Context) -> Boolean,
        val fix: (Context) -> Intent,
        val isAvailable: (Context) -> Boolean = { true },
        // A runtime permission is asked for with Android's own prompt instead of opening [fix].
        val runtimePermission: String? = null,
        // Opens one of Boundary's own screens, which only works once setup is done.
        val needsSetupDone: Boolean = false
    )

    val accessibility = Entry(R.string.permissions_accessibility, R.string.permissions_accessibility_desc, true,
        { PermissionUtils.isAccessibilityServiceEnabled(it, AppBlockerService::class.java) },
        { Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS) })
    val overlay = Entry(R.string.permissions_overlay, R.string.permissions_overlay_desc, true,
        { PermissionUtils.hasOverlayPermission(it) },
        { Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri(it)) })
    val notifications = Entry(R.string.permissions_notifications, R.string.permissions_notifications_desc, true,
        { PermissionUtils.isNotificationPermissionGiven(it) },
        { Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, it.packageName) })

    /** Rows that are not needed to finish setup. */
    val optional = listOf(
        // Live Updates (Android 16): the unlock countdown as a chip in the status bar.
        Entry(R.string.permissions_live_updates, R.string.permissions_live_updates_desc, false,
            { canPostLiveUpdates(it) },
            { Intent(ACTION_MANAGE_APP_PROMOTED_NOTIFICATIONS).putExtra(Settings.EXTRA_APP_PACKAGE, it.packageName) },
            isAvailable = { Build.VERSION.SDK_INT >= 36 }),
        Entry(R.string.permissions_battery, R.string.permissions_battery_desc, true,
            { (it.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(it.packageName) },
            { Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri(it)) }),
        Entry(R.string.permissions_device_admin, R.string.permissions_device_admin_desc, false,
            { AntiUninstallManager.isAdminActive(it) },
            { openPage(it, AntiUninstallFragment.FRAGMENT_ID) },
            needsSetupDone = true),
        Entry(R.string.permissions_secure_settings, R.string.permissions_secure_settings_desc, false,
            { PermissionUtils.hasWriteSecureSettings(it) },
            { openPage(it, PaymentPause.PAGE_FRAGMENT_ID) },
            needsSetupDone = true),
        Entry(R.string.permissions_usage_access, R.string.permissions_usage_access_desc, false,
            { PaymentPause.hasUsageAccess(it) },
            { Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS) }),
        Entry(R.string.permissions_notification_access, R.string.permissions_notification_access_desc, false,
            { NotificationManagerCompat.getEnabledListenerPackages(it).contains(it.packageName) },
            { Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }),
        Entry(R.string.permissions_physical_activity, R.string.permissions_physical_activity_desc, false,
            { StepCounting.hasPermission(it) },
            { appDetails(it) },
            isAvailable = { Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && StepCounting.isAvailable(it) },
            runtimePermission = Manifest.permission.ACTIVITY_RECOGNITION),
        Entry(R.string.permissions_camera, R.string.permissions_camera_desc, false,
            { ContextCompat.checkSelfPermission(it, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED },
            { appDetails(it) },
            runtimePermission = Manifest.permission.CAMERA),
        Entry(R.string.permissions_nfc, R.string.permissions_nfc_desc, false,
            { NfcAdapter.getDefaultAdapter(it)?.isEnabled == true },
            { Intent(Settings.ACTION_NFC_SETTINGS) },
            isAvailable = { NfcAdapter.getDefaultAdapter(it) != null })
    )

    val all = listOf(accessibility, overlay, notifications) + optional

    /**
     * Fills [container] with a row per available entry. [requestPermission] asks for a runtime
     * permission; Android only asks once or twice, after that the row opens the app info page.
     */
    fun render(
        context: Context,
        inflater: LayoutInflater,
        container: LinearLayout,
        entries: List<Entry>,
        requestPermission: (Entry) -> Unit
    ) {
        container.removeAllViews()
        entries.filter { runCatching { it.isAvailable(context) }.getOrDefault(false) }.forEach { entry ->
            val row = ItemPermissionStatusBinding.inflate(inflater, container, false)
            val isOn = runCatching { entry.isOn(context) }.getOrDefault(false)
            row.permissionTitle.text = context.getString(entry.titleRes)
            val need = context.getString(if (entry.isRequired) R.string.permissions_required else R.string.permissions_optional)
            row.permissionDesc.text = context.getString(R.string.permissions_row_desc, need, context.getString(entry.descRes))
            row.permissionStatusIcon.text = if (isOn) "✅" else if (entry.isRequired) "❌" else "⚪"
            row.permissionFix.isVisible = !isOn
            row.permissionFix.setOnClickListener {
                if (entry.runtimePermission != null) {
                    requestPermission(entry)
                } else {
                    runCatching { context.startActivity(entry.fix(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }
            container.addView(row.root)
        }
    }

    fun openFix(context: Context, entry: Entry) {
        runCatching { context.startActivity(entry.fix(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    // NotificationManager.canPostPromotedNotifications() is Android 16 API; this app compiles
    // against Android 15, so it is called by name.
    private fun canPostLiveUpdates(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        return manager.javaClass.getMethod("canPostPromotedNotifications").invoke(manager) as Boolean
    }

    private fun appDetails(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context))

    private fun packageUri(context: Context): Uri = Uri.parse("package:" + context.packageName)

    private fun openPage(context: Context, fragmentId: String): Intent =
        Intent(context, FragmentActivity::class.java).putExtra("fragment", fragmentId)
}
