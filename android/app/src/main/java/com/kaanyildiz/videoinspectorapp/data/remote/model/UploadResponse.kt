package com.kaanyildiz.videoinspectorapp.data.remote.model

data class UploadResponse(
    val data: String?,   // ör: "Upload başarılı"
    val path: String?    // ör: "/files/2025-09-07/VID_xxx.mp4"
)
