package com.purrfectbytes.android.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.purrfectbytes.android.services.EdgeVoice
import com.purrfectbytes.android.services.SequenceStep
import com.purrfectbytes.android.services.SpeedSequence
import com.purrfectbytes.android.viewmodels.MainUiState
import com.purrfectbytes.android.viewmodels.MainViewModel

/** The cards of the main screen that say how the text is read: by whom, how often, how fast. */

/** What the cards of this file can ask for. A voice that is null is the voice of the language. */
internal class SpeechActions(
    val onEngineChange: (String) -> Unit,
    val onVoiceChange: (String?) -> Unit,
    val onSecondVoiceChange: (String?) -> Unit,
    val onConversationChange: (Boolean) -> Unit,
    val onSequenceUseChange: (Boolean) -> Unit,
    val onAddStep: () -> Unit,
    val onRemoveStep: (Int) -> Unit,
    val onStepChange: (Int, SequenceStep) -> Unit,
    val onSlowSpeechChange: (Boolean) -> Unit,
    val onRepetitionsChange: (Int) -> Unit
)

@Composable
internal fun VoiceCard(
    uiState: MainUiState,
    engines: List<Pair<String, String>>,
    enabled: Boolean,
    actions: SpeechActions
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            CardTitle("TTS Engine")
            Spacer(modifier = Modifier.height(8.dp))
            DropdownField(
                value = engines.find { it.first == uiState.selectedTtsEngine }?.second ?: engines.first().second,
                options = engines,
                optionLabel = { it.second },
                onSelect = { actions.onEngineChange(it.first) },
                enabled = enabled
            )

            Spacer(modifier = Modifier.height(16.dp))

            CardTitle("Voice")
            Spacer(modifier = Modifier.height(8.dp))
            VoiceField(
                voices = uiState.voices,
                selected = uiState.selectedVoice,
                voiceOfLanguage = uiState.voiceOfLanguage,
                enabled = enabled && uiState.canChooseVoice,
                onSelect = actions.onVoiceChange
            )
            Spacer(modifier = Modifier.height(4.dp))
            Hint(
                when {
                    !uiState.canChooseVoice -> "The phone's engine reads with the voice that is set on the phone"
                    uiState.isLoadingVoices -> "Loading voices…"
                    uiState.voices.isEmpty() -> "The list of voices could not be loaded"
                    else -> "Voices of Edge TTS for the selected language"
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            SwitchRow(
                label = "Conversation mode (two voices)",
                checked = uiState.isConversation,
                enabled = enabled && uiState.canChooseVoice,
                onCheckedChange = actions.onConversationChange
            )
            if (!uiState.canChooseVoice) {
                Hint("Conversation mode needs an engine with voices to choose from: Edge TTS")
            }

            if (uiState.isConversation) {
                Spacer(modifier = Modifier.height(8.dp))
                Text("Second voice (speaker B)", style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(4.dp))
                VoiceField(
                    voices = uiState.voices,
                    selected = uiState.selectedSecondVoice,
                    voiceOfLanguage = uiState.secondVoiceOfLanguage?.id,
                    enabled = enabled,
                    onSelect = actions.onSecondVoiceChange
                )
                Spacer(modifier = Modifier.height(4.dp))
                Hint(
                    "The lines of the text take turns: line 1 is read by the first voice, line 2 by " +
                        "the second, and so on. At least 2 lines are needed.\n" +
                        "Speakers can be named instead - 직원: 혼자 오셨어요? - and lines with the same " +
                        "name have the same voice (two names at most). The name stays on screen but " +
                        "is never spoken or highlighted."
                )
            }
        }
    }
}

@Composable
private fun CardTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Medium
    )
}

/**
 * The voices to choose from, the first choice being the voice of the language. A voice
 * that is chosen but not in the list - the list may not be there yet - goes by its name.
 */
@Composable
private fun VoiceField(
    voices: List<EdgeVoice>,
    selected: String?,
    voiceOfLanguage: String?,
    enabled: Boolean,
    onSelect: (String?) -> Unit
) {
    fun labelOf(voice: String): String = voices.firstOrNull { it.id == voice }?.label ?: voice
    val unchosen = if (voiceOfLanguage == null) "Default voice" else "Default voice: ${labelOf(voiceOfLanguage)}"

    DropdownField(
        value = selected?.let { labelOf(it) } ?: unchosen,
        options = listOf<EdgeVoice?>(null) + voices,
        optionLabel = { it?.label ?: unchosen },
        onSelect = { onSelect(it?.id) },
        enabled = enabled
    )
}

