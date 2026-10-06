package com.purrfectbytes.android.viewmodels

import com.purrfectbytes.android.services.AppSettings
import com.purrfectbytes.android.services.EdgeTTSEngine
import com.purrfectbytes.android.services.EdgeVoice
import com.purrfectbytes.android.services.EdgeVoiceCatalogue
import com.purrfectbytes.android.services.RecentVideo
import com.purrfectbytes.android.services.SequenceStep
import com.purrfectbytes.android.services.SpeedSequence
import com.purrfectbytes.android.services.StudyItem
import com.purrfectbytes.android.services.TTSService
import com.purrfectbytes.android.services.TextSource
import com.purrfectbytes.android.services.YouTubeChannel
import com.purrfectbytes.android.services.YouTubeMetadataFormat
import com.purrfectbytes.android.services.YouTubeMetadataGenerator
import com.purrfectbytes.android.services.YouTubePlaylist
import java.io.File

enum class OcrMode(val displayName: String) {
    INTERACTIVE("Interactive Text Selection (Show boxes)"),
    AUTO_INSERT("Auto Extract (Insert all text automatically)")
}

data class MainUiState(
    val text: String = "",
    val selectedLanguage: String = "en",
    val selectedTtsEngine: String = TTSService.ENGINE_EDGE,
    val ocrMode: OcrMode = OcrMode.INTERACTIVE,
    val isSlowSpeech: Boolean = false,
    val repetitions: Int = 10,
    /** True when [voicedText] is spoken in place of the text, which is still what is shown. */
    val useVoicedText: Boolean = false,
    val voicedText: String = "",
    /** The voices to choose from; empty with the phone's engine and until the list is there. */
    val voices: List<EdgeVoice> = emptyList(),
    val isLoadingVoices: Boolean = false,
    /** The name of the voice that was chosen; null is the voice of the language. */
    val selectedVoice: String? = null,
    /** The voice of the second speaker of a conversation; null is [secondVoiceOfLanguage]. */
    val selectedSecondVoice: String? = null,
    /** True when every line of the text is a turn in a conversation of two voices. */
    val isConversation: Boolean = false,
    /** True when [sequenceSteps] take the place of [repetitions] and [isSlowSpeech]. */
    val useSequence: Boolean = false,
    val sequenceSteps: List<SequenceStep> = SpeedSequence.DEFAULT,
    val errorMessage: String? = null,
    val successMessage: String? = null,
    val isGeneratingPreview: Boolean = false,
    val isConvertingVideo: Boolean = false,
    /** What the render is doing at the moment, in words for the user. */
    val renderProgress: String? = null,
    /** The text of the video that is ready, what it repeats and how long it plays. */
    val videoText: String? = null,
    val videoSummary: String = "",
    val videoSeconds: Double = 0.0,
    val isGeneratingMetadata: Boolean = false,
    val youtubeTitle: String = "",
    val youtubeDescription: String = "",
    /** The text the title and description were written for. */
    val metadataText: String? = null,
    /** The saved sources of texts, and the id of the one that is given credit. */
    val sources: List<TextSource> = emptyList(),
    val selectedSourceId: String? = null,
    val isExtractingItems: Boolean = false,
    /** The words and grammar points the model found, and the positions of those that are ticked. */
    val studyItems: List<StudyItem> = emptyList(),
    val approvedItems: Set<Int> = emptySet(),
    /** The text [studyItems] were found in; null when none were asked for. */
    val itemsText: String? = null,
    /** The videos that are kept to be watched and uploaded, the newest first. */
    val recentVideos: List<RecentVideo> = emptyList(),
    val isUploadingToYouTube: Boolean = false,
    /** Where the video that was uploaded last in this run is watched. */
    val lastUploadUrl: String? = null,
    val isDetectingLanguage: Boolean = false,
    val detectedLanguageNotice: String? = null,
    val isDetectingLanguageError: Boolean = false,
    val isYouTubeConnected: Boolean = false,
    val selectedPlaylistId: String? = null,
    val selectedPlaylistName: String = "",
    val availablePlaylists: List<YouTubePlaylist> = emptyList(),
    val isFetchingPlaylists: Boolean = false,
    val selectedPrivacy: String = AppSettings.DEFAULT_PRIVACY,
    val youtubeChannels: List<YouTubeChannel> = emptyList(),
    val selectedChannelId: String? = null,
    val selectedChannelName: String? = null,
    val showChannelPicker: Boolean = false,
    val isFetchingChannels: Boolean = false,
    val metadataProvider: String = YouTubeMetadataGenerator.PROVIDER_GEMINI
) {
    companion object {
        /** [title] cut to the length YouTube takes, without cutting a character in half. */
        fun titleOfLength(title: String): String {
            val limit = YouTubeMetadataFormat.MAX_TITLE_LENGTH
            if (title.length <= limit) return title
            return title.substring(0, if (Character.isHighSurrogate(title[limit - 1])) limit - 1 else limit)
        }
    }

    /** True when the title and description describe another text than the video shows. */
    val metadataIsForAnotherText: Boolean
        get() = videoText != null && metadataText != null && videoText != metadataText

    /** Voices can be chosen with Edge TTS; the phone's engine has the voice of the phone. */
    val canChooseVoice: Boolean
        get() = selectedTtsEngine == TTSService.ENGINE_EDGE

    /** The name of the voice that reads the language when none is chosen. */
    val voiceOfLanguage: String
        get() = EdgeTTSEngine.defaultVoice(selectedLanguage)

    /** The second speaker's voice when none is chosen: one that can be told from the first. */
    val secondVoiceOfLanguage: EdgeVoice?
        get() = EdgeVoiceCatalogue.secondVoiceFor(voices, selectedVoice ?: voiceOfLanguage)

    /** True when the list of items belongs to the text as it is now. */
    val hasItems: Boolean
        get() = itemsText != null && itemsText == text.trim()

    /** The items the description is to explain; null leaves the choice to the model. */
    val itemsToExplain: List<StudyItem>?
        get() = if (hasItems) studyItems.filterIndexed { index, _ -> index in approvedItems } else null

    fun withoutItems(): MainUiState = copy(studyItems = emptyList(), approvedItems = emptySet(), itemsText = null)

    /** Where [video] is watched on YouTube, or null when it is not uploaded. */
    fun uploadOf(video: File?): String? =
        recentVideos.firstOrNull { it.file.path == video?.path }?.uploadedUrl

    /** This state with everything that belongs to the YouTube account removed. */
    fun disconnected(): MainUiState = copy(
        isYouTubeConnected = false,
        youtubeChannels = emptyList(),
        selectedChannelId = null,
        selectedChannelName = null,
        showChannelPicker = false,
        isFetchingChannels = false,
        availablePlaylists = emptyList(),
        selectedPlaylistId = null,
        selectedPlaylistName = "",
        isFetchingPlaylists = false
    )
}
