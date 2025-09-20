// app/src/main/java/.../worker/UploadWorker.kt
package com.kaanyildiz.videoinspectorapp.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.kaanyildiz.videoinspectorapp.data.remote.ApiService
import com.kaanyildiz.videoinspectorapp.domain.token.TokenStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException
import java.io.File
import java.io.IOException

@HiltWorker
class UploadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val api: ApiService,
    private val tokenStore: TokenStore
) : CoroutineWorker(appContext, params) {

    companion object { private const val TAG = "UploadWorker" }

    override suspend fun doWork(): Result {
        Log.d(TAG, "doWork() START")

        val baseDir = applicationContext.getExternalFilesDir("captures")
        Log.d(TAG, "captures dir: ${baseDir?.absolutePath}")
        if (baseDir == null) {
            Log.w(TAG, "captures not found -> success()")
            return Result.success()
        }

        val pairs = findPairs(baseDir)
        Log.d(TAG, "found pairs: ${pairs.size}")
        if (pairs.isEmpty()) return Result.success()

        val token = tokenStore.token()
        Log.d(TAG, "token null? ${token.isNullOrBlank()}")
        if (token.isNullOrBlank()) return Result.retry()

        val bearer = "Bearer $token"

        for ((idx, p) in pairs.withIndex()) {
            Log.d(TAG, "[$idx] uploading: file=${p.file.name} meta=${p.metaFile.name} isVideo=${p.isVideo} channel=${p.channel}")
            try {
                val metaText = p.metaFile.readText()
                val metaBody = metaText.toRequestBody("application/json".toMediaType())
                val channelBody = p.channel.toRequestBody("text/plain".toMediaType())

                val mime = if (p.isVideo) "video/mp4" else "image/jpeg"
                val fileReq = p.file.asRequestBody(mime.toMediaType())
                val filePart = MultipartBody.Part.createFormData("file", p.file.name, fileReq)

                val type = if (p.isVideo) "video" else "photo"
                val typeBody = type.toRequestBody("text/plain".toMediaType())

                val resp = try {
                    api.uploadMedia(
                        bearer = bearer,
                        file = filePart,
                        type = typeBody,
                        meta = metaBody,
                        channel = channelBody
                    )
                } catch (e: HttpException) {
                    Log.e(TAG, "http ${e.code()} ${e.message()}")
                    if (e.code() in 500..599) return Result.retry()
                    continue
                } catch (e: IOException) {
                    Log.e(TAG, "io ${e.message}", e)
                    return Result.retry()
                } catch (e: Exception) {
                    Log.e(TAG, "unexpected ${e.message}", e)
                    return Result.retry()
                }

                Log.d(TAG, "resp.isSuccessful=${resp.isSuccessful} code=${resp.code()}")
                if (!resp.isSuccessful) {
                    if (resp.code() in 500..599) return Result.retry()
                    // 4xx ise bu dosyayı atla
                    continue
                }

                moveToUploaded(p.file, p.metaFile)
                Log.d(TAG, "moved to uploaded/")

                delay(150L)

            } catch (e: Exception) {
                Log.e(TAG, "loop error ${e.message}", e)
                return Result.retry()
            }
        }

        Log.d(TAG, "doWork() END -> success")
        return Result.success()
    }

    private data class PairItem(
        val file: File,
        val metaFile: File,
        val isVideo: Boolean,
        val channel: String
    )

    private fun findPairs(baseDir: File): List<PairItem> {
        val out = mutableListOf<PairItem>()
        baseDir.listFiles()?.forEach { chDir ->
            if (!chDir.isDirectory) return@forEach
            if (chDir.name == "uploaded") return@forEach

            val channelName = chDir.name
            chDir.listFiles()?.forEach { f ->
                if (f.isFile && f.extension.lowercase() in listOf("mp4", "jpg", "jpeg")) {
                    val meta = File(f.parentFile, f.nameWithoutExtension + ".json")
                    if (meta.exists()) {
                        out += PairItem(
                            file = f,
                            metaFile = meta,
                            isVideo = f.extension.lowercase() == "mp4",
                            channel = channelName
                        )
                    } else {
                        Log.w(TAG, "meta not found for ${f.name}")
                    }
                }
            }
        }
        return out
    }

    private fun moveToUploaded(vararg files: File) {
        files.forEach { f ->
            val dest = File(f.parentFile, "uploaded").apply { mkdirs() }
            val ok = f.renameTo(File(dest, f.name))
            Log.d(TAG, "move ${f.name} -> uploaded ok=$ok")
        }
    }
}
