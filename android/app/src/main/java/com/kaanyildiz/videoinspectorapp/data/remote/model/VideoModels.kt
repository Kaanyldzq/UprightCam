package com.kaanyildiz.videoinspectorapp.data.remote.model

// Tek bir video kaydı
data class VideoItemDto(
    val id: Long,
    val type: String,             // "photo" | "video"
    val path: String,
    val thumbnail_url: String?,
    val status: String?,           // "waiting" | "valid" | "not_valid"
    val channel_id: Int?
)

// API /videos cevabı
data class VideosResponse(
    val data: List<VideoItemDto>,
    val total: Int,
    val page: Int,
    val pageSize: Int
)
