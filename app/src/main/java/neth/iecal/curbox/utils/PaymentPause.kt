package neth.iecal.curbox.utils

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.Settings as BoundarySettings
import neth.iecal.curbox.hardcoded.ReelAppConfig
import neth.iecal.curbox.hardcoded.URL_BAR_ID_LIST
import neth.iecal.curbox.services.AppBlockerService
import neth.iecal.curbox.services.PaymentPauseJob
import neth.iecal.curbox.services.PaymentPauseWatchService
import neth.iecal.curbox.ui.activity.FragmentActivity

/**
 * Switches Boundary's accessibility service off for a few minutes, so payment apps that refuse to
 * run next to an accessibility service work, then switches it back on by itself.
 *
 * Needs WRITE_SECURE_SETTINGS (granted once with adb). Without it a pause could not turn the
 * service back on, so no pause is offered at all.
 */
object PaymentPause {
    const val PAUSE_MS = 5 * 60_000L
    const val PAGE_FRAGMENT_ID = "payment_pause"

    private const val TAG = "PaymentPause"
    private const val PREFS = "payment_pause"
    private const val KEY_ENDS_AT = "ends_at"
    private const val CHANNEL_ID = "payment_pause"
    const val NOTIFICATION_ID = 7716
    private const val OPEN_APP_DELAY_MS = 700L

    /** Android's Settings, app installers and the phone makers' security apps that can stop apps. */
    private val SETTINGS_PACKAGES = setOf(
        "com.android.settings",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.samsung.android.lool",
        "com.samsung.android.sm.devicesecurity",
        "com.miui.securitycenter",
        "com.coloros.safecenter",
        "com.oplus.battery",
        "com.iqoo.secure",
        "com.huawei.systemmanager",
        "com.oneplus.security"
    )

    fun canPause(context: Context): Boolean = PermissionUtils.hasWriteSecureSettings(context)

    /** When the running pause ends, or 0 when no pause is running. */
    fun endsAt(context: Context): Long = prefs(context).getLong(KEY_ENDS_AT, 0L)

    fun isActive(context: Context): Boolean = endsAt(context) > 0L

    /**
     * Starts a pause and opens [packageName]. Returns false when the pause could not start,
     * including when [packageName] is itself blocked, so a blocked app can never be opened this way.
     */
    fun start(context: Context, packageName: String, settings: BoundarySettings): Boolean {
        if (!canPause(context)) return false
        if (packageName in watchedPackages(context, settings)) return false
        val endsAt = System.currentTimeMillis() + PAUSE_MS
        prefs(context).edit(commit = true) { putLong(KEY_ENDS_AT, endsAt) }
        // Schedule the way back before switching anything off.
        PaymentPauseJob.schedule(context, endsAt)
        if (!setServiceEnabled(context, false)) {
            end(context)
            return false
        }
        startWatching(context, endsAt)
        // Give Android a moment to really stop the service, so the app starts without seeing it.
        Handler(Looper.getMainLooper()).postDelayed({
            context.packageManager.getLaunchIntentForPackage(packageName)?.let {
                context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }, OPEN_APP_DELAY_MS)
        return true
    }

    /** Ends the pause once its time is up. Safe to call often. */
    fun endIfDue(context: Context) {
        val endsAt = endsAt(context)
        if (endsAt > 0L && System.currentTimeMillis() >= endsAt) end(context)
    }

    /** Ends the pause now and switches the blocker back on. */
    fun end(context: Context) {
        try {
            setServiceEnabled(context, true)
        } finally {
            prefs(context).edit(commit = true) { remove(KEY_ENDS_AT) }
            PaymentPauseJob.schedule(context, null)
            context.stopService(Intent(context, PaymentPauseWatchService::class.java))
            notificationManager(context).cancel(NOTIFICATION_ID)
        }
    }

    /** Switches Boundary's accessibility service on or off. Needs WRITE_SECURE_SETTINGS. */
    fun setServiceEnabled(context: Context, enabled: Boolean): Boolean = try {
        val resolver = context.contentResolver
        val ours = ComponentName(context, AppBlockerService::class.java).flattenToString()
        val services = linkedSetOf<String>()
        Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.takeIf { it.isNotEmpty() }
            ?.let { current ->
                val splitter = TextUtils.SimpleStringSplitter(':')
                splitter.setString(current)
                while (splitter.hasNext()) services.add(splitter.next())
            }
        services.removeAll { ComponentName.unflattenFromString(it)?.flattenToString() == ours }
        if (enabled) services.add(ours)
        Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, services.joinToString(":"))
        if (enabled) Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        true
    } catch (e: Exception) {
        Log.e(TAG, "Could not switch the blocker ${if (enabled) "on" else "off"}", e)
        false
    }

    /** Usage access lets the pause see which app is in front while the accessibility service is off. */
    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Apps that end a pause as soon as they open: apps in active app groups, short video apps the
     * Reels blocker covers, and every browser when website blocking is on (usage access cannot
     * tell which site is open). Settings and the app installer are always on it, so the pause
     * cannot be used to remove the device admin, force stop Boundary or uninstall it.
     * A blocked app stays on this list even if it was also picked as a payment app, so the
     * payment list cannot be used to open blocked apps.
     */
    fun watchedPackages(context: Context, settings: BoundarySettings): Set<String> {
        val packages = mutableSetOf<String>()
        settings.blockedAppGroups.filter { it.isActive }.forEach { group ->
            packages += group.selectedPackages.map(String::trim)
        }
        if (settings.reelBlockerConfig.isActive) {
            packages += ReelAppConfig.reelData.keys - settings.reelBlockerConfig.excludedPackages.toSet()
        }
        if (settings.keywordBlockerConfig.isActive) packages += browsers(context)
        packages += SETTINGS_PACKAGES
        packages -= context.packageName
        return packages
    }

    private fun browsers(context: Context): Set<String> {
        val viewWeb = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        val installed = runCatching {
            context.packageManager.queryIntentActivities(viewWeb, PackageManager.MATCH_ALL)
                .map { it.activityInfo.packageName }
        }.getOrDefault(emptyList())
        return installed.toSet() + URL_BAR_ID_LIST.keys
    }

    private fun startWatching(context: Context, endsAt: Long) {
        try {
            ContextCompat.startForegroundService(context, Intent(context, PaymentPauseWatchService::class.java))
        } catch (e: Exception) {
            // The job still ends the pause on time; only the early end is lost.
            Log.e(TAG, "could not start the pause watcher", e)
            runCatching { notificationManager(context).notify(NOTIFICATION_ID, buildNotification(context, endsAt)) }
        }
    }

    fun buildNotification(context: Context, endsAt: Long): Notification {
        val manager = notificationManager(context)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.payment_pause_title),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        val open = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            Intent(context, FragmentActivity::class.java)
                .putExtra("fragment", PAGE_FRAGMENT_ID)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif_payment)
            .setContentTitle(context.getString(R.string.payment_pause_notification_title))
            .setContentText(context.getString(R.string.payment_pause_notification_text))
            .setOngoing(true)
            .setContentIntent(open)
        return TimerNotificationLayout.apply(
            builder,
            context,
            label = context.getString(R.string.payment_pause_notification_title),
            endsAtMs = endsAt,
            detail = context.getString(R.string.payment_pause_notification_text)
        ).build()
    }

    private fun notificationManager(context: Context) =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
