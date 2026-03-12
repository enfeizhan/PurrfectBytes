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

    companion object {
        private const val YOUTUBE_PROMPT_TEMPLATE = """STRICT OPERATING MODE: Generate the requested content and immediately stop. Do not include any conversational filler, follow-up suggestions, or questions. Any text following the hashtags is a violation of this instruction.

You are a YouTube content creator helping generate titles and descriptions for language learning videos. The videos feature a sentence with synchronized audio and character-by-character highlighting for pronunciation practice.

IMPORTANT RULES:

ALL explanations, descriptions, breakdowns, and grammar points MUST be written in English, regardless of the target sentence language.

NEVER ask follow-up questions - generate the complete output immediately based on the given sentence.

Use SINGLE asterisks (text) for bold formatting, never double asterisks.

Identify the language automatically.

CRITICAL: Keep the title under 100 characters (strict limit).

Provide accurate romanization (if applicable: Japanese→Romaji, Korean→Romanization, Chinese→Pinyin, etc.).

TRANSLATION RULE: If the target sentence is NOT English, you MUST include the "English Translation" section. If the target sentence IS English, you MUST DELETE the "English Translation" section entirely.

MANDATORY FORMATTING for Breakdowns/Grammar: You must start with the [Original Script], followed by the [Romanization or IPA Phonetics] in parentheses, then the English meaning.

Example for English Breakdown: Word (IPA Phonetics) = English Meaning.

Break down the sentence into meaningful components (explanations in English).

Highlight 2-4 key grammar points (explanations in English).

Match the proficiency level appropriately (beginner/intermediate/advanced).

Use natural, encouraging tone.

Include relevant hashtags for the specific language.

Terminate the response immediately after the final hashtag. Do not include any text, sign-offs, or questions after the hashtags.

Given a target sentence, generate:

TITLE (following this format - MUST be under 100 characters, but don't output TITLE):

My Study Journal: [LANGUAGE] Sentence - "[TARGET_SENTENCE]" | Reading & Pronunciation

DESCRIPTION with these sections (don't output DESCRIPTION):

📚 Study Journal Entry

[Brief intro about learning this sentence today - MUST be in English]

📝 Today's Sentence:

[TARGET_SENTENCE in original language]

([Romanization/IPA if applicable])

📖 English Translation:[ONLY include this section if the target language is NOT English. If English, remove this entire section]

"[Translation in English]"

🔤 Breakdown:

• [Original Script] ([Romanization/IPA]) = [Meaning in English]

• [Original Script] ([Romanization/IPA]) = [Meaning in English]

📚 Grammar Points:

• [Original Script] ([Romanization/IPA]) - [Explanation in English]

• [Original Script] ([Romanization/IPA]) - [Explanation in English]

🎯 Perfect for:

• [Proficiency level] learners

• [Learning goal 1]

• [Learning goal 2]

💡 Study Tip:

[Helpful context or usage note about this sentence - in English]

📌 Credit:

This sentence is sourced from another creator's content. All credit goes to the original author.

👍 Enjoyed this study session? Please give it a thumbs up!

🔔 Subscribe to follow my language learning journey and practice together!

☕ Want to support more learning content? Scan the QR code (bottom-left corner)—my cat thanks you! 😺

#[LanguageLearning] #[NativeLanguageName] #Learn[Language] #[Language]Language #[NativeStudyHashtag] #[ProficiencyTest] #[Language]Practice #Study[Language] #[Language]Grammar #LanguageLearning

Final Output Check: Ensure the last sentence of the response is not a question.

TARGET SENTENCE: {sentence}"""
    }

    private val generativeModel = GenerativeModel(
        modelName = "gemini-2.5-flash",
        apiKey = BuildConfig.GEMINI_API_KEY
    )

    suspend fun generateMetadata(text: String, provider: String = "gemini"): Pair<String, String> {
        return withContext(Dispatchers.IO) {
            try {
                val prompt = YOUTUBE_PROMPT_TEMPLATE.replace("{sentence}", text)

                val responseText = if (provider == "anthropic") {
                    generateAnthropicMetadata(prompt)
                } else {
                    generateGeminiMetadata(prompt)
                }
                
                parseResponse(responseText)
            } catch (e: Exception) {
                Pair("Error Generated Title", "Could not generate description: ${e.message}")
            }
        }
    }

    private fun parseResponse(rawText: String): Pair<String, String> {
        val lines = rawText.trim().split("\n")
        
        // Find first non-empty line as title
        var title = ""
        var descriptionStart = 0
        for (i in lines.indices) {
            val stripped = lines[i].trim()
            if (stripped.isNotEmpty()) {
                title = stripped
                descriptionStart = i + 1
                break
            }
        }

        // Rest is description
        val descriptionLines = if (descriptionStart < lines.size) {
            lines.subList(descriptionStart, lines.size)
        } else {
            emptyList()
        }
        val description = descriptionLines.joinToString("\n").trim()

        // Clean up title
        for (prefix in listOf("TITLE:", "Title:", "title:")) {
            if (title.startsWith(prefix, ignoreCase = true)) {
                title = title.substring(prefix.length).trim()
            }
        }

        // Reconstruct title to guarantee format
        var language = "Language"
        val langPattern = java.util.regex.Pattern.compile("My Study Journal:\\s*([a-zA-Z\\s]+?)(?:\\s*Sentence)?\\s*-", java.util.regex.Pattern.CASE_INSENSITIVE)
        val langMatcher = langPattern.matcher(title)
        if (langMatcher.find()) {
            language = langMatcher.group(1)?.trim() ?: "Language"
            if (language.lowercase().endsWith(" sentence")) {
                language = language.substring(0, language.length - 9).trim()
            }
        }

        var sentence = "..."
        val sentencePattern = java.util.regex.Pattern.compile("\"(.*?)\"")
        val sentenceMatcher = sentencePattern.matcher(title)
        if (sentenceMatcher.find()) {
            sentence = sentenceMatcher.group(1)?.trim() ?: "..."
        } else {
            val fallbackPattern = java.util.regex.Pattern.compile("-\\s*(.*?)\\s*\\|")
            val fallbackMatcher = fallbackPattern.matcher(title)
            if (fallbackMatcher.find()) {
                sentence = fallbackMatcher.group(1)?.trim() ?: "..."
            }
        }

        val prefix = "My Study Journal: $language Sentence - \""
        val suffix = "\" | Reading & Pronunciation"
        
        var finalTitle = if (prefix.length + sentence.length + suffix.length > 100) {
            val allowedLen = 100 - prefix.length - suffix.length - 3
            if (allowedLen > 0) {
                prefix + sentence.take(allowedLen).trim() + "..." + suffix
            } else {
                (prefix + sentence + suffix).take(97) + "..."
            }
        } else {
            prefix + sentence + suffix
        }

        return Pair(finalTitle, description)
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
            maxTokens = 2048,
            messages = listOf(AnthropicMessage(role = "user", content = prompt))
        )
        val response = anthropicService.generateMessage(
            apiKey = BuildConfig.ANTHROPIC_API_KEY,
            request = request
        )
        return response.content.firstOrNull()?.text ?: throw Exception("Empty response from Anthropic")
    }
}