@Composable
internal fun RepetitionCard(
    uiState: MainUiState,
    enabled: Boolean,
    actions: SpeechActions
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            SwitchRow(
                label = "Custom speed sequence",
                checked = uiState.useSequence,
                enabled = enabled,
                onCheckedChange = actions.onSequenceUseChange
            )

            if (uiState.useSequence) {
                Spacer(modifier = Modifier.height(8.dp))
                SequenceSteps(uiState.sequenceSteps, enabled, actions)
            }

            Spacer(modifier = Modifier.height(12.dp))

            // A sequence says for each of its steps how often and how fast
            SwitchRow(
                label = "Slow speech speed",
                checked = uiState.isSlowSpeech,
                enabled = enabled && !uiState.useSequence,
                onCheckedChange = actions.onSlowSpeechChange
            )

            Spacer(modifier = Modifier.height(16.dp))

            CardTitle("Number of Repetitions:")
            Spacer(modifier = Modifier.height(8.dp))
            RepetitionsField(
                repetitions = uiState.repetitions,
                enabled = enabled && !uiState.useSequence,
                onRepetitionsChange = actions.onRepetitionsChange
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SequenceSteps(
    steps: List<SequenceStep>,
    enabled: Boolean,
    actions: SpeechActions
) {
    Text("Repetition steps (played in order):", style = MaterialTheme.typography.bodyMedium)
    Spacer(modifier = Modifier.height(4.dp))

    steps.forEachIndexed { index, step ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StepCountField(
                count = step.count,
                enabled = enabled,
                onCountChange = { actions.onStepChange(index, step.copy(count = it)) },
                modifier = Modifier.width(80.dp)
            )
            SpeedChip("Normal", selected = !step.slow, enabled = enabled) {
                actions.onStepChange(index, step.copy(slow = false))
            }
            SpeedChip("Slow", selected = step.slow, enabled = enabled) {
                actions.onStepChange(index, step.copy(slow = true))
            }
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = { actions.onRemoveStep(index) }, enabled = enabled) {
                Icon(Icons.Default.Close, contentDescription = "Remove step ${index + 1}")
            }
        }
    }

    TextButton(
        onClick = actions.onAddStep,
        enabled = enabled && steps.size < SpeedSequence.MAX_STEPS
    ) {
        Icon(Icons.Default.Add, contentDescription = null)
        Spacer(modifier = Modifier.width(4.dp))
        Text("Add step")
    }

    val problem = SpeedSequence.problemWith(steps)
    if (problem != null) {
        Text(
            text = problem,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    } else {
        val total = SpeedSequence.total(steps)
        Hint("${SpeedSequence.describe(steps)}: $total ${if (total == 1) "repetition" else "repetitions"} in all")
    }
    Hint(
        "At most ${SpeedSequence.MAX_TOTAL_REPETITIONS} repetitions in all. The sequence takes the place of " +
            "\"Slow speech speed\" and \"Number of Repetitions\" while it is switched on."
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpeedChip(label: String, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onSelect,
        label = { Text(label) },
        enabled = enabled,
        leadingIcon = if (selected) {
            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
        } else null
    )
}

/** Keeps what is typed, like [RepetitionsField]; a count from elsewhere takes its place. */
@Composable
private fun StepCountField(
    count: Int,
    enabled: Boolean,
    onCountChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val range = 1..SpeedSequence.MAX_TOTAL_REPETITIONS
    var typed by remember(count) { mutableStateOf(count.toString()) }
    val isValid = typed.toIntOrNull() in range

    OutlinedTextField(
        value = typed,
        onValueChange = { input ->
            typed = input.filter { it.isDigit() }.take(3)
            typed.toIntOrNull()?.takeIf { it in range }?.let(onCountChange)
        },
        modifier = modifier.onFocusChanged { focus ->
            if (!focus.isFocused && !isValid) typed = count.toString()
        },
        enabled = enabled,
        isError = !isValid,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
    )
}

/**
 * The field keeps what is typed, so it can be emptied to type a new number. Only a
 * number in range is passed on; anything else is put right when the field is left.
 */
@Composable
private fun RepetitionsField(
    repetitions: Int,
    enabled: Boolean,
    onRepetitionsChange: (Int) -> Unit
) {
    val range = MainViewModel.MIN_REPETITIONS..MainViewModel.MAX_REPETITIONS
    var typed by remember { mutableStateOf(repetitions.toString()) }
    val isValid = typed.toIntOrNull() in range

    OutlinedTextField(
        value = typed,
        onValueChange = { input ->
            typed = input.filter { it.isDigit() }.take(3)
            typed.toIntOrNull()?.takeIf { it in range }?.let(onRepetitionsChange)
        },
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focus ->
                if (!focus.isFocused && !isValid) typed = repetitions.toString()
            },
        label = { Text("${range.first} - ${range.last}") },
        enabled = enabled,
        isError = !isValid,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        supportingText = {
            Text(
                text = "The video repeats the reading this many times (1 = no repetition)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    )
}
