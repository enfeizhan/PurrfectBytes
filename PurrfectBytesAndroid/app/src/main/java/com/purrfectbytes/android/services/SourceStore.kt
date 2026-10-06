package com.purrfectbytes.android.services

import android.content.Context
import android.util.Log
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where texts are taken from, such as a textbook. [credit] is the sentence that names
 * the source in the description of a video, word for word.
 */
data class TextSource(val id: String, val name: String, val credit: String, val createdAt: String)

/** The source cannot be saved; the message says why, in words for the user. */
class SourceException(message: String) : Exception(message)

/**
 * The saved sources, kept in a file of the same form as the web app's
 * (source_store.py), so the list of one app can be given to the other.
 */
@Singleton
class SourceStore internal constructor(
    private val file: File,
    private val clock: () -> Long = System::currentTimeMillis
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(File(context.filesDir, FILE_NAME))

    companion object {
        private const val TAG = "SourceStore"
        private const val FILE_NAME = "saved_sources.json"
    }

    /** All saved sources, oldest first. A file that is missing or cannot be read holds none. */
    @Synchronized
    fun list(): List<TextSource> {
        if (!file.isFile) return emptyList()
        return try {
            JsonParser.parseString(file.readText()).asJsonArray.mapNotNull { entry ->
                if (!entry.isJsonObject) return@mapNotNull null
                val fields = entry.asJsonObject
                fun field(name: String): String =
                    fields.get(name)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

                val source = TextSource(field("id"), field("name"), field("credit"), field("created_at"))
                source.takeIf { it.id.isNotEmpty() && it.name.isNotEmpty() && it.credit.isNotEmpty() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the saved sources", e)
            emptyList()
        }
    }

    fun find(id: String): TextSource? = list().firstOrNull { it.id == id }

    /** Saves a new source. Throws [SourceException] when the name or the credit is missing. */
    @Synchronized
    fun add(name: String, credit: String): TextSource {
        if (name.isBlank()) throw SourceException("Source name is required")
        if (credit.isBlank()) throw SourceException("Credit line is required")

        val source = TextSource(
            id = UUID.randomUUID().toString().replace("-", "").take(8),
            name = name.trim(),
            credit = credit.trim(),
            createdAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date(clock()))
        )
        write(list() + source)
        return source
    }

    /** Removes the source with [id]. Returns false when there is none. */
    @Synchronized
    fun delete(id: String): Boolean {
        val sources = list()
        val remaining = sources.filter { it.id != id }
        if (remaining.size == sources.size) return false
        write(remaining)
        return true
    }

    private fun write(sources: List<TextSource>) {
        val entries = JsonArray()
        sources.forEach { source ->
            entries.add(
                JsonObject().apply {
                    addProperty("id", source.id)
                    addProperty("name", source.name)
                    addProperty("credit", source.credit)
                    addProperty("created_at", source.createdAt)
                }
            )
        }
        val text = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(entries)

        // Written beside the file and moved into place: the list is never lost to half a write
        val draft = File(file.parentFile, "${file.name}.part")
        try {
            file.parentFile?.mkdirs()
            draft.writeText(text)
            if (!draft.renameTo(file)) throw IOException("could not replace ${file.name}")
        } catch (e: IOException) {
            draft.delete()
            throw SourceException("The source could not be saved: ${e.message}")
        }
    }
}
