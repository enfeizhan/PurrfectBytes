package com.purrfectbytes.android.services

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** The service is a stand-in on this computer: nothing here reaches the internet. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EdgeVoiceCatalogueTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var file: File

    private var now = 1_790_553_600_000L
    private val minute = 60_000L
    private val day = 24 * 60 * minute

    private val allVoices = EdgeTtsProtocol.parseVoices(EdgeTtsProtocolTest.voicesOfTheService())

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        file = File(folder.root, "edge_voices.json")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** A catalogue as the app has it after it was started. */
    private fun catalogue() = EdgeVoiceCatalogue(
        engine = EdgeTTSEngine(
            client = EdgeTTSEngine.defaultClient(),
            voicesUrl = server.url("/voices/list?trustedclienttoken=test").toString()
        ),
        file = file,
        clock = { now }
    )

    private fun theList() = MockResponse().setBody(EdgeTtsProtocolTest.voicesOfTheService())

    private fun aListOf(vararg voices: String) = MockResponse().setBody(
        voices.joinToString(",", "[", "]") { """{"ShortName":"$it","Gender":"Female","Locale":"${it.take(5)}"}""" }
    )

    private fun names(voices: List<EdgeVoice>) = voices.map { it.id }

    // ------------------------------------------------------------ languages

    @Test
    fun `the voices of a language are those that speak it`() = runBlocking {
        server.enqueue(theList())
        val catalogue = catalogue()

        assertEquals(listOf("ja-JP-KeitaNeural", "ja-JP-NanamiNeural"), names(catalogue.voicesFor("ja")))
        assertEquals(
            listOf("ko-KR-HyunsuMultilingualNeural", "ko-KR-InJoonNeural", "ko-KR-SunHiNeural"),
            names(catalogue.voicesFor("ko"))
        )
        assertEquals(22, catalogue.voicesFor("en").size)
        assertEquals(emptyList<String>(), names(catalogue.voicesFor("sw")))
    }

    @Test
    fun `Finnish is not the beginning of Filipino`() {
        assertEquals(
            listOf("fi-FI-HarriNeural", "fi-FI-NooraNeural"),
            names(EdgeVoiceCatalogue.voicesOf(allVoices, "fi"))
        )
    }

    @Test
    fun `Norwegian is found where the service files it`() {
        assertEquals(
            listOf("nb-NO-FinnNeural", "nb-NO-PernilleNeural"),
            names(EdgeVoiceCatalogue.voicesOf(allVoices, "no"))
        )
    }

    @Test
    fun `the dialects of a language belong to it`() {
        val chinese = names(EdgeVoiceCatalogue.voicesOf(allVoices, "zh"))

        assertEquals(8, chinese.size)
        assertTrue("zh-CN-liaoning-XiaobeiNeural" in chinese)
        assertEquals(chinese, names(EdgeVoiceCatalogue.voicesOf(allVoices, "ZH-cn")))
    }

    @Test
    fun `every voice the app reads with by itself is in the list of its language`() {
        listOf("ja", "ko", "fi", "no", "vi", "pl", "zh", "en").forEach { language ->
            val voice = EdgeTTSEngine.defaultVoice(language)
            assertTrue(
                "$voice is not among the voices of $language",
                voice in names(EdgeVoiceCatalogue.voicesOf(allVoices, language))
            )
        }
    }

    // ------------------------------------------------------- the second voice

    @Test
    fun `the second speaker is of the other gender and from the same country`() {
        val english = EdgeVoiceCatalogue.voicesOf(allVoices, "en")

        assertEquals("en-US-AndrewNeural", EdgeVoiceCatalogue.secondVoiceFor(english, "en-US-AriaNeural")?.id)
        assertEquals("en-US-AvaNeural", EdgeVoiceCatalogue.secondVoiceFor(english, "en-US-GuyNeural")?.id)
        assertEquals("en-GB-RyanNeural", EdgeVoiceCatalogue.secondVoiceFor(english, "en-GB-SoniaNeural")?.id)
    }

    @Test
    fun `the second speaker is another voice, whatever there is to choose from`() {
        val women = listOf(
            EdgeVoice("xx-AA-AnaNeural", "Female", "xx-AA"),
            EdgeVoice("xx-BB-BeaNeural", "Female", "xx-BB"),
            EdgeVoice("xx-AA-CloeNeural", "Female", "xx-AA")
        )
        val abroad = women + EdgeVoice("xx-BB-DanNeural", "Male", "xx-BB")

        assertEquals("the same country", "xx-AA-CloeNeural", EdgeVoiceCatalogue.secondVoiceFor(women, "xx-AA-AnaNeural")?.id)
        assertEquals("the other gender", "xx-BB-DanNeural", EdgeVoiceCatalogue.secondVoiceFor(abroad, "xx-AA-AnaNeural")?.id)
        assertEquals("a voice that is not listed", "xx-AA-AnaNeural", EdgeVoiceCatalogue.secondVoiceFor(women, "xx-CC-EvaNeural")?.id)
        assertNull(EdgeVoiceCatalogue.secondVoiceFor(women.take(1), "xx-AA-AnaNeural"))
        assertNull(EdgeVoiceCatalogue.secondVoiceFor(emptyList(), "xx-AA-AnaNeural"))
    }

    // --------------------------------------------------------------- keeping

    @Test
    fun `the list is asked for once`() = runBlocking {
        server.enqueue(theList())
        val catalogue = catalogue()

        catalogue.voicesFor("ja")
        catalogue.voicesFor("ko")
        now += 6 * day
        catalogue.voicesFor("en")

        assertEquals(1, server.requestCount)
    }

    @Test
    fun `the list is still there when the app is started again`() = runBlocking {
        server.enqueue(theList())
        catalogue().voicesFor("ja")

        now += 6 * day
        val voices = catalogue().voicesFor("ja")

        assertEquals(listOf("ja-JP-KeitaNeural", "ja-JP-NanamiNeural"), names(voices))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `after a week the list is asked for again`() = runBlocking {
        server.enqueue(aListOf("ja-JP-NanamiNeural"))
        server.enqueue(aListOf("ja-JP-NanamiNeural", "ja-JP-AoiNeural"))
        val catalogue = catalogue()
        assertEquals(listOf("ja-JP-NanamiNeural"), names(catalogue.voicesFor("ja")))

        now += 7 * day + minute

        assertEquals(listOf("ja-JP-NanamiNeural", "ja-JP-AoiNeural"), names(catalogue.voicesFor("ja")))
        assertEquals(listOf("ja-JP-NanamiNeural", "ja-JP-AoiNeural"), names(catalogue().voicesFor("ja")))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `an old list is used when a new one cannot be had`() = runBlocking {
        server.enqueue(aListOf("ja-JP-NanamiNeural"))
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(aListOf("ja-JP-NanamiNeural", "ja-JP-AoiNeural"))
        catalogue().voicesFor("ja")
        now += 8 * day

        val catalogue = catalogue()
        assertEquals(listOf("ja-JP-NanamiNeural"), names(catalogue.voicesFor("ja")))
        assertEquals(2, server.requestCount)

        // The service is left alone for a while
        now += 9 * minute
        assertEquals(listOf("ja-JP-NanamiNeural"), names(catalogue.voicesFor("ja")))
        assertEquals(2, server.requestCount)

        now += 2 * minute
        assertEquals(listOf("ja-JP-NanamiNeural", "ja-JP-AoiNeural"), names(catalogue.voicesFor("ja")))
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `without any list the failure is reported, and the next request asks again`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(theList())
        val catalogue = catalogue()

        val error = runCatching { catalogue.voicesFor("ja") }.exceptionOrNull()

        assertTrue(error is EdgeTtsException)
        assertEquals(
            "Could not load the list of voices: Edge TTS refused the connection (HTTP 503)",
            error!!.message
        )
        assertEquals(2, catalogue.voicesFor("ja").size)
    }

    @Test
    fun `a service that cannot be reached is a failure as well`() = runBlocking {
        server.shutdown()

        val error = runCatching { catalogue().voicesFor("ja") }.exceptionOrNull()

        assertTrue(error is EdgeTtsException)
        assertTrue(error!!.message!!.startsWith("Could not load the list of voices: "))
    }

    @Test
    fun `a file that cannot be read is replaced`() = runBlocking {
        server.enqueue(theList())
        file.writeText("{\"fetched_at\": \"yesterday\", \"voices\": [{\"id\": 42")

        assertEquals(2, catalogue().voicesFor("ja").size)

        assertEquals(2, catalogue().voicesFor("ja").size)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a name in the file that could break a request is not offered`() = runBlocking {
        file.writeText(
            """{"fetched_at": $now, "voices": [
                {"id": "ja-JP-NanamiNeural", "gender": "Female", "locale": "ja-JP"},
                {"id": "ja-JP-x'/><break/>", "gender": "Female", "locale": "ja-JP"}]}"""
        )

        assertEquals(listOf("ja-JP-NanamiNeural"), names(catalogue().voicesFor("ja")))
        assertEquals(0, server.requestCount)
    }
}
