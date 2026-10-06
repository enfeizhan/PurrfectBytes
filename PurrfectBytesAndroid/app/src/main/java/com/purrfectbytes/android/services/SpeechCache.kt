package com.purrfectbytes.android.services

import android.content.Context
import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Speech that was generated before, kept to be used again.
 *
 * Generating speech is the slowest step of a video and the only one that needs the
 * network. The same text in the same voice at the same speed sounds the same, so a
 * video that is rendered again - which is what happens while one is being adjusted -
 * copies its speech from here instead of asking for it again.
 *
 * A port of the web app's tts_cache.py. An entry is two files: the audio, and the times
 * of its words together with when it was stored. The second is written last and is what
 * makes the entry valid, so an entry that was only half written is never used.
 *
 * Entries are copied out, never handed over: the caller deletes its files when it is done
 * with them. Nothing depends on the cache - whatever goes wrong here, the speech is
 * generated as it would be without it.
 */
@Singleton
class SpeechCache internal constructor(
    private val folder: File,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val clock: () -> Long = System::currentTimeMillis
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(File(context.cacheDir, FOLDER))

    companion object {
        private const val TAG = "SpeechCache"
        private const val FOLDER = "tts_cache"

        /** Raised when speech stored by an older version must not be used any more. */
        private const val FORMAT_VERSION = 1

        /** An entry is a few tens of kilobytes, so this is some 20 MB at most. */
        const val DEFAULT_MAX_ENTRIES = 500

        private const val AUDIO = ".mp3"
        private const val DETAILS = ".json"
    }

    /**
     * Stands for everything that decides how the speech sounds. [voice] is the voice that
     * reads, not the choice that led to it: "no voice chosen" means another voice when
     * the voice of the language changes.
     */
    fun key(text: String, languageCode: String, engine: String, voice: String, rate: String): String {
        val material = listOf(FORMAT_VERSION.toString(), "mp3", engine, languageCode, voice, rate, text)
            .joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * Copies the speech stored under [key] to [destination]. Returns null when there is
     * none, or when it cannot be read.
     */
    fun fetch(key: String, destination: File): SpeechAudio? {
        val words = try {
            val details = JsonParser.parseString(detailsOf(key).readText()).asJsonObject
            details.getAsJsonArray("words").map { entry ->
                val word = entry.asJsonObject
                WordBoundary(
                    word = word.get("word").asString,
                    start = word.get("start").asDouble,
                    end = word.get("end").asDouble
                )
            }
        } catch (e: Exception) {
            return null
        }

        try {
            destination.parentFile?.mkdirs()
            audioOf(key).copyTo(destination, overwrite = true)
        } catch (e: Exception) {
            // Half a copy would look like speech to the video
            Log.w(TAG, "Could not use the speech stored as ${key.take(8)}", e)
            destination.delete()
            return null
        }

        // The entries used longest ago are the first to go
        val now = clock()
        detailsOf(key).setLastModified(now)
        audioOf(key).setLastModified(now)
        return SpeechAudio(destination, words)
    }

    /** Keeps a copy of [speech] under [key]. */
    fun store(key: String, speech: SpeechAudio) {
        try {
            folder.mkdirs()
            publish(audioOf(key)) { draft -> speech.file.copyTo(draft, overwrite = true) }

            val words = JsonArray()
            speech.wordBoundaries.forEach { boundary ->
                words.add(
                    JsonObject().apply {
                        addProperty("word", boundary.word)
                        addProperty("start", boundary.start)
                        addProperty("end", boundary.end)
                    }
                )
            }
            val details = JsonObject().apply {
                addProperty("stored_at", clock())
                add("words", words)
            }
            publish(detailsOf(key)) { draft -> draft.writeText(details.toString()) }
            detailsOf(key).setLastModified(clock())
        } catch (e: Exception) {
            Log.w(TAG, "Could not store speech as ${key.take(8)}", e)
            return
        }
        removeOldest()
    }

    /** Removes every entry. Returns how many there were. */
    fun clear(): Int {
        val entries = entries()
        entries.forEach { remove(it) }
        return entries.size
    }

    private fun audioOf(key: String) = File(folder, key + AUDIO)

    private fun detailsOf(key: String) = File(folder, key + DETAILS)

    /** The keys of all entries that are complete. */
    private fun entries(): List<String> =
        folder.listFiles { file -> file.name.endsWith(DETAILS) }.orEmpty().map { it.name.removeSuffix(DETAILS) }

    /** Writes a file under another name and moves it into place, so that no reader finds half of it. */
    private fun publish(target: File, write: (File) -> Unit) {
        val draft = File(folder, "${target.name}.${UUID.randomUUID().toString().take(8)}.part")
        try {
            write(draft)
            if (!draft.renameTo(target)) throw IOException("could not replace ${target.name}")
        } catch (e: Exception) {
            draft.delete()
            throw e
        }
    }

    private fun remove(key: String) {
        // The details first: without them the entry is none, and its audio is not read any more
        detailsOf(key).delete()
        audioOf(key).delete()
    }

    private fun removeOldest() {
        val entries = entries()
        if (entries.size <= maxEntries) return
        entries.sortedBy { detailsOf(it).lastModified() }
            .take(entries.size - maxEntries)
            .forEach { remove(it) }
    }
}
