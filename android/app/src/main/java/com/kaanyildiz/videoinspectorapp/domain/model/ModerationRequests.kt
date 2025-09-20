// com/kaanyildiz/videoinspectorapp/data/remote/model/ModerationRequests.kt
package com.kaanyildiz.videoinspectorapp.data.remote.model

// Moshi kullanıyorsun; alan adları birebir aynıysa anotasyona gerek yok.
data class ValidateRequest(
    val status: String // "valid" | "not_valid"
)

data class CommentRequest(
    val comment: String
)
