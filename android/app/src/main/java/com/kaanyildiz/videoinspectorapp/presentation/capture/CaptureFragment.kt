// app/src/main/java/com/kaanyildiz/videoinspectorapp/presentation/capture/CaptureFragment.kt
package com.kaanyildiz.videoinspectorapp.presentation.capture

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.view.*
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.exifinterface.media.ExifInterface as ExifX
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.LocationServices
import com.kaanyildiz.videoinspectorapp.databinding.FragmentCaptureBinding
import com.kaanyildiz.videoinspectorapp.domain.token.TokenStore
import com.kaanyildiz.videoinspectorapp.sensors.LevelSensor
import com.kaanyildiz.videoinspectorapp.horizonlock.RotationVectorProvider
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject
import kotlin.coroutines.resume
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

// Camera2 interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import android.hardware.camera2.CaptureRequest
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import android.util.Range
import android.hardware.camera2.CameraCharacteristics
import androidx.camera.camera2.interop.Camera2CameraInfo

// GL önizleme
import android.opengl.GLSurfaceView
import com.kaanyildiz.videoinspectorapp.gl.HorizonGLRenderer
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch

// GL encoder
import com.kaanyildiz.videoinspectorapp.encode.GLVideoEncoder

@AndroidEntryPoint
class CaptureFragment : Fragment(), LevelSensor.Listener {

    @Inject lateinit var tokenStore: TokenStore

    private var _binding: FragmentCaptureBinding? = null
    private val binding get() = _binding!!

    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null

    private var placeholderView: TextView? = null

    // Seçimler
    private var currentAspectLabel = "16:9"
    private var currentResolutionLabel = "1080p"
    private var currentSize = computeSize(currentAspectLabel, currentResolutionLabel)

    // Sayaç
    private var startMs = 0L
    private var durationSec = 0L
    private val timerHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private var timerRunnable: Runnable? = null

    private val locationClient by lazy {
        LocationServices.getFusedLocationProviderClient(requireContext())
    }

    private val channelFolderName by lazy { "1" }
    @Volatile private var rebindPending = false
    private var lensFacing = CameraSelector.LENS_FACING_BACK

    // Preview aynalama sadece UI için; çıktı dosyaları aynalanmaz
    private val FORCE_UNMIRROR_FRONT_STILLS = false

    // Gestures
    private var currentScale = 1f
    private var autoScale = 1f

    // Sensörler
    private var levelSensor: LevelSensor? = null
    private var rotationVectorProvider: RotationVectorProvider? = null

    // GL
    private lateinit var glView: GLSurfaceView
    private val glRenderer = HorizonGLRenderer()
    private val cameraExecutor by lazy { Executors.newSingleThreadExecutor() }

    // GL video kaydı
    private var glEncoder: GLVideoEncoder? = null
    private var currentVideoFile: File? = null

    // Preview yüzeyi hazır mı?
    @Volatile private var cameraSurfaceReady: Boolean = false

    // Baz açı / roll
    private var previewBaseDeg: Float = 0f
    @Volatile private var lastRollDegRaw: Float = 0f

    // Low-pass roll (önizleme için)
    @Volatile private var rollFilt: Float = 0f
    private val ROLL_ALPHA = 0.85f

    // Kayıt durumu
    @Volatile private var isRecording: Boolean = false

    // --- CİHAZ DÜZELTMESİ: 270° (= -90°) ekle ---
    private val EXTRA_DEVICE_FIX_DEG = -90f

