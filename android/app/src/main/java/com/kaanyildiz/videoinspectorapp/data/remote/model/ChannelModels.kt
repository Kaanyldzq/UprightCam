// app/src/main/java/com/kaanyildiz/videoinspectorapp/data/remote/model/ChannelModels.kt
package com.kaanyildiz.videoinspectorapp.data.remote.model

data class ChannelDto(
    val id: Long,
    val name: String,
    val status: String // "active" | "passive"
)

data class ChannelsResponse(
    val data: List<ChannelDto>
)
