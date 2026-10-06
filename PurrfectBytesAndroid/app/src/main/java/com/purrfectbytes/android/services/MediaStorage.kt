package com.purrfectbytes.android.services

import android.content.Context
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where the app keeps its working files: speech, photos taken for text recognition,
 * preview images and rendered videos.
 *
 * All of them can be made again, so they live in the cache folder. The app deletes a
 * file when a newer one replaces it and clears what is left over when it starts, but for
 * the videos of [VideoLibrary]; Android may also clear the folder when the phone runs
 * out of space.
 */
@Singleton
class MediaStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "MediaStorage"
        private const val SPEECH = "speech"
        private const val PHOTOS = "photos"
        private const val PREVIEWS = "preview_images"
        private const val VIDEOS = "videos"
        private const val FRAMES_PREFIX = "frames_"

        /** Speech files that versions up to 1.2 left in the external files folder. */
        private val OLD_SPEECH_FILE = Regex("""tts_\d+\.(mp3|wav)""")
    }

    /** Named by chance, not by the clock: speech that is kept from before is there within a millisecond. */
    fun newSpeechFile(extension: String): File =
        File(folder(SPEECH), "tts_${UUID.randomUUID()}.$extension")

    fun newPhotoFile(): File {
        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        return File(folder(PHOTOS), "$name.jpg")
    }

    fun newPreviewFile(): File = File(folder(PREVIEWS), "preview_${UUID.randomUUID()}.png")

    fun newVideoFile(prefix: String = "output"): File =
        File(folder(VIDEOS), "${prefix}_${UUID.randomUUID()}.mp4")

    /** The folder of the videos, those that are finished and those that are being made. */
    internal fun videoFolder(): File = folder(VIDEOS)

    /** A fresh folder for the frames of one render. */
    fun newFramesFolder(): File =
        File(context.cacheDir, "$FRAMES_PREFIX${UUID.randomUUID()}").apply { mkdirs() }

    /** Deletes [file] when it is one of the app's working files. Anything else is left alone. */
    fun discard(file: File?) {
        if (file == null || !isWorkingFile(file)) return
        if (file.exists() && !file.deleteRecursively()) Log.w(TAG, "Could not delete ${file.name}")
    }

    /** Deletes the photo behind [uri] when the app's camera took it. Gallery photos are left alone. */
    fun discard(uri: Uri?) {
        if (uri?.scheme != "file") return
        uri.path?.let { discard(File(it)) }
    }

    /**
     * Deletes working files written before [olderThan]. Called when the app starts, when
     * nothing on screen refers to the files of the previous run any more. The files in
     * [keep] stay: the videos that can still be watched and uploaded.
     */
    fun removeLeftovers(olderThan: Long = System.currentTimeMillis(), keep: Collection<File> = emptyList()) {
        val folders = listOf(SPEECH, PHOTOS, PREVIEWS, VIDEOS).map { File(context.cacheDir, it) }
        val frames = context.cacheDir.listFiles { file ->
            file.isDirectory && file.name.startsWith(FRAMES_PREFIX)
        }.orEmpty()
        val oldSpeech = context.getExternalFilesDir(null)?.listFiles { file ->
            file.isFile && OLD_SPEECH_FILE.matches(file.name)
        }.orEmpty()

        val kept = keep.map { it.absolutePath }.toSet()
        val stale = (folders.flatMap { it.listFiles().orEmpty().toList() } + frames + oldSpeech)
            .filter { it.lastModified() < olderThan && it.absolutePath !in kept }
        stale.forEach { file ->
            if (!file.deleteRecursively()) Log.w(TAG, "Could not delete ${file.name}")
        }
        if (stale.isNotEmpty()) Log.d(TAG, "Removed ${stale.size} leftover file(s)")
    }

    private fun folder(name: String): File = File(context.cacheDir, name).apply { mkdirs() }

    private fun isWorkingFile(file: File): Boolean {
        val cache = context.cacheDir.canonicalFile
        var parent = file.canonicalFile.parentFile
        while (parent != null) {
            if (parent == cache) return true
            parent = parent.parentFile
        }
        return false
    }
}
