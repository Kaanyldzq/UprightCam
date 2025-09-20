// app/src/main/java/com/kaanyildiz/videoinspectorapp/presentation/inspector/InspectorListViewModel.kt
package com.kaanyildiz.videoinspectorapp.presentation.inspector

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kaanyildiz.videoinspectorapp.data.remote.ApiService
import com.kaanyildiz.videoinspectorapp.data.remote.model.MediaItemDto
import com.kaanyildiz.videoinspectorapp.domain.token.TokenStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class InspectorListState(
    val loading: Boolean = false,
    val items: List<MediaItemDto> = emptyList(),
    val error: String? = null,
    val lastStatus: String? = null
)

@HiltViewModel
class InspectorListViewModel @Inject constructor(
    private val api: ApiService,
    private val tokenStore: TokenStore
) : ViewModel() {

    val state = MutableStateFlow(InspectorListState())

    fun load(status: String?) {
        state.update { it.copy(loading = true, error = null, lastStatus = status) }
        viewModelScope.launch {
            val token = tokenStore.token()
            if (token == null) {
                state.update { it.copy(loading = false, error = "Token bulunamadı") }
                return@launch
            }
            val resp = api.listMedia(
                bearer = "Bearer $token",
                status = status,
                channel = null, // istersen 1 verip kanala göre filtrele
                page = 1,
                pageSize = 100
            )
            if (resp.isSuccessful) {
                state.update { it.copy(loading = false, items = resp.body().orEmpty()) }
            } else {
                state.update { it.copy(loading = false, error = "Liste alınamadı (${resp.code()})") }
            }
        }
    }

    fun refresh() = load(state.value.lastStatus)
}
