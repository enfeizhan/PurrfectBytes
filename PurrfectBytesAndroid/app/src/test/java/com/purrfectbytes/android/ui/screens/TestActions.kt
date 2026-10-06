package com.purrfectbytes.android.ui.screens

import com.purrfectbytes.android.services.SequenceStep

/** Actions that do nothing, but for those a test wants to hear of. */
internal fun speechActions(
    onEngineChange: (String) -> Unit = {},
    onVoiceChange: (String?) -> Unit = {},
    onSecondVoiceChange: (String?) -> Unit = {},
    onConversationChange: (Boolean) -> Unit = {},
    onSequenceUseChange: (Boolean) -> Unit = {},
    onAddStep: () -> Unit = {},
    onRemoveStep: (Int) -> Unit = {},
    onStepChange: (Int, SequenceStep) -> Unit = { _, _ -> },
    onSlowSpeechChange: (Boolean) -> Unit = {},
    onRepetitionsChange: (Int) -> Unit = {}
) = SpeechActions(
    onEngineChange = onEngineChange,
    onVoiceChange = onVoiceChange,
    onSecondVoiceChange = onSecondVoiceChange,
    onConversationChange = onConversationChange,
    onSequenceUseChange = onSequenceUseChange,
    onAddStep = onAddStep,
    onRemoveStep = onRemoveStep,
    onStepChange = onStepChange,
    onSlowSpeechChange = onSlowSpeechChange,
    onRepetitionsChange = onRepetitionsChange
)
