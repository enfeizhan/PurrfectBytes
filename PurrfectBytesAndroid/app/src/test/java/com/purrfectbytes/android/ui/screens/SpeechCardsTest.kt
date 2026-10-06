package com.purrfectbytes.android.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.purrfectbytes.android.services.EdgeVoice
import com.purrfectbytes.android.services.SequenceStep
import com.purrfectbytes.android.services.TTSService
import com.purrfectbytes.android.viewmodels.MainUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2000dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SpeechCardsTest {

    @get:Rule
    val compose = createComposeRule()

    private val engines = listOf("edge" to "Microsoft Edge TTS", "native" to "Android Native TTS")
    private val calls = mutableListOf<String>()

    private val actions = speechActions(
        onEngineChange = { calls += "engine $it" },
        onVoiceChange = { calls += "voice $it" },
        onSecondVoiceChange = { calls += "second voice $it" },
        onConversationChange = { calls += "conversation $it" },
        onSequenceUseChange = { calls += "sequence $it" },
        onAddStep = { calls += "add step" },
        onRemoveStep = { calls += "remove step $it" },
        onStepChange = { index, step -> calls += "step $index: ${step.count} ${if (step.slow) "slow" else "normal"}" },
        onSlowSpeechChange = { calls += "slow $it" },
        onRepetitionsChange = { calls += "repetitions $it" }
    )

    private val japanese = MainUiState(
        selectedLanguage = "ja",
        voices = listOf(
            EdgeVoice("ja-JP-KeitaNeural", "Male", "ja-JP"),
            EdgeVoice("ja-JP-NanamiNeural", "Female", "ja-JP")
        )
    )

    private fun showVoices(state: MainUiState, enabled: Boolean = true) {
        compose.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                VoiceCard(uiState = state, engines = engines, enabled = enabled, actions = actions)
            }
        }
    }

    private fun showRepetitions(state: MainUiState, enabled: Boolean = true) {
        compose.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                RepetitionCard(uiState = state, enabled = enabled, actions = actions)
            }
        }
    }

    private fun steps(vararg steps: Pair<Int, Boolean>) = steps.map { SequenceStep(it.first, it.second) }

    // ----------------------------------------------------------------- voices

    @Test
    fun `the voice of the language is named until another is chosen`() {
        showVoices(japanese)

        compose.onNodeWithText("Default voice: Nanami - Female (ja-JP)").assertIsDisplayed().performClick()
        compose.onNodeWithText("Keita - Male (ja-JP)").performClick()

        assertEquals(listOf("voice ja-JP-KeitaNeural"), calls)
    }

    @Test
    fun `the voice of the language can be chosen again`() {
        showVoices(japanese.copy(selectedVoice = "ja-JP-KeitaNeural"))

        compose.onNodeWithText("Keita - Male (ja-JP)").assertIsDisplayed().performClick()
        compose.onNodeWithText("Default voice: Nanami - Female (ja-JP)").performClick()

        assertEquals(listOf("voice null"), calls)
    }

    @Test
    fun `while the voices are on their way the voice that was chosen goes by its name`() {
        showVoices(japanese.copy(voices = emptyList(), isLoadingVoices = true, selectedVoice = "ja-JP-KeitaNeural"))

        compose.onNodeWithText("ja-JP-KeitaNeural").assertIsDisplayed()
        compose.onNodeWithText("Loading voices…").assertIsDisplayed()
    }

    @Test
    fun `a list that could not be loaded is said to be missing`() {
        showVoices(japanese.copy(voices = emptyList()))

        compose.onNodeWithText("Default voice: ja-JP-NanamiNeural").assertIsDisplayed()
        compose.onNodeWithText("The list of voices could not be loaded").assertIsDisplayed()
    }

    @Test
    fun `the phone's engine leaves no voice to choose and no conversation to have`() {
        showVoices(japanese.copy(selectedTtsEngine = TTSService.ENGINE_NATIVE, voices = emptyList()))

        compose.onNodeWithText("Android Native TTS").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Default voice: ja-JP-NanamiNeural").assertIsNotEnabled()
        compose.onNodeWithText("The phone's engine reads with the voice that is set on the phone").assertIsDisplayed()
        compose.onNodeWithText("Conversation mode (two voices)").assertIsNotEnabled().assertIsOff()
        compose.onNodeWithText("Conversation mode needs an engine with voices to choose from: Edge TTS")
            .assertIsDisplayed()
    }

    @Test
    fun `a conversation has a second voice that is another than the first`() {
        var state by mutableStateOf(japanese)
        compose.setContent {
            VoiceCard(uiState = state, engines = engines, enabled = true, actions = actions)
        }

        compose.onNodeWithText("Second voice (speaker B)").assertDoesNotExist()
        compose.onNodeWithText("Conversation mode (two voices)").assertIsOff().performClick()
        assertEquals(listOf("conversation true"), calls)

        state = japanese.copy(isConversation = true)
        compose.onNodeWithText("Conversation mode (two voices)").assertIsOn()
        compose.onNodeWithText("Second voice (speaker B)").assertIsDisplayed()
        compose.onNodeWithText("Default voice: Keita - Male (ja-JP)").assertIsDisplayed()
        compose.onNodeWithText("The name stays on screen", substring = true).assertIsDisplayed()

        // The first speaker takes the voice of the second: the second gets the other one
        state = state.copy(selectedVoice = "ja-JP-KeitaNeural")
        compose.onNodeWithText("Default voice: Nanami - Female (ja-JP)").assertIsDisplayed().performClick()
        compose.onAllNodesWithText("Keita - Male (ja-JP)").onLast().performClick()
        assertEquals(listOf("conversation true", "second voice ja-JP-KeitaNeural"), calls)
    }

    @Test
    fun `nothing is chosen while a video is rendered`() {
        showVoices(japanese.copy(isConversation = true), enabled = false)

        compose.onNodeWithText("Microsoft Edge TTS").assertIsNotEnabled()
        compose.onNodeWithText("Default voice: Nanami - Female (ja-JP)").assertIsNotEnabled()
        compose.onNodeWithText("Default voice: Keita - Male (ja-JP)").assertIsNotEnabled()
        compose.onNodeWithText("Conversation mode (two voices)").assertIsNotEnabled()
    }

    // --------------------------------------------------------------- sequence

    private fun countField(count: Int) = hasSetTextAction() and hasText(count.toString())

    @Test
    fun `without a sequence there is a speed and a number of repetitions`() {
        showRepetitions(MainUiState())

        compose.onNodeWithText("Custom speed sequence").assertIsOff().performClick()
        compose.onNodeWithText("Slow speech speed").assertIsEnabled().assertIsOff().performClick()
        compose.onNode(hasSetTextAction() and hasText("1 - 100")).assertIsEnabled()
        compose.onNodeWithText("Repetition steps (played in order):").assertDoesNotExist()

        assertEquals(listOf("sequence true", "slow true"), calls)
    }

    @Test
    fun `a sequence takes the place of speed and repetitions`() {
        showRepetitions(MainUiState(useSequence = true))

        compose.onNodeWithText("Custom speed sequence").assertIsOn()
        compose.onNodeWithText("Slow speech speed").assertIsNotEnabled()
        compose.onNode(hasSetTextAction() and hasText("1 - 100")).assertIsNotEnabled()
        compose.onNodeWithText("Repetition steps (played in order):").assertIsDisplayed()
        compose.onNodeWithText("3 normal, 4 slow, 3 normal: 10 repetitions in all").assertIsDisplayed()
    }

    @Test
    fun `every step has a count and a speed`() {
        showRepetitions(MainUiState(useSequence = true, sequenceSteps = steps(2 to false, 5 to true)))

        compose.onAllNodesWithText("Normal").assertCountEquals(2)
        compose.onAllNodesWithText("Normal")[0].assertIsSelected()
        compose.onAllNodesWithText("Slow")[1].assertIsSelected()

        compose.onAllNodesWithText("Slow")[0].performClick()
        compose.onAllNodesWithText("Normal")[1].performClick()
        compose.onNode(countField(5)).performTextReplacement("12")

        assertEquals(listOf("step 0: 2 slow", "step 1: 5 normal", "step 1: 12 slow"), calls)
    }

    @Test
    fun `a count that cannot be is not passed on`() {
        showRepetitions(MainUiState(useSequence = true, sequenceSteps = steps(2 to false)))

        compose.onNode(countField(2)).performTextReplacement("")
        compose.onNode(hasSetTextAction() and hasText("")).performTextReplacement("0")
        compose.onNode(countField(0)).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
        compose.onNode(countField(0)).performTextReplacement("250")

        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `the count follows the step it belongs to`() {
        var state by mutableStateOf(MainUiState(useSequence = true, sequenceSteps = steps(2 to false, 5 to true)))
        compose.setContent {
            RepetitionCard(uiState = state, enabled = true, actions = actions)
        }

        // The first step is removed: the second is now the first
        state = state.copy(sequenceSteps = steps(5 to true))

        compose.onNode(countField(5)).assertIsDisplayed()
        compose.onNode(countField(2)).assertDoesNotExist()
        compose.onAllNodesWithText("Slow").assertCountEquals(1)
    }

    @Test
    fun `steps are added and removed`() {
        showRepetitions(MainUiState(useSequence = true, sequenceSteps = steps(2 to false, 5 to true)))

        compose.onNodeWithContentDescription("Remove step 2").performClick()
        compose.onNodeWithText("Add step").performClick()

        assertEquals(listOf("remove step 1", "add step"), calls)
    }

    @Test
    fun `a sequence has room for twenty steps`() {
        showRepetitions(MainUiState(useSequence = true, sequenceSteps = List(20) { SequenceStep(1, false) }))

        compose.onNodeWithText("Add step").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `what is wrong with a sequence is said where it is set`() {
        var state by mutableStateOf(MainUiState(useSequence = true, sequenceSteps = steps(60 to false, 60 to true)))
        compose.setContent {
            RepetitionCard(uiState = state, enabled = true, actions = actions)
        }

        compose.onNodeWithText("Sequence totals 120 repetitions (max 100)").assertIsDisplayed()

        state = state.copy(sequenceSteps = emptyList())
        compose.onNodeWithText("Add at least one sequence step").assertIsDisplayed()

        state = state.copy(sequenceSteps = steps(1 to true))
        compose.onNodeWithText("1 slow: 1 repetition in all").assertIsDisplayed()
    }

    // ------------------------------------------------------------ the override

    @Test
    fun `the text to voice instead is asked for when it is wanted`() {
        var use by mutableStateOf(false)
        var voiced by mutableStateOf("")
        compose.setContent {
            TextInputCard(
                text = "会議を行った",
                enabled = true,
                onTextChange = {},
                useVoicedText = use,
                voicedText = voiced,
                onVoicedTextUseChange = { use = it },
                onVoicedTextChange = { voiced = it }
            )
        }
        val field = hasSetTextAction() and hasText("Text to voice instead")

        compose.onNode(field).assertDoesNotExist()
        compose.onNodeWithText("Pronunciation override").assertIsOff().performClick()

        compose.onNodeWithText("Pronunciation override").assertIsOn()
        compose.onNode(field).assertIsDisplayed().performTextReplacement("会議をおこなった")
        assertEquals("会議をおこなった", voiced)
        compose.onNodeWithText("the video still shows the text above", substring = true).assertIsDisplayed()
        assertEquals(
            "会議を行った",
            compose.onNode(hasSetTextAction() and hasText("会議を行った"))
                .fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text
        )
    }

    // ----------------------------------------------------------- the progress

    @Test
    fun `a render says what it is doing`() {
        var progress by mutableStateOf<String?>(null)
        var rendering by mutableStateOf(false)
        compose.setContent {
            ActionButtons(
                canStart = !rendering,
                isGeneratingPreview = false,
                isConvertingVideo = rendering,
                onPreview = {},
                onRender = {},
                progress = progress
            )
        }

        compose.onNodeWithText("elapsed", substring = true).assertDoesNotExist()

        rendering = true
        compose.onNodeWithText("⏳ Starting… 0s elapsed").assertIsDisplayed()

        progress = "Generating speech 3 of 12"
        compose.onNodeWithText("⏳ Generating speech 3 of 12…", substring = true).assertIsDisplayed()

        rendering = false
        compose.onNodeWithText("elapsed", substring = true).assertDoesNotExist()
    }
}
