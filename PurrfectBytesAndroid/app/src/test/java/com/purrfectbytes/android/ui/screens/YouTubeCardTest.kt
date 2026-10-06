package com.purrfectbytes.android.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.purrfectbytes.android.services.StudyItem
import com.purrfectbytes.android.services.TextSource
import com.purrfectbytes.android.services.YouTubeChannel
import com.purrfectbytes.android.services.YouTubePlaylist
import com.purrfectbytes.android.viewmodels.MainUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2000dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class YouTubeCardTest {

    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private var playlist: YouTubePlaylist? = YouTubePlaylist("unset", "unset")

    private val actions = YouTubeActions(
        onConnect = { calls += "connect" },
        onDisconnect = { calls += "disconnect" },
        onShowChannelPicker = { calls += "show channels" },
        onDismissChannelPicker = { calls += "hide channels" },
        onSelectChannel = { calls += "channel ${it.id}" },
        onTitleChange = { calls += "title $it" },
        onDescriptionChange = { calls += "description $it" },
        onProviderChange = { calls += "provider $it" },
        onGenerateMetadata = { calls += "generate" },
        onSelectPlaylist = { playlist = it; calls += "playlist ${it?.id}" },
        onPrivacyChange = { calls += "privacy $it" },
        onUpload = { calls += "upload" },
        onSelectSource = { calls += "source $it" },
        onSaveSource = { name, credit ->
            calls += "save $name: $credit"
            canSave
        },
        onDeleteSource = { calls += "delete source" },
        onExtractItems = { calls += "extract" },
        onToggleItem = { calls += "item $it" }
    )

    /** Whether the next source is saved or refused. */
    private var canSave = true

    private val ready = MainUiState(
        text = "猫です",
        isYouTubeConnected = true,
        youtubeChannels = listOf(YouTubeChannel("c1", "Cat Channel")),
        selectedChannelId = "c1",
        selectedChannelName = "Cat Channel",
        availablePlaylists = listOf(YouTubePlaylist("p1", "Japanese"), YouTubePlaylist("p2", "Korean")),
        youtubeTitle = "A title",
        youtubeDescription = "A description"
    )

    private fun show(state: MainUiState, hasVideo: Boolean = true) {
        compose.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                YouTubeCard(uiState = state, hasVideo = hasVideo, actions = actions)
            }
        }
    }

    @Test
    fun `without a sign-in there is only the button to connect`() {
        show(MainUiState())

        compose.onNodeWithText("Connect to YouTube").performClick()
        assertEquals(listOf("connect"), calls)
        compose.onNodeWithText("Disconnect").assertDoesNotExist()
        compose.onNodeWithText("Upload to YouTube").assertIsNotEnabled()
    }

    @Test
    fun `a connected account can be disconnected`() {
        show(ready)

        compose.onNodeWithText("Channel: Cat Channel").assertIsDisplayed()
        compose.onNodeWithText("Disconnect").performClick()

        assertEquals(listOf("disconnect"), calls)
    }

    @Test
    fun `uploads are private unless something else was chosen`() {
        show(ready)

        compose.onNodeWithText("Private").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Public").performClick()

        assertEquals(listOf("privacy Public"), calls)
    }

    @Test
    fun `an upload needs a connection, a video, a title and a description`() {
        var state by mutableStateOf(ready)
        var hasVideo by mutableStateOf(true)
        compose.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                YouTubeCard(uiState = state, hasVideo = hasVideo, actions = actions)
            }
        }
        val upload = compose.onNodeWithText("Upload to YouTube")

        upload.assertIsEnabled()

        hasVideo = false
        upload.assertIsNotEnabled()
        hasVideo = true

        state = ready.copy(youtubeTitle = " ")
        upload.assertIsNotEnabled()

        state = ready.copy(youtubeDescription = "")
        upload.assertIsNotEnabled()

        state = ready.disconnected()
        upload.assertIsNotEnabled()

        state = ready.copy(isUploadingToYouTube = true)
        compose.onNodeWithText("Uploading...").assertIsNotEnabled()

        state = ready
        upload.performScrollTo().performClick()
        assertEquals(listOf("upload"), calls)
    }

    @Test
    fun `a title written for another text is pointed out`() {
        var state by mutableStateOf(ready.copy(videoText = "猫です", metadataText = "猫です"))
        compose.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                YouTubeCard(uiState = state, hasVideo = true, actions = actions)
            }
        }
        val warning = compose.onNodeWithText("written for a different text", substring = true)

        warning.assertDoesNotExist()

        state = state.copy(videoText = "犬です")
        warning.performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Upload to YouTube").assertIsEnabled()
    }

    @Test
    fun `a playlist can be chosen and taken back`() {
        var state by mutableStateOf(ready)
        compose.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                YouTubeCard(uiState = state, hasVideo = true, actions = actions)
            }
        }

        compose.onNodeWithText("Playlist (optional):").performScrollTo()
        compose.onNodeWithText("Privacy:").assertIsDisplayed()
        // The field is empty until a playlist is chosen; it sits right under its label
        state = ready.copy(selectedPlaylistId = "p1", selectedPlaylistName = "Japanese")
        compose.onNodeWithText("Japanese").performClick()
        compose.onNodeWithText("Korean").performClick()
        assertEquals("p2", playlist?.id)

        compose.onNodeWithText("Japanese").performClick()
        compose.onNodeWithText("No playlist").performClick()
        assertNull(playlist)
    }

    @Test
    fun `the model that writes the text can be chosen`() {
        show(ready)

        compose.onNodeWithText("Claude").performClick()
        compose.onNodeWithText("Auto-Fill Title & Description").performClick()

        assertEquals(listOf("provider anthropic", "generate"), calls)
    }

    // -------------------------------------------------------------- sources

    private val sources = listOf(
        TextSource("3f9a1c2e", "Shin Kanzen Master N1", "From 新完全マスター N1 by 3A Corporation.", ""),
        TextSource("b7d40a19", "Drama", "From a drama.", "")
    )

    @Test
    fun `the credit is general until a source is chosen`() {
        show(ready.copy(sources = sources))

        compose.onNodeWithText("The credit of a saved source takes the place of the general one").assertIsDisplayed()
        compose.onNodeWithText("Delete source").assertIsNotEnabled()
        compose.onNodeWithText("— Generic credit —").performClick()
        compose.onNodeWithText("Drama").performClick()

        assertEquals(listOf("source b7d40a19"), calls)
    }

    @Test
    fun `the source that is chosen shows its credit and can be deleted or left`() {
        show(ready.copy(sources = sources, selectedSourceId = "3f9a1c2e"))

        compose.onNodeWithText("From 新完全マスター N1 by 3A Corporation.").assertIsDisplayed()
        compose.onNodeWithText("Delete source").assertIsEnabled().performClick()
        compose.onNodeWithText("Shin Kanzen Master N1").performClick()
        compose.onNodeWithText("— Generic credit —").performClick()

        assertEquals(listOf("delete source", "source null"), calls)
    }

    @Test
    fun `a source that is gone from the list is not shown as chosen`() {
        show(ready.copy(sources = sources, selectedSourceId = "gone"))

        compose.onNodeWithText("— Generic credit —").assertIsDisplayed()
        compose.onNodeWithText("Delete source").assertIsNotEnabled()
    }

    @Test
    fun `a new source is saved with its name and its credit`() {
        show(ready)
        val name = hasSetTextAction() and hasText("Source name")
        val credit = hasSetTextAction() and hasText("Exact credit line")

        compose.onNode(name).assertDoesNotExist()
        compose.onNodeWithText("+ New source").performClick()
        compose.onNodeWithText("Save source").assertIsNotEnabled()

        compose.onNode(name).performTextInput("Textbook")
        compose.onNodeWithText("Save source").assertIsNotEnabled()
        compose.onNode(credit).performTextInput("From my textbook.")
        compose.onNodeWithText("Save source").assertIsEnabled().performClick()

        assertEquals(listOf("save Textbook: From my textbook."), calls)
        compose.onNode(hasSetTextAction() and hasText("Textbook")).assertDoesNotExist()
        compose.onNodeWithText("+ New source").assertIsDisplayed()
    }

    @Test
    fun `a source that could not be saved stays to be corrected`() {
        canSave = false
        show(ready)

        compose.onNodeWithText("+ New source").performClick()
        compose.onNode(hasSetTextAction() and hasText("Source name")).performTextInput("Textbook")
        compose.onNode(hasSetTextAction() and hasText("Exact credit line")).performTextInput("From my textbook.")
        compose.onNodeWithText("Save source").performClick()

        compose.onNode(hasSetTextAction() and hasText("Textbook")).assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNode(hasSetTextAction() and hasText("Textbook")).assertDoesNotExist()
    }

    // ---------------------------------------------------------------- items

    private val items = listOf(
        StudyItem(isGrammar = false, term = "猫", phonetics = "ねこ", meaning = "cat"),
        StudyItem(isGrammar = true, term = "です", phonetics = "", meaning = "polite copula"),
        StudyItem(isGrammar = false, term = "犬", phonetics = "いぬ", meaning = "dog")
    )

    private val reviewed = ready.copy(studyItems = items, approvedItems = setOf(0, 1), itemsText = "猫です")

    @Test
    fun `the items can be asked for when there is text`() {
        var state by mutableStateOf(ready)
        compose.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                YouTubeCard(uiState = state, hasVideo = true, actions = actions)
            }
        }

        compose.onNodeWithText("Review items").assertDoesNotExist()
        compose.onNodeWithText("Extract Vocabulary & Grammar").assertIsEnabled().performClick()
        assertEquals(listOf("extract"), calls)

        state = ready.copy(isExtractingItems = true)
        compose.onNodeWithText("Extracting...").assertIsNotEnabled()
        compose.onNodeWithText("Auto-Fill Title & Description").assertIsNotEnabled()

        state = ready.copy(text = " ")
        compose.onNodeWithText("Extract Vocabulary & Grammar").assertIsNotEnabled()
    }

    @Test
    fun `the items are reviewed first and the source is chosen right before the text is written`() {
        show(reviewed)

        val tops = listOf(
            "Extract Vocabulary & Grammar",
            "Review items",
            "Text source (for the credit line):",
            "Auto-Fill Title & Description"
        ).map { compose.onNodeWithText(it).getUnclippedBoundsInRoot().top }

        assertEquals(tops.sorted(), tops)
    }

    @Test
    fun `the items are listed by their kind, ticked or not`() {
        show(reviewed)

        compose.onNodeWithText("Review items").assertIsDisplayed()
        compose.onNodeWithText("🔤 Breakdown").assertIsDisplayed()
        compose.onNodeWithText("📚 Grammar Points").assertIsDisplayed()
        compose.onNodeWithText("猫 (ねこ) = cat").assertIsOn()
        compose.onNodeWithText("です = polite copula").assertIsOn()
        compose.onNodeWithText("犬 (いぬ) = dog").assertIsOff()

        compose.onNodeWithText("犬 (いぬ) = dog").performClick()
        compose.onNodeWithText("です = polite copula").performClick()

        assertEquals(listOf("item 2", "item 1"), calls)
    }

    @Test
    fun `a kind without items says so`() {
        show(reviewed.copy(studyItems = items.take(1), approvedItems = setOf(0)))

        compose.onNodeWithText("猫 (ねこ) = cat").assertIsDisplayed()
        compose.onNodeWithText("None found").assertIsDisplayed()
    }

    @Test
    fun `items of another text are not shown`() {
        show(reviewed.copy(text = "犬です"))

        compose.onNodeWithText("Review items").assertDoesNotExist()
        compose.onNodeWithText("猫 (ねこ) = cat").assertDoesNotExist()
    }

    // ---------------------------------------------------------------- title

    @Test
    fun `the title is counted against what YouTube takes`() {
        var state by mutableStateOf(ready)
        compose.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                YouTubeCard(uiState = state, hasVideo = true, actions = actions)
            }
        }

        compose.onNodeWithText("7/100 characters").assertIsDisplayed()

        state = ready.copy(youtubeTitle = "x".repeat(100))
        compose.onNodeWithText("100/100 characters").assertIsDisplayed()
    }

    // --------------------------------------------------------------- upload

    @Test
    fun `a video that is on YouTube is not uploaded again`() {
        var uploadedUrl by mutableStateOf<String?>("https://www.youtube.com/watch?v=abc123")
        val state = ready.copy(lastUploadUrl = "https://www.youtube.com/watch?v=abc123")
        compose.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                YouTubeCard(uiState = state, hasVideo = true, actions = actions, uploadedUrl = uploadedUrl)
            }
        }

        compose.onNodeWithText("Upload to YouTube").assertIsNotEnabled()
        compose.onNodeWithText("✅ This video is on YouTube. Render or choose another one to upload.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("🔗 https://www.youtube.com/watch?v=abc123").assertIsDisplayed()

        // Another video was rendered
        uploadedUrl = null
        compose.onNodeWithText("Upload to YouTube").assertIsEnabled()
        compose.onNodeWithText("✅ The last upload:").assertIsDisplayed()
        compose.onNodeWithText("🔗 https://www.youtube.com/watch?v=abc123").assertIsDisplayed()
    }

    @Test
    fun `a video that was uploaded before the app was started shows where it is`() {
        compose.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                YouTubeCard(
                    uiState = ready,
                    hasVideo = true,
                    actions = actions,
                    uploadedUrl = "https://www.youtube.com/watch?v=old456"
                )
            }
        }

        compose.onNodeWithText("Upload to YouTube").assertIsNotEnabled()
        compose.onNodeWithText("🔗 https://www.youtube.com/watch?v=old456").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `before the first upload there is no link`() {
        show(ready)

        compose.onNodeWithText("🔗", substring = true).assertDoesNotExist()
        compose.onNodeWithText("The last upload", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Upload to YouTube").assertIsEnabled()
    }

    @Test
    fun `with several channels one can be picked`() {
        val two = ready.copy(
            youtubeChannels = listOf(YouTubeChannel("c1", "Cat Channel"), YouTubeChannel("c2", "Dog Channel")),
            showChannelPicker = true
        )
        show(two)

        compose.onNodeWithText("Select YouTube Channel").assertIsDisplayed()
        compose.onNodeWithText("Dog Channel").performClick()

        assertEquals(listOf("channel c2"), calls)
    }
}
