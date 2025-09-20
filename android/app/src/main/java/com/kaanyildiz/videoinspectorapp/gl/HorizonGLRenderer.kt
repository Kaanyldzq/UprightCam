// app/src/main/java/com/kaanyildiz/videoinspectorapp/gl/HorizonGLRenderer.kt
package com.kaanyildiz.videoinspectorapp.gl

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Size
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Preview ve encoder için ayrık parite (flip) + yumuşatma.
 * - Preview paritesi default 0° (flip yok) => telefonu ters tutarsan önizleme de ters görünür.
 * - Encoder paritesi roll’a göre 0°/180°; yumuşatma ile uygulanır => kayıt her zaman dik çıkar.
 */
class HorizonGLRenderer : GLSurfaceView.Renderer {

    // === Kamera girdisi (OES) ===
    private var cameraTexId = 0
    private var cameraSt: SurfaceTexture? = null
    private var cameraSurface: Surface? = null
    private var cameraSize: Size = Size(1080, 1920)

    // === Encoder hedefi ===
    private var encoderSurface: Surface? = null
    private var encoderEglSurface: android.opengl.EGLSurface? = null
    @Volatile private var recording = false

    // === GL/EGL ===
    private var eglDisplay: android.opengl.EGLDisplay? = null
    private var eglContext: android.opengl.EGLContext? = null
    private var windowSurface: android.opengl.EGLSurface? = null

    // === Boyut/Viewport ===
    private var srcW = 1920
    private var srcH = 1080
    private var encW = 1080
    private var encH = 1920
    private var prevW = 0
    private var prevH = 0
    private var fillCropEnabled = true

    // === Program / attribs ===
    private var prog = 0
    private var aPosLoc = 0
    private var aTexLoc = 0
    private var uMvpLoc = 0
    private var uTexMatLoc = 0

    // frame sinyali
    @Volatile private var frameAvailable = false

    // ROLL / ROTATE
    /** Fragment → setRoll(): POZİTİF derece; GL içinde -roll uygulanır. */
    @Volatile private var rollDeg = 0f

    /** Önizleme baz açısı (UI). */
    @Volatile private var previewFixedDeg = 0f

    /** Encoder baz açısı (kayıt boyunca). */
    @Volatile private var encoderFixedDeg = 0f

    // --- AYRI PARITE: preview & encoder
    @Volatile private var targetPreviewParityDeg: Float = 0f   // 0 veya 180
    @Volatile private var currentPreviewParityDeg: Float = 0f

    @Volatile private var targetEncoderParityDeg: Float = 0f   // 0 veya 180
    @Volatile private var currentEncoderParityDeg: Float = 0f

    private var lastFrameNs: Long = 0L
    private val paritySpeedDegPerSec = 1200f // ~180° ≈ 150 ms

    // Fullscreen quad
    private val VERT = floatArrayOf(-1f,-1f,  1f,-1f,  -1f,1f,  1f,1f)
    private val TEX  = floatArrayOf( 0f, 0f,  1f, 0f,   0f,1f,  1f,1f)
    private val vb: FloatBuffer = ByteBuffer.allocateDirect(VERT.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(VERT).position(0) }
    private val tb: FloatBuffer = ByteBuffer.allocateDirect(TEX.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(TEX).position(0) }

    // Matrisler
    private val stMatrix = FloatArray(16)
    private val mvp = FloatArray(16)
    private val rot = FloatArray(16)
    private val scl = FloatArray(16)
    private val tmp = FloatArray(16)

    // === Public API ===
    fun createOrUpdateCameraSurface(requestSize: Size): Surface {
        cameraSize = requestSize
        srcW = cameraSize.width
        srcH = cameraSize.height

        if (cameraSt == null) {
            cameraTexId = createOesTex()
            cameraSt = SurfaceTexture(cameraTexId).apply {
                setDefaultBufferSize(cameraSize.width, cameraSize.height)
                setOnFrameAvailableListener {
                    frameAvailable = true
                    onNewFrame?.invoke()
                }
            }
            cameraSurface = Surface(cameraSt)
        } else {
            cameraSt?.setDefaultBufferSize(cameraSize.width, cameraSize.height)
        }
        return cameraSurface!!
    }

    var onNewFrame: (() -> Unit)? = null

    fun setRoll(deg: Float) {
        if (!deg.isNaN() && !deg.isInfinite()) rollDeg = deg
    }

    fun setPreviewFixedRotateDeg(deg: Float) { previewFixedDeg = deg }
    fun setEncoderFixedRotateDeg(deg: Float) { encoderFixedDeg = deg }

    /** Önizleme paritesi: 0°/180°. Uygulamada hep 0° gönder (flip istemiyoruz). */
    fun setPreviewUpsideDown(up: Boolean) {
        targetPreviewParityDeg = if (up) 180f else 0f
    }

    /** Encoder paritesi: 0°/180° (kayıt her zaman dik olsun diye). */
    fun setEncoderUpsideDown(up: Boolean) {
        targetEncoderParityDeg = if (up) 180f else 0f
    }

