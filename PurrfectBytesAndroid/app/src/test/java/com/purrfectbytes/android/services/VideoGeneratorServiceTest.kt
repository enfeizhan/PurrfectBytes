package com.purrfectbytes.android.services

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.util.concurrent.TimeUnit

/**
 * Makes real videos: the frames are drawn by Android's text engine and encoded by the
 * FFmpeg installed on this computer, with the same arguments the app gives to its own
 * FFmpeg. The tests are skipped where FFmpeg is not installed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VideoGeneratorServiceTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val storage = MediaStorage(context)

    private val ffmpeg = findProgram("ffmpeg")
    private val ffprobe = findProgram("ffprobe")

    @Before
    fun needsFFmpeg() {
        assumeTrue("FFmpeg is not installed", ffmpeg != null && ffprobe != null)
    }

    private fun findProgram(name: String): String? =
        System.getenv("PATH").orEmpty().split(File.pathSeparator)
            .map { File(it, name) }
            .firstOrNull { it.canExecute() }
            ?.absolutePath

    private class Output(val exitCode: Int, val bytes: ByteArray, val errors: String) {
        val text: String get() = String(bytes).trim()
    }

    private fun execute(command: List<String>): Output {
        val errors = folder.newFile()
        val process = ProcessBuilder(command).redirectError(errors).start()
        val bytes = process.inputStream.readBytes()
        assertTrue("timed out: $command", process.waitFor(120, TimeUnit.SECONDS))
        return Output(process.exitValue(), bytes, errors.readText())
    }

    /** [audioLength] stands for what the phone reads from the file; by default FFmpeg measures it. */
    private fun service(audioLength: Double? = null) = VideoGeneratorService(
        context = context,
        storage = storage,
        ffmpeg = { arguments ->
            val result = execute(listOf(ffmpeg!!, "-hide_banner", "-loglevel", "error") + arguments)
            if (result.exitCode != 0) throw VideoException("FFmpeg could not encode the video. ${result.errors}")
        },
        audioSeconds = { file -> audioLength ?: seconds(file) }
    )

    /** A tone as an MP3 like the ones Edge TTS sends: 24 kHz, mono, 48 kbit/s. */
    private fun audio(seconds: Double): File {
        val file = File(folder.root, "audio_$seconds.mp3")
        val result = execute(
            listOf(
                ffmpeg!!, "-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=$seconds",
                "-ar", "24000", "-ac", "1", "-b:a", "48k", file.path
            )
        )
        assertEquals(result.errors, 0, result.exitCode)
        return file
    }

    private fun probe(video: File, stream: String, entries: String): List<String> =
        execute(
            listOf(
                ffprobe!!, "-v", "error", "-select_streams", stream,
                "-show_entries", "stream=$entries", "-of", "csv=p=0", video.path
            )
        ).text.split(",")

    private fun seconds(file: File): Double =
        execute(
            listOf(ffprobe!!, "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", file.path)
        ).text.toDouble()

    /** The colour at ([x], [y]) of frame number [frame], as red, green and blue. */
    private fun pixel(video: File, frame: Int, x: Int, y: Int): Triple<Int, Int, Int> {
        val picture = execute(
            listOf(
                ffmpeg!!, "-hide_banner", "-loglevel", "error", "-i", video.path,
                "-vf", "select=eq(n\\,$frame)", "-frames:v", "1",
                "-f", "rawvideo", "-pix_fmt", "rgb24", "-"
            )
        ).bytes
        assertEquals(FrameRenderer.WIDTH * FrameRenderer.HEIGHT * 3, picture.size)
        val at = (y * FrameRenderer.WIDTH + x) * 3
        return Triple(picture[at].toInt() and 0xFF, picture[at + 1].toInt() and 0xFF, picture[at + 2].toInt() and 0xFF)
    }

    private fun Triple<Int, Int, Int>.isRed() = first > 170 && second < 100 && third < 100

    private fun steps(vararg steps: Pair<Int, Boolean>) = steps.map { SequenceStep(it.first, it.second) }

    /** One voice reading [text] [repetitions] times. */
    private fun plan(text: String, repetitions: Int = 1): RenderPlan =
        RenderPlanner.plan(RenderRequest(text = text, steps = steps(repetitions to false)))

    private suspend fun VideoGeneratorService.video(
        text: String,
        speech: SpeechAudio,
        repetitions: Int = 1
    ): Result<File> = generateVideo(plan(text, repetitions), listOf(speech), "en").map { it.file }

    /** A point inside the red box of unit [unit]: near its top, above where the letter begins. */
    private fun cornerOf(text: String, unit: Int): Pair<Int, Int> {
        val renderer = FrameRenderer(null, null, null)
        val box = renderer.highlightBox(renderer.prepare(text), unit)!!.second
        return box.centerX().toInt() to (box.top.toInt() + 5)
    }

    @Test
    fun `the video is as long as the audio, once for every repetition`() = runBlocking {
        val speech = SpeechAudio(audio(2.4))

        val once = service().video("Hello world", speech, repetitions = 1).getOrThrow()
        val thrice = service().video("Hello world", speech, repetitions = 3).getOrThrow()

        assertEquals(2.4, seconds(once), 0.15)
        assertEquals(3 * seconds(once), seconds(thrice), 0.15)
    }

    @Test
    fun `the video is what YouTube expects`() = runBlocking {
        val video = service().video("Hello world", SpeechAudio(audio(1.0)), repetitions = 2).getOrThrow()

        assertEquals(
            listOf("h264", "1280", "720", "yuv420p", "30/1"),
            probe(video, "v:0", "codec_name,width,height,pix_fmt,r_frame_rate")
        )
        assertEquals(listOf("aac"), probe(video, "a:0", "codec_name"))
    }

    @Test
    fun `the highlight is on the word that is being spoken`() = runBlocking {
        val text = "Hello world"
        val words = listOf(WordBoundary("Hello", 0.5, 1.0), WordBoundary("world", 1.5, 2.0))
        val video = service().video(text, SpeechAudio(audio(2.4), words)).getOrThrow()

        val (hX, hY) = cornerOf(text, 0) // H
        val (wX, wY) = cornerOf(text, 5) // w
        val (dX, dY) = cornerOf(text, 9) // d

        // Before the first word the highlight waits on the first letter,
        // which is spoken from 0.5 s to 0.6 s: up to frame 17
        assertTrue(pixel(video, frame = 0, x = hX, y = hY).isRed())
        assertTrue(pixel(video, frame = 17, x = hX, y = hY).isRed())
        assertFalse(pixel(video, frame = 18, x = hX, y = hY).isRed())

        // "world" starts at 1.5 s, which is frame 45, to the frame
        assertFalse(pixel(video, frame = 44, x = wX, y = wY).isRed())
        assertTrue(pixel(video, frame = 45, x = wX, y = wY).isRed())
        assertTrue(pixel(video, frame = 47, x = wX, y = wY).isRed())
        assertFalse(pixel(video, frame = 48, x = wX, y = wY).isRed())

        // Its last letter starts at 1.9 s, frame 57, and stays to the end
        assertFalse(pixel(video, frame = 56, x = dX, y = dY).isRed())
        assertTrue(pixel(video, frame = 57, x = dX, y = dY).isRed())
        assertTrue(pixel(video, frame = 70, x = dX, y = dY).isRed())
    }

    @Test
    fun `without word times the highlight moves evenly`() = runBlocking {
        val text = "abcd"
        val video = service(audioLength = 2.0).video(text, SpeechAudio(audio(2.0))).getOrThrow()

        // Four letters in two seconds: half a second, 15 frames, each
        listOf(0, 1, 2, 3).forEach { unit ->
            val (x, y) = cornerOf(text, unit)
            assertTrue("letter $unit at its start", pixel(video, frame = unit * 15, x = x, y = y).isRed())
            assertTrue("letter $unit at its end", pixel(video, frame = unit * 15 + 14, x = x, y = y).isRed())
            if (unit > 0) {
                assertFalse("letter $unit too early", pixel(video, frame = unit * 15 - 1, x = x, y = y).isRed())
            }
        }
    }

    @Test
    fun `only the finished video is left behind`() = runBlocking {
        val video = service().video("Hello world", SpeechAudio(audio(1.0)), repetitions = 2).getOrThrow()

        val left = context.cacheDir.walkTopDown().filter { it.isFile }.toList()
        assertEquals(listOf(video), left)
    }

    @Test
    fun `a failure of FFmpeg is reported with its reason and leaves nothing behind`() = runBlocking {
        val broken = File(folder.root, "broken.mp3").apply { writeText("this is not audio") }
        val failing = VideoGeneratorService(
            context = context,
            storage = storage,
            ffmpeg = { arguments ->
                val result = execute(listOf(ffmpeg!!, "-hide_banner", "-loglevel", "error") + arguments)
                if (result.exitCode != 0) throw VideoException("FFmpeg could not encode the video. ${result.errors}")
            },
            audioSeconds = { null }
        )

        val result = failing.video("Hello world", SpeechAudio(broken))

        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()!!.message!!
        assertTrue(message, message.startsWith("FFmpeg could not encode the video."))
        assertTrue(message, message.contains("broken.mp3"))
        assertEquals(emptyList<File>(), context.cacheDir.walkTopDown().filter { it.isFile }.toList())
    }

    // ------------------------------------------------------- several clips

    private fun conversation(text: String, vararg steps: Pair<Int, Boolean> = arrayOf(1 to false)): RenderPlan =
        RenderPlanner.plan(
            RenderRequest(text = text, conversation = true, steps = steps(*steps), isSequence = steps.size > 1)
        )

    private fun boxOf(plan: RenderPlan, position: Int): android.graphics.RectF {
        val renderer = FrameRenderer(null, null, null)
        val prepared = renderer.prepare(plan.displayText, startAligned = plan.isConversation)
        val unit = prepared.units.indexOfFirst { it.start == position }
        return renderer.highlightBox(prepared, unit)!!.second
    }

    /** A point inside the red box of the character at [position] of what [plan] shows. */
    private fun cornerOf(plan: RenderPlan, position: Int): Pair<Int, Int> =
        boxOf(plan, position).let { box -> box.centerX().toInt() to (box.top.toInt() + 5) }

    /**
     * The very corner of that box, where no character reaches - Korean ones are as high as
     * their box. Only a picture shows it as it was drawn; a video blurs it.
     */
    private fun edgeOf(plan: RenderPlan, position: Int): Pair<Int, Int> =
        boxOf(plan, position).let { box -> (box.left.toInt() + 1) to (box.top.toInt() + 1) }

    /** A point of the badge of slowed down speech where none of its letters is. */
    private val badge = FrameRenderer(null, null, null).slowBadgeBox().let { box ->
        (box.left.toInt() + 6) to box.centerY().toInt()
    }

    @Test
    fun `a conversation plays its lines one after the other, as often as asked for`() = runBlocking {
        val plan = conversation("A: Hello\nB: Good morning", 2 to false)
        val speech = listOf(SpeechAudio(audio(1.0)), SpeechAudio(audio(2.0)))
        val progress = mutableListOf<String>()

        val video = service().generateVideo(plan, speech, "en") { progress += it }.getOrThrow()

        assertEquals(6.0, seconds(video.file), 0.3)
        assertEquals(seconds(video.file), video.seconds, 0.2)
        assertEquals(
            listOf("Encoding clip 1 of 2", "Encoding clip 2 of 2", "Putting the video together"),
            progress
        )
        assertEquals(
            listOf("h264", "1280", "720", "yuv420p", "30/1"),
            probe(video.file, "v:0", "codec_name,width,height,pix_fmt,r_frame_rate")
        )
    }

    @Test
    fun `the highlight is on the line that is being spoken, and never on a name`() = runBlocking {
        val plan = conversation("A: Hello\nB: Good morning")
        val speech = listOf(
            SpeechAudio(audio(1.0), listOf(WordBoundary("Hello", 0.5, 1.0))),
            SpeechAudio(audio(2.0), listOf(WordBoundary("Good", 0.5, 1.0), WordBoundary("morning", 1.0, 1.9)))
        )

        val video = service().generateVideo(plan, speech, "en").getOrThrow().file

        val (hX, hY) = cornerOf(plan, 3) // H of Hello
        val (gX, gY) = cornerOf(plan, 12) // G of Good
        val names = listOf(cornerOf(plan, 0), cornerOf(plan, 9))

        // The first line is heard in the first second, the second line after it
        assertTrue(pixel(video, frame = 10, x = hX, y = hY).isRed())
        assertFalse(pixel(video, frame = 10, x = gX, y = gY).isRed())
        assertTrue(pixel(video, frame = 40, x = gX, y = gY).isRed())
        assertFalse(pixel(video, frame = 40, x = hX, y = hY).isRed())
        listOf(10, 40, 80).forEach { frame ->
            names.forEach { (x, y) -> assertFalse("a name at frame $frame", pixel(video, frame, x, y).isRed()) }
        }
    }

    @Test
    fun `without word times the highlight moves evenly across its own line`() = runBlocking {
        val plan = conversation("abcd\nefgh")
        val speech = listOf(SpeechAudio(audio(2.0)), SpeechAudio(audio(2.0)))

        val video = service(audioLength = 2.0).generateVideo(plan, speech, "en").getOrThrow().file

        // "— abcd" and "— efgh": four letters in two seconds, 15 frames each
        val (bX, bY) = cornerOf(plan, 3)
        val (fX, fY) = cornerOf(plan, 10)
        assertFalse(pixel(video, frame = 14, x = bX, y = bY).isRed())
        assertTrue(pixel(video, frame = 15, x = bX, y = bY).isRed())
        assertTrue(pixel(video, frame = 29, x = bX, y = bY).isRed())
        assertFalse(pixel(video, frame = 30, x = bX, y = bY).isRed())
        // The second clip begins where the audio of the first has ended, a little after 2 s
        assertTrue(pixel(video, frame = 85, x = fX, y = fY).isRed())
        assertFalse(pixel(video, frame = 85, x = bX, y = bY).isRed())
    }

    @Test
    fun `slowed down repetitions are marked and the others are not`() = runBlocking {
        val plan = RenderPlanner.plan(
            RenderRequest(text = "Hello", steps = steps(1 to false, 1 to true, 1 to false), isSequence = true)
        )
        // The clips of the plan: at normal speed, and slowed down
        val speech = listOf(SpeechAudio(audio(1.0)), SpeechAudio(audio(2.0)))
        val (x, y) = badge

        val video = service().generateVideo(plan, speech, "en").getOrThrow()

        assertEquals(4.0, seconds(video.file), 0.3)
        assertFalse("the first second is at normal speed", pixel(video.file, frame = 10, x = x, y = y).isRed())
        assertTrue("then two seconds slowed down", pixel(video.file, frame = 60, x = x, y = y).isRed())
        assertFalse("and a second at normal speed again", pixel(video.file, frame = 110, x = x, y = y).isRed())
    }

    @Test
    fun `a conversation of several clips leaves only the video behind`() = runBlocking {
        val plan = conversation("A: Hello\nB: Good morning", 1 to true, 1 to false)
        val speech = List(4) { SpeechAudio(audio(1.0)) }

        val video = service().generateVideo(plan, speech, "en").getOrThrow()

        assertEquals(4.0, seconds(video.file), 0.3)
        assertEquals(listOf(video.file), context.cacheDir.walkTopDown().filter { it.isFile }.toList())
    }

    @Test
    fun `speech for another number of clips is refused`() = runBlocking {
        val plan = conversation("A: Hello\nB: Good morning")

        val result = service().generateVideo(plan, listOf(SpeechAudio(audio(1.0))), "en")

        assertEquals("2 clips, but speech for 1", result.exceptionOrNull()!!.message)
        assertEquals(emptyList<File>(), context.cacheDir.walkTopDown().filter { it.isFile }.toList())
    }

    @Test
    fun `a clip that cannot be encoded fails the video and leaves nothing behind`() = runBlocking {
        val plan = conversation("A: Hello\nB: Good morning")
        val broken = File(folder.root, "broken.mp3").apply { writeText("this is not audio") }

        val result = service(audioLength = 1.0).generateVideo(plan, listOf(SpeechAudio(audio(1.0)), SpeechAudio(broken)), "en")

        assertTrue(result.exceptionOrNull()!!.message!!.startsWith("FFmpeg could not encode the video."))
        assertEquals(emptyList<File>(), context.cacheDir.walkTopDown().filter { it.isFile }.toList())
    }

    // --------------------------------------------------------------- preview

    private val red = Color.parseColor("#dc3232")

    @Test
    fun `the preview of a conversation highlights what is spoken first`() = runBlocking {
        val plan = conversation("직원: 혼자 오셨어요?\n관광객: 아니요.")
        val (spokenX, spokenY) = edgeOf(plan, 4) // 혼
        val (nameX, nameY) = edgeOf(plan, 0) // 직

        val preview = BitmapFactory.decodeFile(service().getPreviewImage(plan, "ko").getOrThrow().path)

        assertEquals(red, preview.getPixel(spokenX, spokenY))
        assertFalse(preview.getPixel(nameX, nameY) == red)
        assertFalse(preview.getPixel(badge.first, badge.second) == red)
    }

    @Test
    fun `the preview of a video that begins slowly is marked as slow`() = runBlocking {
        val slowFirst = RenderPlanner.plan(
            RenderRequest(text = "Hello", steps = steps(2 to true, 3 to false), isSequence = true)
        )
        val slowOnly = RenderPlanner.plan(RenderRequest(text = "Hello", steps = steps(3 to true)))
        val normalFirst = RenderPlanner.plan(
            RenderRequest(text = "Hello", steps = steps(3 to false, 2 to true), isSequence = true)
        )

        fun marked(plan: RenderPlan): Boolean = runBlocking {
            val preview = BitmapFactory.decodeFile(service().getPreviewImage(plan, "en").getOrThrow().path)
            preview.getPixel(badge.first, badge.second) == red
        }

        assertTrue(marked(slowFirst))
        assertTrue(marked(slowOnly))
        assertFalse(marked(normalFirst))
    }

    @Test
    fun `the preview is a picture of the first frame`() = runBlocking {
        val preview = service().getPreviewImage(plan("Hello world"), "en").getOrThrow()

        val size = execute(
            listOf(
                ffprobe!!, "-v", "error", "-select_streams", "v:0",
                "-show_entries", "stream=codec_name,width,height", "-of", "csv=p=0", preview.path
            )
        ).text
        assertEquals("png,1280,720", size)
    }
}
