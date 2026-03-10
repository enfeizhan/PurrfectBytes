package com.purrfectbytes.android.data.remote

import retrofit2.Response
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST

data class ConversionResponse(
    val success: Boolean,
    val audio_filename: String? = null,
    val video_filename: String? = null,
    val audio_url: String? = null,
    val video_url: String? = null,
    val duration: Double? = null,
    val message: String? = null,
    val error: String? = null
)

data class MetadataResponse(
    val success: Boolean,
    val title: String? = null,
    val description: String? = null,
    val error: String? = null
)

data class UploadResponse(
    val success: Boolean,
    val video_id: String? = null,
    val video_url: String? = null,
    val message: String? = null,
    val error: String? = null
)

data class AuthStatusResponse(
    val success: Boolean,
    val configured: Boolean,
    val authenticated: Boolean,
    val error: String? = null
)

data class AuthUrlResponse(
    val success: Boolean,
    val auth_url: String? = null,
    val error: String? = null
)

interface PurrfectBytesApi {

    @retrofit2.http.GET("/youtube/auth-status")
    suspend fun getAuthStatus(): Response<AuthStatusResponse>

    @retrofit2.http.GET("/youtube/auth-url")
    suspend fun getAuthUrl(): Response<AuthUrlResponse>

    @FormUrlEncoded
    @POST("/convert-to-video")
    suspend fun convertToVideo(
        @Field("text") text: String,
        @Field("language") language: String,
        @Field("slow") slow: Boolean,
        @Field("repetitions") repetitions: Int,
        @Field("engine") engine: String = "edge"
    ): Response<ConversionResponse>

    @FormUrlEncoded
    @POST("/generate-youtube-metadata")
    suspend fun generateYouTubeMetadata(
        @Field("text") text: String,
        @Field("provider") provider: String = "gemini"
    ): Response<MetadataResponse>

    @FormUrlEncoded
    @POST("/youtube/upload")
    suspend fun uploadToYouTube(
        @Field("video_filename") videoFilename: String,
        @Field("title") title: String,
        @Field("description") description: String = "",
        @Field("tags") tags: String = "",
        @Field("playlist_id") playlistId: String = "",
        @Field("privacy_status") privacyStatus: String = "public"
    ): Response<UploadResponse>
}
