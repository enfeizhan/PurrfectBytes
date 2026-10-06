package com.purrfectbytes.android.viewmodels

import android.content.Context
import com.purrfectbytes.android.services.AnthropicRequest
import com.purrfectbytes.android.services.AnthropicResponse
import com.purrfectbytes.android.services.AnthropicService
import com.purrfectbytes.android.services.AppSettings
import com.purrfectbytes.android.services.EdgeTTSEngine
import com.purrfectbytes.android.services.EdgeVoice
import com.purrfectbytes.android.services.EdgeVoiceCatalogue
import com.purrfectbytes.android.services.LanguageDetector
import com.purrfectbytes.android.services.MediaStorage
import com.purrfectbytes.android.services.SourceStore
import com.purrfectbytes.android.services.SpeechCache
import com.purrfectbytes.android.services.TTSService
import com.purrfectbytes.android.services.TextRecognitionProcessor
import com.purrfectbytes.android.services.VideoGeneratorService
import com.purrfectbytes.android.services.VideoLibrary
import com.purrfectbytes.android.services.YouTubeAccountService
import com.purrfectbytes.android.services.YouTubeAuthManager
import com.purrfectbytes.android.services.YouTubeChannel
import com.purrfectbytes.android.services.YouTubeMetadataGenerator
import com.purrfectbytes.android.services.YouTubePlaylist
import com.purrfectbytes.android.services.YouTubeUpload
import com.purrfectbytes.android.services.YouTubeVideoUploader
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import java.util.Collections

/** Answers with [language] without looking at the text. */
class FakeDetector(var language: String?) : LanguageDetector() {
    var asked = 0

    override suspend fun detect(text: String): String? {
        asked++
        return language
    }

    override fun close() = Unit
}

private val noClaude = object : AnthropicService {
    override suspend fun generateMessage(apiKey: String, request: AnthropicRequest): AnthropicResponse =
        throw AssertionError("Claude must not be asked")
}

/**
 * An engine that asks this computer instead of Microsoft, at a port where nothing
 * answers: whatever it is asked for fails at once.
 */
fun edgeEngineWithoutService() = EdgeTTSEngine(
    client = EdgeTTSEngine.defaultClient(),
    serviceUrl = "ws://127.0.0.1:9/edge/v1?TrustedClientToken=test",
    voicesUrl = "http://127.0.0.1:9/voices/list?trustedclienttoken=test"
)

/** The list of voices as the app keeps it, so that it is found and not asked for. */
fun keepVoices(context: Context, voices: List<EdgeVoice>) {
    val entries = voices.joinToString(",") { """{"id":"${it.id}","gender":"${it.gender}","locale":"${it.locale}"}""" }
    File(context.cacheDir, "edge_voices.json")
        .writeText("""{"fetched_at":${System.currentTimeMillis()},"voices":[$entries]}""")
}

/**
 * Stands for YouTube and the sign-in to it: an account with one channel and two
 * playlists that takes every upload, or refuses it with [failure].
 */
class FakeYouTube(context: Context) {

    class Upload(
        val video: File,
        val title: String,
        val description: String,
        val tags: List<String>,
        val privacy: String,
        val playlistId: String?,
        val accessToken: String
    )

    /** What was uploaded, upload by upload. */
    val uploads: MutableList<Upload> = Collections.synchronizedList(mutableListOf())

    var failure: Exception? = null

    /** An upload goes on for as long as this is not completed. */
    var hold: CompletableDeferred<Unit>? = null

    var signedIn = true

    val auth: YouTubeAuthManager = object : YouTubeAuthManager(context) {
        override fun isAuthorized() = signedIn
        override suspend fun getFreshAccessToken() = "token-${uploads.size + 1}"
        override fun logout() {
            signedIn = false
        }
    }

    val account: YouTubeAccountService = object : YouTubeAccountService() {
        override suspend fun channels(accessToken: String) = listOf(YouTubeChannel("c1", "Cat Channel"))
        override suspend fun playlists(accessToken: String) =
            listOf(YouTubePlaylist("p1", "Japanese"), YouTubePlaylist("p2", "Korean"))
    }

    val uploader: YouTubeVideoUploader = object : YouTubeVideoUploader() {
        override suspend fun uploadVideo(
            videoFile: File,
            title: String,
            description: String,
            tags: List<String>,
            privacyStatus: String,
            playlistId: String?,
            accessToken: String
        ): Result<YouTubeUpload> {
            uploads += Upload(videoFile, title, description, tags, privacyStatus, playlistId, accessToken)
            hold?.await()
            failure?.let { return Result.failure(it) }
            return Result.success(YouTubeUpload("video${uploads.size}"))
        }
    }
}

/**
 * A view model whose models and language detector are stand-ins, so that a test can
 * never reach Gemini, Claude or any other service. [voices] are the voices of Edge TTS
 * it has to choose from; [edge] and [video] make speech and videos. Without [youTube]
 * nobody is signed in.
 */
fun testViewModel(
    context: Context,
    detector: LanguageDetector = FakeDetector(null),
    gemini: suspend (String) -> String? = { throw AssertionError("Gemini must not be asked") },
    voices: List<EdgeVoice> = emptyList(),
    edge: EdgeTTSEngine = edgeEngineWithoutService(),
    video: (MediaStorage) -> VideoGeneratorService = { VideoGeneratorService(context, it) },
    youTube: FakeYouTube? = null
): MainViewModel {
    if (voices.isNotEmpty()) keepVoices(context, voices)

    val storage = MediaStorage(context)
    return MainViewModel(
        ttsService = TTSService(context, storage, edge, SpeechCache(context)),
        textRecognitionProcessor = TextRecognitionProcessor(context, detector),
        languageDetector = detector,
        videoGeneratorService = video(storage),
        youtubeMetadataGenerator = YouTubeMetadataGenerator(noClaude, "test-key", "test-key", gemini),
        youtubeVideoUploader = youTube?.uploader ?: YouTubeVideoUploader(),
        youtubeAccountService = youTube?.account ?: YouTubeAccountService(),
        youtubeAuthManager = youTube?.auth ?: YouTubeAuthManager(context),
        voiceCatalogue = EdgeVoiceCatalogue(edge, context),
        sourceStore = SourceStore(context),
        library = VideoLibrary(storage),
        storage = storage,
        settings = AppSettings(context)
    )
}
