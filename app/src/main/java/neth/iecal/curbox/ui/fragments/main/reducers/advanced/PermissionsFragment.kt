package neth.iecal.curbox.ui.fragments.main.reducers.advanced

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import neth.iecal.curbox.R
import neth.iecal.curbox.databinding.FragmentPermissionsBinding
import neth.iecal.curbox.databinding.ItemPermissionStatusBinding
import neth.iecal.curbox.services.AppBlockerService
import neth.iecal.curbox.ui.activity.FragmentActivity
import neth.iecal.curbox.utils.AntiUninstallManager
import neth.iecal.curbox.utils.PaymentPause
import neth.iecal.curbox.utils.PermissionUtils

/**
 * One place that shows every permission Boundary uses, whether it is on, and a button that
 * opens the right screen to fix it. Rechecked every time the screen comes back.
 */
class PermissionsFragment : Fragment() {

    companion object {
        const val FRAGMENT_ID = "permissions_status"
        // Settings.ACTION_MANAGE_APP_PROMOTED_NOTIFICATIONS (Android 16).
        private const val ACTION_MANAGE_APP_PROMOTED_NOTIFICATIONS =
            "android.settings.MANAGE_APP_PROMOTED_NOTIFICATIONS"
    }

    private var _binding: FragmentPermissionsBinding? = null
    private val binding get() = _binding!!

    private class Entry(
        val titleRes: Int,
        val descRes: Int,
        val isRequired: Boolean,
        val isOn: (Context) -> Boolean,
        val fix: (Context) -> Intent,
        val isAvailable: Boolean = true
    )

    private val entries = listOf(
        Entry(R.string.permissions_accessibility, R.string.permissions_accessibility_desc, true,
            { PermissionUtils.isAccessibilityServiceEnabled(it, AppBlockerService::class.java) },
            { Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS) }),
        Entry(R.string.permissions_overlay, R.string.permissions_overlay_desc, true,
            { PermissionUtils.hasOverlayPermission(it) },
            { Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri(it)) }),
        Entry(R.string.permissions_notifications, R.string.permissions_notifications_desc, true,
            { PermissionUtils.isNotificationPermissionGiven(it) },
            { Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, it.packageName) }),
        // Live Updates (Android 16): the unlock countdown as a chip in the status bar.
        Entry(R.string.permissions_live_updates, R.string.permissions_live_updates_desc, false,
            { canPostLiveUpdates(it) },
            { Intent(ACTION_MANAGE_APP_PROMOTED_NOTIFICATIONS).putExtra(Settings.EXTRA_APP_PACKAGE, it.packageName) },
            isAvailable = Build.VERSION.SDK_INT >= 36),
        Entry(R.string.permissions_battery, R.string.permissions_battery_desc, true,
            { (it.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(it.packageName) },
            { Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri(it)) }),
        Entry(R.string.permissions_device_admin, R.string.permissions_device_admin_desc, false,
            { AntiUninstallManager.isAdminActive(it) },
            { openPage(it, AntiUninstallFragment.FRAGMENT_ID) }),
        Entry(R.string.permissions_secure_settings, R.string.permissions_secure_settings_desc, false,
            { PermissionUtils.hasWriteSecureSettings(it) },
            { openPage(it, PaymentPause.PAGE_FRAGMENT_ID) }),
        Entry(R.string.permissions_usage_access, R.string.permissions_usage_access_desc, false,
            { PaymentPause.hasUsageAccess(it) },
            { Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS) }),
        Entry(R.string.permissions_notification_access, R.string.permissions_notification_access_desc, false,
            { NotificationManagerCompat.getEnabledListenerPackages(it).contains(it.packageName) },
            { Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) })
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPermissionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun render() {
        val context = requireContext()
        binding.permissionsContainer.removeAllViews()
        entries.filter { it.isAvailable }.forEach { entry ->
            val row = ItemPermissionStatusBinding.inflate(layoutInflater, binding.permissionsContainer, false)
            val isOn = runCatching { entry.isOn(context) }.getOrDefault(false)
            row.permissionTitle.text = getString(entry.titleRes)
            val need = getString(if (entry.isRequired) R.string.permissions_required else R.string.permissions_optional)
            row.permissionDesc.text = getString(R.string.permissions_row_desc, need, getString(entry.descRes))
            row.permissionStatusIcon.text = if (isOn) "✅" else if (entry.isRequired) "❌" else "⚪"
            row.permissionFix.isVisible = !isOn
            row.permissionFix.setOnClickListener {
                runCatching { startActivity(entry.fix(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            binding.permissionsContainer.addView(row.root)
        }
    }

    // NotificationManager.canPostPromotedNotifications() is Android 16 API; this app compiles
    // against Android 15, so it is called by name.
    private fun canPostLiveUpdates(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        return manager.javaClass.getMethod("canPostPromotedNotifications").invoke(manager) as Boolean
    }

    private fun packageUri(context: Context): Uri = Uri.parse("package:" + context.packageName)

    private fun openPage(context: Context, fragmentId: String): Intent =
        Intent(context, FragmentActivity::class.java).putExtra("fragment", fragmentId)
}
