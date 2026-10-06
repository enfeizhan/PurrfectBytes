package com.purrfectbytes.android.ui.screens

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.purrfectbytes.android.BuildConfig
import com.purrfectbytes.android.ui.theme.PurrfectBytesTheme
import com.purrfectbytes.android.viewmodels.MainViewModel
import com.purrfectbytes.android.viewmodels.testViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The whole main screen on a phone-sized display, with stand-ins for everything online. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var model: MainViewModel

    private val textField = hasSetTextAction() and hasText("Type or paste your text here...")

    private fun show() {
        model = testViewModel(context)
        compose.setContent {
            PurrfectBytesTheme {
                MainScreen(viewModel = model)
            }
        }
    }

    @Test
    fun `the screen opens with its version and nothing to render`() {
        show()

        compose.onNodeWithText("🎵 PurrfectBytes").assertIsDisplayed()
        compose.onNodeWithText("Text to Speech Converter (v${BuildConfig.VERSION_NAME})").assertIsDisplayed()
        compose.onNodeWithText("Render MP4").assertIsNotEnabled()
        compose.onNodeWithText("Preview").assertIsNotEnabled()
        compose.onNodeWithText("Connect to YouTube").assertExists()
        compose.onNodeWithText("🎬 Video Ready!").assertDoesNotExist()
        compose.onNodeWithText("🎵 Audio Generated Successfully!").assertDoesNotExist()
    }

    @Test
    fun `typing text makes rendering possible`() {
        show()

        compose.onNode(textField).performScrollTo().performTextInput("猫です")

        assertEquals("猫です", model.uiState.value.text)
        compose.onNodeWithText("Render MP4").assertIsEnabled()
        compose.onNodeWithText("Preview").assertIsEnabled()
        compose.onNodeWithText("Detect Language").assertIsEnabled()
    }

    @Test
    fun `an error is in view at the top of the page and can be dismissed`() {
        show()

        model.onCameraPermissionDenied(blocked = false)

        // The page is not scrolled: the message is on screen although it belongs to no card
        compose.onNodeWithText("❌ The camera can only be used with the camera permission.").assertIsDisplayed()
        compose.onNodeWithText("🎵 PurrfectBytes").assertIsDisplayed()

        compose.onNodeWithContentDescription("Dismiss error").performClick()
        assertNull(model.uiState.value.errorMessage)
        compose.onNodeWithText("❌ The camera can only be used with the camera permission.").assertDoesNotExist()
    }

    @Test
    fun `an error is in view at the bottom of the page as well`() {
        show()

        compose.onNodeWithText("Upload to YouTube").performScrollTo()
        model.onCameraPermissionDenied(blocked = true)

        compose.onNodeWithText("camera is blocked", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a message does not keep the last button out of reach`() {
        show()
        model.onCameraPermissionDenied(blocked = true)

        // Scroll to the end of the page
        compose.onNode(hasScrollAction()).performSemanticsAction(SemanticsActions.ScrollBy) { scrollBy ->
            scrollBy(0f, 100_000f)
        }

        val button = compose.onNodeWithText("Upload to YouTube").getUnclippedBoundsInRoot()
        val message = compose.onNodeWithText("camera is blocked", substring = true).getUnclippedBoundsInRoot()
        assertTrue(
            "the button ends at ${button.bottom}, the message starts at ${message.top}",
            button.bottom <= message.top
        )
    }

    @Test
    fun `how the text is read is chosen on the way from the text to the video`() {
        show()

        listOf(
            "Pronunciation override",
            "Voice",
            "Conversation mode (two voices)",
            "Custom speed sequence",
            "Slow speech speed",
            "Number of Repetitions:",
            "Extract Vocabulary & Grammar",
            "— Generic credit —",
            "0/100 characters"
        ).forEach { text ->
            compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithText("Review items").assertDoesNotExist()
        compose.onNodeWithText("Second voice (speaker B)").assertDoesNotExist()
    }

    @Test
    fun `a conversation and a sequence are switched on where they are shown`() {
        show()

        compose.onNodeWithText("Conversation mode (two voices)").performScrollTo().performClick()
        compose.onNodeWithText("Custom speed sequence").performScrollTo().performClick()
        compose.onNodeWithText("Pronunciation override").performScrollTo().performClick()

        val state = model.uiState.value
        assertTrue(state.isConversation)
        assertTrue(state.useSequence)
        assertTrue(state.useVoicedText)
        compose.onNodeWithText("Second voice (speaker B)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Repetition steps (played in order):").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Slow speech speed").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `a conversation of one line is refused where the message is seen`() {
        show()
        compose.onNode(textField).performScrollTo().performTextInput("Only one line")
        compose.onNodeWithText("Conversation mode (two voices)").performScrollTo().performClick()

        compose.onNodeWithText("Render MP4").performScrollTo().performClick()

        compose.onNodeWithText("Conversation mode needs at least 2 lines", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Render MP4").assertIsEnabled()
    }

    @Test
    fun `a source is saved on the screen and chosen from then on`() {
        show()

        compose.onNodeWithText("+ New source").performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasText("Source name")).performScrollTo().performTextInput("Textbook")
        compose.onNode(hasSetTextAction() and hasText("Exact credit line"))
            .performScrollTo().performTextInput("From my textbook.")
        compose.onNodeWithText("Save source").performScrollTo().performClick()

        assertEquals("Textbook", model.uiState.value.sources.single().name)
        compose.onNodeWithText("Textbook").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("From my textbook.").assertIsDisplayed()
        compose.onNodeWithText("✅ Saved source: Textbook").assertIsDisplayed()
    }

    @Test
    fun `uploads start out private`() {
        show()

        compose.onNodeWithText("Private").performScrollTo().assertIsDisplayed()
    }
}
