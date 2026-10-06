package com.purrfectbytes.android.viewmodels

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.purrfectbytes.android.services.AppSettings
import com.purrfectbytes.android.services.EdgeVoiceCatalogue
import com.purrfectbytes.android.services.LanguageDetector
import com.purrfectbytes.android.services.MediaStorage
import com.purrfectbytes.android.services.RecognitionScript
import com.purrfectbytes.android.services.RecognizedText
import com.purrfectbytes.android.services.RenderInputException
import com.purrfectbytes.android.services.RenderPlan
import com.purrfectbytes.android.services.RenderPlanner
import com.purrfectbytes.android.services.RenderRequest
import com.purrfectbytes.android.services.SequenceStep
import com.purrfectbytes.android.services.SourceException
import com.purrfectbytes.android.services.SourceStore
import com.purrfectbytes.android.services.SpeechAudio
import com.purrfectbytes.android.services.SpeedSequence
import com.purrfectbytes.android.services.TTSService
import com.purrfectbytes.android.services.TextRecognitionProcessor
import com.purrfectbytes.android.services.VideoGeneratorService
import com.purrfectbytes.android.services.VideoLibrary
import com.purrfectbytes.android.services.YouTubeAccountService
import com.purrfectbytes.android.services.YouTubeAuthManager
import com.purrfectbytes.android.services.YouTubeChannel
import com.purrfectbytes.android.services.YouTubeMetadataGenerator
import com.purrfectbytes.android.services.YouTubeMetadataFormat
import com.purrfectbytes.android.services.YouTubePlaylist
import com.purrfectbytes.android.services.YouTubeVideoUploader
import com.purrfectbytes.android.services.isYouTubeSignInProblem
import com.purrfectbytes.android.services.youTubeMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val ttsService: TTSService,
    private val textRecognitionProcessor: TextRecognitionProcessor,
    private val languageDetector: LanguageDetector,
    private val videoGeneratorService: VideoGeneratorService,
    private val youtubeMetadataGenerator: YouTubeMetadataGenerator,
    private val youtubeVideoUploader: YouTubeVideoUploader,
    private val youtubeAccountService: YouTubeAccountService,
    private val youtubeAuthManager: YouTubeAuthManager,
    private val voiceCatalogue: EdgeVoiceCatalogue,
    private val sourceStore: SourceStore,
    private val library: VideoLibrary,
    private val storage: MediaStorage,
    private val settings: AppSettings
) : ViewModel() {

    companion object {
        private const val TAG = "MainViewModel"

        /** Shorter text is too little for the language to be told reliably. */
        private const val MIN_LENGTH_FOR_DETECTION = 10
        private const val DETECTION_DELAY_MILLIS = 1500L

        const val MIN_REPETITIONS = 1
        const val MAX_REPETITIONS = 100
    }

    private val _uiState = MutableStateFlow(
        sourceStore.list().let { sources ->
            MainUiState(
                selectedPrivacy = settings.uploadPrivacy,
                sequenceSteps = settings.speedSequence,
                sources = sources,
                selectedSourceId = settings.textSource?.takeIf { id -> sources.any { it.id == id } },
                recentVideos = library.list()
            )
        }
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    /** The speech of one reading: one file, or one for every line of a conversation. */
    private val _generatedAudioFiles = MutableStateFlow<List<File>>(emptyList())
    val generatedAudioFiles: StateFlow<List<File>> = _generatedAudioFiles.asStateFlow()

    private val _generatedVideoFile = MutableStateFlow<File?>(null)
    val generatedVideoFile: StateFlow<File?> = _generatedVideoFile.asStateFlow()

    private val _previewImageFile = MutableStateFlow<File?>(null)
    val previewImageFile: StateFlow<File?> = _previewImageFile.asStateFlow()

    private val _capturedPhotoUri = MutableStateFlow<Uri?>(null)
    val capturedPhotoUri: StateFlow<Uri?> = _capturedPhotoUri.asStateFlow()

    private val _showCamera = MutableStateFlow(false)
    val showCamera: StateFlow<Boolean> = _showCamera.asStateFlow()

    private val _recognizedText = MutableStateFlow<RecognizedText?>(null)
    val recognizedText: StateFlow<RecognizedText?> = _recognizedText.asStateFlow()

    private val _isAnalyzingPhoto = MutableStateFlow(false)
    val isAnalyzingPhoto: StateFlow<Boolean> = _isAnalyzingPhoto.asStateFlow()

    private val _selectedScript = MutableStateFlow(RecognitionScript.AUTO)
    val selectedScript: StateFlow<RecognitionScript> = _selectedScript.asStateFlow()

    private var languageDetectionJob: Job? = null
    private var photoAnalysisJob: Job? = null
    private var voicesJob: Job? = null

    /** True once the user has picked a language by hand; detection then no longer overrides it. */
    private var languageChosenByUser = false

    val isLoading = ttsService.isLoading

    val supportedLanguages = ttsService.getSupportedLanguages()
    val supportedTtsEngines = listOf(
        TTSService.ENGINE_EDGE to "Microsoft Edge TTS - Natural neural voices (Best quality)",
        TTSService.ENGINE_NATIVE to "Android Native TTS - Offline voices"
    )

    init {
        // Nothing on screen refers to the files of an earlier run any more, but for the
        // videos that are kept to be watched and uploaded
        val startedAt = System.currentTimeMillis()
        viewModelScope.launch(Dispatchers.IO) {
            storage.removeLeftovers(olderThan = startedAt, keep = library.files())
        }
        // Restore persisted YouTube connection
        if (youtubeAuthManager.isAuthorized()) {
            _uiState.update { it.copy(isYouTubeConnected = true) }
            fetchYouTubeChannels()
        }
        loadVoices()
    }

    // ---------------------------------------------------------------- messages

    private fun showError(message: String) {
        _uiState.update { it.copy(errorMessage = message) }
    }

    private fun showSuccess(message: String) {
        _uiState.update { it.copy(successMessage = message) }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun dismissSuccess() {
        _uiState.update { it.copy(successMessage = null) }
    }

    // -------------------------------------------------------- text and options

    fun updateText(text: String) {
        _uiState.update {
            // Words that were found in another text say nothing about this one
            val items = if (it.itemsText != null && it.itemsText != text.trim()) it.withoutItems() else it
            items.copy(text = text, detectedLanguageNotice = null)
        }
        if (text.isBlank()) languageChosenByUser = false

        languageDetectionJob?.cancel()
        if (text.trim().length >= MIN_LENGTH_FOR_DETECTION) {
            detectLanguage(automatic = true)
        }
    }

    /** Text that replaces what was there, such as text read from a photo. */
    private fun replaceText(text: String) {
        languageChosenByUser = false
        updateText(text)
    }

    fun updateLanguage(languageCode: String) {
        languageChosenByUser = true
        languageDetectionJob?.cancel()
        _uiState.update { it.copy(selectedLanguage = languageCode, detectedLanguageNotice = null) }
        loadVoices()
    }

    fun updateTtsEngine(engine: String) {
        _uiState.update {
            it.copy(
                selectedTtsEngine = engine,
                // Two voices need an engine that has voices to choose from
                isConversation = it.isConversation && engine == TTSService.ENGINE_EDGE
            )
        }
        loadVoices()
    }

    fun updateVoicedTextUse(use: Boolean) {
        _uiState.update { it.copy(useVoicedText = use) }
    }

    fun updateVoicedText(text: String) {
        _uiState.update { it.copy(voicedText = text) }
    }

    // ------------------------------------------------------------------ voices

    /**
     * Shows the voices of the engine and the language. What was chosen for a language is
     * remembered apart from what is shown: a choice is not lost by looking at another
     * language, or while the list cannot be had.
     */
    private fun loadVoices() {
        voicesJob?.cancel()
        val state = _uiState.value
        val language = state.selectedLanguage

        if (!state.canChooseVoice) {
            _uiState.update {
                it.copy(voices = emptyList(), isLoadingVoices = false, selectedVoice = null, selectedSecondVoice = null)
            }
            return
        }

        _uiState.update {
            it.copy(
                voices = emptyList(),
                isLoadingVoices = true,
                selectedVoice = settings.voiceFor(language),
                selectedSecondVoice = settings.voiceFor(language, secondSpeaker = true)
            )
        }
        // Only the newest request may fill the list: an answer for a language that was
        // left in the meantime is dropped together with its job
        voicesJob = viewModelScope.launch {
            val voices = try {
                voiceCatalogue.voicesFor(language)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "No voices to choose from", e)
                emptyList()
            }
            _uiState.update { current ->
                // A voice that the service has taken away cannot read any more
                fun offered(voice: String?) = voice?.takeIf { voices.isEmpty() || voices.any { it.id == voice } }
                current.copy(
                    voices = voices,
                    isLoadingVoices = false,
                    selectedVoice = offered(current.selectedVoice),
                    selectedSecondVoice = offered(current.selectedSecondVoice)
                )
            }
        }
    }

    /** [voice] is the name of a voice of Edge TTS; null is the voice of the language. */
    fun updateVoice(voice: String?) {
        settings.rememberVoice(_uiState.value.selectedLanguage, voice)
        _uiState.update { it.copy(selectedVoice = voice) }
    }

    fun updateSecondVoice(voice: String?) {
        settings.rememberVoice(_uiState.value.selectedLanguage, voice, secondSpeaker = true)
        _uiState.update { it.copy(selectedSecondVoice = voice) }
    }

    fun updateConversation(isConversation: Boolean) {
        _uiState.update { it.copy(isConversation = isConversation && it.canChooseVoice) }
    }

    // ---------------------------------------------------------- speed sequence

    fun updateSequenceUse(use: Boolean) {
        _uiState.update { it.copy(useSequence = use) }
    }

    fun addSequenceStep() {
        changeSequence { steps ->
            if (steps.size < SpeedSequence.MAX_STEPS) steps + SequenceStep(count = 1, slow = false) else steps
        }
    }

    fun removeSequenceStep(index: Int) {
        changeSequence { steps -> steps.filterIndexed { position, _ -> position != index } }
    }

    fun updateSequenceStep(index: Int, step: SequenceStep) {
        changeSequence { steps -> steps.mapIndexed { position, old -> if (position == index) step else old } }
    }

    private fun changeSequence(change: (List<SequenceStep>) -> List<SequenceStep>) {
        _uiState.update { it.copy(sequenceSteps = change(it.sequenceSteps)) }
        settings.speedSequence = _uiState.value.sequenceSteps
    }

    fun updateOcrMode(mode: OcrMode) {
        _uiState.update { it.copy(ocrMode = mode) }
    }

    fun updateSlowSpeech(isSlow: Boolean) {
        _uiState.update { it.copy(isSlowSpeech = isSlow) }
    }

    fun updateRepetitions(repetitions: Int) {
        _uiState.update { it.copy(repetitions = repetitions.coerceIn(MIN_REPETITIONS, MAX_REPETITIONS)) }
    }

    /** The "Detect Language" button: the user asks, so the answer replaces the current choice. */
    fun autoDetectLanguage() {
        if (_uiState.value.text.isBlank()) {
            showError("Please enter some text to detect")
            return
        }
        detectLanguage(automatic = false)
    }

    private fun detectLanguage(automatic: Boolean) {
        languageDetectionJob?.cancel()
        languageDetectionJob = viewModelScope.launch {
            if (automatic) {
                delay(DETECTION_DELAY_MILLIS) // wait until the typing pauses
            } else {
                _uiState.update { it.copy(isDetectingLanguage = true, errorMessage = null) }
            }

            val detected = try {
                languageDetector.detect(_uiState.value.text)
            } catch (e: CancellationException) {
                _uiState.update { it.copy(isDetectingLanguage = false) }
                throw e
            } catch (e: Exception) {
                languageNotice("❌ Detection failed: ${e.message}", isError = true)
                return@launch
            }

            if (detected == null) {
                languageNotice("❌ Could not identify language", isError = true)
                return@launch
            }

            // ML Kit returns BCP-47 codes like zh-Latn, zh, en, fr
            // We match the prefix for simplicity
            val match = supportedLanguages.find { it.first == detected.substringBefore("-") }
            if (match == null) {
                languageNotice("❌ Detected unsupported language: $detected", isError = true)
                return@launch
            }

            val (code, name) = match
            val current = _uiState.value.selectedLanguage
            if (automatic && languageChosenByUser && code != current) {
                val chosen = supportedLanguages.find { it.first == current }?.second ?: current
                languageNotice(
                    "Looks like $name. Keeping your choice, $chosen - tap Detect Language to switch.",
                    isError = false
                )
            } else {
                if (!automatic) languageChosenByUser = false
                _uiState.update {
                    it.copy(
                        isDetectingLanguage = false,
                        selectedLanguage = code,
                        detectedLanguageNotice = "✓ Detected: $name",
                        isDetectingLanguageError = false
                    )
                }
                if (code != current) loadVoices()
            }
        }
    }

    private fun languageNotice(notice: String, isError: Boolean) {
        _uiState.update {
            it.copy(
                isDetectingLanguage = false,
                detectedLanguageNotice = notice,
                isDetectingLanguageError = isError
            )
        }
    }

    // ------------------------------------------------------- preview and video

    /** What is to be rendered, as it is chosen on screen. */
    private fun requestOf(state: MainUiState) = RenderRequest(
        text = state.text,
        voicedText = state.voicedText.takeIf { state.useVoicedText },
        conversation = state.isConversation && state.canChooseVoice,
        voice = state.selectedVoice,
        secondVoice = state.selectedSecondVoice ?: state.secondVoiceOfLanguage?.id,
        steps = if (state.useSequence) {
            state.sequenceSteps
        } else {
            listOf(SequenceStep(count = state.repetitions, slow = state.isSlowSpeech))
        },
        isSequence = state.useSequence
    )

    /** The plan of what is on screen, or null, with the reason shown, when there is none. */
    private fun planOf(state: MainUiState): RenderPlan? =
        try {
            RenderPlanner.plan(requestOf(state))
        } catch (e: RenderInputException) {
            showError(e.message ?: "This cannot be made into a video")
            null
        }

    fun generatePreview() {
        val state = _uiState.value
        val plan = planOf(state) ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(isGeneratingPreview = true, errorMessage = null) }
            try {
                videoGeneratorService.getPreviewImage(plan, state.selectedLanguage).fold(
                    onSuccess = { image ->
                        storage.discard(_previewImageFile.value)
                        _previewImageFile.value = image
                    },
                    onFailure = { showError("Could not draw the preview: ${it.message}") }
                )
            } finally {
                _uiState.update { it.copy(isGeneratingPreview = false) }
            }
        }
    }

    fun dismissPreview() {
        storage.discard(_previewImageFile.value)
        _previewImageFile.value = null
    }

    fun generateNativeVideo() {
        val state = _uiState.value
        if (state.isConvertingVideo) return
        val plan = planOf(state) ?: return

        viewModelScope.launch {
            _uiState.update {
                it.copy(isConvertingVideo = true, renderProgress = null, errorMessage = null, successMessage = null)
            }
            val speech = mutableListOf<SpeechAudio>()
            var kept = emptyList<File>()
            try {
                // 1. Every piece of speech once, however often it is played
                plan.clips.forEachIndexed { index, clip ->
                    val which = if (plan.clips.size == 1) "" else " ${index + 1} of ${plan.clips.size}"
                    _uiState.update { it.copy(renderProgress = "Generating speech$which") }

                    speech += ttsService.generateAudio(
                        text = clip.spokenText,
                        languageCode = state.selectedLanguage,
                        isSlow = clip.slow,
                        engine = state.selectedTtsEngine,
                        voice = clip.voice
                    ).getOrElse { error ->
                        showError("Could not generate the audio$which: ${error.message}")
                        return@launch
                    }
                }

                // 2. The video of that speech, in the order of the repetitions
                val video = videoGeneratorService.generateVideo(
                    plan = plan,
                    speech = speech,
                    languageCode = state.selectedLanguage,
                    onProgress = { progress -> _uiState.update { it.copy(renderProgress = progress) } }
                ).getOrElse { error ->
                    showError(error.message ?: "Could not make the video")
                    return@launch
                }
                // The videos before it stay to be watched and uploaded, the oldest make room
                library.add(video, state.text, plan.summary)
                _generatedVideoFile.value = video.file

                // What is kept to listen to is the first run through the text
                ttsService.stopAudio()
                _generatedAudioFiles.value.forEach { storage.discard(it) }
                kept = plan.firstReading.map { speech[it].file }
                _generatedAudioFiles.value = kept

                _uiState.update {
                    it.copy(
                        videoText = state.text,
                        videoSummary = plan.summary,
                        videoSeconds = video.seconds,
                        recentVideos = library.list(),
                        successMessage = plan.message
                    )
                }
            } finally {
                speech.map { it.file }.filter { it !in kept }.forEach { storage.discard(it) }
                _uiState.update { it.copy(isConvertingVideo = false, renderProgress = null) }
            }
        }
    }

    /** Puts the video away. It stays among the recent videos. */
    fun dismissVideo() {
        _generatedVideoFile.value = null
        _uiState.update { it.copy(videoText = null) }
    }

    /** Takes one of the recent videos to be watched and uploaded. */
    fun selectVideo(video: File) {
        val chosen = library.find(video)
        if (chosen == null) {
            _uiState.update {
                it.copy(recentVideos = library.list(), errorMessage = "This video is no longer there")
            }
            return
        }

        if (_generatedVideoFile.value != video) {
            // The audio that is there belongs to the video that was rendered last
            ttsService.stopAudio()
            _generatedAudioFiles.value.forEach { storage.discard(it) }
            _generatedAudioFiles.value = emptyList()
        }
        _generatedVideoFile.value = video
        _uiState.update {
            it.copy(videoText = chosen.text, videoSummary = chosen.summary, videoSeconds = chosen.seconds)
        }
    }

    fun deleteVideo(video: File) {
        library.delete(video)
        if (_generatedVideoFile.value == video) dismissVideo()
        _uiState.update { it.copy(recentVideos = library.list()) }
    }

    fun playAudio() {
        ttsService.playAudio(_generatedAudioFiles.value)
    }

    fun stopAudio() {
        ttsService.stopAudio()
    }

    // ------------------------------------------------------------ YouTube text

    /** Asks the model for the words and grammar points of the text, for the user to choose from. */
    fun extractItems() {
        val state = _uiState.value
        val text = state.text.trim()
        if (text.isEmpty()) {
            showError("Please enter some text")
            return
        }
        if (state.isExtractingItems) return

        viewModelScope.launch {
            _uiState.update { it.copy(isExtractingItems = true, errorMessage = null) }
            try {
                val items = youtubeMetadataGenerator.extractItems(text, state.metadataProvider)
                _uiState.update {
                    // The text may have changed while the model was thinking
                    if (it.text.trim() != text) {
                        it
                    } else {
                        it.copy(studyItems = items, approvedItems = items.indices.toSet(), itemsText = text)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError("Extraction failed: ${e.message}")
            } finally {
                _uiState.update { it.copy(isExtractingItems = false) }
            }
        }
    }

    /** Ticks or unticks the item at [index] of the list to choose from. */
    fun toggleItem(index: Int) {
        _uiState.update {
            it.copy(approvedItems = if (index in it.approvedItems) it.approvedItems - index else it.approvedItems + index)
        }
    }

    fun generateMetadata() {
        val state = _uiState.value
        if (state.text.isBlank()) {
            showError("Please enter some text")
            return
        }
        if (state.isGeneratingMetadata) return

        val credit = state.selectedSourceId?.let { id ->
            sourceStore.find(id)?.credit ?: run {
                showError("Unknown text source - it may have been deleted")
                return
            }
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isGeneratingMetadata = true, errorMessage = null) }
            try {
                val (title, description) = youtubeMetadataGenerator.generateMetadata(
                    text = state.text,
                    provider = state.metadataProvider,
                    credit = credit,
                    items = state.itemsToExplain
                )
                _uiState.update {
                    it.copy(
                        youtubeTitle = title,
                        youtubeDescription = description,
                        metadataText = state.text,
                        successMessage = "YouTube Title and Description generated successfully!"
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The old title and description stay as they were
                showError("Could not write the title and description. ${e.message}")
            } finally {
                _uiState.update { it.copy(isGeneratingMetadata = false) }
            }
        }
    }

    // ----------------------------------------------------------- text sources

    /** [id] is the id of a saved source; null is the general credit. */
    fun selectSource(id: String?) {
        settings.textSource = id
        _uiState.update { it.copy(selectedSourceId = id) }
    }

    /** Saves a new source and chooses it. Returns false, with the reason shown, when it cannot be saved. */
    fun saveSource(name: String, credit: String): Boolean {
        return try {
            val source = sourceStore.add(name, credit)
            settings.textSource = source.id
            _uiState.update {
                it.copy(
                    sources = sourceStore.list(),
                    selectedSourceId = source.id,
                    successMessage = "Saved source: ${source.name}"
                )
            }
            true
        } catch (e: SourceException) {
            showError(e.message ?: "The source could not be saved")
            false
        }
    }

    fun deleteSelectedSource() {
        val id = _uiState.value.selectedSourceId
        if (id == null) {
            showError("Select a saved source to delete")
            return
        }
        try {
            sourceStore.delete(id)
            settings.textSource = null
            _uiState.update {
                it.copy(sources = sourceStore.list(), selectedSourceId = null, successMessage = "Source deleted")
            }
        } catch (e: SourceException) {
            showError(e.message ?: "The source could not be deleted")
        }
    }

    fun updateYoutubeTitle(title: String) {
        _uiState.update { it.copy(youtubeTitle = MainUiState.titleOfLength(title)) }
    }

    fun updateYoutubeDescription(description: String) {
        _uiState.update { it.copy(youtubeDescription = description) }
    }

    fun updateMetadataProvider(provider: String) {
        _uiState.update { it.copy(metadataProvider = provider) }
    }

    // --------------------------------------------------------- YouTube account

    fun startYouTubeAuth(): Intent {
        return youtubeAuthManager.getAuthIntent()
    }

    fun handleYouTubeAuthResult(intent: Intent?) {
        if (intent == null) return
        viewModelScope.launch {
            youtubeAuthManager.handleAuthResult(intent).fold(
                onSuccess = {
                    _uiState.update { it.copy(isYouTubeConnected = true, errorMessage = null) }
                    fetchYouTubeChannels()
                },
                onFailure = { showError(it.message ?: "YouTube sign-in failed") }
            )
        }
    }

    fun disconnectYouTube() {
        youtubeAuthManager.logout()
        _uiState.update { it.disconnected().copy(successMessage = "Disconnected from YouTube") }
    }

    /** Shows what went wrong, and asks to connect again when the sign-in is the problem. */
    private fun onYouTubeFailure(what: String, error: Throwable) {
        Log.e(TAG, what, error)
        if (error.isYouTubeSignInProblem()) {
            youtubeAuthManager.logout()
            _uiState.update {
                it.disconnected().copy(errorMessage = "The YouTube sign-in has expired. Please connect again.")
            }
        } else {
            showError("$what: ${error.youTubeMessage()}")
        }
    }

    fun fetchYouTubeChannels() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isFetchingChannels = true,
                    youtubeChannels = emptyList(),
                    selectedChannelId = null,
                    selectedChannelName = null
                )
            }
            try {
                val channels = youtubeAccountService.channels(youtubeAuthManager.getFreshAccessToken())
                _uiState.update { it.copy(youtubeChannels = channels, showChannelPicker = channels.size > 1) }

                // Auto-select if there's only one channel
                if (channels.size == 1) {
                    selectYouTubeChannel(channels[0])
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onYouTubeFailure("Failed to fetch channels", e)
            } finally {
                _uiState.update { it.copy(isFetchingChannels = false) }
            }
        }
    }

    fun selectYouTubeChannel(channel: YouTubeChannel) {
        _uiState.update {
            it.copy(
                selectedChannelId = channel.id,
                selectedChannelName = channel.title,
                showChannelPicker = false,
                // Reset playlist when channel changes
                selectedPlaylistId = null,
                selectedPlaylistName = "",
                availablePlaylists = emptyList()
            )
        }
        fetchYouTubePlaylists()
    }

    private fun fetchYouTubePlaylists() {
        viewModelScope.launch {
            _uiState.update { it.copy(isFetchingPlaylists = true) }
            try {
                val playlists = youtubeAccountService.playlists(youtubeAuthManager.getFreshAccessToken())
                _uiState.update { it.copy(availablePlaylists = playlists) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onYouTubeFailure("Failed to fetch playlists", e)
            } finally {
                _uiState.update { it.copy(isFetchingPlaylists = false) }
            }
        }
    }

    fun dismissChannelPicker() {
        _uiState.update { it.copy(showChannelPicker = false) }
    }

    fun showChannelPickerDialog() {
        _uiState.update { it.copy(showChannelPicker = true) }
    }

    fun updateYouTubePlaylist(playlist: YouTubePlaylist?) {
        _uiState.update {
            it.copy(
                selectedPlaylistId = playlist?.id,
                selectedPlaylistName = playlist?.title.orEmpty()
            )
        }
    }

    fun updateYouTubePrivacy(privacy: String) {
        settings.uploadPrivacy = privacy
        _uiState.update { it.copy(selectedPrivacy = privacy) }
    }

    fun uploadToYouTube() {
        val state = _uiState.value
        val videoFile = _generatedVideoFile.value

        if (videoFile == null || !videoFile.exists()) {
            showError("No video available to upload")
            return
        }
        if (!state.isYouTubeConnected) {
            showError("Not connected to YouTube")
            return
        }
        // A second tap, or a tap on a video that is on YouTube already, would upload it twice
        if (state.isUploadingToYouTube) return
        if (state.uploadOf(videoFile) != null) {
            showError("This video was already uploaded - render or choose another one")
            return
        }
        if (state.youtubeTitle.isBlank()) {
            showError("The title is empty - write one or generate the title and description")
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isUploadingToYouTube = true, errorMessage = null, successMessage = null) }
            try {
                val upload = youtubeVideoUploader.uploadVideo(
                    videoFile = videoFile,
                    title = state.youtubeTitle,
                    description = state.youtubeDescription,
                    tags = YouTubeMetadataFormat.tagsOf(state.youtubeDescription),
                    privacyStatus = state.selectedPrivacy,
                    playlistId = state.selectedPlaylistId,
                    accessToken = youtubeAuthManager.getFreshAccessToken()
                ).getOrThrow()

                library.markUploaded(videoFile, upload.url)
                _uiState.update {
                    it.copy(
                        recentVideos = library.list(),
                        lastUploadUrl = upload.url,
                        successMessage = "YouTube Upload Successful! Video ID: ${upload.videoId}",
                        errorMessage = upload.playlistError?.let { reason ->
                            "The video is uploaded, but it could not be added to " +
                                "\"${state.selectedPlaylistName}\": $reason"
                        }
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onYouTubeFailure("YouTube Upload Failed", e)
            } finally {
                _uiState.update { it.copy(isUploadingToYouTube = false) }
            }
        }
    }

    // --------------------------------------------------------- camera and OCR

    fun openCamera() {
        _showCamera.value = true
    }

    fun closeCamera() {
        _showCamera.value = false
    }

    fun onCameraPermissionDenied(blocked: Boolean) {
        showError(
            if (blocked) {
                "The camera is blocked for this app. Allow it in the phone's Settings > Apps > PurrfectBytes > Permissions."
            } else {
                "The camera can only be used with the camera permission."
            }
        )
    }

    /** Where the camera saves the next photo. */
    fun newPhotoFile(): File = storage.newPhotoFile()

    fun onPhotoCaptured(uri: Uri) {
        val previous = _capturedPhotoUri.value
        if (previous != uri) storage.discard(previous)

        _capturedPhotoUri.value = uri
        _showCamera.value = false
        _selectedScript.value = RecognitionScript.AUTO
        analyzePhotoForText(uri)
    }

    fun clearPhoto() {
        photoAnalysisJob?.cancel()
        _isAnalyzingPhoto.value = false
        removePhoto()
    }

    private fun removePhoto() {
        storage.discard(_capturedPhotoUri.value)
        _capturedPhotoUri.value = null
        _recognizedText.value = null
    }

    private fun analyzePhotoForText(uri: Uri) {
        photoAnalysisJob?.cancel()
        photoAnalysisJob = viewModelScope.launch {
            _isAnalyzingPhoto.value = true
            _recognizedText.value = null
            _uiState.update { it.copy(errorMessage = null) }

            val result = textRecognitionProcessor.processImageFromUri(uri, _selectedScript.value)
            _isAnalyzingPhoto.value = false

            result.fold(
                onSuccess = { recognized ->
                    val blocks = recognized.blocks
                    if (blocks.isEmpty()) {
                        _recognizedText.value = recognized
                        showError("No text detected. Try a different language option.")
                        return@fold
                    }

                    val recognizer = recognized.script.displayName
                    if (_uiState.value.ocrMode == OcrMode.AUTO_INSERT) {
                        replaceText(blocks.joinToString("\n") { it.text })
                        showSuccess("Auto extracted ${blocks.size} text block(s) using the $recognizer recognizer!")
                        removePhoto() // Dismiss UI on auto insert
                    } else {
                        _recognizedText.value = recognized
                        showSuccess("Found ${blocks.size} text block(s) using the $recognizer recognizer!")
                    }
                },
                onFailure = { error ->
                    showError("Failed to analyze text: ${error.message}")
                }
            )
        }
    }

    fun reanalyzeWithScript(script: RecognitionScript) {
        _selectedScript.value = script
        _capturedPhotoUri.value?.let { uri ->
            analyzePhotoForText(uri)
        }
    }

    fun onTextBlockClick(text: String) {
        replaceText(text)
        showSuccess("Text added to input field")
    }

    override fun onCleared() {
        super.onCleared()
        // All three start again by themselves when they are next used
        ttsService.cleanup()
        textRecognitionProcessor.close()
        languageDetector.close()
    }
}
