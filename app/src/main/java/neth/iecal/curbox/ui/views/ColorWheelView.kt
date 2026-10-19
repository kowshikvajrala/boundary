package neth.iecal.curbox.ui.views

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

class ColorWheelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 3f
        color = Color.WHITE
        setShadowLayer(resources.displayMetrics.density * 2f, 0f, 0f, Color.BLACK)
    }

    private var wheelBitmap: Bitmap? = null
    private var centerX = 0f
    private var centerY = 0f
    private var radius = 0f
    private var hue = 0f
    private var saturation = 0f
    private var brightness = 1f

    var selectedColor: Int = Color.WHITE
        private set

    var onColorChanged: ((Int) -> Unit)? = null

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        centerX = width / 2f
        centerY = height / 2f
        radius = min(
            width - paddingLeft - paddingRight,
            height - paddingTop - paddingBottom
        ) / 2f
        buildWheelBitmap(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        wheelBitmap?.let { canvas.drawBitmap(it, 0f, 0f, bitmapPaint) }

        if (brightness < 1f) {
            shadePaint.alpha = ((1f - brightness) * 255).toInt()
            canvas.drawCircle(centerX, centerY, radius, shadePaint)
        }

        val markerRadius = resources.displayMetrics.density * 7f
        val angle = Math.toRadians(hue.toDouble())
        val markerX = centerX + cos(angle).toFloat() * saturation * radius
        val markerY = centerY + sin(angle).toFloat() * saturation * radius
        canvas.drawCircle(markerX, markerY, markerRadius, markerPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                updateFromTouch(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_UP -> {
                updateFromTouch(event.x, event.y)
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    fun setColor(color: Int, notify: Boolean = false) {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hue = hsv[0]
        saturation = hsv[1]
        brightness = hsv[2]
        updateSelectedColor(notify)
    }

    fun setBrightness(value: Float, notify: Boolean = true) {
        brightness = value.coerceIn(0f, 1f)
        updateSelectedColor(notify)
    }

    fun getBrightness(): Float = brightness

    private fun updateFromTouch(x: Float, y: Float) {
        if (radius <= 0f) return
        val dx = x - centerX
        val dy = y - centerY
        saturation = (hypot(dx.toDouble(), dy.toDouble()).toFloat() / radius).coerceIn(0f, 1f)
        hue = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat().let {
            if (it < 0f) it + 360f else it
        }
        updateSelectedColor(notify = true)
    }

    private fun updateSelectedColor(notify: Boolean) {
        selectedColor = Color.HSVToColor(floatArrayOf(hue, saturation, brightness)) and 0xFFFFFF
        invalidate()
        if (notify) onColorChanged?.invoke(selectedColor)
    }

    private fun buildWheelBitmap(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || radius <= 0f) return
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val dy = y - centerY
            for (x in 0 until width) {
                val dx = x - centerX
                val distance = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                if (distance <= radius) {
                    val pixelHue = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat().let {
                        if (it < 0f) it + 360f else it
                    }
                    pixels[y * width + x] = Color.HSVToColor(
                        floatArrayOf(pixelHue, distance / radius, 1f)
                    )
                }
            }
        }
        wheelBitmap?.recycle()
        wheelBitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    override fun onDetachedFromWindow() {
        wheelBitmap?.recycle()
        wheelBitmap = null
        super.onDetachedFromWindow()
    }
}
