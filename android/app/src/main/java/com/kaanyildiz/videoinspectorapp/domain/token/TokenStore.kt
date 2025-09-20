// app/src/main/java/com/kaanyildiz/videoinspectorapp/domain/token/TokenStore.kt
package com.kaanyildiz.videoinspectorapp.domain.token

interface TokenStore {
    suspend fun save(token: String, role: String, email: String)
    suspend fun token(): String?
    suspend fun role(): String?
    suspend fun email(): String?
    suspend fun clear()
}
