// app/src/main/java/.../data/remote/model/MediaItemDto.kt
package com.kaanyildiz.videoinspectorapp.data.remote.model

import com.squareup.moshi.Json

data class MediaItemDto(
    val id: Long,
    val type: String, // "image" | "video"
    val path: String, // "/files/2025-09-06/xxx.mp4"
    @Json(name = "uploaded_by_email") val uploadedByEmail: String?,
    @Json(name = "channel_id") val channelId: Int?,
    val status: String?, // null=waiting
    @Json(name = "thumbnail_url") val thumbnailUrl: String?
)
