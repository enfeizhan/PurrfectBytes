package com.purrfectbytes.android.services

import android.content.Context
import android.media.MediaPlayer
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import java.io.File
import java.io.IOException
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TTSService @Inject constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "TTSService"
        const val UTTERANCE_ID_PREFIX = "purrfect_"
    }
    
    private var textToSpeech: TextToSpeech? = null
    private var isInitialized = false
    private var mediaPlayer: MediaPlayer? = null
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading
    
    private val _currentStatus = MutableStateFlow("")
    val currentStatus: StateFlow<String> = _currentStatus
    
    // Language mappings that match web app
    private val supportedLanguages = mapOf(
        "en" to Locale.ENGLISH,
        "es" to Locale("es"),
        "fr" to Locale.FRENCH,
        "de" to Locale.GERMAN,
        "it" to Locale.ITALIAN,
        "pt" to Locale("pt"),
        "ru" to Locale("ru"),
        "ja" to Locale.JAPANESE,
        "ko" to Locale.KOREAN,
        "zh" to Locale.CHINESE,
        "ar" to Locale("ar"),
        "hi" to Locale("hi"),
        "nl" to Locale("nl"),
        "pl" to Locale("pl"),
        "tr" to Locale("tr"),
        "sv" to Locale("sv"),
        "da" to Locale("da"),
        "no" to Locale("no"),
        "fi" to Locale("fi")
    )
    
    suspend fun initialize(): Boolean = withContext(Dispatchers.Main) {
        if (isInitialized) return@withContext true
        
        _isLoading.value = true
        _currentStatus.value = "Initializing text-to-speech engine..."
        
        return@withContext suspendCoroutine { continuation ->
            textToSpeech = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    isInitialized = true
                    _currentStatus.value = "Ready"
                    Log.d(TAG, "TTS initialized successfully")
                    continuation.resume(true)
                } else {
                    _currentStatus.value = "Failed to initialize TTS"
                    Log.e(TAG, "TTS initialization failed")
                    continuation.resume(false)
                }
                _isLoading.value = false
            }
        }
    }
    
    suspend fun generateAudio(
        text: String,
        languageCode: String = "en",
        isSlow: Boolean = false,
        repetitions: Int = 1
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            if (!isInitialized) {
                val initialized = initialize()
                if (!initialized) {
                    return@withContext Result.failure(Exception("Failed to initialize TTS"))
                }
            }
            
            _isLoading.value = true
            _currentStatus.value = "Generating audio..."
            
            val locale = supportedLanguages[languageCode] ?: Locale.ENGLISH
            val result = textToSpeech?.setLanguage(locale)
            
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "Language $languageCode not supported, falling back to English")
                textToSpeech?.setLanguage(Locale.ENGLISH)
            }
            
            // Set speech rate
            val speechRate = if (isSlow) 0.5f else 1.0f
            textToSpeech?.setSpeechRate(speechRate)
            
            // Create output file
            val fileName = "tts_${System.currentTimeMillis()}.wav"
            val outputFile = File(context.getExternalFilesDir(null), fileName)
            
            if (repetitions == 1) {
                // Single generation
                val success = generateSingleAudio(text, outputFile)
                return@withContext if (success) {
                    Result.success(outputFile)
                } else {
                    Result.failure(Exception("Failed to generate audio"))
                }
            } else {
                // Multiple repetitions - generate once then repeat
                val tempFile = File(context.getExternalFilesDir(null), "temp_${System.currentTimeMillis()}.wav")
                val singleSuccess = generateSingleAudio(text, tempFile)
                
                if (singleSuccess) {
                    _currentStatus.value = "Creating ${repetitions} repetitions..."
                    val concatenated = concatenateAudio(tempFile, repetitions, outputFile)
                    tempFile.delete()
                    
                    return@withContext if (concatenated) {
                        Result.success(outputFile)
                    } else {
                        Result.failure(Exception("Failed to concatenate audio"))
                    }
                } else {
                    tempFile.delete()
                    return@withContext Result.failure(Exception("Failed to generate base audio"))
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error generating audio", e)
            return@withContext Result.failure(e)
        } finally {
            _isLoading.value = false
        }
    }
    
    private suspend fun generateSingleAudio(text: String, outputFile: File): Boolean = 
        suspendCoroutine { continuation ->
            val utteranceId = "${UTTERANCE_ID_PREFIX}${System.currentTimeMillis()}"
            
            textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    Log.d(TAG, "TTS started for: $utteranceId")
                }
                
                override fun onDone(utteranceId: String?) {
                    Log.d(TAG, "TTS completed for: $utteranceId")
                    if (utteranceId?.startsWith(UTTERANCE_ID_PREFIX) == true) {
                        continuation.resume(true)
                    }
                }
                
                override fun onError(utteranceId: String?) {
                    Log.e(TAG, "TTS error for: $utteranceId")
                    if (utteranceId?.startsWith(UTTERANCE_ID_PREFIX) == true) {
                        continuation.resume(false)
                    }
                }
            })
            
            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            }
            
            val result = textToSpeech?.synthesizeToFile(text, params, outputFile, utteranceId)
            
            if (result != TextToSpeech.SUCCESS) {
                continuation.resume(false)
            }
        }
    
    private fun concatenateAudio(inputFile: File, repetitions: Int, outputFile: File): Boolean {
        return try {
            // Simple approach: copy the file content multiple times
            // This is a basic implementation - for better audio concatenation,
            // you'd want to use audio processing libraries
            outputFile.outputStream().use { output ->
                repeat(repetitions) {
                    inputFile.inputStream().use { input ->
                        input.copyTo(output)
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error concatenating audio", e)
            false
        }
    }
    
    fun playAudio(audioFile: File) {
        try {
            stopAudio()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(audioFile.absolutePath)
                setOnPreparedListener { start() }
                setOnCompletionListener { 
                    Log.d(TAG, "Audio playback completed")
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error: $what, $extra")
                    true
                }
                prepareAsync()
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error playing audio", e)
        }
    }
    
    fun stopAudio() {
        mediaPlayer?.apply {
            if (isPlaying) {
                stop()
            }
            release()
        }
        mediaPlayer = null
    }
    
    fun getSupportedLanguages(): List<Pair<String, String>> {
        return listOf(
            "en" to "English",
            "es" to "Spanish", 
            "fr" to "French",
            "de" to "German",
            "it" to "Italian",
            "pt" to "Portuguese",
            "ru" to "Russian",
            "ja" to "Japanese",
            "ko" to "Korean",
            "zh" to "Chinese",
            "ar" to "Arabic",
            "hi" to "Hindi",
            "nl" to "Dutch",
            "pl" to "Polish",
            "tr" to "Turkish",
            "sv" to "Swedish",
            "da" to "Danish",
            "no" to "Norwegian",
            "fi" to "Finnish"
        )
    }
    
    fun cleanup() {
        stopAudio()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        isInitialized = false
    }
}