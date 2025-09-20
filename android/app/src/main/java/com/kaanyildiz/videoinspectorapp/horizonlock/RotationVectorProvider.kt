// app/src/main/java/com/kaanyildiz/videoinspectorapp/horizonlock/RotationVectorProvider.kt
package com.kaanyildiz.videoinspectorapp.horizonlock

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.WindowManager
import java.lang.Math.toDegrees
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2

class RotationVectorProvider(
    context: Context,
    private val onOrientation: (rollDeg: Float, pitchDeg: Float, isUpsideDown: Boolean) -> Unit
) : SensorEventListener {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rv: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val grav: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GRAVITY)

    @Suppress("DEPRECATION")
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val R = FloatArray(9)
    private val Rremap = FloatArray(9)
    private val orientation = FloatArray(3)
    private var gVec: FloatArray? = null

    private var registered = false
    fun start() {
        if (registered) return
        rv?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        grav?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        registered = true
    }
    fun stop() {
        if (!registered) return
        sm.unregisterListener(this)
        registered = false
        inited = false
    }

    // --- unwrap + smoothing ---
    private var inited = false
    private var lastUnwrapped = 0.0
    private var smooth = 0.0
    private var alpha = 0.12
    fun setSmoothingAlpha(a: Double) { alpha = a.coerceIn(0.02, 0.3) }

    // --- upsideDown histerezis state ---
    private var lastUpside = false
    private val upOn  = 0.25f   // z>+0.25 -> upsideDown=ON
    private val upOff = -0.25f  // z<-0.25 -> upsideDown=OFF

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_GRAVITY -> { gVec = e.values.clone(); return }
            Sensor.TYPE_ROTATION_VECTOR -> {
                try { SensorManager.getRotationMatrixFromVector(R, e.values) } catch (_: Throwable) { return }

                @Suppress("DEPRECATION")
                val rot = wm.defaultDisplay.rotation
                val (xAxis, yAxis) = when (rot) {
                    Surface.ROTATION_0   -> SensorManager.AXIS_X to SensorManager.AXIS_Y
                    Surface.ROTATION_90  -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                    Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                    Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                    else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
                }
                SensorManager.remapCoordinateSystem(R, xAxis, yAxis, Rremap)

                SensorManager.getOrientation(Rremap, orientation)
                var pitch = orientation[1].toDouble()
                var roll  = orientation[2].toDouble()

                // Pitch ~90°'de GRAVITY ile roll'ü stabilize et
                if (abs(pitch) > Math.toRadians(80.0)) {
                    gVec?.let { gv ->
                        val gx = gv[0].toDouble()
                        val gy = gv[1].toDouble()
                        roll = atan2(gx, gy)
                    }
                }

                // unwrap + low-pass
                roll = normalizePi(roll)
                if (!inited) {
                    lastUnwrapped = roll
                    smooth = roll
                    inited = true
                } else {
                    var d = roll - lastUnwrapped
                    if (d > PI)  d -= 2 * PI
                    if (d < -PI) d += 2 * PI
                    lastUnwrapped += d
                    smooth += alpha * (lastUnwrapped - smooth)
                }

                val rollDeg  = toDegrees(smooth).toFloat()
                val pitchDeg = toDegrees(pitch).toFloat().coerceIn(-180f, 180f)

                // +Y ekseninin dünya Z bileşeni (Rremap[7]) ile sağlam upsideDown + histerezis
                val z = Rremap[7]
                lastUpside = if (lastUpside) {
                    if (z < upOff) false else true
                } else {
                    if (z > upOn) true else false
                }

                onOrientation(rollDeg, pitchDeg, lastUpside)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun normalizePi(a: Double): Double {
        var x = a
        while (x > PI)  x -= 2 * PI
        while (x < -PI) x += 2 * PI
        return x
    }
}
