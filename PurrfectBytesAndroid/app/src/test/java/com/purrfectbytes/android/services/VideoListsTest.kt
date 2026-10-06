package com.purrfectbytes.android.services

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** The lists and arguments that are handed to FFmpeg. */
class VideoListsTest {

    @Test
    fun `pictures are listed with the time they are shown`() {
        val first = File("/cache/frames/unit_1.jpg")
        val second = File("/cache/frames/it's.jpg")

        val list = VideoGeneratorService.slideshowOf(
            listOf(FrameSegment(0, 13), FrameSegment(1, 6)),
            mapOf(0 to first, 1 to second)
        )

        assertEquals(
            """
            ffconcat version 1.0
            file '/cache/frames/unit_1.jpg'
            option framerate 30
            duration 0.433333
            file '/cache/frames/it'\''s.jpg'
            option framerate 30
            duration 0.700000
            file '/cache/frames/it'\''s.jpg'
            option framerate 30

            """.trimIndent(),
            list
        )
    }

    @Test
    fun `a clip is repeated by listing it again`() {
        val normal = File("/cache/videos/clip_1.mp4")
        val slow = File("/cache/videos/it's slow.mp4")

        assertEquals(
            "ffconcat version 1.0\nfile '/cache/videos/clip_1.mp4'\nfile '/cache/videos/it'\\''s slow.mp4'\n" +
                "file '/cache/videos/clip_1.mp4'\n",
            VideoGeneratorService.playlistOf(listOf(normal, slow, normal))
        )
        assertEquals(
            "ffconcat version 1.0\nfile '/cache/videos/clip_1.mp4'\n",
            VideoGeneratorService.playlistOf(listOf(normal))
        )
    }

    @Test
    fun `the clip is encoded at 30 frames a second and cut where the audio ends`() {
        val arguments = VideoGeneratorService.encodeArguments(
            File("/cache/frames/frames.txt"), File("/cache/speech/tts.mp3"), File("/cache/videos/single.mp4")
        )

        assertEquals(
            "-y -f concat -safe 0 -i /cache/frames/frames.txt -i /cache/speech/tts.mp3 -vf fps=30 " +
                "-c:v libx264 -preset ultrafast -crf 24 -pix_fmt yuv420p -c:a aac -b:a 192k " +
                "-movflags +faststart -shortest /cache/videos/single.mp4",
            arguments.joinToString(" ")
        )
    }

    @Test
    fun `the clips are copied into the video instead of being encoded again`() {
        val arguments = VideoGeneratorService.joinArguments(
            File("/cache/frames/playlist.txt"), File("/cache/videos/output.mp4")
        )

        assertEquals(
            "-y -f concat -safe 0 -i /cache/frames/playlist.txt -c copy -movflags +faststart /cache/videos/output.mp4",
            arguments.joinToString(" ")
        )
    }
}
