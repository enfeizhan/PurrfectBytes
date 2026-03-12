package com.purrfectbytes.android.viewmodels

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.purrfectbytes.android.services.TTSService
import com.purrfectbytes.android.services.TextRecognitionProcessor
import com.purrfectbytes.android.services.RecognizedTextBlock
import com.purrfectbytes.android.services.RecognitionScript
import com.purrfectbytes.android.services.VideoGeneratorService
import com.purrfectbytes.android.services.YouTubeMetadataGenerator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import java.io.File
import javax.inject.Inject

import com.purrfectbytes.android.services.YouTubeAuthManager
import com.purrfectbytes.android.services.YouTubeVideoUploader
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.services.youtube.YouTubeScopes
import com.google.api.services.youtube.YouTube
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import android.accounts.Account
import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.purrfectbytes.android.di.dataStore
import kotlinx.coroutines.flow.first

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ttsService: TTSService,
    private val textRecognitionProcessor: TextRecognitionProcessor,
    private val videoGeneratorService: VideoGeneratorService,
    private val youtubeMetadataGenerator: YouTubeMetadataGenerator,
    private val youtubeVideoUploader: YouTubeVideoUploader,
    private val youtubeAuthManager: YouTubeAuthManager
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()
    
    private val _generatedAudioFile = MutableStateFlow<File?>(null)
    val generatedAudioFile: StateFlow<File?> = _generatedAudioFile.asStateFlow()

    private val _generatedVideoFile = MutableStateFlow<File?>(null)
    val generatedVideoFile: StateFlow<File?> = _generatedVideoFile.asStateFlow()

    private val _previewImageFile = MutableStateFlow<File?>(null)
    val previewImageFile: StateFlow<File?> = _previewImageFile.asStateFlow()

    private val _capturedPhotoUri = MutableStateFlow<Uri?>(null)
    val capturedPhotoUri: StateFlow<Uri?> = _capturedPhotoUri.asStateFlow()

    private val _showCamera = MutableStateFlow(false)
    // Emits the intent the user must launch to grant YouTube OAuth permissions
    private val _youtubeAuthIntent = MutableStateFlow<Intent?>(null)
    val youtubeAuthIntent: StateFlow<Intent?> = _youtubeAuthIntent.asStateFlow()

    private enum class PendingAuthAction { UPLOAD, FETCH_CHANNELS, FETCH_PLAYLISTS }
    private var pendingAuthAction: PendingAuthAction = PendingAuthAction.UPLOAD

    fun consumeYoutubeAuthIntent() { _youtubeAuthIntent.value = null }

    fun retryAfterConsent() {
        when (pendingAuthAction) {
            PendingAuthAction.FETCH_CHANNELS -> {
                fetchYouTubeChannels()
            }
            PendingAuthAction.FETCH_PLAYLISTS -> {
                val channelId = _uiState.value.selectedChannelId
                if (channelId != null) fetchYouTubePlaylists(channelId)
            }
            PendingAuthAction.UPLOAD -> uploadToYouTube()
        }
    }
    fun startYouTubeAuth(): Intent {
        return youtubeAuthManager.getAuthIntent()
    }

    fun handleYouTubeAuthResult(intent: Intent?) {
        if (intent == null) return
        viewModelScope.launch {
            try {
                val success = youtubeAuthManager.handleAuthResult(intent)
                if (success) {
                    setYouTubeConnected(true)
                } else {
                    _uiState.value = _uiState.value.copy(errorMessage = "YouTube authentication failed")
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(errorMessage = "OAuth error: ${e.message}")
            }
        }
    }

    val showCamera: StateFlow<Boolean> = _showCamera.asStateFlow()

    private val _recognizedTextBlocks = MutableStateFlow<List<RecognizedTextBlock>>(emptyList())
    val recognizedTextBlocks: StateFlow<List<RecognizedTextBlock>> = _recognizedTextBlocks.asStateFlow()

    private val _isAnalyzingPhoto = MutableStateFlow(false)
    val isAnalyzingPhoto: StateFlow<Boolean> = _isAnalyzingPhoto.asStateFlow()

    private val _showTextAnalyzer = MutableStateFlow(false)
    val showTextAnalyzer: StateFlow<Boolean> = _showTextAnalyzer.asStateFlow()

    private val _selectedScript = MutableStateFlow(RecognitionScript.AUTO)
    val selectedScript: StateFlow<RecognitionScript> = _selectedScript.asStateFlow()
    
    private var languageDetectionJob: Job? = null

    val isLoading = ttsService.isLoading
    val currentStatus = ttsService.currentStatus
    
    val supportedLanguages = ttsService.getSupportedLanguages()
    val supportedTtsEngines = listOf(
        "edge" to "Microsoft Edge TTS - Natural neural voices (Best quality)",
        "native" to "Android Native TTS - Offline voices"
    )

    companion object {
        private val KEY_YT_ACCOUNT = stringPreferencesKey("yt_account_name")
    }

    init {
        // Initialize TTS service
        viewModelScope.launch {
            ttsService.initialize()
        }
        // Restore persisted YouTube account
        viewModelScope.launch {
            val prefs = context.dataStore.data.first()
            val savedAccount = prefs[KEY_YT_ACCOUNT]
            if (!savedAccount.isNullOrBlank()) {
                _uiState.value = _uiState.value.copy(
                    isYouTubeConnected = true,
                    connectedAccountName = savedAccount
                )
            }
        }
    }
    
    fun updateText(text: String) {
        _uiState.value = _uiState.value.copy(text = text, detectedLanguageNotice = null)
        
        languageDetectionJob?.cancel()
        if (text.trim().length >= 10) {
            languageDetectionJob = viewModelScope.launch {
                delay(1500) // 1.5 seconds debounce
                autoDetectLanguage(isAuto = true)
            }
        }
    }
    
    fun updateLanguage(languageCode: String) {
        _uiState.value = _uiState.value.copy(selectedLanguage = languageCode)
    }
    
    fun updateTtsEngine(engine: String) {
        _uiState.value = _uiState.value.copy(selectedTtsEngine = engine)
    }
    
    fun updateOcrMode(mode: OcrMode) {
        _uiState.value = _uiState.value.copy(ocrMode = mode)
    }
    
    fun updateSlowSpeech(isSlow: Boolean) {
        _uiState.value = _uiState.value.copy(isSlowSpeech = isSlow)
    }
    
    fun updateRepetitions(repetitions: Int) {
        _uiState.value = _uiState.value.copy(repetitions = repetitions)
    }
    
    fun generateAudio() {
        val currentState = _uiState.value
        
        if (currentState.text.isBlank()) {
            _uiState.value = currentState.copy(errorMessage = "Please enter some text")
            return
        }
        
        viewModelScope.launch {
            try {
                _uiState.value = currentState.copy(errorMessage = null)
                
                val result = ttsService.generateAudio(
                    text = currentState.text,
                    languageCode = currentState.selectedLanguage,
                    isSlow = currentState.isSlowSpeech,
                    repetitions = currentState.repetitions,
                    engine = currentState.selectedTtsEngine
                )
                
                result.fold(
                    onSuccess = { audioFile ->
                        _generatedAudioFile.value = audioFile
                        _uiState.value = currentState.copy(
                            successMessage = if (currentState.repetitions > 1) {
                                "Audio generated successfully with ${currentState.repetitions} repetitions!"
                            } else {
                                "Audio generated successfully!"
                            }
                        )
                    },
                    onFailure = { exception ->
                        _uiState.value = currentState.copy(
                            errorMessage = "Failed to generate audio: ${exception.message}"
                        )
                    }
                )
                
            } catch (e: Exception) {
                _uiState.value = currentState.copy(
                    errorMessage = "Unexpected error: ${e.message}"
                )
            }
        }
    }

    fun generateMetadata() {
        val currentState = _uiState.value
        
        if (currentState.text.isBlank()) {
            _uiState.value = currentState.copy(errorMessage = "Please enter some text")
            return
        }

        viewModelScope.launch {
            _uiState.value = currentState.copy(isGeneratingMetadata = true, errorMessage = null)
            try {
                val (title, description) = youtubeMetadataGenerator.generateMetadata(currentState.text)
                
                _uiState.value = _uiState.value.copy(
                    isGeneratingMetadata = false,
                    youtubeTitle = title,
                    youtubeDescription = description,
                    successMessage = "YouTube Title and Description generated successfully!"
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isGeneratingMetadata = false,
                    errorMessage = "Failed to generate metadata: ${e.message}"
                )
            }
        }
    }
    
    fun updateYoutubeTitle(title: String) {
        _uiState.value = _uiState.value.copy(youtubeTitle = title)
    }

    fun updateYoutubeDescription(description: String) {
        _uiState.value = _uiState.value.copy(youtubeDescription = description)
    }

    fun setYouTubeConnected(connected: Boolean, accountName: String? = null) {
        _uiState.value = _uiState.value.copy(
            isYouTubeConnected = connected,
            connectedAccountName = accountName
        )
        // Persist account name to DataStore
        viewModelScope.launch {
            context.dataStore.edit { prefs ->
                if (connected && accountName != null) {
                    prefs[KEY_YT_ACCOUNT] = accountName
                } else {
                    prefs.remove(KEY_YT_ACCOUNT)
                }
            }
        }
        // Fetch channels after connecting
        if (connected) {
            fetchYouTubeChannels()
        }
    }

    fun fetchYouTubeChannels() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isFetchingChannels = true,
                youtubeChannels = emptyList(),
                selectedChannelId = null,
                selectedChannelName = null
            )
            try {
                val accessToken = youtubeAuthManager.getFreshAccessToken()
                val channels = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val transport = NetHttpTransport()
                    val jsonFactory = GsonFactory.getDefaultInstance()
                    val requestInitializer = com.google.api.client.http.HttpRequestInitializer { request ->
                        request.headers.authorization = "Bearer $accessToken"
                    }
                    val youtubeService = YouTube.Builder(transport, jsonFactory, requestInitializer)
                        .setApplicationName("PurrfectBytes")
                        .build()

                    val response = youtubeService.channels()
                        .list("snippet")
                        .setMine(true)
                        .execute()

                    val items = response.items
                    if (items.isNullOrEmpty()) {
                        emptyList()
                    } else {
                        println("YouTube API returned ${items.size} channels")
                        items.map { ch ->
                            YouTubeChannel(
                                id = ch.id ?: "",
                                title = ch.snippet?.title ?: "Unknown Channel",
                                thumbnailUrl = try { ch.snippet?.thumbnails?.get("default")?.let { (it as? com.google.api.services.youtube.model.Thumbnail)?.url } } catch (_: Exception) { null }
                            )
                        }
                    }
                }

                if (channels.isEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        isFetchingChannels = false,
                        successMessage = "YouTube Connected"
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        isFetchingChannels = false,
                        youtubeChannels = channels,
                        showChannelPicker = channels.size > 1
                    )

                    // Auto-select if there's only one channel
                    if (channels.size == 1) {
                        selectYouTubeChannel(channels[0])
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.value = _uiState.value.copy(
                    isFetchingChannels = false,
                    errorMessage = "Failed to fetch channels: ${e.message}"
                )
            }
        }
    }

    fun selectYouTubeChannel(channel: YouTubeChannel) {
        _uiState.value = _uiState.value.copy(
            selectedChannelId = channel.id,
            selectedChannelName = channel.title,
            showChannelPicker = false,
            // Reset playlist when channel changes
            selectedPlaylistId = null,
            selectedPlaylistName = "",
            availablePlaylists = emptyList()
        )
        fetchYouTubePlaylists(channel.id)
    }

    fun fetchYouTubePlaylists(channelId: String) {
        _uiState.value = _uiState.value.copy(isFetchingPlaylists = true, errorMessage = null)
        viewModelScope.launch {
            try {
                val accessToken = youtubeAuthManager.getFreshAccessToken()
                val playlists = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val transport = NetHttpTransport()
                    val jsonFactory = GsonFactory.getDefaultInstance()
                    val requestInitializer = com.google.api.client.http.HttpRequestInitializer { request ->
                        request.headers.authorization = "Bearer $accessToken"
                    }
                    val youtubeService = YouTube.Builder(transport, jsonFactory, requestInitializer)
                        .setApplicationName("PurrfectBytes")
                        .build()

                    val response = youtubeService.playlists()
                        .list("snippet")
                        .setMine(true) // Native Android OAuth binds to the main identity, so we use mine=true
                        .setMaxResults(50L)
                        .execute()

                    val items = response.items
                    if (items.isNullOrEmpty()) {
                        emptyList()
                    } else {
                        items.map { pl ->
                            YouTubePlaylist(
                                id = pl.id ?: "",
                                title = pl.snippet?.title ?: "Unknown Playlist"
                            )
                        }
                    }
                }

                _uiState.value = _uiState.value.copy(
                    isFetchingPlaylists = false,
                    availablePlaylists = playlists
                )
            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.value = _uiState.value.copy(
                    isFetchingPlaylists = false,
                    errorMessage = "Failed to fetch playlists: ${e.message}"
                )
            }
        }
    }

    fun dismissChannelPicker() {
        _uiState.value = _uiState.value.copy(showChannelPicker = false)
    }

    fun showChannelPickerDialog() {
        _uiState.value = _uiState.value.copy(showChannelPicker = true)
    }

    fun updateYouTubePlaylist(playlist: YouTubePlaylist) {
        _uiState.value = _uiState.value.copy(
            selectedPlaylistId = playlist.id,
            selectedPlaylistName = playlist.title
        )
    }

    fun updateYouTubePrivacy(privacy: String) {
        _uiState.value = _uiState.value.copy(selectedPrivacy = privacy)
    }

    fun uploadToYouTube() {
        val currentState = _uiState.value
        val videoFile = _generatedVideoFile.value
        
        if (videoFile == null || !videoFile.exists()) {
            _uiState.value = currentState.copy(errorMessage = "No video available to upload")
            return
        }
        
        if (!currentState.isYouTubeConnected) {
            _uiState.value = currentState.copy(errorMessage = "Not connected to YouTube")
            return
        }

        viewModelScope.launch {
            _uiState.value = currentState.copy(isUploadingToYouTube = true, errorMessage = null, successMessage = null)
            
            try {
                val accessToken = youtubeAuthManager.getFreshAccessToken()

                val result = youtubeVideoUploader.uploadVideo(
                    videoFile = videoFile,
                    title = currentState.youtubeTitle,
                    description = currentState.youtubeDescription,
                    privacyStatus = currentState.selectedPrivacy,
                    playlistId = currentState.selectedPlaylistId,
                    accessToken = accessToken
                )

                result.onSuccess { videoId ->
                    _uiState.value = _uiState.value.copy(
                        isUploadingToYouTube = false,
                        successMessage = "YouTube Upload Successful! Video ID: $videoId"
                    )
                }.onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        isUploadingToYouTube = false,
                        errorMessage = "YouTube Upload Failed: ${error.message}"
                    )
                }

            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isUploadingToYouTube = false,
                    errorMessage = "Error initiating upload: ${e.message}"
                )
            }
        }
    }

    fun generateNativeVideo() {
        val currentState = _uiState.value
        if (currentState.text.isBlank()) {
            _uiState.value = currentState.copy(errorMessage = "Please enter some text")
            return
        }

        viewModelScope.launch {
            _uiState.value = currentState.copy(isConvertingVideo = true, errorMessage = null)
            try {
                // 1. Generate audio natively
                val audioResult = ttsService.generateAudio(
                    text = currentState.text,
                    languageCode = currentState.selectedLanguage,
                    isSlow = currentState.isSlowSpeech,
                    repetitions = currentState.repetitions,
                    engine = currentState.selectedTtsEngine
                )
                
                audioResult.fold(
                    onSuccess = { audioFile ->
                        _generatedAudioFile.value = audioFile
                        
                        // 2. Generate video natively using the new audio file
                        val videoFile = videoGeneratorService.generateVideo(
                            text = currentState.text,
                            audioFile = audioFile,
                            repetitions = currentState.repetitions
                        )
                        
                        if (videoFile != null) {
                            _generatedVideoFile.value = videoFile
                            _uiState.value = _uiState.value.copy(
                                isConvertingVideo = false,
                                successMessage = "Video generated successfully!"
                            )
                        } else {
                            _uiState.value = _uiState.value.copy(
                                isConvertingVideo = false,
                                errorMessage = "Failed to encode video."
                            )
                        }
                    },
                    onFailure = { exception ->
                        _uiState.value = _uiState.value.copy(
                            isConvertingVideo = false,
                            errorMessage = "Failed to generate audio for video: ${exception.message}"
                        )
                    }
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isConvertingVideo = false,
                    errorMessage = "Unexpected error generating video: ${e.message}"
                )
            }
        }
    }
    
    fun autoDetectLanguage(isAuto: Boolean = false) {
        val currentState = _uiState.value
        if (currentState.text.isBlank()) {
            if (!isAuto) {
                _uiState.value = currentState.copy(errorMessage = "Please enter some text to detect")
            }
            return
        }

        _uiState.value = currentState.copy(isDetectingLanguage = !isAuto, errorMessage = null)
        
        val languageIdentifier = LanguageIdentification.getClient()
        languageIdentifier.identifyLanguage(currentState.text)
            .addOnSuccessListener { languageCode ->
                if (languageCode == "und") {
                    _uiState.value = _uiState.value.copy(
                        isDetectingLanguage = false,
                        detectedLanguageNotice = "❌ Could not identify language",
                        isDetectingLanguageError = true
                    )
                } else {
                    // Try to match the detected language code with supported languages
                    val supported = ttsService.getSupportedLanguages()
                    // ML Kit returns BCP-47 codes like zh-Latn, zh, en, fr
                    // We match the prefix for simplicity
                    val matchedCode = supported.find { it.first == languageCode.substringBefore("-") }?.first
                    
                    if (matchedCode != null) {
                        val languageName = supported.find { it.first == matchedCode }?.second ?: matchedCode
                        _uiState.value = _uiState.value.copy(
                            isDetectingLanguage = false,
                            selectedLanguage = matchedCode,
                            detectedLanguageNotice = "✓ Detected: $languageName",
                            isDetectingLanguageError = false
                        )
                    } else {
                         _uiState.value = _uiState.value.copy(
                            isDetectingLanguage = false,
                            detectedLanguageNotice = "❌ Detected unsupported language: $languageCode",
                            isDetectingLanguageError = true
                        )
                    }
                }
            }
            .addOnFailureListener { e ->
                _uiState.value = _uiState.value.copy(
                    isDetectingLanguage = false,
                    detectedLanguageNotice = "❌ Detection failed: ${e.message}",
                    isDetectingLanguageError = true
                )
            }
    }

    fun playAudio() {
        _generatedAudioFile.value?.let { audioFile ->
            ttsService.playAudio(audioFile)
        }
    }
    
    fun stopAudio() {
        ttsService.stopAudio()
    }
    
    fun clearMessages() {
        _uiState.value = _uiState.value.copy(
            errorMessage = null,
            successMessage = null
        )
    }

    fun dismissVideo() {
        _generatedVideoFile.value = null
    }

    fun dismissPreview() {
        _previewImageFile.value = null
    }
    
    fun generatePreview() {
        val currentState = _uiState.value
        
        if (currentState.text.isBlank()) {
            _uiState.value = currentState.copy(errorMessage = "Please enter some text")
            return
        }

        viewModelScope.launch {
            try {
                _uiState.value = currentState.copy(errorMessage = null)
                val previewImage = videoGeneratorService.getPreviewImage(currentState.text)
                
                if (previewImage != null) {
                    _previewImageFile.value = previewImage
                } else {
                    _uiState.value = currentState.copy(
                        errorMessage = "Failed to generate preview."
                    )
                }
            } catch (e: Exception) {
                _uiState.value = currentState.copy(
                    errorMessage = "Unexpected error generating preview: ${e.message}"
                )
            }
        }
    }

    fun openCamera() {
        _showCamera.value = true
    }

    fun closeCamera() {
        _showCamera.value = false
    }

    fun onPhotoCaptured(uri: Uri) {
        _capturedPhotoUri.value = uri
        _showCamera.value = false
        _uiState.value = _uiState.value.copy(
            successMessage = "Photo captured successfully! Analyzing text..."
        )
        analyzePhotoForText(uri)
    }

    fun clearPhoto() {
        _capturedPhotoUri.value = null
        _recognizedTextBlocks.value = emptyList()
        _showTextAnalyzer.value = false
    }

    private fun analyzePhotoForText(uri: Uri) {
        viewModelScope.launch {
            _isAnalyzingPhoto.value = true
            // Only show analyzer immediately if interactive, otherwise we'll decide later
            if (_uiState.value.ocrMode == OcrMode.INTERACTIVE) {
                _showTextAnalyzer.value = true
            }

            textRecognitionProcessor.processImageFromUri(uri, _selectedScript.value).fold(
                onSuccess = { blocks ->
                    _recognizedTextBlocks.value = blocks
                    _isAnalyzingPhoto.value = false
                    if (blocks.isNotEmpty()) {
                        val scriptInfo = blocks.firstOrNull()?.detectedLanguage ?: "unknown"
                        
                        if (_uiState.value.ocrMode == OcrMode.AUTO_INSERT) {
                            val allText = blocks.joinToString("\n") { it.text }
                            updateText(allText)
                            _uiState.value = _uiState.value.copy(
                                successMessage = "Auto extracted ${blocks.size} text block(s) using $scriptInfo recognizer!"
                            )
                            clearPhoto() // Dismiss UI on auto insert
                        } else {
                            _showTextAnalyzer.value = true
                            _uiState.value = _uiState.value.copy(
                                successMessage = "Found ${blocks.size} text block(s) using $scriptInfo recognizer!"
                            )
                        }
                    } else {
                        _uiState.value = _uiState.value.copy(
                            errorMessage = "No text detected. Try a different language option."
                        )
                    }
                },
                onFailure = { error ->
                    _isAnalyzingPhoto.value = false
                    _uiState.value = _uiState.value.copy(
                        errorMessage = "Failed to analyze text: ${error.message}"
                    )
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
        updateText(text)
        _showTextAnalyzer.value = false
        _uiState.value = _uiState.value.copy(
            successMessage = "Text added to input field"
        )
    }

    fun dismissTextAnalyzer() {
        _showTextAnalyzer.value = false
    }

    override fun onCleared() {
        super.onCleared()
        ttsService.cleanup()
        textRecognitionProcessor.close()
    }
}

enum class OcrMode(val displayName: String) {
    INTERACTIVE("Interactive Text Selection (Show boxes)"),
    AUTO_INSERT("Auto Extract (Insert all text automatically)")
}

data class YouTubePlaylist(
    val id: String,
    val title: String
)

data class YouTubeChannel(
    val id: String,
    val title: String,
    val thumbnailUrl: String? = null
)

data class MainUiState(
    val text: String = "",
    val selectedLanguage: String = "en",
    val selectedTtsEngine: String = "edge",
    val ocrMode: OcrMode = OcrMode.INTERACTIVE,
    val isSlowSpeech: Boolean = false,
    val repetitions: Int = 10,
    val errorMessage: String? = null,
    val successMessage: String? = null,
    val isConvertingVideo: Boolean = false,
    val isGeneratingMetadata: Boolean = false,
    val youtubeTitle: String = "",
    val youtubeDescription: String = "",
    val isUploadingToYouTube: Boolean = false,
    val isDetectingLanguage: Boolean = false,
    val detectedLanguageNotice: String? = null,
    val isDetectingLanguageError: Boolean = false,
    val isYouTubeConnected: Boolean = false,
    val connectedAccountName: String? = null,
    val selectedPlaylistId: String? = null,
    val selectedPlaylistName: String = "",
    val availablePlaylists: List<YouTubePlaylist> = emptyList(),
    val isFetchingPlaylists: Boolean = false,
    val selectedPrivacy: String = "Public",
    val youtubeChannels: List<YouTubeChannel> = emptyList(),
    val selectedChannelId: String? = null,
    val selectedChannelName: String? = null,
    val showChannelPicker: Boolean = false,
    val isFetchingChannels: Boolean = false
)