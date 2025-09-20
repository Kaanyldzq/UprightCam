// app/src/main/java/com/kaanyildiz/videoinspectorapp/ui/HorizonPreviewTransformer.kt
package com.kaanyildiz.videoinspectorapp.ui

import android.view.View
import android.view.ViewTreeObserver
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Sensör roll açısına karşı açı uygulayıp preview'ü döndürür.
 * Ayrıca taşmayı önlemek için gereken auto-scale'i hesaplayıp onScale callback'ine gönderir.
 *
 * Not: Döndürme = BU sınıf
 *      Kullanıcı pinch-zoom'u = Dışarıda (userScale)
 *      Nihai ölçek = userScale * autoScale
 */
class HorizonPreviewTransformer(
    private val container: View,
    private val frontCamera: () -> Boolean = { false },   // ön kamera kullanıyorsan true dön
    private val onScale: (Float) -> Unit = {},            // hesaplanan auto-scale'i iletir
    private val smoothingAlpha: Float = 0.12f,            // ÖNERİ: 0.10–0.15
    private val deadBandDeg: Float = 0.5f                 // ÖNERİ: 0.5°
) {
    private var w = 0
    private var h = 0
    private var ready = false

    // Low-pass için iç durum
    private var hasSample = false
    private var filteredRollDeg = 0f

    init {
        container.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                container.viewTreeObserver.removeOnGlobalLayoutListener(this)
                w = container.width
                h = container.height
                ready = w > 0 && h > 0
            }
        })
    }

    /**
     * Sensörden gelen roll açısını derece cinsinden ver.
     * Low-pass + dead band uygular, sonra karşı açıyla preview'ü döndürür
     * ve çerçeve taşmasını engellemek için auto-scale hesaplayıp bildirir.
     */
    fun apply(rollDeg: Float) {
        if (!ready) return

        // İlk örnekte filtreden direkt geçir
        if (!hasSample) {
            filteredRollDeg = rollDeg
            hasSample = true
        } else {
            val delta = rollDeg - filteredRollDeg
            // Dead band: küçük değişimleri yok say
            if (abs(delta) >= deadBandDeg) {
                // Low-pass: out = out + alpha * (in - out)
                filteredRollDeg += smoothingAlpha * delta
            }
            // Aksi halde filteredRollDeg aynı kalır
        }

        // Ön kamerada kullanıcı algısına göre işareti değiştir
        val counter = if (frontCamera()) filteredRollDeg else -filteredRollDeg

        // 1) Karşı açıyla döndür
        container.rotation = counter

        // 2) Dönen dikdörtgeni sığdıracak auto-scale'i hesapla
        // s >= max( w / (w|cosθ| + h|sinθ|), h / (w|sinθ| + h|cosθ|) )
        val theta = Math.toRadians(counter.toDouble())
        val c = abs(cos(theta))
        val s = abs(sin(theta))

        val W = w.toDouble()
        val H = h.toDouble()

        val scaleW = W / (W * c + H * s)
        val scaleH = H / (W * s + H * c)
        val needed = 1.0 / min(scaleW, scaleH)  // en küçük scale'in tersi
        val clamp = max(1.0, min(needed, 1.50)) // öneri: 1.35–1.50 arası sınırla

        // 3) Auto-scale'i dışarı bildir (kullanıcı pinch-zoom'u ile çarpılacak)
        onScale(clamp.toFloat())
    }
}
