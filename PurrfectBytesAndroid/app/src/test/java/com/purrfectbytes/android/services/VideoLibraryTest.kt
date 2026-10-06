package com.purrfectbytes.android.services

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VideoLibraryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val storage = MediaStorage(context)

    private var now = 1_790_553_600_000L

    /** The library as it is after the app was started again. */
    private fun library(maxVideos: Int = 3) = VideoLibrary(storage, maxVideos, clock = { now })

    /** A video that was rendered a minute after the one before. */
    private fun rendered(seconds: Double = 12.5): RenderedVideo {
        now += 60_000
        val file = storage.newVideoFile().apply { writeText("video") }
        return RenderedVideo(file, seconds)
    }

    private fun filesOfTheFolder(): Set<File> = storage.videoFolder().listFiles().orEmpty().toSet()

    @Test
    fun `a video is kept with what is known of it`() {
        val video = rendered(seconds = 36.2)

        val added = library().add(video, "직원: 혼자 오셨어요?\n관광객: 아니요.", "Conversation of 2 lines, 3 repetitions")

        assertEquals(
            RecentVideo(video.file, "직원: 혼자 오셨어요?\n관광객: 아니요.", "Conversation of 2 lines, 3 repetitions", 36.2, now),
            added
        )
        assertEquals(listOf(added), library().list())
        assertEquals(added, library().find(video.file))
        assertEquals("직원: 혼자 오셨어요?", added.title)
    }

    @Test
    fun `a video goes by the first line of its text that is not empty`() {
        fun titleOf(text: String) = RecentVideo(File("video.mp4"), text, "", 1.0, 0).title

        assertEquals("Hello", titleOf("\n  \n  Hello  \nworld"))
        assertEquals("", titleOf(" \n "))
    }

    @Test
    fun `the newest video comes first`() {
        val library = library()

        val texts = listOf("one", "two", "three").onEach { library.add(rendered(), it, "") }

        assertEquals(texts.reversed(), library.list().map { it.text })
    }

    @Test
    fun `the oldest videos make room for a new one`() {
        val library = library(maxVideos = 3)
        val videos = listOf("one", "two", "three", "four", "five").map { text ->
            rendered().also { library.add(it, text, "") }
        }

        assertEquals(listOf("five", "four", "three"), library.list().map { it.text })
        assertFalse(videos[0].file.exists())
        assertFalse(videos[1].file.exists())
        assertEquals(6, filesOfTheFolder().size)
        assertEquals(filesOfTheFolder(), library.files().toSet())
    }

    @Test
    fun `a library that holds more than it may gives way when a video is added`() {
        val generous = library(maxVideos = 5)
        repeat(5) { generous.add(rendered(), "video $it", "") }

        library(maxVideos = 2).add(rendered(), "the newest", "")

        assertEquals(listOf("the newest", "video 4"), library().list().map { it.text })
    }

    @Test
    fun `a video that is uploaded says where it is watched`() {
        val library = library()
        val hello = rendered().also { library.add(it, "Hello", "") }
        val bye = rendered().also { library.add(it, "Bye", "") }

        val uploaded = library.markUploaded(hello.file, "https://www.youtube.com/watch?v=abc123")

        assertEquals("https://www.youtube.com/watch?v=abc123", uploaded?.uploadedUrl)
        assertEquals("Hello", uploaded?.text)
        assertEquals(
            listOf(null, "https://www.youtube.com/watch?v=abc123"),
            library().list().map { it.uploadedUrl }
        )
        assertNull(library().find(bye.file)?.uploadedUrl)
    }

    @Test
    fun `a video that is not in the library cannot be marked`() {
        val stranger = storage.newVideoFile().apply { writeText("video") }

        assertNull(library().markUploaded(stranger, "https://www.youtube.com/watch?v=abc123"))

        assertEquals(setOf(stranger), filesOfTheFolder())
    }

    @Test
    fun `a video is deleted with what was known of it`() {
        val library = library()
        val hello = rendered().also { library.add(it, "Hello", "") }
        val bye = rendered().also { library.add(it, "Bye", "") }

        library.delete(hello.file)

        assertEquals(listOf("Bye"), library.list().map { it.text })
        assertFalse(hello.file.exists())
        assertEquals(2, filesOfTheFolder().size)
        assertTrue(bye.file.exists())
    }

    @Test
    fun `files that belong to no video of the library are not listed`() {
        val library = library()
        val hello = rendered().also { library.add(it, "Hello", "") }
        val clip = storage.newVideoFile("clip").apply { writeText("a clip that was left over") }
        File(storage.videoFolder(), "output_without_video.json").writeText("""{"text": "gone"}""")
        val broken = storage.newVideoFile().apply { writeText("video") }
        File(storage.videoFolder(), broken.nameWithoutExtension + ".json").writeText("""{"text": "half""")

        assertEquals(listOf(hello.file), library.list().map { it.file })
        assertFalse(clip in library.files())
        assertFalse(broken in library.files())
    }

    @Test
    fun `the videos of the library outlive the clearing at the start`() {
        val library = library()
        val hello = rendered().also { library.add(it, "Hello", "") }
        val clip = storage.newVideoFile("clip").apply { writeText("a clip that was left over") }
        val speech = storage.newSpeechFile("mp3").apply { writeText("speech") }

        storage.removeLeftovers(olderThan = System.currentTimeMillis() + 60_000, keep = library.files())

        assertEquals(listOf("Hello"), library().list().map { it.text })
        assertTrue(hello.file.exists())
        assertFalse(clip.exists())
        assertFalse(speech.exists())
    }

    @Test
    fun `without the library nothing is spared`() {
        val hello = rendered().also { library().add(it, "Hello", "") }

        storage.removeLeftovers(olderThan = System.currentTimeMillis() + 60_000)

        assertFalse(hello.file.exists())
        assertTrue(library().list().isEmpty())
        assertTrue(filesOfTheFolder().isEmpty())
    }
}
