package com.purrfectbytes.android.services

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaStorageTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val storage = MediaStorage(context)

    private fun File.written(): File = apply {
        parentFile?.mkdirs()
        writeText("x")
    }

    private fun File.aged(): File = apply { setLastModified(System.currentTimeMillis() - 60_000) }

    @Test
    fun `working files are kept in the cache folder`() {
        val files = listOf(
            storage.newSpeechFile("mp3"),
            storage.newPhotoFile(),
            storage.newPreviewFile(),
            storage.newVideoFile(),
            storage.newFramesFolder()
        )

        files.forEach { file ->
            assertTrue("$file is outside the cache", file.canonicalPath.startsWith(context.cacheDir.canonicalPath))
        }
        assertEquals(files.size, files.map { it.path }.distinct().size)
    }

    @Test
    fun `a replaced file is deleted`() {
        val video = storage.newVideoFile().written()

        storage.discard(video)

        assertFalse(video.exists())
    }

    @Test
    fun `files that are not the app's own are never deleted`() {
        val elsewhere = File(context.filesDir, "keep.txt").written()
        val outside = File(context.getExternalFilesDir(null), "20260928_083400.jpg").written()

        storage.discard(elsewhere)
        storage.discard(Uri.fromFile(outside))
        storage.discard(null as File?)
        storage.discard(null as Uri?)

        assertTrue(elsewhere.exists())
        assertTrue(outside.exists())
    }

    @Test
    fun `a photo from the camera is deleted but one from the gallery is left alone`() {
        val photo = storage.newPhotoFile().written()

        storage.discard(Uri.parse("content://media/picker/0/com.android.providers.media.photopicker/media/1000"))
        assertTrue(photo.exists())

        storage.discard(Uri.fromFile(photo))
        assertFalse(photo.exists())
    }

    @Test
    fun `leftovers of an earlier run are removed at the start`() {
        val old = listOf(
            storage.newSpeechFile("mp3").written().aged(),
            storage.newPhotoFile().written().aged(),
            storage.newPreviewFile().written().aged(),
            storage.newVideoFile().written().aged(),
            File(storage.newFramesFolder(), "unit_1.jpg").written().aged().parentFile!!.aged(),
            // Speech that versions up to 1.2 left in the external files folder
            File(context.getExternalFilesDir(null), "tts_1790584440000.mp3").written().aged(),
            File(context.getExternalFilesDir(null), "tts_1790584440001.wav").written().aged()
        )

        storage.removeLeftovers(olderThan = System.currentTimeMillis() - 1_000)

        old.forEach { assertFalse("$it is still there", it.exists()) }
    }

    @Test
    fun `files of this run and files of the user stay`() {
        val startedAt = System.currentTimeMillis() - 1_000
        val kept = listOf(
            storage.newVideoFile().written(), // written after the start
            // Photos taken with versions up to 1.2: the app never deletes those by itself
            File(context.getExternalFilesDir(null), "20260101_120000.jpg").written().aged(),
            File(context.getExternalFilesDir(null), "my_tts_notes.mp3").written().aged(),
            File(context.filesDir, "settings.json").written().aged()
        )

        storage.removeLeftovers(olderThan = startedAt)

        kept.forEach { assertTrue("$it was deleted", it.exists()) }
    }
}
