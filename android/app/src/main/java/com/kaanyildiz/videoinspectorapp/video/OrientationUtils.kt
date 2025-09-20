// app/src/main/java/com/kaanyildiz/videoinspectorapp/video/OrientationUtils.kt
package com.kaanyildiz.videoinspectorapp.video

import android.view.Surface
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector

/**
 * (Sadece MediaRecorder kullanan senaryoda) orientationHint üretir.
 * Şu an VideoCapture/Recorder kullanıyorsun; burada dursun, ileride MediaRecorder'a geçersen kullanırsın.
 */
fun orientationHintForMediaRecorder(
    cameraInfo: CameraInfo,
    displayRotation: Int,
    lensFacing: Int
): Int {
    val degrees = when (displayRotation) {
        Surface.ROTATION_0   -> 0
        Surface.ROTATION_90  -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }
    val sensorRotation = cameraInfo.sensorRotationDegrees
    // Ön kamera için ayna telafisi
    val sign = if (lensFacing == CameraSelector.LENS_FACING_FRONT) -1 else 1
    val result = (sensorRotation - sign * degrees + 360) % 360

    val snaps = intArrayOf(0, 90, 180, 270)
    return snaps.minBy { k -> kotlin.math.abs(k - result) }
}
