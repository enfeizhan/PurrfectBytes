package com.purrfectbytes.android.services

import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.purrfectbytes.android.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class YouTubeMetadataGenerator @Inject constructor(
    private val anthropicService: AnthropicService
) {

    private val generativeModel = GenerativeModel(
        modelName = "gemini-2.5-flash",
        apiKey = BuildConfig.GEMINI_API_KEY
    )

    suspend fun generateMetadata(text: String, provider: String = "gemini"): Pair<String, String> {
        return withContext(Dispatchers.IO) {
            try {
                val prompt = """
                    You are a YouTube SEO expert. I have a script/text for a short video.
                    Generate an engaging, click-worthy YouTube title and a detailed description with relevant hashtags for this text.
                    
                    The text:
                    "$text"
                    
                    Format the output exactly as JSON:
                    {
                        "title": "Your Generated Title Here",
                        "description": "Your Generated Description Here"
                    }
                """.trimIndent()

                val responseText = if (provider == "anthropic") {
                    generateAnthropicMetadata(prompt)
                } else {
                    generateGeminiMetadata(prompt)
                }
                
                // Extract JSON from response (handling potential markdown formatting)
                val jsonString = responseText.substringAfter("{").substringBeforeLast("}")
                val json = JSONObject("{$jsonString}")
                
                val title = json.getString("title")
                val description = json.getString("description")
                
                Pair(title, description)
            } catch (e: Exception) {
                Pair("Error Generated Title", "Could not generate description: ${e.message}")
            }
        }
    }

    private suspend fun generateGeminiMetadata(prompt: String): String {
        val response = generativeModel.generateContent(
            content {
                text(prompt)
            }
        )
        return response.text ?: throw Exception("Empty response from Gemini")
    }

    private suspend fun generateAnthropicMetadata(prompt: String): String {
        val request = AnthropicRequest(
            model = "claude-3-haiku-20240307",
            maxTokens = 1024,
            messages = listOf(AnthropicMessage(role = "user", content = prompt))
        )
        val response = anthropicService.generateMessage(
            apiKey = BuildConfig.ANTHROPIC_API_KEY,
            request = request
        )
        return response.content.firstOrNull()?.text ?: throw Exception("Empty response from Anthropic")
    }
}
