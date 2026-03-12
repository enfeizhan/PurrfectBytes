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
class YouTubeMetadataGenerator @Inject constructor() {

    private val generativeModel = GenerativeModel(
        modelName = "gemini-2.0-flash",
        apiKey = BuildConfig.GEMINI_API_KEY
    )

    suspend fun generateMetadata(text: String): Pair<String, String> {
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

                val response = generativeModel.generateContent(
                    content {
                        text(prompt)
                    }
                )

                val responseText = response.text ?: throw Exception("Empty response from Gemini")
                
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
}
