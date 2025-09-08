package com.purrfectbytes.android.viewmodels

import android.app.Application
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.purrfectbytes.android.data.repository.TTSRepository
import com.purrfectbytes.android.ui.states.HomeUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.*
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    application: Application,
    private val repository: TTSRepository
) : AndroidViewModel(application) {
    
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
    
    private var textToSpeech: TextToSpeech? = null
    private var ttsInitialized = false
    
    val languages = mapOf(
        "en" to "English",
        "es" to "Spanish",
        "fr" to "French",
        "de" to "German",
        "it" to "Italian",
        "pt" to "Portuguese",
        "ru" to "Russian",
        "ja" to "Japanese",
        "ko" to "Korean",
        "zh" to "Chinese"
    )
    
    init {
        initializeTTS()
    }
    
    private fun initializeTTS() {
        textToSpeech = TextToSpeech(getApplication()) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsInitialized = true
                textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        _uiState.update { it.copy(isPlaying = true, isPaused = false) }
                    }
                    
                    override fun onDone(utteranceId: String?) {
                        _uiState.update { it.copy(isPlaying = false, isPaused = false) }
                    }
                    
                    override fun onError(utteranceId: String?) {
                        _uiState.update { it.copy(isPlaying = false, isPaused = false) }
                    }
                })
            }
        }
    }
    
    fun updateInputText(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }
    
    fun updateLanguage(languageCode: String) {
        _uiState.update { 
            it.copy(
                selectedLanguage = languageCode,
                selectedLanguageName = languages[languageCode] ?: "English"
            )
        }
    }
    
    fun updateSlowMode(slow: Boolean) {
        _uiState.update { it.copy(slowMode = slow) }
    }
    
    fun updateTTSMode(useLocal: Boolean) {
        _uiState.update { it.copy(useLocalTTS = useLocal) }
    }
    
    suspend fun convertToSpeech() {
        val text = _uiState.value.inputText
        if (text.isBlank()) return
        
        _uiState.update { it.copy(isProcessing = true) }
        
        if (_uiState.value.useLocalTTS) {
            // Use local TTS
            if (ttsInitialized) {
                val locale = when (_uiState.value.selectedLanguage) {
                    "en" -> Locale.ENGLISH
                    "es" -> Locale("es", "ES")
                    "fr" -> Locale.FRENCH
                    "de" -> Locale.GERMAN
                    "it" -> Locale.ITALIAN
                    "pt" -> Locale("pt", "PT")
                    "ru" -> Locale("ru", "RU")
                    "ja" -> Locale.JAPANESE
                    "ko" -> Locale.KOREAN
                    "zh" -> Locale.CHINESE
                    else -> Locale.ENGLISH
                }
                
                textToSpeech?.language = locale
                textToSpeech?.setSpeechRate(if (_uiState.value.slowMode) 0.5f else 1.0f)
                
                val params = Bundle()
                textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "utterance")
            }
        } else {
            // Use server TTS
            viewModelScope.launch {
                try {
                    val result = repository.convertTextToSpeech(
                        text = text,
                        language = _uiState.value.selectedLanguage,
                        slow = _uiState.value.slowMode
                    )
                    
                    // Handle the server response
                    result.onSuccess { audioFile ->
                        // Play the audio file from server
                        _uiState.update { it.copy(currentAudioFile = audioFile) }
                    }
                } catch (e: Exception) {
                    // Handle error
                    e.printStackTrace()
                }
            }
        }
        
        _uiState.update { it.copy(isProcessing = false) }
    }
    
    fun stopAudio() {
        textToSpeech?.stop()
        _uiState.update { it.copy(isPlaying = false, isPaused = false) }
    }
    
    fun pauseAudio() {
        if (_uiState.value.isPaused) {
            // Resume
            textToSpeech?.speak(
                _uiState.value.inputText,
                TextToSpeech.QUEUE_ADD,
                null,
                "utterance"
            )
            _uiState.update { it.copy(isPaused = false) }
        } else {
            // Pause
            textToSpeech?.stop()
            _uiState.update { it.copy(isPaused = true) }
        }
    }
    
    fun clearText() {
        stopAudio()
        _uiState.update { it.copy(inputText = "") }
    }
    
    override fun onCleared() {
        super.onCleared()
        textToSpeech?.shutdown()
    }
}