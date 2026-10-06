package com.purrfectbytes.android.services

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.core.graphics.scale
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.purrfectbytes.android.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/** Runs FFmpeg. Throws [VideoException] with the reason when FFmpeg fails. */
internal fun interface FFmpegRunner {
    fun run(arguments: List<String>)
}

/** A finished video and how long it plays, in seconds. */
data class RenderedVideo(val file: File, val seconds: Double)

@Singleton
class VideoGeneratorService internal constructor(
    private val context: Context,
    private val storage: MediaStorage,
    private val ffmpeg: FFmpegRunner,
    /** The length of an audio file, or of a video, in seconds; null when it cannot be read. */
    private val audioSeconds: (File) -> Double?
) {
    @Inject
    constructor(@ApplicationContext context: Context, storage: MediaStorage) :
        this(context, storage, FFmpegRunner(::runFFmpegKit), ::readAudioSeconds)

    companion object {
        private const val TAG = "VideoGenerator"
        internal const val FPS = 30

        /**
         * The last picture is held a little longer than the audio is thought to be. The
         * length read from an MP3 is an estimate, and FFmpeg cuts the video where the
         * audio really ends.
         */
        internal const val EXTRA_SECONDS = 0.5

        private const val JPEG_QUALITY = 95

        /**
         * The list FFmpeg reads the pictures from: each one with how long it is shown. The
         * last one is listed twice because FFmpeg ignores the duration of the final entry.
         *
         * FFmpeg times pictures in steps of 1/25 second unless told otherwise, which moved
         * the highlight up to two frames away from the sound. "option framerate" makes the
         * steps as long as the frames of the video.
         */
        internal fun slideshowOf(segments: List<FrameSegment>, pictures: Map<Int, File>): String = buildString {
            append("ffconcat version 1.0\n")
            segments.forEachIndexed { index, segment ->
                val seconds = segment.frameCount.toDouble() / FPS +
                    if (index == segments.lastIndex) EXTRA_SECONDS else 0.0
                append(entryOf(pictures.getValue(segment.unitIndex)))
                append(String.format(Locale.US, "duration %.6f\n", seconds))
            }
            append(entryOf(pictures.getValue(segments.last().unitIndex)))
        }

        private fun entryOf(picture: File): String = "file ${quoted(picture)}\noption framerate $FPS\n"

        /** The list that plays [clips] one after the other; a clip is repeated by listing it again. */
        internal fun playlistOf(clips: List<File>): String =
            "ffconcat version 1.0\n" + clips.joinToString("") { "file ${quoted(it)}\n" }

        private fun quoted(file: File): String = "'" + file.absolutePath.replace("'", "'\\''") + "'"

        /** One clip: the pictures and the audio together. */
        internal fun encodeArguments(slideshow: File, audio: File, output: File): List<String> = listOf(
            "-y",
            "-f", "concat", "-safe", "0", "-i", slideshow.absolutePath,
            "-i", audio.absolutePath,
            "-vf", "fps=$FPS",
            "-c:v", "libx264", "-preset", "ultrafast", "-crf", "24", "-pix_fmt", "yuv420p",
            "-c:a", "aac", "-b:a", "192k",
            "-movflags", "+faststart",
            "-shortest", output.absolutePath
        )

        /** The clips of a list in one video, copied rather than encoded again. */
        internal fun joinArguments(playlist: File, output: File): List<String> = listOf(
            "-y",
            "-f", "concat", "-safe", "0", "-i", playlist.absolutePath,
            "-c", "copy", "-movflags", "+faststart", output.absolutePath
        )

        private fun runFFmpegKit(arguments: List<String>) {
            val session = FFmpegKit.executeWithArguments(arguments.toTypedArray())
            if (ReturnCode.isSuccess(session.returnCode)) return

            val log = session.allLogsAsString.orEmpty()
            Log.e(TAG, "FFmpeg failed (${session.returnCode}):\n$log")
            // The reason is in the last lines of what FFmpeg printed
            val reason = log.lines().map { it.trim() }.filter { it.isNotEmpty() }.takeLast(2).joinToString(" ")
            throw VideoException("FFmpeg could not encode the video. ${reason.takeLast(300)}".trim())
        }

        private fun readAudioSeconds(file: File): Double? {
            val retriever = MediaMetadataRetriever()
            return try {
                retriever.setDataSource(file.absolutePath)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                    ?.takeIf { it > 0 }
                    ?.let { it / 1000.0 }
            } catch (e: Exception) {
                Log.w(TAG, "Could not read the length of ${file.name}", e)
                null
            } finally {
                try {
                    retriever.release()
                } catch (e: Exception) {
                    // Nothing useful can be done about a retriever that fails to close
                }
            }
        }
    }

    /**
     * Background, QR code and logo at the size they are drawn; decoded once, then reused.
     * The QR code is always drawn, so the background is always the one with the note
     * that points at it.
     */
    private class Assets(val background: Bitmap?, val qrCode: Bitmap?, val logo: Bitmap?)

    private val assets: Assets by lazy {
        Assets(
            background = decode(R.drawable.background_qr, FrameRenderer.WIDTH, FrameRenderer.HEIGHT),
            qrCode = decode(R.drawable.paypal_qr, FrameRenderer.QR_SIZE, FrameRenderer.QR_SIZE),
            logo = decode(R.drawable.logo_small, FrameRenderer.LOGO_SIZE, FrameRenderer.LOGO_SIZE)
        )
    }

    /**
     * Decodes a picture close to the size it is drawn at. The pictures are about a
     * megapixel each; decoding them whole, enlarged for the screen's density, took
     * about 100 MB for every preview and every render.
     */
    private fun decode(resource: Int, width: Int, height: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
                inScaled = false
            }
            BitmapFactory.decodeResource(context.resources, resource, bounds)

            var sample = 1
            while (bounds.outWidth / (sample * 2) >= width && bounds.outHeight / (sample * 2) >= height) {
                sample *= 2
            }
            val options = BitmapFactory.Options().apply {
                inScaled = false
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = BitmapFactory.decodeResource(context.resources, resource, options) ?: return null
            val scaled = decoded.scale(width, height)
            if (scaled !== decoded) decoded.recycle()
            scaled
        } catch (e: Exception) {
            Log.w(TAG, "Could not load picture $resource", e)
            null
        }
    }

    private fun rendererFor(languageCode: String) =
        FrameRenderer(assets.background, assets.qrCode, assets.logo, localeOf(languageCode))

    /**
     * Chinese, Japanese and Korean share characters that each language draws differently;
     * the locale tells Android which shapes to use.
     */
    private fun localeOf(languageCode: String): Locale? = when (languageCode.substringBefore("-")) {
        "ja" -> Locale.JAPANESE
        "ko" -> Locale.KOREAN
        "zh" -> Locale.SIMPLIFIED_CHINESE
        else -> null
    }

    /**
     * A picture of how the video of [plan] begins: the first character that is spoken is
     * highlighted, and speech that begins slowly is marked as slow.
     */
    suspend fun getPreviewImage(plan: RenderPlan, languageCode: String): Result<File> =
        withContext(Dispatchers.IO) {
            try {
                val renderer = rendererFor(languageCode)
                val prepared = renderer.prepare(plan.displayText, startAligned = plan.isConversation)
                val first = plan.clips[plan.order.first()]
                val unit = HighlightTiming.unitsWithin(prepared.units, first.offset, first.shownText.length)
                    .firstOrNull() ?: -1
                val frame = renderer.render(prepared, unit, slowBadge = first.slow)
                prepared.recycle()

                val imageFile = storage.newPreviewFile()
                FileOutputStream(imageFile).use { out ->
                    frame.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                frame.recycle()
                Result.success(imageFile)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Could not draw the preview", e)
                Result.failure(e)
            }
        }

    /**
     * Makes the video of [plan]. Every clip is encoded once, with the highlight following
     * its speech; the video plays the clips in the order of the plan, copied rather than
     * encoded again. [speech] holds the audio of the clips, in their order.
     *
     * [onProgress] is told what is being done, in words for the user.
     */
    suspend fun generateVideo(
        plan: RenderPlan,
        speech: List<SpeechAudio>,
        languageCode: String,
        onProgress: (String) -> Unit = {}
    ): Result<RenderedVideo> = withContext(Dispatchers.IO) {
        val framesFolder = storage.newFramesFolder()
        val clips = mutableListOf<File>()
        val output = storage.newVideoFile()
        var prepared: FrameRenderer.PreparedText? = null
        try {
            require(speech.size == plan.clips.size) { "${plan.clips.size} clips, but speech for ${speech.size}" }

            val renderer = rendererFor(languageCode)
            val text = renderer.prepare(plan.displayText, startAligned = plan.isConversation).also { prepared = it }
            // The clips find their place on screen by counting characters
            check(text.text == plan.displayText) { "The text on screen is not the text of the plan" }

            // One picture for each position of the highlight, however long it stays there
            val pictures = mutableMapOf<Pair<Int, Boolean>, File>()
            val seconds = DoubleArray(plan.clips.size)

            plan.clips.forEachIndexed { index, clip ->
                coroutineContext.ensureActive()
                onProgress(
                    if (plan.clips.size == 1) "Encoding the video" else "Encoding clip ${index + 1} of ${plan.clips.size}"
                )

                val audio = speech[index]
                val duration = audioSeconds(audio.file) ?: (clip.shownText.length * 0.1)
                seconds[index] = duration

                val line = HighlightTiming.unitsWithin(text.units, clip.offset, clip.shownText.length)
                val startTimes = HighlightTiming.unitStartTimes(
                    clip.shownText, text.units.slice(line), duration, audio.wordBoundaries, clip.offset
                )
                val segments = HighlightTiming.frameSegments(startTimes, duration, FPS).map { segment ->
                    // From the position in the line to the position in the text on screen
                    if (segment.unitIndex < 0) segment else segment.copy(unitIndex = line.first + segment.unitIndex)
                }

                val shown = segments.map { it.unitIndex }.distinct().associateWith { unit ->
                    pictures.getOrPut(unit to clip.slow) {
                        coroutineContext.ensureActive()
                        val frame = renderer.render(text, unit, slowBadge = clip.slow)
                        val file = File(framesFolder, "unit_${unit + 1}${if (clip.slow) "_slow" else ""}.jpg")
                        FileOutputStream(file).use { out ->
                            frame.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                        }
                        frame.recycle()
                        file
                    }
                }

                val slideshow = File(framesFolder, "frames_$index.txt")
                slideshow.writeText(slideshowOf(segments, shown))

                coroutineContext.ensureActive()
                val encoded = storage.newVideoFile("clip").also { clips += it }
                ffmpeg.run(encodeArguments(slideshow, audio.file, encoded))
            }

            coroutineContext.ensureActive()
            onProgress("Putting the video together")
            val playlist = File(framesFolder, "playlist.txt")
            playlist.writeText(playlistOf(plan.order.map { clips[it] }))
            ffmpeg.run(joinArguments(playlist, output))

            // The lengths of the clips are estimates; the video knows how long it is
            Result.success(RenderedVideo(output, audioSeconds(output) ?: plan.order.sumOf { seconds[it] }))
        } catch (e: CancellationException) {
            output.delete()
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Could not make the video", e)
            output.delete()
            Result.failure(e)
        } finally {
            prepared?.recycle()
            framesFolder.deleteRecursively()
            clips.forEach { it.delete() }
        }
    }
}

class VideoException(message: String) : Exception(message)
