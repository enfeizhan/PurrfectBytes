package com.purrfectbytes.android.viewmodels

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.purrfectbytes.android.services.MediaStorage
import com.purrfectbytes.android.services.RenderedVideo
import com.purrfectbytes.android.services.VideoLibrary
import com.purrfectbytes.android.services.YouTubePlaylist
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/** From the tap on "Upload to YouTube" to the link, with a stand-in for YouTube. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UploadFlowTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val youTube = FakeYouTube(context)

    private val description = "📚 Study Journal Entry\n\nToday I learned this.\n\n" +
        "#Japanese #JLPT #日本語 #LanguageLearning #Japanese"

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** A video as it is left by a render. */
    private fun rendered(text: String): File {
        val storage = MediaStorage(context)
        val file = storage.newVideoFile().apply { writeText("video of $text") }
        VideoLibrary(storage).add(RenderedVideo(file, 3.0), text, "")
        return file
    }

    /** The app with [video] ready and title and description written. */
    private fun viewModel(video: File?): MainViewModel {
        val model = testViewModel(context, youTube = youTube)
        video?.let { model.selectVideo(it) }
        model.updateYoutubeTitle("My Study Journal: Japanese Sentence")
        model.updateYoutubeDescription(description)
        return model
    }

    private fun MainViewModel.state() = uiState.value

    @Test
    fun `whoever is signed in is connected from the start, with channel and playlists`() {
        val state = viewModel(video = null).state()

        assertTrue(state.isYouTubeConnected)
        assertEquals("Cat Channel", state.selectedChannelName)
        assertEquals(listOf("Japanese", "Korean"), state.availablePlaylists.map { it.title })
        assertNull(state.errorMessage)
    }

    @Test
    fun `a video is uploaded with what is on screen`() {
        val video = rendered("猫です")
        val model = viewModel(video)
        model.updateYouTubePrivacy("Unlisted")
        model.updateYouTubePlaylist(YouTubePlaylist("p2", "Korean"))

        model.uploadToYouTube()

        val upload = youTube.uploads.single()
        assertEquals(video, upload.video)
        assertEquals("My Study Journal: Japanese Sentence", upload.title)
        assertEquals(description, upload.description)
        assertEquals("Unlisted", upload.privacy)
        assertEquals("p2", upload.playlistId)
        assertEquals("token-1", upload.accessToken)
    }

    @Test
    fun `the hashtags of the description are the tags of the upload`() {
        val model = viewModel(rendered("猫です"))

        model.uploadToYouTube()

        assertEquals(listOf("Japanese", "JLPT", "日本語", "LanguageLearning"), youTube.uploads.single().tags)
    }

    @Test
    fun `a description without hashtags is uploaded without tags`() {
        val model = viewModel(rendered("猫です"))
        model.updateYoutubeDescription("Just a description")

        model.uploadToYouTube()

        assertEquals(emptyList<String>(), youTube.uploads.single().tags)
    }

    @Test
    fun `an upload leaves a link that stays`() {
        val video = rendered("猫です")
        val model = viewModel(video)

        model.uploadToYouTube()

        val state = model.state()
        assertEquals("YouTube Upload Successful! Video ID: video1", state.successMessage)
        assertNull(state.errorMessage)
        assertFalse(state.isUploadingToYouTube)
        assertEquals("https://www.youtube.com/watch?v=video1", state.lastUploadUrl)
        assertEquals("https://www.youtube.com/watch?v=video1", state.uploadOf(video))
        assertEquals("https://www.youtube.com/watch?v=video1", state.recentVideos.single().uploadedUrl)

        model.dismissSuccess()
        assertEquals("https://www.youtube.com/watch?v=video1", model.state().uploadOf(video))
    }

    @Test
    fun `a video is uploaded once`() {
        val model = viewModel(rendered("猫です"))
        model.uploadToYouTube()

        model.uploadToYouTube()

        assertEquals(1, youTube.uploads.size)
        assertEquals(
            "This video was already uploaded - render or choose another one",
            model.state().errorMessage
        )
    }

    @Test
    fun `a video that was uploaded is known as such when the app is started again`() {
        val video = rendered("猫です")
        viewModel(video).uploadToYouTube()

        val restarted = viewModel(video)
        restarted.uploadToYouTube()

        assertEquals("https://www.youtube.com/watch?v=video1", restarted.state().uploadOf(video))
        assertNull("the link of the run before belongs to its video", restarted.state().lastUploadUrl)
        assertEquals(1, youTube.uploads.size)
        assertEquals(
            "This video was already uploaded - render or choose another one",
            restarted.state().errorMessage
        )
    }

    @Test
    fun `another video can be uploaded after the first`() {
        val cat = rendered("猫です")
        val dog = rendered("犬です")
        val model = viewModel(cat)
        model.uploadToYouTube()

        model.selectVideo(dog)
        assertNull(model.state().uploadOf(dog))
        model.uploadToYouTube()

        assertEquals(listOf(cat, dog), youTube.uploads.map { it.video })
        assertEquals("https://www.youtube.com/watch?v=video2", model.state().uploadOf(dog))
        assertEquals("https://www.youtube.com/watch?v=video1", model.state().uploadOf(cat))
        assertEquals("https://www.youtube.com/watch?v=video2", model.state().lastUploadUrl)
    }

    @Test
    fun `a second tap while the upload is running does not upload again`() {
        youTube.hold = CompletableDeferred()
        val model = viewModel(rendered("猫です"))

        model.uploadToYouTube()
        assertTrue(model.state().isUploadingToYouTube)
        model.uploadToYouTube()
        model.uploadToYouTube()
        youTube.hold!!.complete(Unit)

        assertEquals(1, youTube.uploads.size)
        assertFalse(model.state().isUploadingToYouTube)
        assertEquals("YouTube Upload Successful! Video ID: video1", model.state().successMessage)
    }

    @Test
    fun `an upload that failed can be tried again`() {
        val video = rendered("猫です")
        val model = viewModel(video)
        youTube.failure = IOException("Unable to resolve host")

        model.uploadToYouTube()

        assertEquals("YouTube Upload Failed: Unable to resolve host", model.state().errorMessage)
        assertNull(model.state().uploadOf(video))
        assertNull(model.state().lastUploadUrl)
        assertFalse(model.state().isUploadingToYouTube)

        youTube.failure = null
        model.uploadToYouTube()

        assertEquals(2, youTube.uploads.size)
        assertEquals("https://www.youtube.com/watch?v=video2", model.state().uploadOf(video))
    }

    @Test
    fun `an upload needs a title`() {
        val model = viewModel(rendered("猫です"))
        model.updateYoutubeTitle("  ")

        model.uploadToYouTube()

        assertEquals(
            "The title is empty - write one or generate the title and description",
            model.state().errorMessage
        )
        assertTrue(youTube.uploads.isEmpty())
    }

    @Test
    fun `an upload needs a video that is still there`() {
        val video = rendered("猫です")
        val model = viewModel(video)
        video.delete()

        model.uploadToYouTube()

        assertEquals("No video available to upload", model.state().errorMessage)
        assertTrue(youTube.uploads.isEmpty())
    }

    @Test
    fun `a video that was deleted is not uploaded`() {
        val video = rendered("猫です")
        val model = viewModel(video)

        model.deleteVideo(video)
        model.uploadToYouTube()

        assertEquals("No video available to upload", model.state().errorMessage)
        assertTrue(youTube.uploads.isEmpty())
    }

    @Test
    fun `a sign-in that has expired asks to connect again`() {
        val model = viewModel(rendered("猫です"))
        youTube.failure = com.purrfectbytes.android.services.YouTubeSignInRequiredException("expired")

        model.uploadToYouTube()

        assertEquals("The YouTube sign-in has expired. Please connect again.", model.state().errorMessage)
        assertFalse(model.state().isYouTubeConnected)
        assertFalse(youTube.signedIn)
    }
}
