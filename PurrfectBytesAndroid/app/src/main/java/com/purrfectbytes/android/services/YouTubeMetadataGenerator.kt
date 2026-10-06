package com.purrfectbytes.android.services

import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.google.gson.JsonParser
import com.purrfectbytes.android.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

/** The title and description could not be written; the message says why. */
class MetadataException(message: String, cause: Throwable? = null) : Exception(message, cause)

@Singleton
class YouTubeMetadataGenerator internal constructor(
    private val anthropicService: AnthropicService,
    private val geminiApiKey: String,
    private val anthropicApiKey: String,
    /** Sends a prompt to Gemini and returns the answer. Replaced in tests. */
    private val askGemini: (suspend (String) -> String?)? = null
) {

    @Inject
    constructor(anthropicService: AnthropicService) : this(
        anthropicService = anthropicService,
        geminiApiKey = BuildConfig.GEMINI_API_KEY,
        anthropicApiKey = BuildConfig.ANTHROPIC_API_KEY
    )

    companion object {
        private const val TAG = "YouTubeMetadata"
        const val PROVIDER_GEMINI = "gemini"
        const val PROVIDER_ANTHROPIC = "anthropic"

        private const val GEMINI_MODEL = "gemini-3.5-flash"
        private const val ANTHROPIC_MODEL = "claude-sonnet-4-5-20250929"

        /** A full description takes a model well under a minute; this only stops a request that hangs. */
        private const val TIMEOUT_MILLIS = 120_000L
    }

    private val generativeModel by lazy {
        GenerativeModel(
            modelName = GEMINI_MODEL,
            apiKey = geminiApiKey
        )
    }

    /**
     * Writes a title and a description for a video of [text].
     *
     * With [items], the sections that explain the sentence hold exactly those words and
     * grammar points: the ones the user kept of what [extractItems] found. [credit] names
     * the source of the text; it is put into the description word for word, in place of
     * the general credit the model writes.
     *
     * Throws [MetadataException] when that fails, so a failure can never be mistaken
     * for a title.
     */
    suspend fun generateMetadata(
        text: String,
        provider: String = PROVIDER_GEMINI,
        credit: String? = null,
        items: List<StudyItem>? = null
    ): Pair<String, String> {
        return withContext(Dispatchers.IO) {
            val responseText = ask(YouTubeMetadataFormat.prompt(text, items), provider)

            val (title, description) = YouTubeMetadataFormat.parse(responseText, text)
            if (title.isBlank() || description.isBlank()) {
                throw MetadataException("${nameOf(provider)} answered, but without a title and a description")
            }
            title to if (credit.isNullOrBlank()) description else YouTubeMetadataFormat.applyCredit(description, credit)
        }
    }

    /**
     * Finds the words and grammar points of [text] that a learner may want explained,
     * for the user to choose from. Throws [MetadataException] when that fails.
     */
    suspend fun extractItems(text: String, provider: String = PROVIDER_GEMINI): List<StudyItem> {
        return withContext(Dispatchers.IO) {
            YouTubeMetadataFormat.parseItems(ask(YouTubeMetadataFormat.extractionPrompt(text), provider))
        }
    }

    private fun nameOf(provider: String): String = if (provider == PROVIDER_ANTHROPIC) "Claude" else "Gemini"

    /** Sends [prompt] to the model of [provider] and returns what it answers. */
    private suspend fun ask(prompt: String, provider: String): String {
        val providerName = nameOf(provider)
        return try {
            withTimeoutOrNull(TIMEOUT_MILLIS) {
                if (provider == PROVIDER_ANTHROPIC) {
                    generateAnthropicMetadata(prompt)
                } else {
                    generateGeminiMetadata(prompt)
                }
            } ?: throw MetadataException("$providerName did not answer within ${TIMEOUT_MILLIS / 1000} seconds")
        } catch (e: CancellationException) {
            throw e
        } catch (e: MetadataException) {
            throw e
        } catch (e: HttpException) {
            Log.e(TAG, "$providerName refused the request", e)
            throw MetadataException("$providerName answered with an error: ${describe(e)}", e)
        } catch (e: Exception) {
            Log.e(TAG, "$providerName request failed", e)
            throw MetadataException("$providerName could not be reached: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    private suspend fun generateGeminiMetadata(prompt: String): String {
        if (geminiApiKey.isBlank()) throw missingKey("GEMINI_API_KEY")

        val answer = if (askGemini != null) {
            askGemini.invoke(prompt)
        } else {
            generativeModel.generateContent(
                content {
                    text(prompt)
                }
            ).text
        }
        return answer?.takeIf { it.isNotBlank() }
            ?: throw MetadataException("Gemini sent an empty answer")
    }

    private suspend fun generateAnthropicMetadata(prompt: String): String {
        if (anthropicApiKey.isBlank()) throw missingKey("ANTHROPIC_API_KEY")

        val request = AnthropicRequest(
            model = ANTHROPIC_MODEL,
            maxTokens = 2048,
            messages = listOf(AnthropicMessage(role = "user", content = prompt))
        )
        val response = anthropicService.generateMessage(
            apiKey = anthropicApiKey,
            request = request
        )
        return response.content?.firstOrNull { it.type == "text" }?.text?.takeIf { it.isNotBlank() }
            ?: throw MetadataException("Claude sent an empty answer")
    }

    private fun missingKey(name: String) = MetadataException(
        "This build has no $name. Add it to local.properties and build the app again."
    )

    /** The error in the service's own words, e.g. "invalid x-api-key", with the HTTP status. */
    private fun describe(e: HttpException): String {
        val detail = try {
            e.response()?.errorBody()?.string()
                ?.let { JsonParser.parseString(it).asJsonObject.getAsJsonObject("error").get("message").asString }
        } catch (parsing: Exception) {
            null
        }
        return if (detail.isNullOrBlank()) "HTTP ${e.code()}" else "$detail (HTTP ${e.code()})"
    }
}
