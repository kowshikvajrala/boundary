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

/**
 * Counts steps walked since [start]. Uses the step detector (one event per step, quick to show)
 * and falls back to the step counter (a running total since boot) on phones without one.
 */
internal class StepCounting(context: Context, private val onSteps: (Int) -> Unit) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager?
    private val detector = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
    private val counter = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    private var steps = 0
    private var counterBaseline = -1f
    private var isRunning = false

    /** Starts counting from zero. Returns false when there is no sensor to listen to. */
    fun start(): Boolean {
        stop()
        steps = 0
        counterBaseline = -1f
        val sensor = detector ?: counter ?: return false
        isRunning = sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI) == true
        if (isRunning) onSteps(0)
        return isRunning
    }

    fun stop() {
        if (isRunning) sensorManager?.unregisterListener(this)
        isRunning = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_STEP_DETECTOR -> steps += 1
            Sensor.TYPE_STEP_COUNTER -> {
                val total = event.values.firstOrNull() ?: return
                // The first report is the total since boot, which becomes the starting point.
                if (counterBaseline < 0f) counterBaseline = total
                steps = (total - counterBaseline).toInt().coerceAtLeast(0)
            }
            else -> return
        }
        onSteps(steps)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
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
