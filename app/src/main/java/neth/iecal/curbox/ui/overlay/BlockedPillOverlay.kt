package neth.iecal.curbox.ui.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import neth.iecal.curbox.R

/**
 * A small pill at the top of the screen, shown instead of the full warning screen when a
 * group can never be unlocked. It ignores touches and hides itself after a moment.
 */
class BlockedPillOverlay(private val service: AccessibilityService) {

    private companion object {
        const val VISIBLE_MS = 2_500L
        const val ANIMATION_MS = 200L
    }

    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hide() }
    private var pillView: View? = null

    /** Safe to call from any thread. [packageName] supplies the icon; null hides it. */
    fun show(packageName: String?, message: String) {
        handler.post {
            try {
                removeNow()
                val view = LayoutInflater.from(service).inflate(R.layout.overlay_blocked_pill, null)
                val icon = packageName?.let {
                    runCatching { service.packageManager.getApplicationIcon(it) }.getOrNull()
                }
                view.findViewById<ImageView>(R.id.pill_icon).apply {
                    if (icon != null) setImageDrawable(icon) else visibility = View.GONE
                }
                view.findViewById<TextView>(R.id.pill_text).text = message

                val density = service.resources.displayMetrics.density
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = statusBarHeight() + (12 * density).toInt()
                }
                windowManager.addView(view, params)
                pillView = view

                view.alpha = 0f
                view.translationY = -16 * density
                view.animate().alpha(1f).translationY(0f).setDuration(ANIMATION_MS).start()
                handler.postDelayed(hideRunnable, VISIBLE_MS)
            } catch (e: Exception) {
                Log.e("BlockedPillOverlay", "Could not show blocked pill", e)
            }
        }
    }

    fun release() {
        handler.post { removeNow() }
    }

    private fun hide() {
        val view = pillView ?: return
        view.animate().alpha(0f).setDuration(ANIMATION_MS).withEndAction { remove(view) }.start()
    }

    private fun removeNow() {
        handler.removeCallbacks(hideRunnable)
        pillView?.let(::remove)
    }

    private fun remove(view: View) {
        runCatching { windowManager.removeView(view) }
        if (pillView === view) pillView = null
    }

    private fun statusBarHeight(): Int {
        val id = service.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) service.resources.getDimensionPixelSize(id) else 0
    }
}
