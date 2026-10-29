package neth.iecal.curbox.utils

import android.content.Context
import android.provider.Settings
import android.util.Log

/**
 * What is left of the removed grayscale feature: checking whether the screen is grayscale and
 * turning it off once, so an update never leaves the phone stuck in grayscale.
 */
class GrayscaleControl {
    private companion object {
        const val DALTONIZER_ENABLED = "accessibility_display_daltonizer_enabled"
    }

    /** Reading secure settings needs no permission. */
    fun isGrayscaleEnabled(context: Context): Boolean =
        Settings.Secure.getInt(context.contentResolver, DALTONIZER_ENABLED, 0) == 1

    /** Needs the adb granted WRITE_SECURE_SETTINGS; does nothing without it. */
    fun disableGrayscale(context: Context) {
        if (!PermissionUtils.hasWriteSecureSettings(context)) return
        try {
            Settings.Secure.putInt(context.contentResolver, DALTONIZER_ENABLED, 0)
        } catch (e: Exception) {
            Log.e("GrayscaleControl", "Could not turn grayscale off", e)
        }
    }
}