    /**
     * COMPAT: CaptureFragment’in çağırdığı ortak setter.
     * Hem preview hem encoder paritesini aynı anda ayarlar.
     * İsterseniz sadece encoder’ı etkilesin diye burada setPreviewUpsideDown(false) sabitleyebilirsiniz.
     */
    fun setUpsideDown(up: Boolean) {
        setPreviewUpsideDown(false)   // preview hep 0°
        setEncoderUpsideDown(up)      // yalnız encoder flip uygular
    }

    /** COMPAT alias (bazı sürümlerde bu adla çağrılıyor olabilir) */
    fun setParity180(up: Boolean) = setUpsideDown(up)

    /** COMPAT alias (bazı sürümlerde bu adla çağrılıyor olabilir) */
    fun setFlipVertical(up: Boolean) = setUpsideDown(up)

    fun setEncoderSurface(s: Surface?) {
        encoderSurface = s
        val d = eglDisplay
        val c = eglContext
        if (d != null && c != null && encoderSurface != null) {
            val cfg = chooseAnyRecordableConfig(d)
            encoderEglSurface?.let { eglDestroySurface(d, it); encoderEglSurface = null }
            encoderEglSurface = eglCreateWindowSurface(d, cfg!!, encoderSurface!!)
        } else if (d != null && encoderEglSurface != null && encoderSurface == null) {
            eglDestroySurface(d, encoderEglSurface!!)
            encoderEglSurface = null
        }
    }

    fun setRecording(active: Boolean) { recording = active }
    fun setCameraStreamSize(w: Int, h: Int) { srcW = w; srcH = h }
    fun setEncoderViewport(w: Int, h: Int) { encW = w; encH = h }
    fun setFillCropEnabled(enabled: Boolean) { fillCropEnabled = enabled }

    // === GLSurfaceView.Renderer ===
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        eglDisplay = EGL14.eglGetCurrentDisplay()
        eglContext = EGL14.eglGetCurrentContext()
        windowSurface = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glClearColor(0f, 0f, 0f, 1f)

        prog = buildProgram(VS, FS_OES)
        aPosLoc = GLES20.glGetAttribLocation(prog, "aPos")
        aTexLoc = GLES20.glGetAttribLocation(prog, "aTex")
        uMvpLoc = GLES20.glGetUniformLocation(prog, "uMVP")
        uTexMatLoc = GLES20.glGetUniformLocation(prog, "uTexMatrix")

        Matrix.setIdentityM(mvp, 0)
        Matrix.setIdentityM(rot, 0)
        Matrix.setIdentityM(scl, 0)

        cameraSt?.setOnFrameAvailableListener {
            frameAvailable = true
            onNewFrame?.invoke()
        }

