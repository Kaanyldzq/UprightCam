// app/src/main/java/com/kaanyildiz/videoinspectorapp/data/repository/VideoRepository.kt
package com.kaanyildiz.videoinspectorapp.data.repository

import com.kaanyildiz.videoinspectorapp.data.remote.ApiService
import com.kaanyildiz.videoinspectorapp.data.remote.model.MediaItemDto
import com.kaanyildiz.videoinspectorapp.data.remote.model.VideoItemDto
import com.kaanyildiz.videoinspectorapp.domain.token.TokenStore
import javax.inject.Inject

class VideoRepository @Inject constructor(
    private val api: ApiService,
    private val tokenStore: TokenStore
) {

    suspend fun loadVideos(
        channels: List<Int> = emptyList(),
        from: String? = null,
        to: String? = null,
        email: String? = null,
        status: String? = null,   // "waiting" | "valid" | "not_valid"
        page: Int = 1,
        pageSize: Int = 20
    ): List<VideoItemDto> {
        val token = tokenStore.token() ?: return emptyList()
        val bearer = "Bearer $token"

        val channelId: Int? = channels.firstOrNull()
        val statusParam = when (status?.lowercase()) {
            "waiting", "valid", "not_valid" -> status.lowercase()
            else -> null
        }

        val resp = api.listMedia(
            bearer = bearer,
            status = statusParam,
            email = email,
            channel = channelId,
            page = page,
            pageSize = pageSize
        )
        if (!resp.isSuccessful) return emptyList()

        val media: List<MediaItemDto> = resp.body().orEmpty()

        return media.map { m ->
            VideoItemDto(
                id = m.id,
                type = m.type,
                path = toFullUrl(m.path),
                status = m.status,
                channel_id = m.channelId, // model zaten Int
                thumbnail_url = m.thumbnailUrl?.let { toFullUrl(it) }
            )
        }
    }

    suspend fun fetchVideos(
        channels: List<Int> = emptyList(),
        from: String? = null,
        to: String? = null,
        email: String? = null,
        status: String? = null,
        page: Int = 1,
        pageSize: Int = 20
    ): List<MediaItemDto> {
        val token = tokenStore.token() ?: return emptyList()
        val bearer = "Bearer $token"

        val channelId: Int? = channels.firstOrNull()
        val statusParam = when (status?.lowercase()) {
            "waiting", "valid", "not_valid" -> status.lowercase()
            else -> null
        }

        val resp = api.listMedia(
            bearer = bearer,
            status = statusParam,
            email = email,
            channel = channelId,
            page = page,
            pageSize = pageSize
        )
        return if (resp.isSuccessful) resp.body().orEmpty() else emptyList()
    }

    private fun toFullUrl(raw: String): String {
        val p = raw.replace('\\', '/')
        return when {
            p.startsWith("http", ignoreCase = true) -> p
            p.startsWith("/files/") -> BASE + p
            p.contains("/uploads/") -> BASE + "/files/" + p.substringAfter("/uploads/")
            p.startsWith("/") -> BASE + p
            else -> p
        }
    }

    companion object {
        // Gerçek cihaz için: PC'nizin LAN IP’si
        private const val BASE = "http://192.168.1.136:3000"
        // İstersen burada BuildConfig.BASE_URL da kullanabilirsin:
        // private val BASE = com.kaanyildiz.videoinspectorapp.BuildConfig.BASE_URL.trimEnd('/')
    }
}
