package com.kaanyildiz.videoinspectorapp.domain.model

data class MediaItem(
    val id: Int,
    val type: String,        // "image" | "video"
    val path: String,
    val uploadedByEmail: String,
    val validationStatus: String?,
    val comments: List<CommentItem>
)
