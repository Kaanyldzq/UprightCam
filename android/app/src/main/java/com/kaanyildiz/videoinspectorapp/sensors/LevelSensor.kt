// app/src/main/java/com/kaanyildiz/videoinspectorapp/sensors/LevelSensor.kt
package com.kaanyildiz.videoinspectorapp.sensors

import android.content.Context
import android.hardware.*
import android.view.Surface
import android.view.WindowManager
import kotlin.math.abs

class LevelSensor(
    context: Context,
    private val smoothingAlpha: Float = 0.12f, // 0.08–0.2
    private val deadBandDeg: Float = 0.5f     // 0.3–1.0°
) : SensorEventListener {

    interface Listener { fun onRollChanged(rollDeg: Float) }

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val rotationVector: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val R = FloatArray(9)
    private val Rremap = FloatArray(9)
    private val orientation = FloatArray(3)

    private var lastFiltered: Float? = null
    var listener: Listener? = null

    fun start() {
        rotationVector?.also {
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sm.unregisterListener(this)
        lastFiltered = null
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return

        SensorManager.getRotationMatrixFromVector(R, event.values)

        // Ekran dönüşüne göre dünya eksenlerine remap
        val displayRotation = wm.defaultDisplay.rotation
        val (axisX, axisY) = when (displayRotation) {
            Surface.ROTATION_0   -> SensorManager.AXIS_X to SensorManager.AXIS_Y
            Surface.ROTATION_90  -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else                 -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }

        SensorManager.remapCoordinateSystem(R, axisX, axisY, Rremap)
        SensorManager.getOrientation(Rremap, orientation)

        val rollRad = orientation[2] // -π..π
        val rollDeg = Math.toDegrees(rollRad.toDouble()).toFloat()

        // EMA smoothing + dead-band
        val filtered = if (lastFiltered == null) {
            rollDeg
        } else {
            val prev = lastFiltered!!
            val delta = rollDeg - prev
            if (abs(delta) < deadBandDeg) prev else prev + smoothingAlpha * delta
        }

        lastFiltered = filtered
        listener?.onRollChanged(filtered)
    }
}
