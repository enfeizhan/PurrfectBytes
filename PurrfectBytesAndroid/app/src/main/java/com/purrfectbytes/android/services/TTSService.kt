package com.purrfectbytes.android.services

import android.content.Context
import android.media.MediaPlayer
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class TTSService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val storage: MediaStorage,
    private val edgeTTSEngine: EdgeTTSEngine,
    private val cache: SpeechCache
) {
    companion object {
        private const val TAG = "TTSService"
        const val UTTERANCE_ID_PREFIX = "purrfect_"
        const val ENGINE_EDGE = "edge"
        const val ENGINE_NATIVE = "native"
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
        "fi" to Locale("fi"),
        "vi" to Locale("vi")
    )

    suspend fun initialize(): Boolean = withContext(Dispatchers.Main) {
        if (isInitialized) return@withContext true

        _isLoading.value = true
        _currentStatus.value = "Initializing text-to-speech engine..."

        return@withContext suspendCancellableCoroutine { continuation ->
            textToSpeech = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    isInitialized = true
                    _currentStatus.value = "Ready"
                    Log.d(TAG, "TTS initialized successfully")
                } else {
                    _currentStatus.value = "Failed to initialize TTS"
                    Log.e(TAG, "TTS initialization failed")
                }
                _isLoading.value = false
                if (continuation.isActive) continuation.resume(status == TextToSpeech.SUCCESS)
            }
        }
    }

    /**
     * Speaks [text] once into a new file. Repeating is left to the video, which repeats
     * the finished clip instead of generating the same speech again.
     *
     * [voice] is one of the voices of Edge TTS; without it, and with the phone's own
     * engine, the text is read by the voice of the language.
     */
    suspend fun generateAudio(
        text: String,
        languageCode: String = "en",
        isSlow: Boolean = false,
        engine: String = ENGINE_EDGE,
        voice: String? = null
    ): Result<SpeechAudio> = withContext(Dispatchers.IO) {
        _isLoading.value = true
        _currentStatus.value = "Generating audio..."
        try {
            if (engine == ENGINE_NATIVE) {
                generateWithPhoneEngine(text, languageCode, isSlow)
            } else {
                generateWithEdge(text, languageCode, isSlow, voice)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error generating audio", e)
            Result.failure(e)
        } finally {
            _isLoading.value = false
            _currentStatus.value = "Ready"
        }
    }

    /**
     * Speech that was generated before is copied, not asked for again. The phone's own
     * engine has no part in this: it needs no network, and what it sounds like changes
     * with the voices installed on the phone.
     */
    private suspend fun generateWithEdge(
        text: String,
        languageCode: String,
        isSlow: Boolean,
        voice: String?
    ): Result<SpeechAudio> {
        val speaker = voice ?: EdgeTTSEngine.defaultVoice(languageCode)
        val key = cache.key(text, languageCode, ENGINE_EDGE, speaker, EdgeTTSEngine.rateOf(isSlow))

        cache.fetch(key, storage.newSpeechFile("mp3"))?.let { kept ->
            Log.d(TAG, "Speech ${key.take(8)} was generated before")
            return Result.success(kept)
        }
        return edgeTTSEngine.generateAudio(text, languageCode, isSlow, storage.newSpeechFile("mp3"), speaker)
            .onSuccess { cache.store(key, it) }
    }

    private suspend fun generateWithPhoneEngine(
        text: String,
        languageCode: String,
        isSlow: Boolean
    ): Result<SpeechAudio> {
        if (!isInitialized && !initialize()) {
            return Result.failure(Exception("The phone's text-to-speech engine could not be started"))
        }
        val tts = textToSpeech
            ?: return Result.failure(Exception("The phone's text-to-speech engine could not be started"))

        val locale = supportedLanguages[languageCode] ?: Locale.ENGLISH
        val availability = tts.setLanguage(locale)
        if (availability == TextToSpeech.LANG_MISSING_DATA || availability == TextToSpeech.LANG_NOT_SUPPORTED) {
            // Reading the text with an English voice instead would produce a video that sounds wrong
            return Result.failure(
                Exception(
                    "This phone has no ${locale.getDisplayLanguage(Locale.ENGLISH)} voice installed. " +
                        "Add it in the phone's text-to-speech settings, or use Edge TTS."
                )
            )
        }

        val limit = TextToSpeech.getMaxSpeechInputLength()
        if (text.length > limit) {
            return Result.failure(
                Exception("The text is too long for the phone's engine (${text.length} of $limit characters)")
            )
        }

        tts.setSpeechRate(if (isSlow) 0.5f else 1.0f)

        val outputFile = storage.newSpeechFile("wav")
        return if (synthesizeToFile(tts, text, outputFile)) {
            Result.success(SpeechAudio(outputFile))
        } else {
            outputFile.delete()
            Result.failure(Exception("The phone's text-to-speech engine could not read this text"))
        }
    }

    private suspend fun synthesizeToFile(tts: TextToSpeech, text: String, outputFile: File): Boolean =
        suspendCancellableCoroutine { continuation ->
            val utteranceId = "${UTTERANCE_ID_PREFIX}${System.currentTimeMillis()}"

            // The engine reports from its own thread and may report more than once
            val reported = AtomicBoolean(false)
            fun report(success: Boolean) {
                if (reported.compareAndSet(false, true) && continuation.isActive) {
                    continuation.resume(success)
                }
            }

            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {
                    Log.d(TAG, "TTS started for: $id")
                }

                override fun onDone(id: String?) {
                    Log.d(TAG, "TTS completed for: $id")
                    if (id == utteranceId) report(true)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) {
                    Log.e(TAG, "TTS error for: $id")
                    if (id == utteranceId) report(false)
                }

                override fun onError(id: String?, errorCode: Int) {
                    Log.e(TAG, "TTS error $errorCode for: $id")
                    if (id == utteranceId) report(false)
                }
            })

            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            }
            if (tts.synthesizeToFile(text, params, outputFile, utteranceId) != TextToSpeech.SUCCESS) {
                report(false)
            }

            continuation.invokeOnCancellation { tts.stop() }
        }

    /** Plays [audioFiles] one after the other, such as the lines of a conversation. */
    fun playAudio(audioFiles: List<File>) {
        stopAudio()
        playFrom(audioFiles, 0)
    }

    private fun playFrom(audioFiles: List<File>, index: Int) {
        val audioFile = audioFiles.getOrNull(index) ?: return
        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(audioFile.absolutePath)
                setOnPreparedListener { start() }
                setOnCompletionListener { finished ->
                    Log.d(TAG, "Audio playback completed")
                    // Stopping in the meantime has put another player, or none, in its place
                    if (mediaPlayer === finished) {
                        finished.release()
                        mediaPlayer = null
                        playFrom(audioFiles, index + 1)
                    }
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
            "fi" to "Finnish",
            "vi" to "Vietnamese"
        )
    }

    /** Frees the player and the phone's engine. Both are started again when next needed. */
    fun cleanup() {
        stopAudio()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        isInitialized = false
    }
}
