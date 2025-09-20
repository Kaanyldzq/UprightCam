// data/token/TokenStoreImpl.kt
package com.kaanyildiz.videoinspectorapp.data.token

import android.content.Context
import androidx.core.content.edit
import com.kaanyildiz.videoinspectorapp.domain.token.TokenStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TokenStoreImpl @Inject constructor(
    @ApplicationContext private val ctx: Context
) : TokenStore {
    private val sp = ctx.getSharedPreferences("auth", Context.MODE_PRIVATE)
    override suspend fun save(token: String, role: String, email: String) {
        sp.edit {
            putString("token", token)
            putString("role", role)
            putString("email", email)
        }
    }
    override suspend fun clear() { sp.edit { clear() } }
    override suspend fun token() = sp.getString("token", null)
    override suspend fun role() = sp.getString("role", null)
    override suspend fun email() = sp.getString("email", null)
}
