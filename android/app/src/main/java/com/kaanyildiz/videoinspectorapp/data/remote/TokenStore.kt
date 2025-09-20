package com.kaanyildiz.videoinspectorapp.data.remote

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TokenStore @Inject constructor(@ApplicationContext context: Context) {
    private val sp = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
    fun save(token: String) = sp.edit().putString("token", token).apply()
    fun token(): String? = sp.getString("token", null)
    fun clear() = sp.edit().remove("token").apply()
}