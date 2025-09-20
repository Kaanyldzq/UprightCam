// app/src/main/java/com/kaanyildiz/videoinspectorapp/presentation/videos/VideoListViewModel.kt
package com.kaanyildiz.videoinspectorapp.presentation.videos

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kaanyildiz.videoinspectorapp.data.remote.model.VideoItemDto
import com.kaanyildiz.videoinspectorapp.data.repository.VideoRepository
import com.kaanyildiz.videoinspectorapp.domain.token.TokenStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class VideoListViewModel @Inject constructor(
    private val repo: VideoRepository,
    private val tokenStore: TokenStore
) : ViewModel() {

    private val _videos = MutableLiveData<List<VideoItemDto>>()
    val videos: LiveData<List<VideoItemDto>> = _videos

    fun fetchVideos(
        channels: List<Int> = emptyList(),
        from: String? = null,
        to: String? = null,
        email: String? = null,
        status: String? = null,   // "" gelirse null'a çeviriyoruz
        page: Int = 1,
        pageSize: Int = 20
    ) {
        viewModelScope.launch {
            try {
                val token = tokenStore.token()
                if (token.isNullOrBlank()) {
                    _videos.postValue(emptyList())
                    return@launch
                }

                val list = repo.loadVideos(
                    channels = channels,
                    from = from,
                    to = to,
                    email = email,
                    status = status?.ifBlank { null },
                    page = page,
                    pageSize = pageSize
                )
                _videos.postValue(list)
            } catch (e: Exception) {
                e.printStackTrace()
                _videos.postValue(emptyList())
            }
        }
    }
}
