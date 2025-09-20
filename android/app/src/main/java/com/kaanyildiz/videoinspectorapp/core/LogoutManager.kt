// com/kaanyildiz/videoinspectorapp/core/LogoutManager.kt
package com.kaanyildiz.videoinspectorapp.core

import com.kaanyildiz.videoinspectorapp.data.remote.ApiService
import com.kaanyildiz.videoinspectorapp.domain.token.TokenStore
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LogoutManager @Inject constructor(
    private val api: ApiService,
    private val tokenStore: TokenStore
) {
    /**
     * Token yoksa da başarılı sayar.
     * Çevrimdışı/401/404/timeout gelse bile local logout yapar.
     */
    suspend fun logoutSilently(isOnline: Boolean) {
        val token = tokenStore.token()
        if (isOnline && !token.isNullOrBlank()) {
            // 🔑 Header’ı gönder
            runCatching { api.logout("Bearer $token") }
        }
        tokenStore.clear()
    }
}
