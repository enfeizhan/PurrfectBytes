package com.purrfectbytes.android.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.purrfectbytes.android.services.TTSService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val ttsService: TTSService
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()
    
    private val _generatedAudioFile = MutableStateFlow<File?>(null)
    val generatedAudioFile: StateFlow<File?> = _generatedAudioFile.asStateFlow()
    
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
    
    override fun onCleared() {
        super.onCleared()
        ttsService.cleanup()
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