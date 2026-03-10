package com.purrfectbytes.android.services

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import okio.ByteString
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class EdgeTTSEngine {

    private val client = OkHttpClient()

    private val voiceMap = mapOf(
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
        "pl" to "pl-PL-AgnieszkaNeural",
        "tr" to "tr-TR-EmelNeural",
        "sv" to "sv-SE-SofieNeural",
        "da" to "da-DK-ChristelNeural",
        "no" to "nb-NO-PernilleNeural",
        "fi" to "fi-FI-NoomiNeural"
    )

    suspend fun generateAudio(text: String, languageCode: String, isSlow: Boolean, outputFile: File): Result<File> = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            try {
                val request = Request.Builder()
                    .url("wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1?TrustedClientToken=6A5AA1D4EAFF4E9FB37E23D68491D6F4")
                    .addHeader("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
                    .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .build()

                val fos = FileOutputStream(outputFile)
                val voiceName = voiceMap[languageCode.substringBefore("-")] ?: "en-US-AriaNeural"
                val rate = if (isSlow) "-30%" else "0%"

                val listener = object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        try {
                            val config = "Content-Type: application/json; charset=utf-8\r\nPath: speech.config\r\n\r\n{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{\"sentenceBoundaryEnabled\":false,\"wordBoundaryEnabled\":true},\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}"
                            webSocket.send(config)

                            val requestId = UUID.randomUUID().toString().replace("-", "")
                            
                            val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'><voice name='$voiceName'><prosody rate='$rate'>$text</prosody></voice></speak>"
                            val reqMsg = "X-RequestId:$requestId\r\nContent-Type:application/ssml+xml\r\nPath:ssml\r\n\r\n$ssml"
                            webSocket.send(reqMsg)
                        } catch (e: Exception) {
                            fos.close()
                            if (continuation.isActive) continuation.resumeWithException(e)
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        try {
                            val separator = "Path:audio\r\n"
                            val textToFind = separator.toByteArray()
                            val byteArray = bytes.toByteArray()
                            
                            var idx = -1
                            for (i in 0 until byteArray.size - textToFind.size) {
                                var match = true
                                for (j in textToFind.indices) {
                                    if (byteArray[i+j] != textToFind[j]) {
                                        match = false
                                        break
                                    }
                                }
                                if (match) {
                                    idx = i + textToFind.size
                                    break
                                }
                            }
                            if (idx != -1) {
                                fos.write(byteArray, idx, byteArray.size - idx)
                            }
                        } catch (e: Exception) {
                            Log.e("EdgeTTS", "Error writing bytes", e)
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, textMsg: String) {
                        if (textMsg.contains("Path:turn.end")) {
                            webSocket.close(1000, "done")
                        }
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        try { fos.close() } catch (e: Exception) {}
                        if (continuation.isActive) {
                            continuation.resume(Result.success(outputFile))
                        }
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        try { fos.close() } catch (e: Exception) {}
                        Log.e("EdgeTTS", "WebSocket Failure", t)
                        if (continuation.isActive) {
                            continuation.resume(Result.failure(t))
                        }
                    }
                }

                client.newWebSocket(request, listener)

                continuation.invokeOnCancellation {
                    try { fos.close() } catch (e: Exception) {}
                }
            } catch (e: Exception) {
                if (continuation.isActive) {
                    continuation.resume(Result.failure(e))
                }
            }
        }
    }
}