    // İzin launcheri
    private val requestPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val camGranted = result[Manifest.permission.CAMERA] == true
        if (camGranted) rebindCameraUseCases() else {
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Kamera izni gerekli")
                .setMessage("Önizleme için Kamera izni gerekiyor. Ayarlar > İzinler’den açın.")
                .setPositiveButton("Ayarları Aç") { _, _ ->
                    val uri = android.net.Uri.fromParts("package", requireContext().packageName, null)
                    startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri
                        )
                    )
                }
                .setNegativeButton("Vazgeç", null)
                .show()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCaptureBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(v: View, savedInstanceState: Bundle?) {
        super.onViewCreated(v, savedInstanceState)

        // GL Surface
        glView = binding.glPreview
        glView.setEGLContextClientVersion(2)
        glView.preserveEGLContextOnPause = true
        glView.setRenderer(glRenderer)
        glView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        glRenderer.onNewFrame = { glView.requestRender() }
        glView.queueEvent {
            glRenderer.setPreviewFixedRotateDeg(0f)
            glRenderer.setPreviewUpsideDown(false) // UI flip yok
        }

        setupLevelContainerGestures()

        levelSensor = LevelSensor(requireContext()).also { it.listener = this@CaptureFragment }

        // Sensör callback — sadece roll’ü güncelle (pariteyi burada DEĞİŞTİRME)
        rotationVectorProvider = RotationVectorProvider(requireContext()) { rollDeg, _, _ ->
            lastRollDegRaw = rollDeg
            rollFilt = if (rollFilt == 0f) rollDeg else (ROLL_ALPHA * rollFilt + (1f - ROLL_ALPHA) * rollDeg)
            glView.queueEvent { glRenderer.setRoll(rollFilt) } // horizon-lock
            glView.requestRender()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val role = tokenStore.role()?.lowercase().orEmpty()
            if (role != "streamer") {
                Toast.makeText(requireContext(), "Çekim ekranı sadece STREAMER içindir.", Toast.LENGTH_SHORT).show()
                parentFragmentManager.popBackStack()
                return@launch
            }

            showPlaceholder()

            val aspectOptions = listOf("1:1", "4:3", "16:9")
            val resOptions = listOf("480p", "720p", "1080p")

            binding.spAspect.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, aspectOptions)
            binding.spResolution.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, resOptions)

            binding.spAspect.setSelection(aspectOptions.indexOf(currentAspectLabel).coerceAtLeast(0))
            binding.spResolution.setSelection(resOptions.indexOf(currentResolutionLabel).coerceAtLeast(0))

            binding.spAspect.setOnItemSelectedListener { label ->
                currentAspectLabel = label
                currentSize = computeSize(currentAspectLabel, currentResolutionLabel)
                rebindCameraUseCases()
            }
            binding.spResolution.setOnItemSelectedListener { label ->
                currentResolutionLabel = label
                currentSize = computeSize(currentAspectLabel, currentResolutionLabel)
                rebindCameraUseCases()
            }

            binding.btnTakePhoto.setOnClickListener { takePhoto() }
            binding.btnStartVideo.setOnClickListener { startVideo() }
            binding.btnStopVideo.setOnClickListener { stopVideo() }

            setRecordingUi(false)
            currentSize = computeSize(currentAspectLabel, currentResolutionLabel)
            ensurePermissions()
        }
    }

    override fun onResume() {
        super.onResume()
        // Portreye kilitliyoruz; targetRotation da ROTATION_0 verildiği için "her zaman dik"
        requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        glView.onResume()
        levelSensor?.start()
        rotationVectorProvider?.start()
    }

    override fun onPause() {
        try { if (glEncoder != null) stopVideo() } catch (_: IllegalStateException) {}
        timerRunnable?.let { timerHandler.removeCallbacks(it) }
        timerRunnable = null
        setRecordingUi(false)
        binding.tvTimer.text = "00:00"
        levelSensor?.stop()
        rotationVectorProvider?.stop()
        glView.onPause()
        requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        super.onPause()
    }

    override fun onRollChanged(rollDeg: Float) { /* overlay için opsiyonel */ }

    private fun setupLevelContainerGestures() {
        val container = binding.levelContainer
        container.post {
            container.pivotX = container.width / 2f
            container.pivotY = container.height / 2f
        }
        val scaleDetector = ScaleGestureDetector(requireContext(),
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    currentScale *= detector.scaleFactor
                    currentScale = currentScale.coerceIn(0.5f, 1.4f)
                    applyContainerTransform(container)
                    return true
                }
            })
        container.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event); true
        }
    }

    private fun applyContainerTransform(v: View) {
        v.scaleX = currentScale * autoScale
        v.scaleY = currentScale * autoScale
    }

    // İzinler
    private fun ensurePermissions() {
        val needs = mutableListOf<String>()
        if (!has(Manifest.permission.CAMERA)) needs += Manifest.permission.CAMERA
        if (!has(Manifest.permission.RECORD_AUDIO)) needs += Manifest.permission.RECORD_AUDIO
        if (!has(Manifest.permission.ACCESS_FINE_LOCATION)) needs += Manifest.permission.ACCESS_FINE_LOCATION
        if (!has(Manifest.permission.ACCESS_COARSE_LOCATION)) needs += Manifest.permission.ACCESS_COARSE_LOCATION
        if (needs.isEmpty()) rebindCameraUseCases() else requestPerms.launch(needs.toTypedArray())
    }

    private fun has(p: String) =
        ContextCompat.checkSelfPermission(requireContext(), p) == PackageManager.PERMISSION_GRANTED
    private fun hasAny(vararg perms: String) =
        perms.any { ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED }

    // Yardımcı: açıyı 0..360 normalize et
    private fun norm360(d: Float) = ((d % 360f) + 360f) % 360f

    // CameraX bind — SADECE Preview + ImageCapture
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    @OptIn(ExperimentalCamera2Interop::class)
    private fun rebindCameraUseCases() {
        if (!isAdded || rebindPending) return
        rebindPending = true
        cameraSurfaceReady = false

        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val aspect = when (currentAspectLabel) {
                    "1:1" -> AspectRatio.RATIO_4_3
                    "4:3" -> AspectRatio.RATIO_4_3
                    else  -> AspectRatio.RATIO_16_9
                }
                val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

                // --- Preview ---
                val preview = Preview.Builder()
                    .setTargetAspectRatio(aspect)
                    .setTargetRotation(Surface.ROTATION_0) // Portreye sabit
                    .build()

                preview.setSurfaceProvider(cameraExecutor) { request ->
                    // 1) CameraX dönüş bilgisini dinle ve GL'ye uygula (+ cihaz düzeltmesi)
                    request.setTransformationInfoListener(cameraExecutor) { info ->
                        val rot = info.rotationDegrees.toFloat()
                        previewBaseDeg = norm360(rot + EXTRA_DEVICE_FIX_DEG)
                        glView.queueEvent {
                            glRenderer.setPreviewFixedRotateDeg(previewBaseDeg)
                            glRenderer.setPreviewUpsideDown(false)
                        }
                        android.util.Log.d(
                            "CaptureFragment",
                            "Rotation=${info.rotationDegrees} base=$previewBaseDeg"
                        )
                    }

                    // 2) GL surface oluştur & bağla
                    val latch = CountDownLatch(1)
                    var sfc: Surface? = null
                    glView.queueEvent {
                        sfc = glRenderer.createOrUpdateCameraSurface(
                            android.util.Size(request.resolution.width, request.resolution.height)
                        )
                        glRenderer.setCameraStreamSize(request.resolution.width, request.resolution.height)
                        latch.countDown()
                    }
                    latch.await()

                    cameraSurfaceReady = (sfc != null)
                    val surface = sfc ?: run { request.willNotProvideSurface(); return@setSurfaceProvider }
                    request.provideSurface(surface, cameraExecutor) {
                        try { surface.release() } catch (_: Exception) {}
                        cameraSurfaceReady = false
                    }
                }

                // --- ImageCapture ---
                val ic = ImageCapture.Builder()
                    .setTargetAspectRatio(aspect)
                    .setTargetRotation(Surface.ROTATION_0) // Portreye sabit
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(viewLifecycleOwner, selector, preview, ic)
                imageCapture = ic

                // 30/60 fps hedefi + stabilizasyon (destekliyse)
                camera?.let { cam ->
                    val c2Info = Camera2CameraInfo.from(cam.cameraInfo)

                    val fpsRanges = runCatching {
                        c2Info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                    }.getOrNull()
                    val targetRange = fpsRanges?.filter { it.upper >= 60 }?.maxByOrNull { it.upper } ?: Range(30, 30)

                    val stabModes = runCatching {
                        c2Info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
                    }.getOrNull()
                    val supportsStabOn =
                        stabModes?.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true

                    val builder = CaptureRequestOptions.Builder()
                        .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, targetRange)

                    if (supportsStabOn) {
                        builder.setCaptureRequestOption(
                            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
                        )
                    }

                    runCatching {
                        Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(builder.build())
                    }.onFailure { err ->
                        android.util.Log.w("CaptureFragment", "CaptureRequest options rejected: $err")
                    }
                }

                // Önizleme UI aynası (sadece görünüm) — şimdilik hep arka kamera
                binding.glPreview.scaleX = 1f

                hidePlaceholder()
            } catch (e: Exception) {
                android.util.Log.e("CaptureFragment", "bind failed", e)
            } finally {
                rebindPending = false
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    // Fotoğraf
    private fun takePhoto() {
        val ic = imageCapture ?: return
        val outDir = ensureChannelDir()
        val ts = timeStamp()
        val photoFile = File(outDir, "IMG_$ts.jpg")

        imageCapture?.targetRotation = Surface.ROTATION_0

        val opts = ImageCapture.OutputFileOptions.Builder(photoFile).build()
        ic.takePicture(
            opts,
            ContextCompat.getMainExecutor(requireContext()),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    android.util.Log.e("CaptureFragment", "takePhoto error", exc)
                }
                override fun onImageSaved(res: ImageCapture.OutputFileResults) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        // 1) Deklanşör anındaki roll’a göre 180° flip gerekirse uygula
                        val needFlip180 = shouldFlipForStill(lastRollDegRaw)
                        makeJpegUprightIfNeeded(photoFile, needFlip180)

                        // 2) (Opsiyonel) Ön kamera aynalı verirse (kullanılmıyor ama dursun)
                        if (lensFacing == CameraSelector.LENS_FACING_FRONT && FORCE_UNMIRROR_FRONT_STILLS) {
                            maybeUnMirrorFrontPhoto(photoFile)
                        }

                        // 3) Meta & yayın & upload
                        val loc = lastKnownLocationOrNull()
                        val metaJson = baseMetadata(false, photoFile, currentSize, null, loc)
                        File(outDir, "${photoFile.nameWithoutExtension}.json").writeText(metaJson, Charsets.UTF_8)
                        publishToMediaStore(photoFile, false)
                        kickUploadNow()
                        Toast.makeText(requireContext(), "Foto kaydedildi: ${photoFile.name}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    // Video (GL encoder) — parite YALNIZCA başlangıçta hesaplanır ve KİLİTLENİR
    @SuppressLint("MissingPermission")
    private fun startVideo() {
        if (!cameraSurfaceReady) {
            Toast.makeText(requireContext(), "Kamera hazırlanıyor, lütfen tekrar deneyin.", Toast.LENGTH_SHORT).show()
            return
        }

        val micGranted = has(Manifest.permission.RECORD_AUDIO)
        if (!micGranted) {
            Toast.makeText(requireContext(), "Mikrofon izni yok. Video sessiz kaydedilecek.", Toast.LENGTH_SHORT).show()
        }

        try {
            val outDir = ensureChannelDir()
            val ts = timeStamp()
            val videoFile = File(outDir, "VID_$ts.mp4")
            currentVideoFile = videoFile

            // MP4 portre (w < h), çift piksel
            var encW = if (currentSize.width % 2 == 0) currentSize.width else currentSize.width - 1
            var encH = if (currentSize.height % 2 == 0) currentSize.height else currentSize.height - 1
            if (encW > encH) { val tmp = encW; encW = encH; encH = tmp }

            glEncoder = GLVideoEncoder(
                outputFile = videoFile,
                width = encW,
                height = encH,
                fps = 30,
                bitRate = 16_000_000,
                enableAudioRequest = true,
                audioSampleRate = 44100,
                audioBitRate = 128_000,
                audioChannels = 1,
                hasRecordAudioPermission = micGranted
            )

            val encSurface = try { glEncoder!!.inputSurface } catch (e: IllegalStateException) {
                android.util.Log.e("CaptureFragment", "inputSurface failed", e)
                Toast.makeText(requireContext(), "Video başlatılamadı (surface).", Toast.LENGTH_SHORT).show()
                return
            }

            // Encoder baz açısı: preview ile aynı (CameraX’in verdiği dönüş + cihaz düzeltmesi)
            val encoderBaseDeg = previewBaseDeg

            // BAŞLANGIÇ PARİTESİNİ SABİTLE (sadece roll’a bak)
            val initialParity180 = (kotlin.math.abs(wrap180(lastRollDegRaw)) >= 90f)

            // GL hazırla + “kick frame”
            val latch = CountDownLatch(1)
            glView.queueEvent {
                glRenderer.setEncoderFixedRotateDeg(encoderBaseDeg) // video baz rotasyon
                glRenderer.setEncoderUpsideDown(initialParity180)
                glRenderer.setEncoderViewport(encW, encH)
                glRenderer.setFillCropEnabled(true)
                glRenderer.setEncoderSurface(encSurface)
                glRenderer.setRecording(true)
                latch.countDown()
            }
            latch.await()
            glView.requestRender()

            glEncoder!!.start()
            isRecording = true

            startMs = SystemClock.elapsedRealtime()
            durationSec = 0
            binding.tvTimer.text = "00:00"
            timerRunnable?.let { timerHandler.removeCallbacks(it) }
            timerRunnable = object : Runnable {
                override fun run() {
                    val elapsed = (SystemClock.elapsedRealtime() - startMs) / 1000
                    durationSec = elapsed
                    binding.tvTimer.text = formatAsMmSs(elapsed)
                    timerHandler.postDelayed(this, 500)
                }
            }
            timerRunnable?.let { timerHandler.post(it) }
            setRecordingUi(true)
        } catch (t: Throwable) {
            android.util.Log.e("CaptureFragment", "GL encoder start failed", t)
            try { glEncoder?.stopAndRelease() } catch (_: Exception) {}
            glEncoder = null
            Toast.makeText(requireContext(), "GL kayıt açılamadı.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopVideo() {
        glEncoder?.let { enc ->
            val stopLatch = CountDownLatch(1)
            glView.queueEvent {
                android.opengl.GLES20.glFinish()
                glRenderer.setRecording(false)
                stopLatch.countDown()
            }
            try { stopLatch.await() } catch (_: InterruptedException) {}

            try { enc.stopAndRelease() } catch (_: Exception) {}

            val detachLatch = CountDownLatch(1)
            glView.queueEvent {
                glRenderer.setEncoderSurface(null)
                glRenderer.setPreviewFixedRotateDeg(previewBaseDeg)
                detachLatch.countDown()
            }
            try { detachLatch.await() } catch (_: InterruptedException) {}

            timerRunnable?.let { timerHandler.removeCallbacks(it) }
            timerRunnable = null
            setRecordingUi(false)
            binding.tvTimer.text = "00:00"
            isRecording = false

            currentVideoFile?.let { vf ->
                val outDir = vf.parentFile ?: ensureChannelDir()
                viewLifecycleOwner.lifecycleScope.launch {
                    val loc = lastKnownLocationOrNull()
                    val metaJson = baseMetadata(true, vf, currentSize, durationSec.toInt(), loc)
                    File(outDir, "${vf.nameWithoutExtension}.json").writeText(metaJson, Charsets.UTF_8)
                    publishToMediaStore(vf, true)
                    kickUploadNow()
                    Toast.makeText(requireContext(), "Video kaydedildi: ${vf.name}", Toast.LENGTH_SHORT).show()
                }
            }
            glEncoder = null
            currentVideoFile = null
            return
        }
    }

    private fun setRecordingUi(isRecording: Boolean) {
        binding.btnStartVideo.isEnabled = !isRecording
        binding.btnStopVideo.isEnabled = isRecording
        binding.btnTakePhoto.isEnabled = !isRecording
    }

    // Placeholder helpers
    private fun showPlaceholder() {
        placeholderView?.let { it.isVisible = true; return }
        val parent = binding.root as? ViewGroup ?: return
        val tv = TextView(requireContext()).apply {
            text = "Capture Screen"
            textSize = 18f
            setPadding(24, 24, 24, 24)
        }
        val lp = if (parent is FrameLayout) {
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER }
        } else {
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        parent.addView(tv, lp)
        placeholderView = tv
    }

    private fun hidePlaceholder() { placeholderView?.isVisible = false }

    // === Yardımcılar ===
    private fun wrap180(d: Float): Float {
        var x = d % 360f
        if (x < -180f) x += 360f
        if (x > 180f) x -= 360f
        return x
    }

    /** Foto için 180° flip gerekli mi? (mutlak roll >= 90°) */
    private fun shouldFlipForStill(rollDeg: Float): Boolean {
        val r = kotlin.math.abs(wrap180(rollDeg))
        return r >= 90f
    }

    /** JPEG’i gerekiyorsa 180° döndür ve EXIF ORIENTATION’ı NORMAL olarak yaz. */
    private fun makeJpegUprightIfNeeded(file: File, flip180: Boolean) {
        try {
            val exif = ExifX(file.absolutePath)
            if (!flip180) {
                exif.setAttribute(ExifX.TAG_ORIENTATION, ExifX.ORIENTATION_NORMAL.toString())
                exif.saveAttributes()
                return
            }
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            val src = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return
            val mat = Matrix().apply { postRotate(180f) }
            val rot = Bitmap.createBitmap(src, 0, 0, src.width, src.height, mat, true)

            val tmp = File(file.parentFile, file.nameWithoutExtension + "_upright_tmp.jpg")
            tmp.outputStream().use { os -> rot.compress(Bitmap.CompressFormat.JPEG, 95, os) }

            if (rot !== src) rot.recycle()
            src.recycle()

            if (file.delete()) {
                tmp.renameTo(file)
            } else {
                tmp.inputStream().use { ins ->
                    file.outputStream().use { outs -> ins.copyTo(outs) }
                }
                tmp.delete()
            }
            val exif2 = ExifX(file.absolutePath)
            exif2.setAttribute(ExifX.TAG_ORIENTATION, ExifX.ORIENTATION_NORMAL.toString())
            exif2.saveAttributes()
        } catch (t: Throwable) {
            android.util.Log.e("CaptureFragment", "makeJpegUprightIfNeeded failed", t)
        }
    }

    /** (Opsiyonel) Ön kamera stillleri aynalı gelirse, yatay eksende çevir. */
    private fun maybeUnMirrorFrontPhoto(file: File) {
        try {
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            val src = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return
            val mat = Matrix().apply { postScale(-1f, 1f, src.width / 2f, src.height / 2f) }
            val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, mat, true)

            val tmp = File(file.parentFile, file.nameWithoutExtension + "_unmirrored_tmp.jpg")
            tmp.outputStream().use { os -> out.compress(Bitmap.CompressFormat.JPEG, 95, os) }

            if (out !== src) out.recycle()
            src.recycle()

            if (file.delete()) tmp.renameTo(file) else {
                tmp.inputStream().use { ins -> file.outputStream().use { outs -> ins.copyTo(outs) } }
                tmp.delete()
            }
            val ex = ExifX(file.absolutePath)
            ex.setAttribute(ExifX.TAG_ORIENTATION, ExifX.ORIENTATION_NORMAL.toString())
            ex.saveAttributes()
        } catch (t: Throwable) {
            android.util.Log.e("CaptureFragment", "maybeUnMirrorFrontPhoto failed", t)
        }
    }

    private fun formatAsMmSs(sec: Long): String {
        val m = sec / 60
        val s = sec % 60
        return String.format("%02d:%02d", m, s)
    }

    private fun ensureChannelDir(): File {
        val base = requireContext().getExternalFilesDir("captures")
            ?: requireContext().getExternalFilesDir(Environment.DIRECTORY_MOVIES)!!
        val dir = File(base, channelFolderName)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun timeStamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            .format(System.currentTimeMillis())

    private fun computeSize(aspect: String, resolutionLabel: String): android.util.Size {
        return when (aspect) {
            "1:1" -> when (resolutionLabel) {
                "480p" -> android.util.Size(720, 720)
                "720p" -> android.util.Size(1080, 1080)
                else   -> android.util.Size(1920, 1920)
            }
            "4:3" -> when (resolutionLabel) {
                "480p" -> android.util.Size(640, 480)
                "720p" -> android.util.Size(960, 720)
                else   -> android.util.Size(1440, 1080)
            }
            else -> when (resolutionLabel) { // 16:9
                "480p" -> android.util.Size(854, 480)
                "720p" -> android.util.Size(1280, 720)
                else   -> android.util.Size(1920, 1080)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun lastKnownLocationOrNull(): Pair<Double?, Double?> =
        suspendCancellableCoroutine { cont ->
            if (!hasAny(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)) {
                cont.resume(null to null); return@suspendCancellableCoroutine
            }
            try {
                locationClient.lastLocation
                    .addOnSuccessListener { loc ->
                        if (loc != null) cont.resume(loc.latitude to loc.longitude)
                        else cont.resume(null to null)
                    }
                    .addOnFailureListener { cont.resume(null to null) }
            } catch (_: SecurityException) {
                cont.resume(null to null)
            }
        }

    private fun baseMetadata(
        isVideo: Boolean,
        file: File,
        resolution: android.util.Size,
        durationSec: Int?,
        loc: Pair<Double?, Double?>,
    ): String {
        val nowDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            .format(System.currentTimeMillis())
        val nowTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            .format(System.currentTimeMillis())

        val obj = JSONObject().apply {
            put("is_video", isVideo)
            put("shot_date", nowDate)
            put("shot_time", nowTime)
            safePut("loc_x", loc.first)
            safePut("loc_y", loc.second)
            put("resolution_w", resolution.width)
            put("resolution_h", resolution.height)
            put("filesize_bytes", file.length())
            safePut("duration_sec", durationSec)
            put("base_name", file.nameWithoutExtension)
        }
        return obj.toString(2)
    }

    private fun kickUploadNow() {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val once = OneTimeWorkRequestBuilder<com.kaanyildiz.videoinspectorapp.worker.UploadWorker>()
            .setConstraints(constraints).build()
        WorkManager.getInstance(requireContext())
            .enqueueUniqueWork("upload_once", ExistingWorkPolicy.REPLACE, once)
    }

    private fun publishToMediaStore(file: File, isVideo: Boolean): Uri? {
        val resolver = requireContext().contentResolver
        if (Build.VERSION.SDK_INT < 29) {
            val mime = if (isVideo) "video/mp4" else "image/jpeg"
            MediaScannerConnection.scanFile(requireContext(), arrayOf(file.absolutePath), arrayOf(mime), null)
            return null
        }
        val collection: Uri =
            if (isVideo) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

        val mime = if (isVideo) "video/mp4" else "image/jpeg"
        val relPath = if (isVideo) "Movies/VideoInspectorApp" else "Pictures/VideoInspectorApp"

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            put(MediaStore.MediaColumns.DATE_TAKEN, System.currentTimeMillis())
            put(MediaStore.MediaColumns.SIZE, file.length())
        }

        val uri = resolver.insert(collection, values) ?: return null
        resolver.openOutputStream(uri)?.use { os -> file.inputStream().use { it.copyTo(os) } }
        val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        resolver.update(uri, done, null, null)
        return uri
    }

    override fun onDestroyView() {
        timerRunnable?.let { timerHandler.removeCallbacks(it) }
        timerRunnable = null
        _binding = null
        try { cameraExecutor.shutdown() } catch (_: Exception) {}
        super.onDestroyView()
    }
}

// --- Extensions ---
private fun Spinner.setOnItemSelectedListener(onSelected: (String) -> Unit) {
    this.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            onSelected(parent?.getItemAtPosition(position).toString())
        }
        override fun onNothingSelected(parent: AdapterView<*>?) {}
    }
}

private fun JSONObject.safePut(key: String, value: Any?) {
    if (value == null) put(key, JSONObject.NULL) else put(key, value)
}
