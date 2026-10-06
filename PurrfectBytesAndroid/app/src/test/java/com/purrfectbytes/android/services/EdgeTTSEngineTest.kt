package com.purrfectbytes.android.services

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * Runs the engine against a stand-in for Microsoft's service on this computer.
 * Nothing here reaches the internet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EdgeTTSEngineTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var output: File

    /** Every message the app sent, in order. */
    private val received: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        output = File(folder.root, "speech.mp3")
    }

    @After
    fun tearDown() {
        try {
            server.shutdown()
        } catch (e: Exception) {
            // A connection the test left open on purpose
        }
    }

    private fun engine(idleTimeoutMillis: Long = 5_000, clock: () -> Long = System::currentTimeMillis) =
        EdgeTTSEngine(
            client = EdgeTTSEngine.defaultClient(),
            serviceUrl = server.url("/edge/v1?TrustedClientToken=test").toString(),
            voicesUrl = server.url("/voices/list?trustedclienttoken=test").toString(),
            idleTimeoutMillis = idleTimeoutMillis,
            clock = clock
        )

    /** Answers the request for speech with [answer], the way the service does. */
    private fun serviceThat(answer: (WebSocket) -> Unit) = MockResponse().withWebSocketUpgrade(
        object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                received += text
                if (text.contains("Path:ssml")) answer(webSocket)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }
        }
    )

    private fun WebSocket.sendAudio(vararg bytes: Byte) =
        send(EdgeTtsProtocolTest.audioMessage(bytes).toByteString())

    private fun WebSocket.sendWord(word: String, offset: Long, duration: Long) = send(
        "X-RequestId:abc\r\nContent-Type:application/json; charset=utf-8\r\nPath:audio.metadata\r\n\r\n" +
            """{"Metadata":[{"Type":"WordBoundary","Data":{"Offset":$offset,"Duration":$duration,"text":{"Text":"$word","Length":${word.length},"BoundaryType":"WordBoundary"}}}]}"""
    )

    private fun WebSocket.sendTurnEnd() = send("X-RequestId:abc\r\nPath:turn.end\r\n\r\n{}")

    @Test
    fun `audio and word times are collected`() = runBlocking {
        server.enqueue(serviceThat { socket ->
            socket.send("X-RequestId:abc\r\nPath:turn.start\r\n\r\n{}")
            socket.sendAudio(1, 2, 3)
            socket.sendWord("Hello", offset = 1_000_000, duration = 5_000_000)
            socket.sendAudio(4, 5)
            socket.sendWord("world", offset = 7_000_000, duration = 4_000_000)
            socket.send(EdgeTtsProtocolTest.audioMessage(ByteArray(0), contentType = null).toByteString())
            socket.sendTurnEnd()
        })

        val speech = engine().generateAudio("Hello world", "en", isSlow = false, outputFile = output).getOrThrow()

        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), speech.file.readBytes())
        assertEquals(
            listOf(WordBoundary("Hello", 0.1, 0.6), WordBoundary("world", 0.7, 1.1)),
            speech.wordBoundaries
        )
    }

    @Test
    fun `the text is sent in a form that cannot break the request`() = runBlocking {
        server.enqueue(serviceThat { socket ->
            socket.sendAudio(1)
            socket.sendTurnEnd()
        })

        engine().generateAudio("Tom & Jerry <3", "en", isSlow = true, outputFile = output).getOrThrow()

        val request = received.single { it.contains("Path:ssml") }
        assertTrue(request.contains("<prosody pitch='+0Hz' rate='-30%' volume='+0%'>Tom &amp; Jerry &lt;3</prosody>"))
        assertTrue(request.contains("<voice name='en-US-AriaNeural'>"))
        assertTrue(received.first().contains("Path:speech.config"))
        assertTrue(received.first().contains("\"wordBoundaryEnabled\":\"true\""))
    }

    @Test
    fun `the voice follows the language`() = runBlocking {
        server.enqueue(serviceThat { socket ->
            socket.sendAudio(1)
            socket.sendTurnEnd()
        })

        engine().generateAudio("こんにちは", "ja", isSlow = false, outputFile = output).getOrThrow()

        assertTrue(received.single { it.contains("Path:ssml") }.contains("<voice name='ja-JP-NanamiNeural'>"))
    }

    @Test
    fun `Polish, Finnish and Vietnamese are read by voices the service has`() = runBlocking {
        val offered = EdgeTtsProtocol.parseVoices(EdgeTtsProtocolTest.voicesOfTheService()).map { it.id }

        listOf("pl" to "pl-PL-ZofiaNeural", "fi" to "fi-FI-NooraNeural", "vi" to "vi-VN-HoaiMyNeural")
            .forEach { (language, voice) ->
                assertEquals(voice, EdgeTTSEngine.defaultVoice(language))
                assertTrue("$voice is not offered", voice in offered)
            }
        assertEquals("nb-NO-PernilleNeural", EdgeTTSEngine.defaultVoice("no"))
        assertEquals("ja-JP-NanamiNeural", EdgeTTSEngine.defaultVoice("ja-JP"))
        assertEquals("en-US-AriaNeural", EdgeTTSEngine.defaultVoice("xx"))
    }

    @Test
    fun `a voice that was chosen reads in place of the voice of the language`() = runBlocking {
        server.enqueue(serviceThat { socket ->
            socket.sendAudio(1)
            socket.sendTurnEnd()
        })

        engine().generateAudio("こんにちは", "ja", isSlow = false, outputFile = output, voice = "ja-JP-KeitaNeural")
            .getOrThrow()

        assertTrue(received.single { it.contains("Path:ssml") }.contains("<voice name='ja-JP-KeitaNeural'>"))
    }

    @Test
    fun `a name that could break the request is not sent`() = runBlocking {
        server.enqueue(serviceThat { socket ->
            socket.sendAudio(1)
            socket.sendTurnEnd()
        })

        engine().generateAudio("こんにちは", "ja", isSlow = false, outputFile = output, voice = "x'><break time='9s'/>")
            .getOrThrow()

        val request = received.single { it.contains("Path:ssml") }
        assertTrue(request.contains("<voice name='ja-JP-NanamiNeural'>"))
        assertFalse(request.contains("break"))
    }

    // ------------------------------------------------------------ voices

    @Test
    fun `the voices are asked for the way a browser asks`() = runBlocking {
        server.enqueue(MockResponse().setBody(EdgeTtsProtocolTest.voicesOfTheService()))

        val voices = engine(clock = { 1790553600_000L }).voices()

        assertEquals(45, voices.size)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/voices/list", request.requestUrl!!.encodedPath)
        assertEquals("test", request.requestUrl!!.queryParameter("trustedclienttoken"))
        assertEquals(EdgeTtsProtocol.secMsGec(1790553600.0), request.requestUrl!!.queryParameter("Sec-MS-GEC"))
        assertEquals(
            "1-${EdgeTtsProtocol.CHROMIUM_FULL_VERSION}",
            request.requestUrl!!.queryParameter("Sec-MS-GEC-Version")
        )
        EdgeTtsProtocol.VOICE_HEADERS.forEach { (name, value) ->
            assertEquals(name, value, request.getHeader(name))
        }
        assertTrue(Regex("muid=[0-9A-F]{32};").matches(request.getHeader("Cookie")!!))
    }

    @Test
    fun `a wrong clock is corrected for the voices as well`() = runBlocking {
        val phoneTime = 1577836800_000L
        server.enqueue(
            MockResponse().setResponseCode(403).setHeader("Date", "Mon, 28 Sep 2026 08:34:00 GMT")
        )
        server.enqueue(MockResponse().setBody(EdgeTtsProtocolTest.voicesOfTheService()))

        val voices = engine(clock = { phoneTime }).voices()

        assertEquals(45, voices.size)
        assertEquals(
            EdgeTtsProtocol.secMsGec(phoneTime / 1000.0),
            server.takeRequest().requestUrl!!.queryParameter("Sec-MS-GEC")
        )
        assertEquals(
            EdgeTtsProtocol.secMsGec(1790584440.0),
            server.takeRequest().requestUrl!!.queryParameter("Sec-MS-GEC")
        )
    }

    @Test
    fun `voices that are refused are a failure that says so`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))

        val error = runCatching { engine().voices() }.exceptionOrNull()

        assertTrue(error is EdgeTtsException)
        assertEquals("Edge TTS refused the connection (HTTP 503)", error!!.message)
    }

    @Test
    fun `an empty list of voices is a failure`() = runBlocking {
        server.enqueue(MockResponse().setBody("[]"))

        val error = runCatching { engine().voices() }.exceptionOrNull()

        assertEquals("Edge TTS sent an empty list of voices", error!!.message)
    }

    @Test
    fun `an answer without audio is a failure`() = runBlocking {
        server.enqueue(serviceThat { socket -> socket.sendTurnEnd() })

        val result = engine().generateAudio("Hello", "en", isSlow = false, outputFile = output)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("no audio"))
        assertFalse("the empty file is removed", output.exists())
    }

    @Test
    fun `a service that goes silent is given up`() = runBlocking {
        server.enqueue(serviceThat { socket -> socket.sendAudio(1) }) // and then nothing

        val result = engine(idleTimeoutMillis = 300).generateAudio("Hello", "en", isSlow = false, outputFile = output)

        assertTrue(result.isFailure)
        assertEquals("Edge TTS stopped responding", result.exceptionOrNull()!!.message)
        assertFalse(output.exists())
    }

    @Test
    fun `a refused connection is reported`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = engine().generateAudio("Hello", "en", isSlow = false, outputFile = output)

        assertTrue(result.isFailure)
        assertEquals("Edge TTS refused the connection (HTTP 500)", result.exceptionOrNull()!!.message)
    }

    @Test
    fun `a connection that stays refused points to the Edge version`() = runBlocking {
        repeat(2) {
            server.enqueue(MockResponse().setResponseCode(403).setHeader("Date", "Mon, 28 Sep 2026 08:34:00 GMT"))
        }

        val result = engine().generateAudio("Hello", "en", isSlow = false, outputFile = output)

        assertEquals(2, server.requestCount)
        assertEquals(
            "Edge TTS refused the connection (HTTP 403). " +
                "If this keeps happening, the Edge version the app reports is too old.",
            result.exceptionOrNull()!!.message
        )
        assertFalse(output.exists())
    }

    @Test
    fun `a wrong clock is corrected with the time of the service`() = runBlocking {
        // The phone thinks it is 1 January 2020; the service says it is 28 September 2026
        val phoneTime = 1577836800_000L
        val serviceTime = 1790584440.0
        server.enqueue(
            MockResponse().setResponseCode(403).setHeader("Date", "Mon, 28 Sep 2026 08:34:00 GMT")
        )
        server.enqueue(serviceThat { socket ->
            socket.sendAudio(9)
            socket.sendTurnEnd()
        })

        val result = engine(clock = { phoneTime }).generateAudio("Hello", "en", isSlow = false, outputFile = output)

        assertTrue(result.isSuccess)
        val first = server.takeRequest().requestUrl!!.queryParameter("Sec-MS-GEC")
        val second = server.takeRequest().requestUrl!!.queryParameter("Sec-MS-GEC")
        assertEquals(EdgeTtsProtocol.secMsGec(phoneTime / 1000.0), first)
        assertEquals(EdgeTtsProtocol.secMsGec(serviceTime), second)
    }

    @Test
    fun `long text is sent in pieces and the times run on`() = runBlocking {
        val answer: (WebSocket) -> Unit = { socket ->
            socket.sendAudio(7)
            socket.sendWord("word", offset = 1_000_000, duration = 2_000_000)
            socket.sendTurnEnd()
        }
        server.enqueue(serviceThat(answer))
        server.enqueue(serviceThat(answer))

        // Just over the 4096 bytes the service takes at once
        val text = "word ".repeat(820)
        val speech = engine().generateAudio(text, "en", isSlow = false, outputFile = output).getOrThrow()

        assertEquals(2, received.count { it.contains("Path:ssml") })
        assertArrayEquals(byteArrayOf(7, 7), speech.file.readBytes())
        // The second piece starts where the first ended (0.3 s) plus the pause the service adds (0.875 s)
        assertEquals(listOf(0.1, 1.275), speech.wordBoundaries.map { it.start })
    }

    @Test
    fun `giving up closes the connection and removes the file`() = runBlocking {
        server.enqueue(serviceThat { socket -> socket.sendAudio(1) }) // and then nothing

        val job = async { engine(idleTimeoutMillis = 60_000).generateAudio("Hello", "en", false, output) }
        while (received.none { it.contains("Path:ssml") }) delay(20)
        delay(100)
        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
        assertFalse(output.exists())
    }
}
