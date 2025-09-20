// app/src/main/java/com/kaanyildiz/videoinspectorapp/data/remote/ApiService.kt
package com.kaanyildiz.videoinspectorapp.data.remote

import com.kaanyildiz.videoinspectorapp.data.remote.model.*
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.*

interface ApiService {

    // --- Auth ---
    @POST("login")
    suspend fun login(@Body body: LoginRequest): Response<LoginResponse>

    @POST("user/role")
    suspend fun userRole(
        @Header("Authorization") bearer: String
    ): Response<RoleResponse>

    @POST("logout")
    suspend fun logout(
        @Header("Authorization") bearer: String
    ): Response<MessageResponse>

    // --- Channels (POST /get-channels) ---
    @POST("get-channels")
    suspend fun getChannels(
        @Header("Authorization") bearer: String
    ): Response<ChannelsResponse>

    // --- Media ---
    // /media/upload : file, type("photo"/"video"), meta(JSON), channel(NUMBER)
    @Multipart
    @POST("media/upload")
    suspend fun uploadMedia(
        @Header("Authorization") bearer: String,
        @Part file: MultipartBody.Part,
        @Part("type") type: RequestBody,     // text/plain -> "photo" | "video"
        @Part("meta") meta: RequestBody,     // application/json
        @Part("channel") channel: RequestBody // text/plain -> "1" (server int'e parse eder)
    ): Response<MessageResponse>

    // /media/list : bekleyen/onaylı liste
    @GET("media/list")
    suspend fun listMedia(
        @Header("Authorization") bearer: String,
        @Query("status") status: String? = null,   // "waiting" | "valid" | "not_valid"
        @Query("email") email: String? = null,
        @Query("channel") channel: Int? = null,
        @Query("page") page: Int? = null,
        @Query("pageSize") pageSize: Int? = null
    ): Response<List<MediaItemDto>>

    @POST("media/{id}/validate")
    suspend fun validate(
        @Header("Authorization") bearer: String,
        @Path("id") id: Long,
        @Body body: ValidateRequest
    ): Response<MessageResponse>

    @POST("media/{id}/comment")
    suspend fun comment(
        @Header("Authorization") bearer: String,
        @Path("id") id: Long,
        @Body body: CommentRequest
    ): Response<MessageResponse>
}
