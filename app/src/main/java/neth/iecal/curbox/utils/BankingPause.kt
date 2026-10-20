package neth.iecal.curbox.utils

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import neth.iecal.curbox.services.AppBlockerService

/**
 * Turns the blocker's accessibility service off while a banking app is open and back on after.
 *
 * Turning it back on needs WRITE_SECURE_SETTINGS, granted once over adb:
 * `adb shell pm grant <package> android.permission.WRITE_SECURE_SETTINGS`.
 * Without it the service can still turn itself off, and the user turns it back on by hand.
 */
object BankingPause {

    private const val TAG = "BankingPause"
    private const val PREFS = "banking_pause"
    private const val KEY_PAUSED_UNTIL = "paused_until"

    /** The longest a single pause may last before blocking comes back regardless. */
    const val MAX_PAUSE_MS = 60 * 60_000L

    fun adbGrantCommand(context: Context): String =
        "adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"

    fun canTurnServiceBackOn(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    fun isServiceEnabled(context: Context): Boolean =
        PermissionUtils.isAccessibilityServiceEnabled(context, AppBlockerService::class.java)

    /** Adds or removes the blocker service from the enabled list. Returns false without permission. */
    fun setServiceEnabled(context: Context, enabled: Boolean): Boolean {
        if (!canTurnServiceBackOn(context)) return false
        return try {
            val component = ComponentName(context, AppBlockerService::class.java).flattenToString()
            val resolver = context.contentResolver
            val services = linkedSetOf<String>()
            Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?.takeIf { it.isNotEmpty() }
                ?.let { current ->
                    val splitter = TextUtils.SimpleStringSplitter(':')
                    splitter.setString(current)
                    while (splitter.hasNext()) services.add(splitter.next())
                }
            if (enabled) services.add(component) else services.removeAll { it.equals(component, ignoreCase = true) }

            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, services.joinToString(":"))
            if (enabled) Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Could not change accessibility services", e)
            false
        }
    }

    // The pause state lives in the main process, where the watcher and the service watchdog run.

    fun markPaused(context: Context, untilMs: Long) {
        prefs(context).edit().putLong(KEY_PAUSED_UNTIL, untilMs).apply()
    }

    fun clearPaused(context: Context) {
        prefs(context).edit().remove(KEY_PAUSED_UNTIL).apply()
    }

    /** True while a banking pause is running, so the watchdog must not turn the service back on. */
    fun isPaused(context: Context): Boolean =
        prefs(context).getLong(KEY_PAUSED_UNTIL, 0L) > System.currentTimeMillis()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
