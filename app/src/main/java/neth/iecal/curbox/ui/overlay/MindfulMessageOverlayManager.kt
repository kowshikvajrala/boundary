package neth.iecal.curbox.ui.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.TextView
import kotlinx.coroutines.*
import neth.iecal.curbox.CrashLogger
import neth.iecal.curbox.R
import neth.iecal.curbox.anti_stimulants.MindfulMessageVariableValues
import neth.iecal.curbox.anti_stimulants.MindfulMessageVariables
import neth.iecal.curbox.data.db.AppDatabase
import neth.iecal.curbox.data.models.AppGroup
import neth.iecal.curbox.data.models.MindfulMessageConfig
import neth.iecal.curbox.utils.TimeTools
import neth.iecal.curbox.utils.UsageStatsHelper

class MindfulMessageOverlayManager(private val context: Context) {

    private var overlayView: View? = null
    var isOverlayVisible = false
    private var windowManager: WindowManager? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var updateJob: Job? = null

    private var sessionStartTime = 0L
    private var textView: TextView? = null

    private val usageHelper = UsageStatsHelper(context)
    private val database = AppDatabase.getInstance(context.applicationContext)

    @SuppressLint("InflateParams")
    fun startDisplaying(
        pkgName: String,
        config: MindfulMessageConfig,
        appGroups: List<AppGroup>
    ) {
        if (!isOverlayVisible || overlayView == null) {
            setupView(config)
            startTicker(pkgName, config, appGroups)
        }
    }

    private fun setupView(config: MindfulMessageConfig) {
        sessionStartTime = System.currentTimeMillis()
        overlayView = LayoutInflater.from(context).inflate(R.layout.mindfulmsg_overlay, null)
        textView = overlayView?.findViewById<TextView>(R.id.mindful_txt)

        val r = (config.bgColor shr 16) and 0xFF
        val g = (config.bgColor shr 8) and 0xFF
        val b = config.bgColor and 0xFF
        val alpha = (config.bgOpacity * 255 / 100)

        textView?.apply {
            textSize = config.textSize
            setTextColor(Color.argb(
                config.textOpacity * 255 / 100,
                (config.textColor shr 16) and 0xFF,
                (config.textColor shr 8) and 0xFF,
                config.textColor and 0xFF
            ))
            setBackgroundColor(Color.argb(alpha, r, g, b))
            setPadding(32, 32, 32, 32)
        }

        val dm = context.resources.displayMetrics
        val screenWidth = dm.widthPixels
        val screenHeight = dm.heightPixels

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenWidth * config.positionX).toInt()
            y = (screenHeight * config.positionY).toInt()
        }
        layoutParams = params

        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager?.addView(overlayView, params)
        isOverlayVisible = true

        overlayView?.viewTreeObserver?.addOnGlobalLayoutListener(object :
            ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                overlayView?.viewTreeObserver?.removeOnGlobalLayoutListener(this)
                val vw = overlayView?.width ?: 0
                val vh = overlayView?.height ?: 0
                params.x = ((screenWidth * config.positionX) - vw / 2f)
                    .toInt().coerceIn(0, (screenWidth - vw).coerceAtLeast(0))
                params.y = ((screenHeight * config.positionY) - vh / 2f)
                    .toInt().coerceIn(0, (screenHeight - vh).coerceAtLeast(0))
                try {
                    windowManager?.updateViewLayout(overlayView, params)
                } catch (_: Exception) {}
            }
        })
    }

    private fun startTicker(
        pkgName: String,
        config: MindfulMessageConfig,
        appGroups: List<AppGroup>
    ) {
        updateJob?.cancel()
        val appName = runCatching {
            val appInfo = context.packageManager.getApplicationInfo(pkgName, 0)
            context.packageManager.getApplicationLabel(appInfo).toString()
        }.getOrDefault(pkgName)
        updateJob = scope.launch {
            try {
                while (isActive) {
                    val formatted = withContext(Dispatchers.IO) {
                        val todayStats =
                            if (MindfulMessageVariables.needsTodayUsage(config.messages)) {
                                usageHelper.getForegroundStatsByRelativeDay(0)
                            } else {
                                emptyList()
                            }
                        val yesterdayStats =
                            if (MindfulMessageVariables.needsYesterdayUsage(config.messages)) {
                                usageHelper.getForegroundStatsByRelativeDay(1)
                            } else {
                                emptyList()
                            }
                        val reelCount = if (MindfulMessageVariables.needsReelCount(config.messages)) {
                            database.reelStatsDao().getCount(TimeTools.getCurrentDate()) ?: 0
                        } else {
                            0
                        }
                        val todayByPackage =
                            todayStats.associate { it.packageName to it.totalTime }
                        val yesterdayByPackage =
                            yesterdayStats.associate { it.packageName to it.totalTime }
                        val appStat = todayStats.find { it.packageName == pkgName }

                        MindfulMessageVariables.format(
                            template = config.messages,
                            values = MindfulMessageVariableValues(
                                appName = appName,
                                appUsageTodayMs = appStat?.totalTime ?: 0L,
                                appUsageYesterdayMs = yesterdayByPackage[pkgName] ?: 0L,
                                appOpensToday = appStat?.sessions ?: 0,
                                screenTimeTodayMs = todayStats.sumOf { it.totalTime },
                                screenTimeYesterdayMs = yesterdayStats.sumOf { it.totalTime },
                                liveSessionMs = System.currentTimeMillis() - sessionStartTime,
                                reelCount = reelCount,
                                todayUsageByPackage = todayByPackage,
                                yesterdayUsageByPackage = yesterdayByPackage
                            ),
                            mindfulApps = config.selectedApps,
                            appGroups = appGroups
                        )
                    }

                    textView?.text = formatted

                    delay(1000)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CrashLogger(context).logNonFatalError(e)
            }
        }
    }

    fun removeOverlay() {
        updateJob?.cancel()
        if (overlayView != null && windowManager != null) {
            try {
                windowManager?.removeView(overlayView)
            } catch (_: Exception) {}
            overlayView = null
            isOverlayVisible = false
        }
    }
}
