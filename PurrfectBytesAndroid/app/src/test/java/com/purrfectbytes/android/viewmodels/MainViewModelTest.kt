package com.purrfectbytes.android.viewmodels

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.purrfectbytes.android.services.AppSettings
import com.purrfectbytes.android.services.EdgeTtsProtocol
import com.purrfectbytes.android.services.EdgeVoice
import com.purrfectbytes.android.services.LanguageDetector
import com.purrfectbytes.android.services.RecentVideo
import com.purrfectbytes.android.services.SequenceStep
import com.purrfectbytes.android.services.SourceStore
import com.purrfectbytes.android.services.SpeedSequence
import com.purrfectbytes.android.services.StudyItem
import com.purrfectbytes.android.services.TTSService
import com.purrfectbytes.android.services.YouTubeChannel
import com.purrfectbytes.android.services.YouTubeMetadataFormat
import com.purrfectbytes.android.services.YouTubePlaylist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
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

/** The models and the language detector are replaced by stand-ins: nothing here goes online. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainViewModelTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dispatcher = StandardTestDispatcher()

    private val answer = "My Study Journal: Japanese Sentence - \"猫です\" | Reading & Pronunciation\n\nThe description"

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(
        detector: LanguageDetector = FakeDetector(null),
        gemini: suspend (String) -> String? = { answer },
        voices: List<EdgeVoice> = emptyList()
    ): MainViewModel = testViewModel(context, detector, gemini, voices)

    /** A part of the voices of the service; see [EdgeTtsProtocol.parseVoices]. */
    private val voicesOfTheService = EdgeTtsProtocol.parseVoices(
        javaClass.getResource("/edge_voices_sample.json")!!.readText()
    )

    /** Lets work on other threads finish as well as work that waits for the clock. */
    private fun TestScope.settle(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        advanceUntilIdle()
        while (!done() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
            advanceUntilIdle()
        }
        assertTrue("timed out", done())
    }

    // ------------------------------------------------------------- metadata

    @Test
    fun `a failed request shows an error and leaves the fields alone`() = runTest(dispatcher) {
        val model = viewModel(gemini = { throw IOException("Unable to resolve host") })
        model.updateText("猫です")
        model.updateYoutubeTitle("My own title")

        model.generateMetadata()
        settle { !model.uiState.value.isGeneratingMetadata && model.uiState.value.errorMessage != null }

        val state = model.uiState.value
        assertEquals(
            "Could not write the title and description. Gemini could not be reached: Unable to resolve host",
            state.errorMessage
        )
        assertNull(state.successMessage)
        assertEquals("My own title", state.youtubeTitle)
        assertEquals("", state.youtubeDescription)
        assertNull(state.metadataText)
    }

    @Test
    fun `an answer fills in title and description`() = runTest(dispatcher) {
        val model = viewModel()
        model.updateText("猫です")

        model.generateMetadata()
        settle { model.uiState.value.youtubeTitle.isNotEmpty() }

        val state = model.uiState.value
        assertEquals("My Study Journal: Japanese Sentence - \"猫です\" | Reading & Pronunciation", state.youtubeTitle)
        assertEquals("The description", state.youtubeDescription)
        assertEquals("猫です", state.metadataText)
        assertNull(state.errorMessage)
        assertFalse(state.isGeneratingMetadata)
    }

    @Test
    fun `text for another video than the one that is ready is noticed`() {
        val state = MainUiState(videoText = "猫です", metadataText = "犬です")

        assertTrue(state.metadataIsForAnotherText)
        assertFalse(state.copy(metadataText = "猫です").metadataIsForAnotherText)
        assertFalse("typed by hand", state.copy(metadataText = null).metadataIsForAnotherText)
        assertFalse("no video yet", state.copy(videoText = null).metadataIsForAnotherText)
    }

    // --------------------------------------------------- items to be explained

    private val found = """[{"kind": "vocabulary", "term": "猫", "phonetics": "ねこ", "meaning": "cat"},
        {"kind": "grammar", "term": "です", "phonetics": "", "meaning": "polite copula"},
        {"kind": "vocabulary", "term": "犬", "phonetics": "いぬ", "meaning": "dog"}]"""

    /** Stands for Gemini: lists the items when asked for them, and writes the text otherwise. */
    private class Model(private val items: String, private val text: String) {
        val prompts = mutableListOf<String>()

        suspend fun answer(prompt: String): String {
            prompts += prompt
            return if (prompt.contains("strict JSON array")) items else text
        }
    }

    @Test
    fun `the items that were found are all ticked`() = runTest(dispatcher) {
        val gemini = Model(found, answer)
        val model = viewModel(gemini = gemini::answer)
        model.updateText(" 猫です ")

        model.extractItems()
        settle { model.uiState.value.hasItems }

        val state = model.uiState.value
        assertEquals(listOf("猫", "です", "犬"), state.studyItems.map { it.term })
        assertEquals(setOf(0, 1, 2), state.approvedItems)
        assertEquals(state.studyItems, state.itemsToExplain)
        assertFalse(state.isExtractingItems)
        assertNull(state.errorMessage)
        assertEquals(listOf(YouTubeMetadataFormat.extractionPrompt("猫です")), gemini.prompts)
    }

    @Test
    fun `the description explains the items that are still ticked`() = runTest(dispatcher) {
        val gemini = Model(found, answer)
        val model = viewModel(gemini = gemini::answer)
        model.updateText("猫です")
        model.extractItems()
        settle { model.uiState.value.hasItems }

        model.toggleItem(2)
        model.toggleItem(0)
        model.toggleItem(0)
        model.generateMetadata()
        settle { model.uiState.value.youtubeTitle.isNotEmpty() }

        val kept = listOf(
            StudyItem(isGrammar = false, term = "猫", phonetics = "ねこ", meaning = "cat"),
            StudyItem(isGrammar = true, term = "です", phonetics = "", meaning = "polite copula")
        )
        assertEquals(setOf(0, 1), model.uiState.value.approvedItems)
        assertEquals(YouTubeMetadataFormat.prompt("猫です", kept), gemini.prompts.last())
    }

    @Test
    fun `with every item unticked the description explains none`() = runTest(dispatcher) {
        val gemini = Model(found, answer)
        val model = viewModel(gemini = gemini::answer)
        model.updateText("猫です")
        model.extractItems()
        settle { model.uiState.value.hasItems }

        (0..2).forEach { model.toggleItem(it) }
        model.generateMetadata()
        settle { model.uiState.value.youtubeTitle.isNotEmpty() }

        assertEquals(YouTubeMetadataFormat.prompt("猫です", emptyList()), gemini.prompts.last())
    }

    @Test
    fun `without a review the model chooses what to explain`() = runTest(dispatcher) {
        val gemini = Model(found, answer)
        val model = viewModel(gemini = gemini::answer)
        model.updateText("猫です")

        model.generateMetadata()
        settle { model.uiState.value.youtubeTitle.isNotEmpty() }

        assertNull(model.uiState.value.itemsToExplain)
        assertEquals(listOf(YouTubeMetadataFormat.prompt("猫です")), gemini.prompts)
    }

    @Test
    fun `items of another text are forgotten`() = runTest(dispatcher) {
        val gemini = Model(found, answer)
        val model = viewModel(gemini = gemini::answer)
        model.updateText("猫です")
        model.extractItems()
        settle { model.uiState.value.hasItems }

        model.updateText("猫です ")
        assertTrue("a space at the end is not another text", model.uiState.value.hasItems)

        model.updateText("犬です")
        assertFalse(model.uiState.value.hasItems)
        assertTrue(model.uiState.value.studyItems.isEmpty())

        // Not even the text it was before brings them back
        model.updateText("猫です")
        model.generateMetadata()
        settle { model.uiState.value.youtubeTitle.isNotEmpty() }
        assertEquals(YouTubeMetadataFormat.prompt("猫です"), gemini.prompts.last())
    }

    @Test
    fun `items that arrive for a text that has changed are dropped`() = runTest(dispatcher) {
        lateinit var model: MainViewModel
        model = viewModel(gemini = {
            model.updateText("犬です")
            found
        })
        model.updateText("猫です")

        model.extractItems()
        settle { !model.uiState.value.isExtractingItems }

        assertFalse(model.uiState.value.hasItems)
        assertTrue(model.uiState.value.studyItems.isEmpty())
    }

    @Test
    fun `items that cannot be found are reported`() = runTest(dispatcher) {
        val model = viewModel(gemini = { "Sorry, I cannot do that." })
        model.updateText("猫です")

        model.extractItems()
        settle { model.uiState.value.errorMessage != null }

        assertEquals(
            "Extraction failed: Could not extract items - the AI response had no item list",
            model.uiState.value.errorMessage
        )
        assertFalse(model.uiState.value.hasItems)
        assertFalse(model.uiState.value.isExtractingItems)
    }

    @Test
    fun `items need text`() {
        val model = viewModel()

        model.extractItems()

        assertEquals("Please enter some text", model.uiState.value.errorMessage)
    }

    // ----------------------------------------------------------- text sources

    private val described = "My Study Journal: Japanese Sentence - \"猫です\" | Reading & Pronunciation\n\n" +
        "Intro\n\n📌 Credit:\n\nThis sentence is sourced from another creator's content." +
        "\n\n👍 Like and subscribe!"

    @Test
    fun `a saved source is chosen, and still chosen when the app is started again`() {
        val model = viewModel()

        assertTrue(model.saveSource(" Textbook ", "From my textbook."))

        val state = model.uiState.value
        assertEquals(listOf("Textbook"), state.sources.map { it.name })
        assertEquals(state.sources.single().id, state.selectedSourceId)
        assertEquals("Saved source: Textbook", state.successMessage)

        val restarted = viewModel().uiState.value
        assertEquals(state.sources, restarted.sources)
        assertEquals(state.selectedSourceId, restarted.selectedSourceId)
    }

    @Test
    fun `a source without name or credit is not saved`() {
        val model = viewModel()

        assertFalse(model.saveSource("Textbook", " "))
        assertEquals("Credit line is required", model.uiState.value.errorMessage)

        assertFalse(model.saveSource("", "From my textbook."))
        assertEquals("Source name is required", model.uiState.value.errorMessage)
        assertTrue(model.uiState.value.sources.isEmpty())
    }

    @Test
    fun `the source that is chosen is given credit in the description`() = runTest(dispatcher) {
        val model = viewModel(gemini = { described })
        model.saveSource("Textbook", "From 新完全マスター N1 by 3A Corporation.")
        model.updateText("猫です")

        model.generateMetadata()
        settle { model.uiState.value.youtubeTitle.isNotEmpty() }

        assertEquals(
            "Intro\n\n📌 Credit:\n\nFrom 新完全マスター N1 by 3A Corporation.\n\n👍 Like and subscribe!",
            model.uiState.value.youtubeDescription
        )
    }

    @Test
    fun `without a source the credit is the general one`() = runTest(dispatcher) {
        val model = viewModel(gemini = { described })
        model.saveSource("Textbook", "From my textbook.")
        model.selectSource(null)
        model.updateText("猫です")

        model.generateMetadata()
        settle { model.uiState.value.youtubeTitle.isNotEmpty() }

        assertTrue(model.uiState.value.youtubeDescription.contains("another creator's content"))
        assertNull(viewModel().uiState.value.selectedSourceId)
    }

    @Test
    fun `a deleted source is no longer chosen`() {
        val model = viewModel()
        model.saveSource("Textbook", "From my textbook.")
        model.saveSource("Drama", "From a drama.")

        model.deleteSelectedSource()

        val state = model.uiState.value
        assertEquals(listOf("Textbook"), state.sources.map { it.name })
        assertNull(state.selectedSourceId)
        assertEquals("Source deleted", state.successMessage)
        assertNull(viewModel().uiState.value.selectedSourceId)

        model.deleteSelectedSource()
        assertEquals("Select a saved source to delete", model.uiState.value.errorMessage)
    }

    @Test
    fun `a source that is gone from the file is not given credit`() = runTest(dispatcher) {
        val model = viewModel(gemini = { throw AssertionError("nothing may be written") })
        model.saveSource("Textbook", "From my textbook.")
        SourceStore(context).delete(model.uiState.value.selectedSourceId!!)
        model.updateText("猫です")

        model.generateMetadata()

        assertEquals("Unknown text source - it may have been deleted", model.uiState.value.errorMessage)
        assertFalse(model.uiState.value.isGeneratingMetadata)
    }

    @Test
    fun `a source that was chosen but is gone is not chosen at the next start`() {
        viewModel().saveSource("Textbook", "From my textbook.")
        val id = viewModel().uiState.value.selectedSourceId!!
        SourceStore(context).delete(id)

        assertNull(viewModel().uiState.value.selectedSourceId)
    }

    // ---------------------------------------------------------------- title

    @Test
    fun `a title is no longer than YouTube takes it`() {
        val model = viewModel()

        model.updateYoutubeTitle("x".repeat(250))
        assertEquals(100, model.uiState.value.youtubeTitle.length)

        // An emoji is two characters to YouTube; it is kept whole or not at all
        model.updateYoutubeTitle("x".repeat(99) + "😀")
        assertEquals("x".repeat(99), model.uiState.value.youtubeTitle)

        model.updateYoutubeTitle("x".repeat(98) + "😀")
        assertEquals("x".repeat(98) + "😀", model.uiState.value.youtubeTitle)
    }

    @Test
    fun `a video that is on YouTube is known by its file`() {
        val uploaded = RecentVideo(
            File("/cache/videos/output_1.mp4"), "猫です", "", 3.0, 0, "https://www.youtube.com/watch?v=abc123"
        )
        val waiting = RecentVideo(File("/cache/videos/output_2.mp4"), "犬です", "", 3.0, 0)
        val state = MainUiState(recentVideos = listOf(waiting, uploaded))

        assertEquals("https://www.youtube.com/watch?v=abc123", state.uploadOf(File("/cache/videos/output_1.mp4")))
        assertNull(state.uploadOf(File("/cache/videos/output_2.mp4")))
        assertNull(state.uploadOf(File("/cache/videos/output_3.mp4")))
        assertNull(state.uploadOf(null))
    }

    // --------------------------------------------------------------- voices

    @Test
    fun `the voices of the language are there to choose from`() = runTest(dispatcher) {
        val model = viewModel(voices = voicesOfTheService)
        settle { model.uiState.value.voices.isNotEmpty() }

        assertEquals(22, model.uiState.value.voices.size)
        assertTrue(model.uiState.value.voices.all { it.language == "en" })

        model.updateLanguage("ja")
        assertTrue("the voices of the language before are not offered", model.uiState.value.voices.isEmpty())
        settle { model.uiState.value.voices.isNotEmpty() }

        val state = model.uiState.value
        assertEquals(listOf("ja-JP-KeitaNeural", "ja-JP-NanamiNeural"), state.voices.map { it.id })
        assertFalse(state.isLoadingVoices)
        assertNull(state.selectedVoice)
        assertEquals("ja-JP-NanamiNeural", state.voiceOfLanguage)
        assertEquals("ja-JP-KeitaNeural", state.secondVoiceOfLanguage?.id)
    }

    @Test
    fun `a detected language brings its voices`() = runTest(dispatcher) {
        val model = testViewModel(context, FakeDetector("ko"), voices = voicesOfTheService)

        model.updateText("안녕하세요, 만나서 반갑습니다.")
        settle { model.uiState.value.voices.any { it.language == "ko" } }

        assertEquals("ko", model.uiState.value.selectedLanguage)
        assertEquals(3, model.uiState.value.voices.size)
    }

    @Test
    fun `a voice is remembered for its language`() = runTest(dispatcher) {
        val model = viewModel(voices = voicesOfTheService)
        model.updateLanguage("ja")
        settle { model.uiState.value.voices.isNotEmpty() }

        model.updateVoice("ja-JP-KeitaNeural")
        model.updateSecondVoice("ja-JP-NanamiNeural")
        model.updateLanguage("ko")
        settle { model.uiState.value.voices.any { it.language == "ko" } }
        assertNull(model.uiState.value.selectedVoice)
        assertNull(model.uiState.value.selectedSecondVoice)

        model.updateLanguage("ja")
        assertEquals("ja-JP-KeitaNeural", model.uiState.value.selectedVoice)
        settle { model.uiState.value.voices.isNotEmpty() }
        assertEquals("ja-JP-KeitaNeural", model.uiState.value.selectedVoice)
        assertEquals("ja-JP-NanamiNeural", model.uiState.value.selectedSecondVoice)

        // The app is started again
        val restarted = viewModel()
        restarted.updateLanguage("ja")
        settle { restarted.uiState.value.voices.isNotEmpty() }
        assertEquals("ja-JP-KeitaNeural", restarted.uiState.value.selectedVoice)
    }

    @Test
    fun `the second speaker can be told from the first`() = runTest(dispatcher) {
        val model = viewModel(voices = voicesOfTheService)
        model.updateLanguage("ja")
        settle { model.uiState.value.voices.isNotEmpty() }

        model.updateVoice("ja-JP-KeitaNeural")

        assertEquals("ja-JP-NanamiNeural", model.uiState.value.secondVoiceOfLanguage?.id)
    }

    @Test
    fun `a voice the service has taken away reads no more`() = runTest(dispatcher) {
        AppSettings(context).rememberVoice("ja", "ja-JP-GoneNeural")
        val model = viewModel(voices = voicesOfTheService)

        model.updateLanguage("ja")
        settle { model.uiState.value.voices.isNotEmpty() }

        assertNull(model.uiState.value.selectedVoice)
    }

    @Test
    fun `without the list the voice that was chosen still reads`() = runTest(dispatcher) {
        AppSettings(context).rememberVoice("ja", "ja-JP-KeitaNeural")
        val model = viewModel()

        model.updateLanguage("ja")
        settle { !model.uiState.value.isLoadingVoices }

        val state = model.uiState.value
        assertTrue(state.voices.isEmpty())
        assertEquals("ja-JP-KeitaNeural", state.selectedVoice)
        assertNull(state.secondVoiceOfLanguage)
        assertNull("the missing list is no error to bother the user with", state.errorMessage)
    }

    @Test
    fun `the phone's engine has one voice and no conversations`() = runTest(dispatcher) {
        val model = viewModel(voices = voicesOfTheService)
        settle { model.uiState.value.voices.isNotEmpty() }
        model.updateVoice("en-GB-SoniaNeural")
        model.updateConversation(true)
        assertTrue(model.uiState.value.isConversation)

        model.updateTtsEngine(TTSService.ENGINE_NATIVE)

        val state = model.uiState.value
        assertFalse(state.canChooseVoice)
        assertFalse(state.isConversation)
        assertTrue(state.voices.isEmpty())
        assertNull(state.selectedVoice)

        model.updateConversation(true)
        assertFalse(model.uiState.value.isConversation)

        // Back with Edge TTS the voice is the one that was chosen
        model.updateTtsEngine(TTSService.ENGINE_EDGE)
        settle { model.uiState.value.voices.isNotEmpty() }
        assertEquals("en-GB-SoniaNeural", model.uiState.value.selectedVoice)
    }

    // ------------------------------------------------------------- sequence

    private fun steps(vararg steps: Pair<Int, Boolean>) = steps.map { SequenceStep(it.first, it.second) }

    @Test
    fun `a sequence starts as three normal, four slow, three normal and is switched off`() {
        val state = viewModel().uiState.value

        assertFalse(state.useSequence)
        assertEquals(steps(3 to false, 4 to true, 3 to false), state.sequenceSteps)
    }

    @Test
    fun `the steps of a sequence are changed one by one and remembered`() {
        val model = viewModel()

        model.removeSequenceStep(1)
        model.addSequenceStep()
        model.updateSequenceStep(2, SequenceStep(5, slow = true))
        model.updateSequenceStep(0, SequenceStep(2, slow = false))
        model.updateSequenceStep(9, SequenceStep(9, slow = true))
        model.removeSequenceStep(9)

        assertEquals(steps(2 to false, 3 to false, 5 to true), model.uiState.value.sequenceSteps)
        assertEquals(steps(2 to false, 3 to false, 5 to true), viewModel().uiState.value.sequenceSteps)
        assertFalse("whether it is used is not remembered", viewModel().uiState.value.useSequence)
    }

    @Test
    fun `a sequence has no more steps than can be played`() {
        val model = viewModel()

        repeat(30) { model.addSequenceStep() }

        assertEquals(SpeedSequence.MAX_STEPS, model.uiState.value.sequenceSteps.size)
    }

    @Test
    fun `what is remembered of a sequence is the last that could be played`() {
        val model = viewModel()

        repeat(3) { model.removeSequenceStep(0) }

        assertTrue(model.uiState.value.sequenceSteps.isEmpty())
        assertEquals(steps(3 to false), viewModel().uiState.value.sequenceSteps)
    }

    // ------------------------------------------------ what cannot be rendered

    @Test
    fun `a conversation of one line is refused before anything is generated`() {
        val model = viewModel()
        model.updateText("Only one line")
        model.updateConversation(true)

        model.generateNativeVideo()

        assertEquals(
            "Conversation mode needs at least 2 lines of text - each line alternates between the two voices",
            model.uiState.value.errorMessage
        )
        assertFalse(model.uiState.value.isConvertingVideo)

        model.dismissError()
        model.generatePreview()
        assertTrue(model.uiState.value.errorMessage!!.startsWith("Conversation mode needs at least 2 lines"))
        assertFalse(model.uiState.value.isGeneratingPreview)
    }

    @Test
    fun `an override with another number of lines is refused`() {
        val model = viewModel()
        model.updateText("A: 会議を行った\nB: 京都に行った")
        model.updateConversation(true)
        model.updateVoicedTextUse(true)
        model.updateVoicedText("会議をおこなった")

        model.generateNativeVideo()

        assertEquals(
            "Pronunciation override must have the same number of lines as the text (2), got 1",
            model.uiState.value.errorMessage
        )
    }

    @Test
    fun `a sequence that cannot be played is refused`() {
        val model = viewModel()
        model.updateText("Hello")
        model.updateSequenceUse(true)
        model.updateSequenceStep(0, SequenceStep(60, slow = false))
        model.updateSequenceStep(1, SequenceStep(60, slow = true))

        model.generateNativeVideo()

        assertEquals("Sequence totals 123 repetitions (max 100)", model.uiState.value.errorMessage)

        repeat(3) { model.removeSequenceStep(0) }
        model.generateNativeVideo()
        assertEquals("Add at least one sequence step", model.uiState.value.errorMessage)
    }

    @Test
    fun `a sequence that is switched off stands in nobody's way`() {
        val model = viewModel()
        model.updateText(" ")
        repeat(3) { model.removeSequenceStep(0) }

        model.generateNativeVideo()

        assertEquals("Please enter some text", model.uiState.value.errorMessage)
    }

    // -------------------------------------------------------------- options

    @Test
    fun `uploads are private until the user chooses otherwise, and the choice is remembered`() {
        assertEquals("Private", viewModel().uiState.value.selectedPrivacy)

        viewModel().updateYouTubePrivacy("Public")

        assertEquals("Public", viewModel().uiState.value.selectedPrivacy)
    }

    @Test
    fun `the number of repetitions stays in range`() {
        val model = viewModel()

        model.updateRepetitions(0)
        assertEquals(1, model.uiState.value.repetitions)
        model.updateRepetitions(250)
        assertEquals(100, model.uiState.value.repetitions)
        model.updateRepetitions(7)
        assertEquals(7, model.uiState.value.repetitions)
    }

    @Test
    fun `messages are dismissed one by one`() {
        val model = viewModel()
        model.onCameraPermissionDenied(blocked = false)
        model.onTextBlockClick("text")

        model.dismissSuccess()
        assertEquals("The camera can only be used with the camera permission.", model.uiState.value.errorMessage)
        assertNull(model.uiState.value.successMessage)

        model.dismissError()
        assertNull(model.uiState.value.errorMessage)
    }

    @Test
    fun `a blocked camera points to the settings`() {
        val model = viewModel()

        model.onCameraPermissionDenied(blocked = true)

        assertTrue(model.uiState.value.errorMessage!!.contains("Settings"))
        assertFalse(model.showCamera.value)
    }

    // ------------------------------------------------------------- language

    @Test
    fun `the language follows the text`() = runTest(dispatcher) {
        val model = viewModel(FakeDetector("ja"))

        model.updateText("吾輩は猫である。名前はまだ無い。")
        advanceUntilIdle()

        assertEquals("ja", model.uiState.value.selectedLanguage)
        assertEquals("✓ Detected: Japanese", model.uiState.value.detectedLanguageNotice)
    }

    @Test
    fun `short text is not enough to tell the language`() = runTest(dispatcher) {
        val detector = FakeDetector("ja")
        val model = viewModel(detector)

        model.updateText("猫です")
        advanceUntilIdle()

        assertEquals(0, detector.asked)
        assertEquals("en", model.uiState.value.selectedLanguage)
    }

    @Test
    fun `typing does not ask again for every letter`() = runTest(dispatcher) {
        val detector = FakeDetector("ja")
        val model = viewModel(detector)

        "吾輩は猫である。名前はまだ無い。".indices.forEach { end ->
            model.updateText("吾輩は猫である。名前はまだ無い。".substring(0, end + 1))
        }
        advanceUntilIdle()

        assertEquals(1, detector.asked)
    }

    @Test
    fun `a language chosen by hand is kept`() = runTest(dispatcher) {
        val model = viewModel(FakeDetector("zh"))
        model.updateText("吾輩は猫である。名前はまだ無")
        advanceUntilIdle()
        assertEquals("zh", model.uiState.value.selectedLanguage)

        model.updateLanguage("ja")
        model.updateText("吾輩は猫である。名前はまだ無い。")
        advanceUntilIdle()

        val state = model.uiState.value
        assertEquals("ja", state.selectedLanguage)
        assertEquals(
            "Looks like Chinese. Keeping your choice, Japanese - tap Detect Language to switch.",
            state.detectedLanguageNotice
        )
        assertFalse(state.isDetectingLanguageError)
    }

    @Test
    fun `the Detect Language button overrides the choice`() = runTest(dispatcher) {
        val model = viewModel(FakeDetector("zh"))
        model.updateLanguage("ja")
        model.updateText("吾輩は猫である。名前はまだ無い。")
        advanceUntilIdle()

        model.autoDetectLanguage()
        advanceUntilIdle()

        assertEquals("zh", model.uiState.value.selectedLanguage)
        assertEquals("✓ Detected: Chinese", model.uiState.value.detectedLanguageNotice)
        assertFalse(model.uiState.value.isDetectingLanguage)
    }

    @Test
    fun `text taken from a photo sets the language again`() = runTest(dispatcher) {
        val detector = FakeDetector("ja")
        val model = viewModel(detector)
        model.updateLanguage("fr")

        detector.language = "ko"
        model.onTextBlockClick("안녕하세요, 만나서 반갑습니다.")
        advanceUntilIdle()

        assertEquals("ko", model.uiState.value.selectedLanguage)
        assertEquals("안녕하세요, 만나서 반갑습니다.", model.uiState.value.text)
    }

    @Test
    fun `a language the app cannot speak is reported`() = runTest(dispatcher) {
        val model = viewModel(FakeDetector("sw"))

        model.updateText("Habari ya asubuhi, rafiki yangu")
        advanceUntilIdle()

        assertEquals("en", model.uiState.value.selectedLanguage)
        assertEquals("❌ Detected unsupported language: sw", model.uiState.value.detectedLanguageNotice)
        assertTrue(model.uiState.value.isDetectingLanguageError)
    }

    @Test
    fun `a failing detector is reported and does not stop the app`() = runTest(dispatcher) {
        val detector = object : LanguageDetector() {
            override suspend fun detect(text: String): String? = throw IllegalStateException("model missing")
            override fun close() = Unit
        }
        val model = viewModel(detector)
        model.updateText("some text to look at")

        model.autoDetectLanguage()
        advanceUntilIdle()

        assertEquals("❌ Detection failed: model missing", model.uiState.value.detectedLanguageNotice)
        assertFalse(model.uiState.value.isDetectingLanguage)
    }

    // -------------------------------------------------------------- YouTube

    @Test
    fun `without a sign-in the app starts disconnected`() {
        val state = viewModel().uiState.value

        assertFalse(state.isYouTubeConnected)
        assertTrue(state.youtubeChannels.isEmpty())
    }

    @Test
    fun `disconnecting forgets the account but keeps what was written`() {
        val connected = MainUiState(
            isYouTubeConnected = true,
            youtubeChannels = listOf(YouTubeChannel("c1", "Channel")),
            selectedChannelId = "c1",
            selectedChannelName = "Channel",
            availablePlaylists = listOf(YouTubePlaylist("p1", "Playlist")),
            selectedPlaylistId = "p1",
            selectedPlaylistName = "Playlist",
            youtubeTitle = "Title",
            youtubeDescription = "Description",
            text = "猫です"
        )

        val state = connected.disconnected()

        assertFalse(state.isYouTubeConnected)
        assertTrue(state.youtubeChannels.isEmpty())
        assertTrue(state.availablePlaylists.isEmpty())
        assertNull(state.selectedChannelName)
        assertNull(state.selectedPlaylistId)
        assertEquals("", state.selectedPlaylistName)
        assertEquals("Title", state.youtubeTitle)
        assertEquals("猫です", state.text)
    }

    @Test
    fun `an upload without a video is refused`() {
        val model = viewModel()

        model.uploadToYouTube()

        assertEquals("No video available to upload", model.uiState.value.errorMessage)
        assertFalse(model.uiState.value.isUploadingToYouTube)
    }

    @Test
    fun `a playlist can be taken back`() {
        val model = viewModel()

        model.updateYouTubePlaylist(YouTubePlaylist("p1", "Playlist"))
        assertEquals("p1", model.uiState.value.selectedPlaylistId)

        model.updateYouTubePlaylist(null)
        assertNull(model.uiState.value.selectedPlaylistId)
        assertEquals("", model.uiState.value.selectedPlaylistName)
    }
}
