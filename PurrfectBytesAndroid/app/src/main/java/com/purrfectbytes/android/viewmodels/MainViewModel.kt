package com.purrfectbytes.android.viewmodels

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.purrfectbytes.android.services.TTSService
import com.purrfectbytes.android.services.TextRecognitionProcessor
import com.purrfectbytes.android.services.RecognizedTextBlock
import com.purrfectbytes.android.services.RecognitionScript
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val ttsService: TTSService,
    private val textRecognitionProcessor: TextRecognitionProcessor
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()
    
    private val _generatedAudioFile = MutableStateFlow<File?>(null)
    val generatedAudioFile: StateFlow<File?> = _generatedAudioFile.asStateFlow()

    private val _capturedPhotoUri = MutableStateFlow<Uri?>(null)
    val capturedPhotoUri: StateFlow<Uri?> = _capturedPhotoUri.asStateFlow()

    private val _showCamera = MutableStateFlow(false)
    val showCamera: StateFlow<Boolean> = _showCamera.asStateFlow()

    private val _recognizedTextBlocks = MutableStateFlow<List<RecognizedTextBlock>>(emptyList())
    val recognizedTextBlocks: StateFlow<List<RecognizedTextBlock>> = _recognizedTextBlocks.asStateFlow()

    private val _isAnalyzingPhoto = MutableStateFlow(false)
    val isAnalyzingPhoto: StateFlow<Boolean> = _isAnalyzingPhoto.asStateFlow()

    private val _showTextAnalyzer = MutableStateFlow(false)
    val showTextAnalyzer: StateFlow<Boolean> = _showTextAnalyzer.asStateFlow()

    private val _selectedScript = MutableStateFlow(RecognitionScript.AUTO)
    val selectedScript: StateFlow<RecognitionScript> = _selectedScript.asStateFlow()

    val isLoading = ttsService.isLoading
    val currentStatus = ttsService.currentStatus
    
    init {
        // Initialize TTS service
        viewModelScope.launch {
            ttsService.initialize()
        }
    }
    
    fun updateText(text: String) {
        _uiState.value = _uiState.value.copy(text = text)
    }
    
    fun updateLanguage(languageCode: String) {
        _uiState.value = _uiState.value.copy(selectedLanguage = languageCode)
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
                    repetitions = currentState.repetitions
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
    
    fun getSupportedLanguages(): List<Pair<String, String>> {
        return ttsService.getSupportedLanguages()
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
            _showTextAnalyzer.value = true

            textRecognitionProcessor.processImageFromUri(uri, _selectedScript.value).fold(
                onSuccess = { blocks ->
                    _recognizedTextBlocks.value = blocks
                    _isAnalyzingPhoto.value = false
                    if (blocks.isNotEmpty()) {
                        val scriptInfo = blocks.firstOrNull()?.detectedLanguage ?: "unknown"
                        _uiState.value = _uiState.value.copy(
                            successMessage = "Found ${blocks.size} text block(s) using $scriptInfo recognizer!"
                        )
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

data class MainUiState(
    val text: String = "",
    val selectedLanguage: String = "en",
    val isSlowSpeech: Boolean = false,
    val repetitions: Int = 1,
    val errorMessage: String? = null,
    val successMessage: String? = null
)