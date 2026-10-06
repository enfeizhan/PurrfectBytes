package com.purrfectbytes.android.services

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Speech saved to a file, with the time of every word when the engine reports them. */
data class SpeechAudio(
    val file: File,
    val wordBoundaries: List<WordBoundary> = emptyList()
)

/**
 * Text to speech through the service behind Microsoft Edge's "Read aloud".
 *
 * The service is unofficial and free: it needs no key, but Microsoft can change it
 * without notice. See [EdgeTtsProtocol] for what to update when that happens.
 */
@Singleton
class EdgeTTSEngine internal constructor(
    private val client: OkHttpClient,
    private val serviceUrl: String = EdgeTtsProtocol.SERVICE_URL,
    private val voicesUrl: String = EdgeTtsProtocol.VOICES_URL,
    /** How long the service may stay silent before the request is given up. */
    private val idleTimeoutMillis: Long = 60_000,
    private val clock: () -> Long = System::currentTimeMillis
) {
    @Inject
    constructor() : this(defaultClient())

    companion object {
        private const val TAG = "EdgeTTS"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .build()

        // The voices the web app reads with (tts_engines.py, DEFAULT_VOICES)
        private val VOICES = mapOf(
            "en" to "en-US-AriaNeural",
            "es" to "es-ES-ElviraNeural",
            "fr" to "fr-FR-DeniseNeural",
            "de" to "de-DE-KatjaNeural",
            "it" to "it-IT-ElsaNeural",
            "pt" to "pt-BR-FranciscaNeural",
            "ru" to "ru-RU-SvetlanaNeural",
            "ja" to "ja-JP-NanamiNeural",
            "ko" to "ko-KR-SunHiNeural",
            "zh" to "zh-CN-XiaoxiaoNeural",
            "ar" to "ar-SA-ZariyahNeural",
            "hi" to "hi-IN-SwaraNeural",
            "nl" to "nl-NL-ColetteNeural",
            "pl" to "pl-PL-ZofiaNeural",
            "tr" to "tr-TR-EmelNeural",
            "sv" to "sv-SE-SofieNeural",
            "da" to "da-DK-ChristelNeural",
            "no" to "nb-NO-PernilleNeural",
            "fi" to "fi-FI-NooraNeural",
            "vi" to "vi-VN-HoaiMyNeural"
        )
        private const val DEFAULT_VOICE = "en-US-AriaNeural"

        /** The voice a language is read with when none is chosen. */
        fun defaultVoice(languageCode: String): String =
            VOICES[languageCode.substringBefore("-")] ?: DEFAULT_VOICE

        /** How fast the voice speaks, the way the service is told. */
        fun rateOf(isSlow: Boolean): String = if (isSlow) "-30%" else "+0%"
    }

    /** How far this phone's clock is from the service's, learned when it refuses a token. */
    @Volatile
    private var clockSkewSeconds = 0.0

    /** Reads [text] with [voice], or with the voice of the language when none is given. */
    suspend fun generateAudio(
        text: String,
        languageCode: String,
        isSlow: Boolean,
        outputFile: File,
        voice: String? = null
    ): Result<SpeechAudio> = withContext(Dispatchers.IO) {
        try {
            val pieces = EdgeTtsProtocol.prepareText(text)
            if (pieces.isEmpty()) throw EdgeTtsException("There is no text to read aloud")

            // The name goes into the request as it is, so it must be one that can
            val speaker = voice?.takeIf { EdgeTtsProtocol.isVoiceName(it) } ?: defaultVoice(languageCode)
            val rate = rateOf(isSlow)

            val words = mutableListOf<WordBoundary>()
            var offsetTicks = 0L
            FileOutputStream(outputFile).use { audio ->
                for (piece in pieces) {
                    offsetTicks = speak(piece, speaker, rate, audio, words, offsetTicks)
                }
            }
            Result.success(SpeechAudio(outputFile, words))
        } catch (e: CancellationException) {
            outputFile.delete()
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Edge TTS failed", e)
            outputFile.delete()
            Result.failure(e)
        }
    }

    /** One request, repeated once with a corrected clock when the service refuses the token. */
    private suspend fun speak(
        escapedText: String,
        voice: String,
        rate: String,
        audio: OutputStream,
        words: MutableList<WordBoundary>,
        offsetTicks: Long
    ): Long = withCorrectedClock { stream(escapedText, voice, rate, audio, words, offsetTicks) }

    private suspend fun <T> withCorrectedClock(request: suspend () -> T): T {
        return try {
            request()
        } catch (e: ConnectionRefused) {
            val serverTime = EdgeTtsProtocol.parseHttpDate(e.serverDate)
            if (e.code != 403 || serverTime == null) throw e
            clockSkewSeconds += serverTime - now()
            request()
        }
    }

    /**
     * All voices of the service, in every language. Throws [EdgeTtsException] or
     * [IOException] when the list cannot be had.
     */
    suspend fun voices(): List<EdgeVoice> = withContext(Dispatchers.IO) {
        try {
            withCorrectedClock { requestVoices() }
        } catch (e: ConnectionRefused) {
            throw EdgeTtsException(e.message.orEmpty(), e)
        }
    }

    private suspend fun requestVoices(): List<EdgeVoice> {
        val request = Request.Builder()
            .url(EdgeTtsProtocol.voicesUrl(voicesUrl, now()))
            .apply { EdgeTtsProtocol.VOICE_HEADERS.forEach { (name, value) -> addHeader(name, value) } }
            .addHeader("Cookie", "muid=${randomId().uppercase()};")
            .build()

        return answerTo(request).use { response ->
            if (!response.isSuccessful) throw ConnectionRefused(response.code, response.header("Date"))
            val voices = EdgeTtsProtocol.parseVoices(response.body?.string().orEmpty())
            if (voices.isEmpty()) throw EdgeTtsException("Edge TTS sent an empty list of voices")
            voices
        }
    }

    /** Waits for the answer without holding a thread, and gives up when the caller does. */
    private suspend fun answerTo(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resume(response) else response.close()
            }

            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
        })
    }

    private fun now(): Double = clock() / 1000.0 + clockSkewSeconds

    /**
     * Sends one piece of text and writes the audio that comes back.
     * Returns where the next piece starts, in ticks from the start of the whole audio.
     */
    private suspend fun stream(
        escapedText: String,
        voice: String,
        rate: String,
        audio: OutputStream,
        words: MutableList<WordBoundary>,
        offsetTicks: Long
    ): Long {
        val events = Channel<Event>(Channel.UNLIMITED)
        val request = Request.Builder()
            .url(EdgeTtsProtocol.connectionUrl(serviceUrl, randomId(), now()))
            .apply { EdgeTtsProtocol.HEADERS.forEach { (name, value) -> addHeader(name, value) } }
            .addHeader("Cookie", "muid=${randomId().uppercase()};")
            .build()

        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                events.trySend(Event.Open)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                events.trySend(Event.Text(text))
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                events.trySend(Event.Binary(bytes.toByteArray()))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                events.trySend(Event.Closed)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                events.trySend(Event.Failed(t, response?.code, response?.header("Date")))
            }
        })

        var finished = false
        try {
            var audioReceived = false
            var lastWordEnd = offsetTicks

            while (true) {
                val event = withTimeoutOrNull(idleTimeoutMillis) { events.receive() }
                    ?: throw EdgeTtsException("Edge TTS stopped responding")

                when (event) {
                    Event.Open -> {
                        val timestamp = EdgeTtsProtocol.timestamp()
                        socket.send(EdgeTtsProtocol.speechConfigMessage(timestamp))
                        socket.send(
                            EdgeTtsProtocol.ssmlMessage(
                                randomId(),
                                timestamp,
                                EdgeTtsProtocol.ssml(voice, rate, escapedText)
                            )
                        )
                    }

                    is Event.Binary -> EdgeTtsProtocol.parseAudioMessage(event.data)?.let {
                        audio.write(it)
                        audioReceived = true
                    }

                    is Event.Text -> when (val message = EdgeTtsProtocol.parseTextMessage(event.data)) {
                        is EdgeTtsProtocol.TextMessage.Words -> message.words.forEach { word ->
                            val start = offsetTicks + word.offsetTicks
                            val end = start + word.durationTicks
                            words += WordBoundary(
                                word = word.text,
                                start = start / EdgeTtsProtocol.TICKS_PER_SECOND,
                                end = end / EdgeTtsProtocol.TICKS_PER_SECOND
                            )
                            lastWordEnd = end
                        }

                        EdgeTtsProtocol.TextMessage.TurnEnd -> {
                            if (!audioReceived) throw noAudio()
                            finished = true
                            socket.close(1000, "done")
                            return lastWordEnd + EdgeTtsProtocol.PADDING_TICKS
                        }

                        EdgeTtsProtocol.TextMessage.Other -> Unit
                    }

                    Event.Closed -> {
                        if (!audioReceived) throw noAudio()
                        finished = true
                        return lastWordEnd + EdgeTtsProtocol.PADDING_TICKS
                    }

                    is Event.Failed -> throw if (event.httpCode != null) {
                        ConnectionRefused(event.httpCode, event.serverDate, event.error)
                    } else {
                        EdgeTtsException("Could not reach Edge TTS: ${event.error.message ?: event.error.javaClass.simpleName}", event.error)
                    }
                }
            }
        } finally {
            // Also runs when the caller gives up: never leave the connection open
            if (!finished) socket.cancel()
        }
    }

    private fun noAudio() = EdgeTtsException(
        "Edge TTS returned no audio. Check that the text matches the selected language."
    )

    private fun randomId(): String = UUID.randomUUID().toString().replace("-", "")

    private sealed interface Event {
        object Open : Event
        class Text(val data: String) : Event
        class Binary(val data: ByteArray) : Event
        object Closed : Event
        class Failed(val error: Throwable, val httpCode: Int?, val serverDate: String?) : Event
    }

    private class ConnectionRefused(val code: Int, val serverDate: String?, cause: Throwable? = null) :
        Exception(
            "Edge TTS refused the connection (HTTP $code)" +
                // Still refused after the clock was corrected: see CHROMIUM_FULL_VERSION
                if (code == 403) ". If this keeps happening, the Edge version the app reports is too old." else "",
            cause
        )
}
