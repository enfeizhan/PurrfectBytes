package com.purrfectbytes.android.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.purrfectbytes.android.viewmodels.MainUiState
import com.purrfectbytes.android.viewmodels.OcrMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InputCardsTest {

    @get:Rule
    val compose = createComposeRule()

    private val engines = listOf("edge" to "Microsoft Edge TTS", "native" to "Android Native TTS")
    private val changes = mutableListOf<Int>()

    private val repetitionsField = hasSetTextAction() and hasText("1 - 100")

    private fun typed() = compose.onNode(repetitionsField)
        .fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text

    private fun showOptions(repetitions: Int = 10) {
        compose.setContent {
            var current by remember { mutableIntStateOf(repetitions) }
            RepetitionCard(
                uiState = MainUiState(repetitions = current),
                enabled = true,
                actions = speechActions(
                    onRepetitionsChange = {
                        changes += it
                        current = it
                    }
                )
            )
        }
    }

    @Test
    fun `the number of repetitions can be emptied and typed again`() {
        showOptions(repetitions = 10)
        assertEquals("10", typed())

        compose.onNode(repetitionsField).performTextClearance()
        assertEquals("", typed())
        assertEquals("an empty field changes nothing", emptyList<Int>(), changes)

        compose.onNode(repetitionsField).performTextInput("25")
        assertEquals("25", typed())
        assertEquals(25, changes.last())
    }

    @Test
    fun `numbers out of range are not passed on`() {
        showOptions(repetitions = 10)

        compose.onNode(repetitionsField).performTextReplacement("0")
        compose.onNode(repetitionsField).performTextReplacement("500")
        assertEquals(emptyList<Int>(), changes)
        compose.onNode(repetitionsField).assert(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Error)
        )

        compose.onNode(repetitionsField).performTextReplacement("100")
        assertEquals(listOf(100), changes)
    }

    @Test
    fun `only digits can be typed`() {
        showOptions(repetitions = 10)

        compose.onNode(repetitionsField).performTextReplacement("1a2.5-")

        assertEquals("125".take(3), typed())
    }

    @Test
    fun `the engine is chosen from a list`() {
        var chosen = ""
        compose.setContent {
            VoiceCard(
                uiState = MainUiState(),
                engines = engines,
                enabled = true,
                actions = speechActions(onEngineChange = { chosen = it })
            )
        }

        compose.onNodeWithText("Microsoft Edge TTS").performClick()
        compose.onNodeWithText("Android Native TTS").performClick()

        assertEquals("native", chosen)
    }

    @Test
    fun `the extraction mode is chosen from a list`() {
        var chosen: OcrMode? = null
        compose.setContent {
            OcrModeCard(mode = OcrMode.INTERACTIVE, enabled = true, onModeChange = { chosen = it })
        }

        compose.onNodeWithText(OcrMode.INTERACTIVE.displayName).performClick()
        compose.onNodeWithText(OcrMode.AUTO_INSERT.displayName).performClick()

        assertEquals(OcrMode.AUTO_INSERT, chosen)
    }

    @Test
    fun `rendering waits for text and for the render that is running`() {
        var canStart by mutableStateOf(false)
        var rendering by mutableStateOf(false)
        var rendered = 0
        compose.setContent {
            ActionButtons(
                canStart = canStart,
                isGeneratingPreview = false,
                isConvertingVideo = rendering,
                onPreview = {},
                onRender = { rendered++ }
            )
        }

        compose.onNodeWithText("Render MP4").assertIsNotEnabled()
        compose.onNodeWithText("Preview").assertIsNotEnabled()

        canStart = true
        compose.onNodeWithText("Render MP4").assertIsEnabled().performClick()
        assertEquals(1, rendered)

        canStart = false
        rendering = true
        compose.onNodeWithText("Rendering").assertIsDisplayed().assertIsNotEnabled()
    }

    @Test
    fun `the language notice is shown under the list`() {
        compose.setContent {
            LanguageCard(
                languages = listOf("en" to "English", "ja" to "Japanese"),
                selectedLanguage = "ja",
                notice = "Looks like Chinese. Keeping your choice, Japanese - tap Detect Language to switch.",
                noticeIsError = false,
                isDetecting = false,
                canDetect = true,
                enabled = true,
                onDetect = {},
                onLanguageChange = {}
            )
        }

        compose.onNodeWithText("Japanese").assertIsDisplayed()
        compose.onNodeWithText("Looks like Chinese", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Detect Language").assertIsEnabled()
    }
}