        // Encoder surface önceden verilmişse EGLSurface oluştur
        encoderSurface?.let { s ->
            val d = eglDisplay ?: return@let
            val cfg = chooseAnyRecordableConfig(d)
            encoderEglSurface = eglCreateWindowSurface(d, cfg!!, s)
        }
        lastFrameNs = 0L
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        prevW = width
        prevH = height
    }

    override fun onDrawFrame(gl: GL10?) {
        if (frameAvailable) {
            cameraSt?.updateTexImage()
            cameraSt?.getTransformMatrix(stMatrix)
            frameAvailable = false
        }

        // --- pariteleri yumuşat ---
        val now = System.nanoTime()
        val dt = if (lastFrameNs == 0L) 0f else (now - lastFrameNs) / 1_000_000_000f
        lastFrameNs = now
        if (dt > 0f) {
            fun stepToward(cur: Float, target: Float): Float {
                val diff = target - cur
                return if (kotlin.math.abs(diff) > 0.01f) {
                    val step = paritySpeedDegPerSec * dt
                    cur + diff.coerceIn(-step, step)
                } else target
            }
            currentPreviewParityDeg = stepToward(currentPreviewParityDeg, targetPreviewParityDeg)
            currentEncoderParityDeg = stepToward(currentEncoderParityDeg, targetEncoderParityDeg)
        } else {
            currentPreviewParityDeg = targetPreviewParityDeg
            currentEncoderParityDeg = targetEncoderParityDeg
        }

        // === Önizleme çizimi === (parite: preview)
        if (prevW > 0 && prevH > 0) {
            GLES20.glViewport(0, 0, prevW, prevH)
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            val base = previewFixedDeg
            val total = normalize(base - rollDeg + currentPreviewParityDeg) // genelde parity=0
            drawSceneForSize(prevW, prevH, total)
        }

        // === Encoder çizimi === (parite: encoder)
        val d = eglDisplay
        val ctx = eglContext
        val win = windowSurface
        val encSurf = encoderEglSurface
        if (recording && d != null && ctx != null && win != null && encSurf != null) {
            if (!EGL14.eglMakeCurrent(d, encSurf, encSurf, ctx)) {
                EGL14.eglMakeCurrent(d, win, win, ctx)
                return
            }

            GLES20.glViewport(0, 0, encW, encH)
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            val base = encoderFixedDeg
            val total = normalize(base - rollDeg + currentEncoderParityDeg)
            drawSceneForSize(encW, encH, total)

            try { EGLExt.eglPresentationTimeANDROID(d, encSurf, System.nanoTime()) } catch (_: Throwable) {}
            EGL14.eglSwapBuffers(d, encSurf)

            // geri dön
            EGL14.eglMakeCurrent(d, win, win, ctx)
        }
    }

    // === Çizim ===
    private fun scaleForFillCrop(dstW: Int, dstH: Int, useDeg: Float): Float {
        if (dstW <= 0 || dstH <= 0 || srcW <= 0 || srcH <= 0) return 1f

        val sBase = if (fillCropEnabled) {
            max(dstW.toFloat() / srcW.toFloat(), dstH.toFloat() / srcH.toFloat())
        } else {
            minOf(dstW.toFloat() / srcW.toFloat(), dstH.toFloat() / srcH.toFloat())
        }

        // Roll-cover
        val theta = Math.toRadians(useDeg.toDouble())
        val dstAR = dstW.toFloat() / dstH.toFloat()
        val cover = (abs(sin(theta)) * dstAR + abs(cos(theta))).toFloat()
        val safety = 1.02f

        val s = sBase * max(1f, cover) * safety
        return if (s.isFinite()) s else sBase
    }

    private fun drawSceneForSize(dstW: Int, dstH: Int, totalRotDeg: Float) {
        val s = scaleForFillCrop(dstW, dstH, totalRotDeg)

        Matrix.setIdentityM(mvp, 0)
        Matrix.setIdentityM(rot, 0)
        Matrix.setIdentityM(scl, 0)

        Matrix.setRotateM(rot, 0, totalRotDeg, 0f, 0f, 1f)
        Matrix.scaleM(scl, 0, s, s, 1f)

        Matrix.multiplyMM(tmp, 0, scl, 0, rot, 0)
        System.arraycopy(tmp, 0, mvp, 0, 16)

        GLES20.glUseProgram(prog)

        vb.position(0)
        GLES20.glVertexAttribPointer(aPosLoc, 2, GLES20.GL_FLOAT, false, 0, vb)
        GLES20.glEnableVertexAttribArray(aPosLoc)

        tb.position(0)
        GLES20.glVertexAttribPointer(aTexLoc, 2, GLES20.GL_FLOAT, false, 0, tb)
        GLES20.glEnableVertexAttribArray(aTexLoc)

        GLES20.glUniformMatrix4fv(uMvpLoc, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(uTexMatLoc, 1, false, stMatrix, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexId)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        GLES20.glDisableVertexAttribArray(aPosLoc)
        GLES20.glDisableVertexAttribArray(aTexLoc)
        GLES20.glUseProgram(0)
    }

    private fun normalize(d: Float): Float {
        var x = d % 360f
        if (x < 0f) x += 360f
        return x
    }

    // === Texture / Program / EGL helpers ===
    private fun createOesTex(): Int {
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        return tex[0]
    }

    private fun buildProgram(vs: String, fs: String): Int {
        fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) throw RuntimeException("shader compile error: " + GLES20.glGetShaderInfoLog(s))
            return s
        }
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("program link error: " + GLES20.glGetProgramInfoLog(p))
        GLES20.glDeleteShader(v)
        GLES20.glDeleteShader(f)
        return p
    }

    private fun chooseAnyRecordableConfig(display: android.opengl.EGLDisplay): android.opengl.EGLConfig? {
        val EGL_RECORDABLE_ANDROID = 0x3142
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val num = IntArray(1)
        EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0)
        return configs[0]
    }

    private fun eglCreateWindowSurface(
        display: android.opengl.EGLDisplay,
        config: android.opengl.EGLConfig,
        surface: Surface
    ): android.opengl.EGLSurface {
        val attrs = intArrayOf(EGL14.EGL_NONE)
        return EGL14.eglCreateWindowSurface(display, config, surface, attrs, 0)
    }

    private fun eglDestroySurface(display: android.opengl.EGLDisplay, surface: android.opengl.EGLSurface) {
        EGL14.eglDestroySurface(display, surface)
    }

    companion object {
        private const val VS = """
            attribute vec4 aPos;
            attribute vec2 aTex;
            uniform mat4 uMVP;
            uniform mat4 uTexMatrix;
            varying vec2 vTex;
            void main() {
                gl_Position = uMVP * aPos;
                vec4 t = uTexMatrix * vec4(aTex, 0.0, 1.0);
                vTex = t.xy;
            }
        """

        private const val FS_OES = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTex;
            uniform samplerExternalOES sTex;
            void main() {
                gl_FragColor = texture2D(sTex, vTex);
            }
        """
    }
}
