package com.purrfectbytes.android.data.api

import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

interface PurrfectBytesApi {
    
    @POST("auth/login")
    suspend fun login(
        @Body request: LoginRequest
    ): Response<LoginResponse>
    
    @POST("auth/register")
    suspend fun register(
        @Body request: RegisterRequest
    ): Response<RegisterResponse>
    
    @Multipart
    @POST("convert")
    suspend fun convertTextToSpeech(
        @Part("text") text: RequestBody,
        @Part("language") language: RequestBody,
        @Part("slow") slow: RequestBody
    ): Response<TTSResponse>
    
    @GET("download/{filename}")
    suspend fun downloadAudio(
        @Path("filename") filename: String
    ): Response<ResponseBody>
    
    @Multipart
    @POST("create_video")
    suspend fun createVideo(
        @Part("text") text: RequestBody,
        @Part("language") language: RequestBody,
        @Part("video_type") videoType: RequestBody,
        @Part("duration") duration: RequestBody,
        @Part("background_color") backgroundColor: RequestBody,
        @Part("text_color") textColor: RequestBody,
        @Part("font_size") fontSize: RequestBody
    ): Response<VideoResponse>
    
    @GET("download_video/{filename}")
    suspend fun downloadVideo(
        @Path("filename") filename: String
    ): Response<ResponseBody>
    
    @GET("user/history")
    suspend fun getUserHistory(
        @Header("Authorization") token: String
    ): Response<List<HistoryItem>>
    
    @Multipart
    @POST("transcribe")
    suspend fun transcribeAudio(
        @Part audio: MultipartBody.Part
    ): Response<TranscriptionResponse>
}

// Data classes for API requests and responses
data class LoginRequest(
    val email: String,
    val password: String
)

data class LoginResponse(
    val token: String,
    val user: User
)

data class RegisterRequest(
    val email: String,
    val password: String,
    val name: String
)

data class RegisterResponse(
    val token: String,
    val user: User
)

data class User(
    val id: String,
    val email: String,
    val name: String
)

data class TTSResponse(
    val success: Boolean,
    val filename: String,
    val download_url: String
)

data class VideoResponse(
    val success: Boolean,
    val filename: String,
    val download_url: String
)

data class HistoryItem(
    val id: String,
    val type: String,
    val text: String,
    val language: String,
    val timestamp: String,
    val filename: String?
)

data class TranscriptionResponse(
    val success: Boolean,
    val text: String,
    val language: String?
)