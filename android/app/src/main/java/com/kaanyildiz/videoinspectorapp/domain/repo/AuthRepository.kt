package com.kaanyildiz.videoinspectorapp.domain.repo

interface AuthRepository {
    suspend fun login(email: String, password: String): String
    suspend fun logout(token: String): String
    suspend fun role(token: String): String
}