package com.purrfectbytes.android.services

import com.google.gson.annotations.SerializedName
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST

interface AnthropicService {
    @Headers("anthropic-version: 2023-06-01", "Content-Type: application/json")
    @POST("v1/messages")
    suspend fun generateMessage(
        @Header("x-api-key") apiKey: String,
        @Body request: AnthropicRequest
    ): AnthropicResponse
}

data class AnthropicRequest(
    val model: String,
    @SerializedName("max_tokens") val maxTokens: Int,
    val messages: List<AnthropicMessage>
)

data class AnthropicMessage(
    val role: String,
    val content: String
)

data class AnthropicResponse(
    val content: List<AnthropicContent>
)

data class AnthropicContent(
    val type: String,
    val text: String
)
