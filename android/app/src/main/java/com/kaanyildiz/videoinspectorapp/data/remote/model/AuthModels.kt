package com.kaanyildiz.videoinspectorapp.data.remote.model

// ---- Auth ----
data class LoginRequest(
    val email: String,
    val password: String
)

// Login backend’in böyle dönüyorsa role/email’i opsiyonel yap:
data class LoginResponse(
    val token: String,
    val role: String? = null,
    val email: String? = null
)

// ---- Role ----
// /user/role cevabı { "role": "streamer" } şeklindeyse:
data class RoleResponse(
    val role: String?
)

// ---- Generic mesaj ----
data class MessageResponse(
    val message: String
)
