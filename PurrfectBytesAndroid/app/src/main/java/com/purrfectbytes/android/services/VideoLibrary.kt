package com.purrfectbytes.android.services

import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** A video that was rendered and is kept to be watched or uploaded, with what is known of it. */
data class RecentVideo(
    val file: File,
    /** The text the video shows. */
    val text: String,
    /** What the video repeats: "10 repetitions"; empty for a single reading. */
    val summary: String,
    val seconds: Double,
    /** When it was rendered, in milliseconds since 1970. */
    val createdAt: Long,
    /** Where the video is watched on YouTube; null as long as it is not uploaded. */
    val uploadedUrl: String? = null
) {
    /** The first line of the text, to tell the video by. */
    val title: String
        get() = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
}

/**
 * The videos that were rendered last. The web app lists its files under "Recent Files";
 * here the newest few are kept, so that a video can be uploaded after the app was closed,
 * or after another one was rendered.
 *
 * What is known of a video is written next to it, in a file of the same name. A video
 * without that file is none of the library's and is cleared away with the other
 * leftovers.
 */
@Singleton
class VideoLibrary internal constructor(
    private val storage: MediaStorage,
    private val maxVideos: Int,
    private val clock: () -> Long
) {
    @Inject
    constructor(storage: MediaStorage) : this(storage, MAX_VIDEOS, System::currentTimeMillis)

    companion object {
        private const val TAG = "VideoLibrary"

        /** A video of ten repetitions is a few megabytes; five of them are little to keep. */
        const val MAX_VIDEOS = 5
    }

    /** Takes [video] into the library. The oldest videos make room for it. */
    @Synchronized
    fun add(video: RenderedVideo, text: String, summary: String): RecentVideo {
        val added = RecentVideo(video.file, text, summary, video.seconds, clock())
        write(added)
        list().filter { it.file != added.file }
            .drop(maxVideos - 1)
            .forEach { delete(it.file) }
        return added
    }

    /** The videos of the library, the newest first. */
    @Synchronized
    fun list(): List<RecentVideo> =
        storage.videoFolder().listFiles { file -> file.extension == "mp4" }.orEmpty()
            .mapNotNull { read(it) }
            .sortedWith(compareByDescending<RecentVideo> { it.createdAt }.thenBy { it.file.name })

    fun find(video: File): RecentVideo? = list().firstOrNull { it.file == video }

    /** Every file of the library: the videos and what is known of them. */
    fun files(): List<File> = list().flatMap { listOf(it.file, detailsOf(it.file)) }

    /** Notes that [video] is on YouTube at [url]. Returns null when the video is not in the library. */
    @Synchronized
    fun markUploaded(video: File, url: String): RecentVideo? {
        val uploaded = find(video)?.copy(uploadedUrl = url) ?: return null
        write(uploaded)
        return uploaded
    }

    @Synchronized
    fun delete(video: File) {
        // What is known of it first: without that, the video is a leftover like any other
        detailsOf(video).delete()
        storage.discard(video)
    }

    private fun detailsOf(video: File) = File(video.parentFile, video.nameWithoutExtension + ".json")

    private fun read(video: File): RecentVideo? {
        val details = detailsOf(video)
        if (!details.isFile) return null
        return try {
            val fields = JsonParser.parseString(details.readText()).asJsonObject
            RecentVideo(
                file = video,
                text = fields.get("text").asString,
                summary = fields.get("summary").asString,
                seconds = fields.get("seconds").asDouble,
                createdAt = fields.get("created_at").asLong,
                uploadedUrl = fields.get("uploaded_url")?.takeIf { it.isJsonPrimitive }?.asString
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not read ${details.name}", e)
            null
        }
    }

    private fun write(video: RecentVideo) {
        val fields = JsonObject().apply {
            addProperty("text", video.text)
            addProperty("summary", video.summary)
            addProperty("seconds", video.seconds)
            addProperty("created_at", video.createdAt)
            video.uploadedUrl?.let { addProperty("uploaded_url", it) }
        }
        val details = detailsOf(video.file)
        val draft = File(details.parentFile, "${details.name}.part")
        try {
            draft.writeText(fields.toString())
            if (!draft.renameTo(details)) throw java.io.IOException("could not replace ${details.name}")
        } catch (e: Exception) {
            // The video is still there to be watched and uploaded in this run
            Log.w(TAG, "Could not write ${details.name}", e)
            draft.delete()
        }
    }
}
