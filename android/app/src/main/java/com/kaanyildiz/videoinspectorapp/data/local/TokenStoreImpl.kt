// app/src/main/java/com/kaanyildiz/videoinspectorapp/data/local/TokenStoreImpl.kt
package com.kaanyildiz.videoinspectorapp.data.local

import android.content.Context
import com.kaanyildiz.videoinspectorapp.domain.token.TokenStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TokenStoreImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : TokenStore {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override suspend fun save(token: String, role: String, email: String) = withContext(Dispatchers.IO) {
        prefs.edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_ROLE, role)
            .putString(KEY_EMAIL, email)
            .apply()
    }

    override suspend fun token(): String? = withContext(Dispatchers.IO) {
        prefs.getString(KEY_TOKEN, null)
    }

    override suspend fun role(): String? = withContext(Dispatchers.IO) {
        prefs.getString(KEY_ROLE, null)
    }

    override suspend fun email(): String? = withContext(Dispatchers.IO) {
        prefs.getString(KEY_EMAIL, null)
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        prefs.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_ROLE)
            .remove(KEY_EMAIL)
            .apply()
    }

    private companion object {
        private const val PREFS_NAME = "auth_prefs"
        private const val KEY_TOKEN = "auth_token"
        private const val KEY_ROLE = "auth_role"
        private const val KEY_EMAIL = "auth_email"
    }
}
