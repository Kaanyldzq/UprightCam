// app/src/main/java/com/kaanyildiz/videoinspectorapp/data/repo/AuthRepositoryImpl.kt
package com.kaanyildiz.videoinspectorapp.data.repo

import com.kaanyildiz.videoinspectorapp.data.remote.ApiService
import com.kaanyildiz.videoinspectorapp.data.remote.model.LoginRequest
import com.kaanyildiz.videoinspectorapp.domain.repo.AuthRepository
import com.kaanyildiz.videoinspectorapp.domain.token.TokenStore
import javax.inject.Inject

class AuthRepositoryImpl @Inject constructor(
    private val api: ApiService,
    private val tokenStore: TokenStore
) : AuthRepository {

    override suspend fun login(email: String, password: String): String {
        val response = api.login(LoginRequest(email = email, password = password))

        if (!response.isSuccessful) {
            throw IllegalStateException("Giriş başarısız: ${response.code()}")
        }

        val body = response.body()
            ?: throw IllegalStateException("Sunucu beklenmedik yanıt döndü (body null)")

        // role/email null gelebilir: güvenli normalize et
        val roleNorm = body.role?.trim()?.lowercase() ?: ""
        val emailNorm = body.email?.trim().orEmpty()

        // Token'ı kaydet
        tokenStore.save(body.token, roleNorm, emailNorm)

        return body.token
    }

    override suspend fun logout(token: String): String {
        // Parametrede gelen token boşsa, store'dan dene
        val tkn = token.takeIf { it.isNotBlank() } ?: (tokenStore.token() ?: "")

        val resp = if (tkn.isNotBlank()) {
            try {
                api.logout("Bearer $tkn")
            } catch (_: Exception) {
                null // offline/timeout vs. — local logout'a düşeceğiz
            }
        } else null

        return if (resp == null || resp.isSuccessful || resp.code() == 401 || resp.code() == 404) {
            tokenStore.clear()
            "Çıkış başarılı."
        } else {
            // İstersen bu durumda da clear edip yine başarılı sayabilirsin
            tokenStore.clear()
            "Çıkış başarısız: ${resp.code()}"
        }
    }

    override suspend fun role(token: String): String {
        return tokenStore.role().orEmpty()
    }
}
