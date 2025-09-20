// app/src/main/java/com/kaanyildiz/videoinspectorapp/encode/GLVideoEncoder.kt
package com.kaanyildiz.videoinspectorapp.encode

import android.Manifest
import android.annotation.SuppressLint
import android.media.*
import android.media.AudioFormat.CHANNEL_IN_MONO
import android.media.AudioFormat.ENCODING_PCM_16BIT
import android.media.MediaCodec.BUFFER_FLAG_CODEC_CONFIG
import android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.annotation.RequiresPermission
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * GL tabanlı video + (varsa izin) AAC ses encoder/muxer.
 * - Video: AVC (H.264) COLOR_FormatSurface (GL Surface)
 * - Ses:  AAC LC (mono, 44.1 kHz); izin yoksa otomatik devre dışı.
 * - Her iki akış tek MediaMuxer’da MP4’e yazılır.
 *
 * ÖNEMLİ: Döndürme GL’de “pişirilir”. MP4 container’a orientation hint YAZMIYORUZ.
 */
@SuppressLint("MissingPermission")
class GLVideoEncoder(
    private val outputFile: File,
    private val width: Int,
    private val height: Int,
    private val fps: Int = 30,
    private val bitRate: Int = 16_000_000,

    // Audio ayarları
    private val enableAudioRequest: Boolean = true,
    private val audioSampleRate: Int = 44100,
    private val audioBitRate: Int = 128_000,
    private val audioChannels: Int = 1,

    /**
     * Dışarıdan gelen RECORD_AUDIO izni bilgisi (Fragment/Activity’de kontrol ediliyor).
     */
    private val hasRecordAudioPermission: Boolean = false
) {
    private val TAG = "GL-Enc"

    // --- Video ---
    private val vCodec: MediaCodec =
        MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)

    // --- Audio ---
    private var aCodec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    @Volatile private var audioEnabled: Boolean = false

    // --- Muxer ---
    private val muxer: MediaMuxer =
        MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val muxerLock = Any()

    /** GL tarafının render edeceği surface — start() öncesi alınır. */
    val inputSurface: Surface

    // Track/index ve durumlar
    @Volatile private var videoTrack = -1
    @Volatile private var audioTrack = -1
    @Volatile private var muxerStarted = false

    private val stopRequested = AtomicBoolean(false)
    @Volatile private var sawVideoEOS = false
    @Volatile private var sawAudioEOS = false

    // Zaman tabanı + “strictly increasing” PTS
    @Volatile private var ptsBaseUs: Long = Long.MIN_VALUE
    @Volatile private var lastVideoPtsUs: Long = -1
    @Volatile private var lastAudioPtsUs: Long = -1

    // Video PTS tamiri için
    @Volatile private var lastVideoRawPtsUs: Long = -1
    private var videoFrameIndex: Long = 0

    // Audio PTS (pcm frame sayısı)
    private var totalPcmFramesWritten: Long = 0L
    private val bytesPerPcmFrame: Int get() = audioChannels * 2 // 16-bit

    // Audio gelmezse fallback
    private var startElapsedMs: Long = 0L
    private val audioInitTimeoutMs: Long = 1800

    // Thread’ler
    private var vDrainThread: Thread? = null
    private var aDrainThread: Thread? = null
    private var aFeedThread: Thread? = null

    init {
        require(width % 2 == 0 && height % 2 == 0) { "width/height must be even" }

        // Video encoder formatı (GL surface input)
        val vFormat = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC, width, height
        ).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            runCatching { setInteger("max-bframes", 0) }
            runCatching {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel4)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            }
            // NOT: KEY_ROTATION KULLANMIYORUZ. Döndürme GL’de uygulanıyor.
            // runCatching { setInteger(MediaFormat.KEY_ROTATION, 0) }
        }

        vCodec.configure(vFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = vCodec.createInputSurface()

        // NOT: Orientation hint YOK. (seek sırasında ters dönmeyi önlemek için)
        // runCatching { muxer.setOrientationHint(0) }

        if (enableAudioRequest && hasRecordAudioPermission) {
            safePrepareAudio()
        } else {
            Log.w(TAG, "Audio disabled: request=$enableAudioRequest, perm=$hasRecordAudioPermission")
            audioEnabled = false
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun safePrepareAudio() {
        try {
            // --- Audio encoder
            aCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).also { codec ->
                val aFormat = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_AAC, audioSampleRate, audioChannels
                ).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, audioBitRate)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
                    setInteger(MediaFormat.KEY_CHANNEL_COUNT, audioChannels)
                    setInteger(MediaFormat.KEY_CHANNEL_MASK, CHANNEL_IN_MONO)
                }
                codec.configure(aFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }

            // --- AudioRecord
            val minBuf = AudioRecord.getMinBufferSize(audioSampleRate, CHANNEL_IN_MONO, ENCODING_PCM_16BIT)
            if (minBuf <= 0) throw IllegalStateException("Bad minBuf=$minBuf")

            val bufSize = max(minBuf, (audioSampleRate / 2) * bytesPerPcmFrame) // ≈ 500ms

            audioRecord = if (Build.VERSION.SDK_INT >= 23) {
                AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(ENCODING_PCM_16BIT)
                            .setSampleRate(audioSampleRate)
                            .setChannelMask(CHANNEL_IN_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufSize)
                    .build()
            } else {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    audioSampleRate,
                    CHANNEL_IN_MONO,
                    ENCODING_PCM_16BIT,
                    bufSize
                )
            }

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException("AudioRecord not initialized")
            }
            audioEnabled = true
            Log.i(TAG, "Audio prepared. minBuf=$minBuf bufSize=$bufSize sr=$audioSampleRate ch=$audioChannels")
        } catch (t: Throwable) {
            Log.e(TAG, "Audio prepare failed", t)
            audioEnabled = false
            releaseAudioInternals()
        }
    }

    fun start() {
        stopRequested.set(false)
        sawVideoEOS = false
        sawAudioEOS = !audioEnabled
        muxerStarted = false
        videoTrack = -1
        audioTrack = -1

        // PTS reset
        ptsBaseUs = Long.MIN_VALUE
        lastVideoPtsUs = -1
        lastAudioPtsUs = -1
        lastVideoRawPtsUs = -1
        videoFrameIndex = 0
        totalPcmFramesWritten = 0

        startElapsedMs = SystemClock.elapsedRealtime()

        vCodec.start()
        Log.i(TAG, "Video encoder started ${width}x$height@$fps, br=$bitRate")

        if (audioEnabled && hasRecordAudioPermission) {
            try {
                aCodec?.start()
            } catch (e: SecurityException) {
                Log.e(TAG, "Audio codec start SecurityException", e)
                audioEnabled = false; sawAudioEOS = true
            } catch (e: Throwable) {
                Log.e(TAG, "Audio codec start failed", e)
                audioEnabled = false; sawAudioEOS = true
            }

            try {
                audioRecord?.startRecording()
            } catch (e: SecurityException) {
                Log.e(TAG, "AudioRecord.startRecording SecurityException", e)
                audioEnabled = false; sawAudioEOS = true
            } catch (e: Throwable) {
                Log.e(TAG, "AudioRecord.startRecording failed", e)
                audioEnabled = false; sawAudioEOS = true
            }

            val rs = audioRecord?.recordingState
            if (rs != AudioRecord.RECORDSTATE_RECORDING) {
                Log.e(TAG, "AudioRecord not in RECORDING state. state=$rs")
                audioEnabled = false
                sawAudioEOS = true
            }
        } else {
            Log.w(TAG, "Audio disabled at start().")
        }

        vDrainThread = Thread(::drainVideo, "GL-Enc-VideoDrain").also { it.start() }
        if (audioEnabled) {
            aDrainThread = Thread(::drainAudio, "GL-Enc-AudioDrain").also { it.start() }
            aFeedThread = Thread(::feedAudio, "GL-Enc-AudioFeed").also { it.start() }
            Log.i(TAG, "Audio threads started")
        }
    }

    fun stopAndRelease() {
        runCatching { vCodec.signalEndOfInputStream() }
        stopRequested.set(true)

        runCatching { aFeedThread?.join(3000) }
        runCatching { aDrainThread?.join(5000) }
        runCatching { vDrainThread?.join(5000) }
        aFeedThread = null; aDrainThread = null; vDrainThread = null

        releaseAudioRecord()

        synchronized(muxerLock) {
            try {
                if (muxerStarted) muxer.stop()
            } catch (e: Exception) {
                Log.w(TAG, "muxer.stop() ignored", e)
            }
            runCatching { muxer.release() }
        }

        runCatching { vCodec.stop() }; runCatching { vCodec.release() }
        aCodec?.let { runCatching { it.stop() }; runCatching { it.release() } }
        aCodec = null

        Log.i(TAG, "Encoders & muxer released")
    }

    // -------- Video drain --------
    private fun drainVideo() {
        val info = MediaCodec.BufferInfo()
        while (!sawVideoEOS) {
            val outIndex = try {
                vCodec.dequeueOutputBuffer(info, 10_000)
            } catch (e: Throwable) {
                Log.e(TAG, "Video dequeueOutputBuffer err", e)
                break
            }

            maybeFallbackToVideoOnly()

            when (outIndex) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (videoTrack < 0) {
                        videoTrack = runCatching { muxer.addTrack(vCodec.outputFormat) }.getOrDefault(-1)
                        Log.i(TAG, "Video track added: $videoTrack fmt=${vCodec.outputFormat}")
                        startMuxerIfReady()
                    }
                }
                else -> if (outIndex >= 0) {
                    val encoded = try { vCodec.getOutputBuffer(outIndex) } catch (_: Throwable) { null }
                    if (encoded != null) {
                        if (info.size > 0 && info.flags and BUFFER_FLAG_CODEC_CONFIG == 0) {
                            // Video PTS düzelt
                            var rawUs = info.presentationTimeUs
                            if (lastVideoRawPtsUs >= 0 && rawUs <= lastVideoRawPtsUs) {
                                rawUs = (videoFrameIndex * 1_000_000L) / fps
                            }
                            lastVideoRawPtsUs = rawUs
                            videoFrameIndex++

                            info.presentationTimeUs = adjustPtsStrict(rawUs, isAudio = false)

                            encoded.position(info.offset)
                            encoded.limit(info.offset + info.size)
                            writeSample(videoTrack, encoded, info)
                        }
                        if (info.flags and BUFFER_FLAG_END_OF_STREAM != 0) {
                            sawVideoEOS = true
                            Log.i(TAG, "Video EOS")
                        }
                    }
                    vCodec.releaseOutputBuffer(outIndex, false)
                }
            }
        }
    }

    // -------- Audio feed (PCM -> aCodec input) --------
    private fun feedAudio() {
        val rec = audioRecord ?: return
        val ac = aCodec ?: return
        val tmp = ByteArray(8192)

        while (!stopRequested.get()) {
            val inIndex = try {
                ac.dequeueInputBuffer(10_000)
            } catch (_: Throwable) { -1 }
            if (inIndex < 0) continue

            val inBuf = try { ac.getInputBuffer(inIndex) } catch (_: Throwable) { null } ?: continue
            inBuf.clear()

            val read = try {
                rec.read(tmp, 0, tmp.size)
            } catch (e: SecurityException) {
                Log.e(TAG, "Audio read SecurityException (mic lost?)", e)
                audioEnabled = false
                break
            } catch (st: IllegalStateException) {
                Log.e(TAG, "Audio read IllegalState", st)
                audioEnabled = false
                break
            } catch (t: Throwable) {
                Log.e(TAG, "Audio read failed", t)
                audioEnabled = false
                break
            }

            if (read <= 0) {
                try { Thread.sleep(5) } catch (_: InterruptedException) {}
                continue
            }

            inBuf.put(tmp, 0, read)

            val frames = read / bytesPerPcmFrame
            val rawPtsUs = (totalPcmFramesWritten * 1_000_000L) / audioSampleRate
            totalPcmFramesWritten += frames
            val adjPtsUs = adjustPtsStrict(rawPtsUs, isAudio = true)

            try {
                ac.queueInputBuffer(inIndex, 0, read, adjPtsUs, 0)
            } catch (t: Throwable) {
                Log.e(TAG, "queueInputBuffer(audio) failed", t)
                audioEnabled = false
                break
            }
        }

        val eosIndex = try { aCodec?.dequeueInputBuffer(10_000) ?: -1 } catch (_: Throwable) { -1 }
        if (eosIndex >= 0) {
            val rawPtsUs = (totalPcmFramesWritten * 1_000_000L) / audioSampleRate
            val adjPtsUs = adjustPtsStrict(rawPtsUs, isAudio = true)
            runCatching { aCodec?.queueInputBuffer(eosIndex, 0, 0, adjPtsUs, BUFFER_FLAG_END_OF_STREAM) }
        }
        Log.w(TAG, "Audio feed thread exit; audioEnabled=$audioEnabled")
    }

    // -------- Audio drain (AAC -> muxer) --------
    private fun drainAudio() {
        val ac = aCodec ?: return
        val info = MediaCodec.BufferInfo()

        while (!sawAudioEOS) {
            val outIndex = try {
                ac.dequeueOutputBuffer(info, 10_000)
            } catch (e: Throwable) {
                Log.e(TAG, "Audio dequeueOutputBuffer err", e)
                break
            }
            when (outIndex) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (audioTrack < 0) {
                        audioTrack = runCatching { muxer.addTrack(ac.outputFormat) }.getOrDefault(-1)
                        Log.i(TAG, "Audio track added: $audioTrack fmt=${ac.outputFormat}")
                        startMuxerIfReady()
                    }
                }
                else -> if (outIndex >= 0) {
                    val encoded = try { ac.getOutputBuffer(outIndex) } catch (_: Throwable) { null }
                    if (encoded != null) {
                        if (info.size > 0 && info.flags and BUFFER_FLAG_CODEC_CONFIG == 0) {
                            info.presentationTimeUs = adjustPtsStrict(info.presentationTimeUs, isAudio = true)
                            encoded.position(info.offset)
                            encoded.limit(info.offset + info.size)
                            writeSample(audioTrack, encoded, info)
                        }
                        if (info.flags and BUFFER_FLAG_END_OF_STREAM != 0) {
                            sawAudioEOS = true
                            Log.i(TAG, "Audio EOS")
                        }
                    }
                    ac.releaseOutputBuffer(outIndex, false)
                }
            }
        }
    }

    // Ortak tabana rebase + sıkı artan PTS
    private fun adjustPtsStrict(rawUs: Long, isAudio: Boolean): Long {
        var base = ptsBaseUs
        if (base == Long.MIN_VALUE) {
            ptsBaseUs = rawUs
            base = rawUs
        }
        var adj = rawUs - base
        if (adj < 0) adj = 0
        if (isAudio) {
            if (adj <= lastAudioPtsUs) adj = lastAudioPtsUs + 1
            lastAudioPtsUs = adj
        } else {
            if (adj <= lastVideoPtsUs) adj = lastVideoPtsUs + 1
            lastVideoPtsUs = adj
        }
        return adj
    }

    // Audio gelmezse videoyla başla
    private fun maybeFallbackToVideoOnly() {
        if (muxerStarted) return
        if (videoTrack >= 0 && audioEnabled && audioTrack < 0) {
            val elapsed = SystemClock.elapsedRealtime() - startElapsedMs
            if (elapsed >= audioInitTimeoutMs) {
                Log.w(TAG, "Audio init timeout -> continue with video-only")
                audioEnabled = false
                startMuxerIfReady()
            }
        }
    }

    // Muxer’ı başlat (lock ile)
    @Synchronized
    private fun startMuxerIfReady() {
        if (!muxerStarted) {
            val videoReady = videoTrack >= 0
            val audioReady = if (audioEnabled) (audioTrack >= 0) else true
            if (videoReady && audioReady) {
                synchronized(muxerLock) {
                    runCatching { muxer.start() }
                        .onSuccess { Log.i(TAG, "Muxer started (audio=${audioTrack >= 0})") }
                        .onFailure { Log.e(TAG, "muxer.start() failed", it) }
                    muxerStarted = true
                }
            }
        }
    }

    private fun writeSample(track: Int, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (track < 0) return
        synchronized(muxerLock) {
            if (muxerStarted) {
                runCatching { muxer.writeSampleData(track, buffer, info) }
                    .onFailure { Log.e(TAG, "muxer.writeSampleData failed", it) }
            }
        }
    }

    private fun releaseAudioRecord() {
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
    }

    private fun releaseAudioInternals() {
        releaseAudioRecord()
        aCodec?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        aCodec = null
    }
}
