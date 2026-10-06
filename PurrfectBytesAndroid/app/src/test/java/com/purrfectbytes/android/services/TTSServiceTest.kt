package com.purrfectbytes.android.services

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Collections

/**
 * Speech through the service as the app uses it, with what it keeps of earlier speech.
 * Microsoft's service is a stand-in on this computer: nothing here reaches the internet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TTSServiceTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var server: MockWebServer

    /** What the app asked the service to read, request by request. */
    private val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private val service by lazy {
        TTSService(
            context = context,
            storage = MediaStorage(context),
            edgeTTSEngine = EdgeTTSEngine(
                client = EdgeTTSEngine.defaultClient(),
                serviceUrl = server.url("/edge/v1?TrustedClientToken=test").toString(),
                idleTimeoutMillis = 5_000
            ),
            cache = SpeechCache(File(folder.root, "tts_cache"))
        )
    }

    /** Answers the next request for speech with [audio] and the time of one word. */
    private fun answerWith(vararg audio: Byte) {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (!text.contains("Path:ssml")) return
                    requests += text
                    if (audio.isNotEmpty()) {
                        webSocket.send(EdgeTtsProtocolTest.audioMessage(audio).toByteString())
                        webSocket.send(
                            "X-RequestId:abc\r\nContent-Type:application/json; charset=utf-8\r\n" +
                                "Path:audio.metadata\r\n\r\n" +
                                """{"Metadata":[{"Type":"WordBoundary","Data":{"Offset":1000000,"Duration":5000000,""" +
                                """"text":{"Text":"Hello","Length":5,"BoundaryType":"WordBoundary"}}}]}"""
                        )
                    }
                    webSocket.send("X-RequestId:abc\r\nPath:turn.end\r\n\r\n{}")
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }
            })
        )
    }

    @Test
    fun `speech that was generated before is not asked for again`() = runBlocking {
        answerWith(1, 2, 3)

        val first = service.generateAudio("Hello", "en").getOrThrow()
        val second = service.generateAudio("Hello", "en").getOrThrow()

        assertEquals(1, requests.size)
        assertNotEquals(first.file, second.file)
        assertArrayEquals(byteArrayOf(1, 2, 3), first.file.readBytes())
        assertArrayEquals(byteArrayOf(1, 2, 3), second.file.readBytes())
        assertEquals(listOf(WordBoundary("Hello", 0.1, 0.6)), first.wordBoundaries)
        assertEquals(first.wordBoundaries, second.wordBoundaries)
    }

    @Test
    fun `the speech that is kept outlives the files that were handed out`() = runBlocking {
        answerWith(1, 2, 3)
        val storage = MediaStorage(context)

        storage.discard(service.generateAudio("Hello", "en").getOrThrow().file)
        storage.removeLeftovers(olderThan = System.currentTimeMillis() + 60_000)
        val again = service.generateAudio("Hello", "en").getOrThrow()

        assertEquals(1, requests.size)
        assertArrayEquals(byteArrayOf(1, 2, 3), again.file.readBytes())
    }

    @Test
    fun `another text, voice, speed or language is other speech`() = runBlocking {
        repeat(5) { answerWith(it.toByte()) }

        service.generateAudio("Hello", "en").getOrThrow()
        service.generateAudio("Hello!", "en").getOrThrow()
        service.generateAudio("Hello", "en", voice = "en-GB-SoniaNeural").getOrThrow()
        service.generateAudio("Hello", "en", isSlow = true).getOrThrow()
        service.generateAudio("Hello", "fr").getOrThrow()

        assertEquals(5, requests.size)
        assertTrue(requests[0].contains("<voice name='en-US-AriaNeural'><prosody pitch='+0Hz' rate='+0%'"))
        assertTrue(requests[2].contains("<voice name='en-GB-SoniaNeural'><prosody pitch='+0Hz' rate='+0%'"))
        assertTrue(requests[3].contains("<voice name='en-US-AriaNeural'><prosody pitch='+0Hz' rate='-30%'"))
        assertTrue(requests[4].contains("<voice name='fr-FR-DeniseNeural'>"))
    }

    @Test
    fun `the voice of the language is the same speech whether it was chosen or not`() = runBlocking {
        answerWith(1, 2, 3)

        service.generateAudio("こんにちは", "ja").getOrThrow()
        val chosen = service.generateAudio("こんにちは", "ja", voice = "ja-JP-NanamiNeural").getOrThrow()

        assertEquals(1, requests.size)
        assertArrayEquals(byteArrayOf(1, 2, 3), chosen.file.readBytes())
    }

    @Test
    fun `speech that failed is asked for again`() = runBlocking {
        answerWith()
        answerWith(1, 2, 3)

        val failed = service.generateAudio("Hello", "en")
        val second = service.generateAudio("Hello", "en")

        assertTrue(failed.isFailure)
        assertArrayEquals(byteArrayOf(1, 2, 3), second.getOrThrow().file.readBytes())
        assertEquals(2, requests.size)
    }

    @Test
    fun `every file of speech has a name of its own`() = runBlocking {
        answerWith(1, 2, 3)

        val files = List(20) { service.generateAudio("Hello", "en").getOrThrow().file }

        assertEquals(20, files.map { it.path }.distinct().size)
        assertTrue(files.all { it.exists() })
    }

    @Test
    fun `Vietnamese is among the languages`() {
        val languages = service.getSupportedLanguages()

        assertTrue(("vi" to "Vietnamese") in languages)
        assertEquals(20, languages.size)
        assertEquals(languages.size, languages.map { it.first }.distinct().size)
    }
}
