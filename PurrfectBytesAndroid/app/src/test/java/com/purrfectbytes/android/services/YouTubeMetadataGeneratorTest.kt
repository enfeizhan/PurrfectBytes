package com.purrfectbytes.android.services

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

/** Both models are replaced by stand-ins: these tests never call Gemini or Claude. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class YouTubeMetadataGeneratorTest {

    private val answer = "My Study Journal: Japanese Sentence - \"猫です\" | Reading & Pronunciation\n\n" +
        "📚 Study Journal Entry\n\nToday's sentence.\n\n#Japanese"

    private class FakeClaude(private val reply: (AnthropicRequest) -> AnthropicResponse) : AnthropicService {
        var lastKey: String? = null
        var lastRequest: AnthropicRequest? = null

        override suspend fun generateMessage(apiKey: String, request: AnthropicRequest): AnthropicResponse {
            lastKey = apiKey
            lastRequest = request
            return reply(request)
        }
    }

    private fun claudeAnswering(text: String?) =
        FakeClaude { AnthropicResponse(listOf(AnthropicContent(type = "text", text = text))) }

    private fun generator(
        claude: AnthropicService = claudeAnswering(answer),
        geminiKey: String = "test-gemini-key",
        claudeKey: String = "test-claude-key",
        gemini: suspend (String) -> String? = { answer }
    ) = YouTubeMetadataGenerator(claude, geminiKey, claudeKey, gemini)

    private fun failureOf(block: suspend () -> Unit): MetadataException = runBlocking {
        try {
            block()
            fail("expected a MetadataException")
            throw IllegalStateException()
        } catch (e: MetadataException) {
            e
        }
    }

    @Test
    fun `Gemini's answer becomes title and description`() = runBlocking {
        var prompt = ""
        val (title, description) = generator(gemini = { prompt = it; answer }).generateMetadata("猫です", "gemini")

        assertEquals("My Study Journal: Japanese Sentence - \"猫です\" | Reading & Pronunciation", title)
        assertTrue(description.startsWith("📚 Study Journal Entry"))
        assertTrue(prompt.endsWith("TARGET SENTENCE: 猫です"))
    }

    @Test
    fun `Claude's answer becomes title and description`() = runBlocking {
        val claude = claudeAnswering(answer)
        val (title, description) = generator(claude = claude).generateMetadata("猫です", "anthropic")

        assertEquals("My Study Journal: Japanese Sentence - \"猫です\" | Reading & Pronunciation", title)
        assertTrue(description.endsWith("#Japanese"))
        assertEquals("test-claude-key", claude.lastKey)
        assertEquals("user", claude.lastRequest!!.messages.single().role)
    }

    @Test
    fun `a failure is reported and never returned as a title`() {
        val error = failureOf {
            generator(gemini = { throw IOException("Unable to resolve host") }).generateMetadata("猫です", "gemini")
        }

        assertEquals("Gemini could not be reached: Unable to resolve host", error.message)
    }

    @Test
    fun `an error from Claude is reported in Claude's words`() {
        val body = """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""
            .toResponseBody("application/json".toMediaType())
        val claude = FakeClaude { throw HttpException(Response.error<AnthropicResponse>(401, body)) }

        val error = failureOf { generator(claude = claude).generateMetadata("猫です", "anthropic") }

        assertEquals("Claude answered with an error: invalid x-api-key (HTTP 401)", error.message)
    }

    @Test
    fun `an error without explanation is reported by its number`() {
        val body = "<html>Bad gateway</html>".toResponseBody("text/html".toMediaType())
        val claude = FakeClaude { throw HttpException(Response.error<AnthropicResponse>(502, body)) }

        val error = failureOf { generator(claude = claude).generateMetadata("猫です", "anthropic") }

        assertEquals("Claude answered with an error: HTTP 502", error.message)
    }

    @Test
    fun `a missing key is named`() {
        val gemini = failureOf { generator(geminiKey = "").generateMetadata("猫です", "gemini") }
        val claude = failureOf { generator(claudeKey = " ").generateMetadata("猫です", "anthropic") }

        assertTrue(gemini.message!!.contains("GEMINI_API_KEY"))
        assertTrue(claude.message!!.contains("ANTHROPIC_API_KEY"))
    }

    @Test
    fun `an empty answer is a failure`() {
        assertEquals(
            "Gemini sent an empty answer",
            failureOf { generator(gemini = { "  " }).generateMetadata("猫です", "gemini") }.message
        )
        assertEquals(
            "Claude sent an empty answer",
            failureOf { generator(claude = claudeAnswering(null)).generateMetadata("猫です", "anthropic") }.message
        )
        assertEquals(
            "Claude sent an empty answer",
            failureOf {
                generator(claude = FakeClaude { AnthropicResponse(null) }).generateMetadata("猫です", "anthropic")
            }.message
        )
    }

    // ------------------------------------------------------------ the credit

    private val described = "My Study Journal: Japanese Sentence - \"猫です\" | Reading & Pronunciation\n\n" +
        "📚 Study Journal Entry\n\n📌 Credit:\n\nThis sentence is sourced from another creator's content." +
        "\n\n👍 Like and subscribe!\n\n#Japanese"

    @Test
    fun `the credit of the source stands in the description`() = runBlocking {
        val (title, description) = generator(gemini = { described })
            .generateMetadata("猫です", "gemini", credit = "From 新完全マスター N1 by 3A Corporation.")

        assertEquals("My Study Journal: Japanese Sentence - \"猫です\" | Reading & Pronunciation", title)
        assertEquals(
            "📚 Study Journal Entry\n\n📌 Credit:\n\nFrom 新完全マスター N1 by 3A Corporation." +
                "\n\n👍 Like and subscribe!\n\n#Japanese",
            description
        )
    }

    @Test
    fun `without a source the credit is the one the model wrote`() = runBlocking {
        val (_, plain) = generator(gemini = { described }).generateMetadata("猫です", "gemini")
        val (_, blank) = generator(gemini = { described }).generateMetadata("猫です", "gemini", credit = "  ")

        assertTrue(plain.contains("sourced from another creator's content"))
        assertEquals(plain, blank)
    }

    @Test
    fun `the model is not told of the credit`() = runBlocking {
        var prompt = ""
        generator(gemini = { prompt = it; described })
            .generateMetadata("猫です", "gemini", credit = "From my secret textbook.")

        assertEquals(YouTubeMetadataFormat.prompt("猫です"), prompt)
    }

    // ------------------------------------------------------------- the items

    private val found = """```json
[{"kind": "vocabulary", "term": "猫", "phonetics": "ねこ", "meaning": "cat"},
 {"kind": "grammar", "term": "です", "phonetics": "", "meaning": "polite copula"}]
```"""

    private val cat = StudyItem(isGrammar = false, term = "猫", phonetics = "ねこ", meaning = "cat")
    private val copula = StudyItem(isGrammar = true, term = "です", phonetics = "", meaning = "polite copula")

    @Test
    fun `Gemini finds the items of a sentence`() = runBlocking {
        var prompt = ""
        val items = generator(gemini = { prompt = it; found }).extractItems(" 猫です\n", "gemini")

        assertEquals(listOf(cat, copula), items)
        assertEquals(YouTubeMetadataFormat.extractionPrompt("猫です"), prompt)
    }

    @Test
    fun `Claude finds the items of a sentence`() = runBlocking {
        val claude = claudeAnswering(found)

        val items = generator(claude = claude).extractItems("猫です", "anthropic")

        assertEquals(listOf(cat, copula), items)
        assertEquals(YouTubeMetadataFormat.extractionPrompt("猫です"), claude.lastRequest!!.messages.single().content)
    }

    @Test
    fun `the items that were approved are all the description explains`() = runBlocking {
        var prompt = ""
        generator(gemini = { prompt = it; answer }).generateMetadata("猫です", "gemini", items = listOf(copula))

        assertEquals(YouTubeMetadataFormat.prompt("猫です", listOf(copula)), prompt)
        assertTrue(prompt.contains("Approved grammar items:\n- です: polite copula"))
        assertTrue(prompt.endsWith("TARGET SENTENCE: 猫です"))
    }

    @Test
    fun `an answer that lists no items is a failure`() {
        assertEquals(
            "Could not extract items - the AI response had no item list",
            failureOf { generator(gemini = { "I cannot help with that." }).extractItems("猫です", "gemini") }.message
        )
        assertEquals(
            "Could not extract items - try again or use a different provider",
            failureOf { generator(gemini = { "[]" }).extractItems("猫です", "gemini") }.message
        )
    }

    @Test
    fun `a model that cannot be asked for items says why`() {
        assertEquals(
            "Gemini could not be reached: Unable to resolve host",
            failureOf {
                generator(gemini = { throw IOException("Unable to resolve host") }).extractItems("猫です", "gemini")
            }.message
        )
        assertTrue(
            failureOf { generator(claudeKey = "").extractItems("猫です", "anthropic") }
                .message!!.contains("ANTHROPIC_API_KEY")
        )
    }

    @Test
    fun `an answer without description is a failure`() {
        val error = failureOf { generator(gemini = { "Only one line" }).generateMetadata("猫です", "gemini") }

        assertEquals("Gemini answered, but without a title and a description", error.message)
    }
}
