package neth.iecal.curbox.ui.activity

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlin.math.sqrt

/**
 * Counts steps walked since [start]. Uses the step detector (one event per step, quick to show)
 * and falls back to the step counter (a running total since boot) on phones without one.
 *
 * Shaking the phone also looks like steps to these sensors. The screen is open while counting,
 * so the phone is in hand: a step counts only at a walking pace and while the phone is not
 * jolted or twisted hard, which shaking always does. [onShakeIgnored] is told when one was dropped.
 */
internal class StepCounting(
    context: Context,
    private val onSteps: (Int) -> Unit,
    private val onShakeIgnored: () -> Unit = {}
) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager?
    private val detector = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
    private val counter = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    private val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private var steps = 0
    private var counterLast = -1f
    private var lastStepNs = 0L
    private var lastRoughMotionNs = Long.MIN_VALUE / 2
    private var isRunning = false

    /** Starts counting from zero. Returns false when there is no sensor to listen to. */
    fun start(): Boolean {
        stop()
        steps = 0
        counterLast = -1f
        lastStepNs = 0L
        lastRoughMotionNs = Long.MIN_VALUE / 2
        val manager = sensorManager ?: return false
        val sensor = detector ?: counter ?: return false
        isRunning = manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        if (!isRunning) return false
        accelerometer?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gyroscope?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        onSteps(0)
        return true
    }

    fun stop() {
        if (isRunning) sensorManager?.unregisterListener(this)
        isRunning = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER ->
                if (magnitude(event) > MAX_WALKING_ACCELERATION) lastRoughMotionNs = event.timestamp
            Sensor.TYPE_GYROSCOPE ->
                if (magnitude(event) > MAX_WALKING_ROTATION) lastRoughMotionNs = event.timestamp
            Sensor.TYPE_STEP_DETECTOR -> {
                if (!isWalkingStep(event.timestamp)) return
                steps += 1
                lastStepNs = event.timestamp
                onSteps(steps)
            }
            Sensor.TYPE_STEP_COUNTER -> {
                val total = event.values.firstOrNull() ?: return
                // The first report is the total since boot, which becomes the starting point.
                val added = if (counterLast < 0f) 0 else (total - counterLast).toInt().coerceAtLeast(0)
                counterLast = total
                if (added == 0) return
                // The counter reports in batches, so a batch with any shaking in it is dropped whole.
                if (isShaking(event.timestamp)) {
                    onShakeIgnored()
                    return
                }
                steps += added
                onSteps(steps)
            }
        }
    }

    private fun isWalkingStep(timestampNs: Long): Boolean {
        if (isShaking(timestampNs)) {
            onShakeIgnored()
            return false
        }
        // Faster than anyone walks or jogs.
        if (lastStepNs != 0L && timestampNs - lastStepNs < MIN_STEP_GAP_NS) {
            onShakeIgnored()
            return false
        }
        return true
    }

    private fun isShaking(timestampNs: Long): Boolean =
        timestampNs - lastRoughMotionNs < SHAKE_WINDOW_NS

    private fun magnitude(event: SensorEvent): Float {
        val (x, y, z) = Triple(event.values[0], event.values[1], event.values[2])
        return sqrt(x * x + y * y + z * z)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        // About 2.2 g including gravity. Walking with the phone in hand stays well below it.
        private const val MAX_WALKING_ACCELERATION = 22f
        // Radians per second. Walking while looking at the phone turns it slowly.
        private const val MAX_WALKING_ROTATION = 3.5f
        // Steps within this long after rough motion do not count.
        private const val SHAKE_WINDOW_NS = 1_000_000_000L
        // At most about 3.5 steps a second.
        private const val MIN_STEP_GAP_NS = 280_000_000L

        fun isAvailable(context: Context): Boolean {
            val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager?
                ?: return false
            return manager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) != null ||
                manager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null
        }

        fun hasPermission(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
            return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACTIVITY_RECOGNITION
            ) == PackageManager.PERMISSION_GRANTED
        }
    }
}
