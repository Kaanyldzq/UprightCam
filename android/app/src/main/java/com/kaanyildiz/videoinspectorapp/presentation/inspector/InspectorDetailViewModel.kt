// app/src/main/java/com/kaanyildiz/videoinspectorapp/presentation/inspector/InspectorDetailViewModel.kt
package com.kaanyildiz.videoinspectorapp.presentation.inspector

import androidx.lifecycle.ViewModel
import com.kaanyildiz.videoinspectorapp.data.remote.ApiService
import com.kaanyildiz.videoinspectorapp.data.remote.model.CommentRequest
import com.kaanyildiz.videoinspectorapp.data.remote.model.ValidateRequest
import com.kaanyildiz.videoinspectorapp.domain.token.TokenStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class InspectorDetailViewModel @Inject constructor(
    private val api: ApiService,
    private val tokenStore: TokenStore
) : ViewModel() {

    suspend fun validate(id: Long, valid: Boolean): Boolean {
        val token = tokenStore.token() ?: return false
        val status = if (valid) "valid" else "not_valid"
        val resp = api.validate("Bearer $token", id, ValidateRequest(status = status))
        return resp.isSuccessful
    }

    suspend fun comment(id: Long, text: String): Boolean {
        val token = tokenStore.token() ?: return false
        val resp = api.comment("Bearer $token", id, CommentRequest(comment = text))
        return resp.isSuccessful
    }
}
