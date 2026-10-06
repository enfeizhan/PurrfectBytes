package com.purrfectbytes.android.viewmodels

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.purrfectbytes.android.services.DesktopFFmpeg
import com.purrfectbytes.android.services.EdgeTTSEngine
import com.purrfectbytes.android.services.EdgeTtsProtocol
import com.purrfectbytes.android.services.EdgeTtsProtocolTest
import com.purrfectbytes.android.services.SequenceStep
import com.purrfectbytes.android.services.VideoGeneratorService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Collections

/**
 * From the tap on "Render MP4" to the video, with everything in between as it is in the
 * app, but for two stand-ins on this computer: one for Microsoft's service, which sends
 * a tone for whatever it is asked to read, and FFmpeg as it is installed here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RenderFlowTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var server: MockWebServer

    /** What the service was asked to read: the voice, the speed and the text of every request. */
    private val spoken: MutableList<Triple<String, String, String>> = Collections.synchronizedList(mutableListOf())

    /** Texts the service refuses to read. */
    private val refused: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    private val request = Regex("<voice name='([^']+)'><prosody pitch='\\+0Hz' rate='([^']+)' volume='\\+0%'>(.*)</prosody>")

    @Before
    fun setUp() {
        assumeTrue("FFmpeg is not installed", DesktopFFmpeg.isInstalled)
        Dispatchers.setMain(Dispatchers.Unconfined)

        // Slowed down speech takes longer: one second at normal speed, two when slow
        val normal = DesktopFFmpeg.tone(1.0, File(folder.root, "normal.mp3")).readBytes()
        val slow = DesktopFFmpeg.tone(2.0, File(folder.root, "slow.mp3")).readBytes()

        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val asked = this@RenderFlowTest.request.find(text) ?: return
                        val (voice, rate, words) = asked.destructured
                        spoken += Triple(voice, rate, EdgeTtsProtocol.unescapeXml(words))
                        if (words !in refused) {
                            val audio = if (rate == "+0%") normal else slow
                            webSocket.send(EdgeTtsProtocolTest.audioMessage(audio).toByteString())
                        }
                        webSocket.send("X-RequestId:abc\r\nPath:turn.end\r\n\r\n{}")
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(1000, null)
                    }
                })
        }
        server.start()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        if (::server.isInitialized) server.shutdown()
    }

    private fun viewModel(): MainViewModel = testViewModel(
        context = context,
        edge = EdgeTTSEngine(
            client = EdgeTTSEngine.defaultClient(),
            serviceUrl = server.url("/edge/v1?TrustedClientToken=test").toString(),
            voicesUrl = server.url("/voices/list?trustedclienttoken=test").toString(),
            idleTimeoutMillis = 5_000
        ),
        video = { storage ->
            VideoGeneratorService(context, storage, DesktopFFmpeg.runner) { file -> DesktopFFmpeg.seconds(file) }
        }
    )

    /** Taps "Render MP4" and waits for the render to end, one way or the other. */
    private fun render(model: MainViewModel): List<String> {
        val progress = Collections.synchronizedList(mutableListOf<String>())
        val watcher = Thread {
            while (!Thread.currentThread().isInterrupted) {
                model.uiState.value.renderProgress?.let { if (progress.lastOrNull() != it) progress += it }
                try {
                    Thread.sleep(1)
                } catch (e: InterruptedException) {
                    return@Thread
                }
            }
        }
        watcher.start()

        model.generateNativeVideo()
        val deadline = System.currentTimeMillis() + 120_000
        while (model.uiState.value.isConvertingVideo && System.currentTimeMillis() < deadline) Thread.sleep(20)
        watcher.interrupt()
        watcher.join()

        assertFalse("the render did not end", model.uiState.value.isConvertingVideo)
        return progress
    }

    private fun filesIn(folder: String): List<File> =
        File(context.cacheDir, folder).listFiles().orEmpty().toList()

    /** The videos that are there, without what is written next to them. */
    private fun videos(): List<File> = filesIn("videos").filter { it.extension == "mp4" }

    @Test
    fun `a text is rendered as it was before there were voices and sequences`() {
        val model = viewModel()
        model.updateText("Hello world")
        model.updateRepetitions(3)

        render(model)

        val state = model.uiState.value
        assertNull(state.errorMessage)
        assertEquals("Video generated and repeated 3 times", state.successMessage)
        assertEquals("3 repetitions", state.videoSummary)
        assertEquals("Hello world", state.videoText)
        assertEquals(listOf(Triple("en-US-AriaNeural", "+0%", "Hello world")), spoken)

        val video = model.generatedVideoFile.value!!
        assertEquals(3.0, DesktopFFmpeg.seconds(video), 0.3)
        assertEquals(DesktopFFmpeg.seconds(video), state.videoSeconds, 0.2)
        assertEquals(1, model.generatedAudioFiles.value.size)
        assertEquals(listOf(video), videos())
        assertEquals(model.generatedAudioFiles.value, filesIn("speech"))

        val recent = state.recentVideos.single()
        assertEquals(video, recent.file)
        assertEquals("Hello world", recent.text)
        assertEquals("3 repetitions", recent.summary)
        assertEquals(state.videoSeconds, recent.seconds, 0.001)
        assertNull(recent.uploadedUrl)
    }

    @Test
    fun `a conversation is read by two voices and played at the speeds of the sequence`() {
        val model = viewModel()
        model.updateLanguage("ko")
        model.updateText("직원: 혼자 오셨어요?\n관광객: 아니요, 친구하고 같이 왔어요.\n직원: 이쪽으로 오세요.")
        model.updateConversation(true)
        model.updateVoice("ko-KR-SunHiNeural")
        model.updateSecondVoice("ko-KR-InJoonNeural")
        model.updateSequenceUse(true)
        model.removeSequenceStep(2)
        model.updateSequenceStep(0, SequenceStep(1, slow = true))
        model.updateSequenceStep(1, SequenceStep(2, slow = false))

        val progress = render(model)

        val state = model.uiState.value
        assertNull(state.errorMessage)
        assertEquals(
            "Conversation video generated (3 lines, 2 voices, sequence 1 slow, 2 normal, 3 repetitions)",
            state.successMessage
        )
        assertEquals("Conversation of 3 lines, sequence: 1 slow, 2 normal", state.videoSummary)

        // Every line once at every speed, the names of the speakers left out
        assertEquals(
            listOf(
                Triple("ko-KR-SunHiNeural", "+0%", "혼자 오셨어요?"),
                Triple("ko-KR-InJoonNeural", "+0%", "아니요, 친구하고 같이 왔어요."),
                Triple("ko-KR-SunHiNeural", "+0%", "이쪽으로 오세요."),
                Triple("ko-KR-SunHiNeural", "-30%", "혼자 오셨어요?"),
                Triple("ko-KR-InJoonNeural", "-30%", "아니요, 친구하고 같이 왔어요."),
                Triple("ko-KR-SunHiNeural", "-30%", "이쪽으로 오세요.")
            ),
            spoken
        )

        // Once slowly, three lines of two seconds, and twice at normal speed
        val video = model.generatedVideoFile.value!!
        assertEquals(6.0 + 2 * 3.0, DesktopFFmpeg.seconds(video), 0.6)
        assertEquals(DesktopFFmpeg.seconds(video), state.videoSeconds, 0.3)

        // What is kept to listen to is the first run through the conversation: the slow one
        val audio = model.generatedAudioFiles.value
        assertEquals(3, audio.size)
        audio.forEach { assertEquals(2.0, DesktopFFmpeg.seconds(it), 0.2) }
        assertEquals(audio.toSet(), filesIn("speech").toSet())
        assertEquals(listOf(video), videos())

        assertEquals("Generating speech 1 of 6", progress.first())
        assertEquals("Putting the video together", progress.last())
        assertTrue(progress.toString(), "Encoding clip 6 of 6" in progress)
        assertNull(state.renderProgress)
    }

    @Test
    fun `without a second voice the second speaker is told from the first`() {
        keepVoices(context, EdgeTtsProtocol.parseVoices(EdgeTtsProtocolTest.voicesOfTheService()))
        val model = viewModel()
        model.updateLanguage("ja")
        val deadline = System.currentTimeMillis() + 10_000
        while (model.uiState.value.voices.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        model.updateText("こんにちは\nこんばんは")
        model.updateConversation(true)

        render(model)

        assertNull(model.uiState.value.errorMessage)
        assertEquals(
            listOf(
                Triple("ja-JP-NanamiNeural", "+0%", "こんにちは"),
                Triple("ja-JP-KeitaNeural", "+0%", "こんばんは")
            ),
            spoken
        )
    }

    @Test
    fun `the override is spoken and the text is shown`() {
        val model = viewModel()
        model.updateLanguage("ja")
        model.updateText("会議を行った")
        model.updateVoicedTextUse(true)
        model.updateVoicedText("会議をおこなった")
        model.updateRepetitions(1)

        render(model)

        assertEquals(listOf(Triple("ja-JP-NanamiNeural", "+0%", "会議をおこなった")), spoken)
        assertEquals("会議を行った", model.uiState.value.videoText)
        assertEquals("Video generated", model.uiState.value.successMessage)

        // Switched off, the override is left alone but not spoken
        spoken.clear()
        model.updateVoicedTextUse(false)
        render(model)
        assertEquals(listOf(Triple("ja-JP-NanamiNeural", "+0%", "会議を行った")), spoken)
    }

    @Test
    fun `a video that is rendered again copies its speech`() {
        val model = viewModel()
        model.updateText("Hello\nHi there")
        model.updateConversation(true)
        model.updateRepetitions(1)

        render(model)
        val first = model.generatedVideoFile.value!!
        render(model)
        val second = model.generatedVideoFile.value!!

        assertEquals("the service was asked once for every line", 2, spoken.size)
        assertEquals("the video before is kept", setOf(first, second), videos().toSet())
        assertEquals(listOf(second, first), model.uiState.value.recentVideos.map { it.file })
        assertEquals(2, filesIn("speech").size)
    }

    // ------------------------------------------------------- recent videos

    private fun renderOf(model: MainViewModel, text: String): File {
        model.updateText(text)
        model.updateRepetitions(1)
        render(model)
        assertNull(model.uiState.value.errorMessage)
        return model.generatedVideoFile.value!!
    }

    @Test
    fun `the last five videos are kept, the newest first`() {
        val model = viewModel()

        val rendered = (1..6).map { renderOf(model, "Video number $it") }

        assertEquals(
            listOf("Video number 6", "Video number 5", "Video number 4", "Video number 3", "Video number 2"),
            model.uiState.value.recentVideos.map { it.text }
        )
        assertFalse(rendered.first().exists())
        assertEquals(rendered.drop(1).toSet(), videos().toSet())
        assertEquals("a video and what is known of it", 10, filesIn("videos").size)
    }

    @Test
    fun `the videos are still there when the app is started again`() {
        val first = viewModel()
        val hello = renderOf(first, "Hello")
        val bye = renderOf(first, "Bye")
        File(context.cacheDir, "videos/clip_left_over.mp4").writeText("left over")
        filesIn("videos").forEach { it.setLastModified(System.currentTimeMillis() - 60_000) }
        filesIn("speech").forEach { it.setLastModified(System.currentTimeMillis() - 60_000) }

        val restarted = viewModel()
        val deadline = System.currentTimeMillis() + 10_000
        while (filesIn("speech").isNotEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)

        assertEquals(listOf("Bye", "Hello"), restarted.uiState.value.recentVideos.map { it.text })
        assertEquals(setOf(hello, bye), videos().toSet())
        assertTrue("the speech of the run before is cleared away", filesIn("speech").isEmpty())
        assertNull("no video is taken up by itself", restarted.generatedVideoFile.value)

        restarted.selectVideo(hello)
        assertEquals(hello, restarted.generatedVideoFile.value)
        assertEquals("Hello", restarted.uiState.value.videoText)
        assertEquals(1.0, restarted.uiState.value.videoSeconds, 0.3)
    }

    @Test
    fun `a video that is chosen takes the place of the one that was rendered last`() {
        val model = viewModel()
        val hello = renderOf(model, "Hello")
        model.updateRepetitions(1)
        val bye = renderOf(model, "Bye")
        assertEquals(1, model.generatedAudioFiles.value.size)

        model.selectVideo(hello)

        assertEquals(hello, model.generatedVideoFile.value)
        assertEquals("Hello", model.uiState.value.videoText)
        assertTrue("the audio belongs to the other video", model.generatedAudioFiles.value.isEmpty())
        assertTrue(filesIn("speech").isEmpty())
        assertTrue(bye.exists())
    }

    @Test
    fun `putting a video away does not delete it`() {
        val model = viewModel()
        val hello = renderOf(model, "Hello")

        model.dismissVideo()

        assertNull(model.generatedVideoFile.value)
        assertTrue(hello.exists())
        assertEquals(listOf(hello), model.uiState.value.recentVideos.map { it.file })
    }

    @Test
    fun `a video that is deleted is gone, with what was known of it`() {
        val model = viewModel()
        val hello = renderOf(model, "Hello")
        val bye = renderOf(model, "Bye")

        model.deleteVideo(hello)
        assertEquals(bye, model.generatedVideoFile.value)
        assertEquals(listOf(bye), model.uiState.value.recentVideos.map { it.file })

        model.deleteVideo(bye)
        assertNull(model.generatedVideoFile.value)
        assertNull(model.uiState.value.videoText)
        assertTrue(model.uiState.value.recentVideos.isEmpty())
        assertEquals(emptyList<File>(), filesIn("videos"))
    }

    @Test
    fun `a video that is gone cannot be chosen`() {
        val model = viewModel()
        val hello = renderOf(model, "Hello")
        model.dismissVideo()
        hello.delete()

        model.selectVideo(hello)

        assertEquals("This video is no longer there", model.uiState.value.errorMessage)
        assertNull(model.generatedVideoFile.value)
        assertTrue(model.uiState.value.recentVideos.isEmpty())
    }

    @Test
    fun `a line that cannot be read ends the render and leaves nothing behind`() {
        refused += "Hi there"
        val model = viewModel()
        model.updateText("Hello\nHi there\nBye")
        model.updateConversation(true)

        render(model)

        val state = model.uiState.value
        assertEquals(
            "Could not generate the audio 2 of 3: Edge TTS returned no audio. " +
                "Check that the text matches the selected language.",
            state.errorMessage
        )
        assertNull(state.successMessage)
        assertNull(model.generatedVideoFile.value)
        assertTrue(model.generatedAudioFiles.value.isEmpty())
        assertTrue(state.recentVideos.isEmpty())
        assertEquals(emptyList<File>(), filesIn("speech"))
        assertEquals(emptyList<File>(), filesIn("videos"))
        assertEquals(2, spoken.size)
    }

    @Test
    fun `a render that fails keeps the video that was there`() {
        val model = viewModel()
        model.updateText("Hello")
        model.updateRepetitions(1)
        render(model)
        val video = model.generatedVideoFile.value!!
        val audio = model.generatedAudioFiles.value

        refused += "Bye"
        model.updateText("Bye")
        render(model)

        assertTrue(model.uiState.value.errorMessage!!.startsWith("Could not generate the audio: "))
        assertEquals(video, model.generatedVideoFile.value)
        assertTrue(video.exists())
        assertEquals(audio, model.generatedAudioFiles.value)
        assertTrue(audio.all { it.exists() })
        assertEquals("Hello", model.uiState.value.videoText)
    }

    @Test
    fun `the preview of a conversation is drawn without asking for speech`() {
        val model = viewModel()
        model.updateText("A: Hello\nB: Hi there")
        model.updateConversation(true)

        model.generatePreview()
        val deadline = System.currentTimeMillis() + 30_000
        while (model.previewImageFile.value == null && System.currentTimeMillis() < deadline) Thread.sleep(20)

        assertTrue(model.previewImageFile.value!!.length() > 1000)
        assertNull(model.uiState.value.errorMessage)
        assertTrue(spoken.isEmpty())
    }
}
