package com.purrfectbytes.android.services

import android.content.Context
import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The voices of Edge TTS to choose from.
 *
 * The service lists about 320 voices in one answer of 135 KB, and the list changes a few
 * times a year. It is asked for once and kept, in memory and in a file, for a week; a
 * list that is older is still used when a newer one cannot be had.
 */
@Singleton
class EdgeVoiceCatalogue internal constructor(
    private val engine: EdgeTTSEngine,
    private val file: File,
    private val clock: () -> Long = System::currentTimeMillis
) {
    @Inject
    constructor(engine: EdgeTTSEngine, @ApplicationContext context: Context) :
        this(engine, File(context.cacheDir, FILE_NAME))

    companion object {
        private const val TAG = "EdgeVoices"
        private const val FILE_NAME = "edge_voices.json"

        internal const val MAX_AGE_MILLIS = 7 * 24 * 60 * 60 * 1000L

        /** After a request that failed, the list on hand is used this long before asking again. */
        internal const val RETRY_AFTER_MILLIS = 10 * 60 * 1000L

        /** Languages the service files under another code than the app. */
        private val SERVICE_LANGUAGE = mapOf("no" to "nb")

        /**
         * The voices among [voices] that speak [languageCode]. The language is compared as
         * a whole: Finnish ("fi") is not the beginning of Filipino ("fil").
         */
        fun voicesOf(voices: List<EdgeVoice>, languageCode: String): List<EdgeVoice> {
            val code = languageCode.substringBefore("-").lowercase()
            val language = SERVICE_LANGUAGE[code] ?: code
            return voices.filter { it.language == language }
        }

        /**
         * The voice of the second speaker when none is chosen: one that can be told from
         * [first], of the other gender and from the same country where there is one.
         * Null when [voices] holds no other voice.
         */
        fun secondVoiceFor(voices: List<EdgeVoice>, first: String): EdgeVoice? {
            val others = voices.filter { it.id != first }
            val speaker = voices.firstOrNull { it.id == first }
                ?: return others.firstOrNull()
            return others.firstOrNull { it.locale == speaker.locale && it.gender != speaker.gender }
                ?: others.firstOrNull { it.gender != speaker.gender }
                ?: others.firstOrNull { it.locale == speaker.locale }
                ?: others.firstOrNull()
        }
    }

    private class Listing(val voices: List<EdgeVoice>, val fetchedAt: Long)

    private val mutex = Mutex()
    private var listing: Listing? = null
    private var askAgainAt = 0L

    /**
     * The voices that speak [languageCode], in the order of the service. Throws
     * [EdgeTtsException] when the list cannot be had and none was kept.
     */
    suspend fun voicesFor(languageCode: String): List<EdgeVoice> = voicesOf(all(), languageCode)

    /** The voices of every language. */
    suspend fun all(): List<EdgeVoice> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val kept = listing ?: read()?.also { listing = it }
            val now = clock()
            if (kept != null && (now - kept.fetchedAt < MAX_AGE_MILLIS || now < askAgainAt)) {
                return@withContext kept.voices
            }

            try {
                val fresh = Listing(engine.voices(), now)
                listing = fresh
                write(fresh)
                fresh.voices
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not load the list of voices", e)
                askAgainAt = now + RETRY_AFTER_MILLIS
                kept?.voices ?: throw EdgeTtsException(
                    "Could not load the list of voices: ${e.message ?: e.javaClass.simpleName}", e
                )
            }
        }
    }

    private fun read(): Listing? {
        return try {
            if (!file.isFile) return null
            val kept = JsonParser.parseString(file.readText()).asJsonObject
            val voices = kept.getAsJsonArray("voices").map { entry ->
                val voice = entry.asJsonObject
                EdgeVoice(
                    id = voice.get("id").asString,
                    gender = voice.get("gender").asString,
                    locale = voice.get("locale").asString
                )
            }.filter { EdgeTtsProtocol.isVoiceName(it.id) }
            if (voices.isEmpty()) null else Listing(voices, kept.get("fetched_at").asLong)
        } catch (e: Exception) {
            // A file that cannot be read is as good as none; the next answer replaces it
            Log.w(TAG, "Could not read ${file.name}", e)
            null
        }
    }

    private fun write(listing: Listing) {
        val voices = JsonArray()
        listing.voices.forEach { voice ->
            voices.add(
                JsonObject().apply {
                    addProperty("id", voice.id)
                    addProperty("gender", voice.gender)
                    addProperty("locale", voice.locale)
                }
            )
        }
        val kept = JsonObject().apply {
            addProperty("fetched_at", listing.fetchedAt)
            add("voices", voices)
        }

        // Written beside the file and moved into place, so that a reader never finds half a list
        val draft = File(file.parentFile, "${file.name}.part")
        try {
            file.parentFile?.mkdirs()
            draft.writeText(kept.toString())
            if (!draft.renameTo(file)) throw java.io.IOException("could not replace ${file.name}")
        } catch (e: Exception) {
            Log.w(TAG, "Could not keep the list of voices", e)
            draft.delete()
        }
    }
}
