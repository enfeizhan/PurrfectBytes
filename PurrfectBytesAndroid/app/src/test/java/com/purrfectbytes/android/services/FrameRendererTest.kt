package com.purrfectbytes.android.services

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/** Lays out and draws real frames, with Android's own text engine running on this computer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FrameRendererTest {

    private val renderer = FrameRenderer(background = null, qrCode = null, logo = null)

    private val red = Color.parseColor("#dc3232")

    private fun boxes(prepared: FrameRenderer.PreparedText): List<RectF> =
        prepared.units.indices.map { index ->
            val box = renderer.highlightBox(prepared, index)
            assertNotNull("unit $index has no place on screen", box)
            box!!.second
        }

    private fun assertInsideFrame(prepared: FrameRenderer.PreparedText) {
        val sides = (FrameRenderer.WIDTH - FrameRenderer.TEXT_WIDTH) / 2f
        boxes(prepared).forEachIndexed { index, box ->
            assertTrue("unit $index starts at ${box.left}", box.left >= sides - 5)
            assertTrue("unit $index ends at ${box.right}", box.right <= FrameRenderer.WIDTH - sides + 5)
            assertTrue("unit $index is above the frame", box.top >= 0)
        }
    }

    @Test
    fun `a short sentence is one line at full size`() {
        val prepared = renderer.prepare("Hello world")

        assertEquals(1, prepared.lineCount)
        assertEquals(FrameRenderer.MAX_TEXT_SIZE, prepared.textSize)
        assertEquals(10, prepared.units.size)
        assertInsideFrame(prepared)
    }

    @Test
    fun `a long sentence is broken between words`() {
        val prepared = renderer.prepare("The quick brown fox jumps over the lazy dog and keeps on running through the forest")

        assertTrue(prepared.lineCount > 1)
        prepared.lineTexts().forEach { line ->
            assertTrue("\"$line\" starts or ends inside a word", line == line.trim())
        }
        assertEquals(
            "The quick brown fox jumps over the lazy dog and keeps on running through the forest",
            prepared.lineTexts().joinToString(" ")
        )
        assertInsideFrame(prepared)
    }

    @Test
    fun `Japanese without spaces is broken too`() {
        // 45 characters: more than twice what fits on a line
        val text = "吾輩は猫である。名前はまだ無い。どこで生れたかとんと見当がつかぬ。何でも薄暗いじめじめした所で"
        val prepared = renderer.prepare(text)

        assertTrue("expected several lines, got ${prepared.lineCount}", prepared.lineCount >= 2)
        assertEquals(text, prepared.lineTexts().joinToString(""))
        assertEquals(text.length, prepared.units.size)
        assertInsideFrame(prepared)
    }

    @Test
    fun `Chinese without spaces is broken too`() {
        val text = "学而时习之不亦说乎有朋自远方来不亦乐乎人不知而不愠不亦君子乎学而时习之不亦说乎"
        val prepared = FrameRenderer(null, null, null, Locale.SIMPLIFIED_CHINESE).prepare(text)

        assertTrue(prepared.lineCount >= 2)
        assertEquals(text, prepared.lineTexts().joinToString(""))
        assertInsideFrame(prepared)
    }

    @Test
    fun `a word longer than a line is broken inside`() {
        val prepared = renderer.prepare("Donaudampfschifffahrtsgesellschaftskapitänswitwe".repeat(2))

        assertTrue(prepared.lineCount >= 2)
        assertInsideFrame(prepared)
    }

    @Test
    fun `line breaks in the text are kept`() {
        val prepared = renderer.prepare("first line\n\nsecond line\n")

        assertEquals(listOf("first line", "second line"), prepared.lineTexts())
    }

    @Test
    fun `very long text is drawn smaller until it fits`() {
        val prepared = renderer.prepare("This sentence is repeated to fill the frame. ".repeat(12))

        assertTrue(prepared.textSize < FrameRenderer.MAX_TEXT_SIZE)
        assertTrue(prepared.textSize >= FrameRenderer.MIN_TEXT_SIZE)
        assertInsideFrame(prepared)
        boxes(prepared).forEach { box ->
            assertTrue("a line ends at ${box.bottom}, below the frame", box.bottom <= FrameRenderer.HEIGHT)
        }
    }

    @Test
    fun `text is centered`() {
        val prepared = renderer.prepare("Hello world")
        val boxes = boxes(prepared)

        val left = boxes.first().left
        val right = FrameRenderer.WIDTH - boxes.last().right
        assertEquals(left, right, 2f)

        val middle = (boxes.first().top + boxes.first().bottom) / 2
        assertEquals(FrameRenderer.HEIGHT / 2f, middle, 30f)
    }

    @Test
    fun `characters follow each other from left to right`() {
        val boxes = boxes(renderer.prepare("Hello"))

        boxes.zipWithNext().forEach { (a, b) -> assertTrue(a.left < b.left) }
    }

    @Test
    fun `Arabic runs from right to left`() {
        val prepared = renderer.prepare("مرحبا بالعالم")
        val boxes = boxes(prepared)

        assertEquals(1, prepared.lineCount)
        assertTrue("the first letter is on the right", boxes.first().left > boxes.last().left)
        assertInsideFrame(prepared)
    }

    @Test
    fun `a Hindi vowel sign stays with its consonant`() {
        // "हिन्दी": the vowel signs are separate code points, but not separate characters
        val prepared = renderer.prepare("हिन्दी")

        assertTrue("${prepared.units.size} units for 6 code points", prepared.units.size < 6)
        assertEquals(0, prepared.units.first().start)
        assertEquals(2, prepared.units.first().end) // ह with its vowel sign
        assertInsideFrame(prepared)
    }

    @Test
    fun `the highlighted character gets a red box and the others do not`() {
        val prepared = renderer.prepare("Hello world")
        val box = renderer.highlightBox(prepared, 4)!!.second

        val plain = renderer.render(prepared, -1)
        val highlighted = renderer.render(prepared, 4)

        // Just inside the corners of the box, where no letter reaches
        val x = box.left.toInt() + 1
        val y = box.top.toInt() + 1
        assertEquals(red, highlighted.getPixel(x, y))
        assertNotEquals(red, plain.getPixel(x, y))

        // The box of another character is untouched
        val other = renderer.highlightBox(prepared, 0)!!.second
        assertNotEquals(red, highlighted.getPixel(other.left.toInt() + 1, other.top.toInt() + 1))
    }

    @Test
    fun `the letter inside the box is white`() {
        val prepared = renderer.prepare("HHHHH")
        val box = renderer.highlightBox(prepared, 2)!!.second
        val frame = renderer.render(prepared, 2)

        val colors = mutableSetOf<Int>()
        for (x in box.left.toInt() + 1 until box.right.toInt()) {
            for (y in box.top.toInt() + 1 until box.bottom.toInt()) {
                colors += frame.getPixel(x, y)
            }
        }
        assertTrue("no white pixel in the box", Color.WHITE in colors)
        assertTrue("no red pixel in the box", red in colors)
    }

    @Test
    fun `the tail of a letter is white below the box as well`() {
        val prepared = renderer.prepare("ggggg")
        val box = renderer.highlightBox(prepared, 2)!!.second
        val frame = renderer.render(prepared, 2)

        val below = mutableSetOf<Int>()
        for (x in box.left.toInt() + 1 until box.right.toInt()) {
            for (y in box.bottom.toInt() + 1 until box.bottom.toInt() + 12) {
                below += frame.getPixel(x, y)
            }
        }
        assertTrue("the tail under the box is not white", Color.WHITE in below)

        // The neighbours keep their colour. The boxes overlap by their padding (4 on each
        // side), so the neighbour is looked at from where the highlighted box has ended.
        val neighbour = renderer.highlightBox(prepared, 3)!!.second
        for (x in box.right.toInt() + 1 until neighbour.right.toInt() - 4) {
            for (y in neighbour.top.toInt() until neighbour.bottom.toInt() + 12) {
                assertNotEquals("white at $x,$y", Color.WHITE, frame.getPixel(x, y))
            }
        }
    }

    @Test
    fun `frames have the size of the video`() {
        val frame = renderer.render(renderer.prepare("Hello"), 0)

        assertEquals(FrameRenderer.WIDTH, frame.width)
        assertEquals(FrameRenderer.HEIGHT, frame.height)
    }

    @Test
    fun `the logo sits above the highlighted character and the QR code in the corner`() {
        val logo = solid(FrameRenderer.LOGO_SIZE, Color.BLUE)
        val qr = solid(FrameRenderer.QR_SIZE, Color.GREEN)
        val withPictures = FrameRenderer(background = null, qrCode = qr, logo = logo)
        val prepared = withPictures.prepare("Hello")
        val box = withPictures.highlightBox(prepared, 2)!!.second

        val frame = withPictures.render(prepared, 2)

        assertEquals(Color.BLUE, frame.getPixel(box.centerX().toInt(), box.top.toInt() - 50))
        // The QR code is drawn at 90%, so it is green mixed with a little of the background
        val corner = frame.getPixel(80, FrameRenderer.HEIGHT - 80)
        assertTrue(Color.green(corner) > 200 && Color.red(corner) < 40)
    }

    @Test
    fun `an index outside the text highlights nothing`() {
        val prepared = renderer.prepare("Hello")

        assertNull(renderer.highlightBox(prepared, -1))
        assertNull(renderer.highlightBox(prepared, 99))
    }

    @Test
    fun `text made of spaces only can still be drawn`() {
        val prepared = renderer.prepare("   \n  ")

        assertEquals(0, prepared.units.size)
        assertNotNull(renderer.render(prepared, -1))
    }

    // --------------------------------------------------------- conversations

    private val margin = (FrameRenderer.WIDTH - FrameRenderer.TEXT_WIDTH) / 2f

    /** Where a conversation begins: the box of its first character, which is 4 wider than the character. */
    private val start = FrameRenderer.START_PADDING - 4f

    /** The first box of every line of the text, in the order of the lines. */
    private fun firstBoxes(prepared: FrameRenderer.PreparedText): List<RectF> {
        val starts = prepared.lineTexts().runningFold(0) { position, line -> position + line.length + 1 }
        return starts.dropLast(1).map { start ->
            val unit = prepared.units.indexOfFirst { it.start >= start }
            renderer.highlightBox(prepared, unit)!!.second
        }
    }

    @Test
    fun `the lines of a conversation begin below each other`() {
        val prepared = renderer.prepare("A: Hi\nB: Good morning to you\nA: Fine", startAligned = true)

        val lefts = firstBoxes(prepared).map { it.left }

        assertEquals(3, lefts.size)
        lefts.forEach { assertEquals(start, it, 1f) }
        assertInsideFrame(prepared)
    }

    @Test
    fun `text that is no conversation stays in the middle`() {
        val prepared = renderer.prepare("A: Hi\nB: Good morning to you\nA: Fine")

        val lefts = firstBoxes(prepared).map { it.left }

        assertTrue("the short lines begin further in than the long one", lefts[0] > lefts[1] + 100)
        assertEquals(lefts[0], lefts[2], 60f)
    }

    @Test
    fun `a long line of a conversation goes on at the same side`() {
        val text = "A: Hi\nB: " + "This line is long enough to need more than one line on screen. ".repeat(2).trim()
        val prepared = renderer.prepare(text, startAligned = true)

        assertTrue(prepared.lineCount >= 3)
        firstBoxes(prepared).forEach { assertEquals(start, it.left, 1f) }
        assertInsideFrame(prepared)
    }

    @Test
    fun `a long conversation does not run under the QR code`() {
        val text = List(12) { "Speaker ${it % 2 + 1}: line number ${it + 1} of a long conversation" }.joinToString("\n")
        val prepared = renderer.prepare(text, startAligned = true)

        // The QR code lies in the corner at the bottom left, 20 from the edges and 120 wide
        val codeEnds = 20 + FrameRenderer.QR_SIZE
        boxes(prepared).forEach { box ->
            assertTrue("a character begins at ${box.left}", box.left > codeEnds)
        }
        assertTrue(boxes(prepared).any { it.bottom > FrameRenderer.HEIGHT - 20 - FrameRenderer.QR_SIZE })
        assertInsideFrame(prepared)
    }

    @Test
    fun `an Arabic conversation begins at the right`() {
        val prepared = renderer.prepare("مرحبا\nأهلا وسهلا بكم في بيتنا", startAligned = true)

        val rights = firstBoxes(prepared).map { it.right }

        rights.forEach { assertEquals(FrameRenderer.WIDTH - margin + 4, it, 1f) }
        assertInsideFrame(prepared)
    }

    @Test
    fun `the highlight of a conversation is drawn where its box is`() {
        val prepared = renderer.prepare("A: Hi\nB: Hello", startAligned = true)
        val unit = prepared.units.indexOfFirst { it.start == 3 } // H of "Hi"
        val box = renderer.highlightBox(prepared, unit)!!.second

        val frame = renderer.render(prepared, unit)

        assertEquals(red, frame.getPixel(box.left.toInt() + 1, box.top.toInt() + 1))
        assertEquals(red, frame.getPixel(box.right.toInt() - 2, box.bottom.toInt() - 2))
        val colors = mutableSetOf<Int>()
        for (x in box.left.toInt() + 1 until box.right.toInt()) {
            for (y in box.top.toInt() + 1 until box.bottom.toInt()) colors += frame.getPixel(x, y)
        }
        assertTrue("the letter in the box is not white", Color.WHITE in colors)
    }

    // ------------------------------------------------------------- the badge

    @Test
    fun `slowed down speech is marked in the corner at the top right`() {
        val prepared = renderer.prepare("Hello world")
        val badge = renderer.slowBadgeBox()

        val slow = renderer.render(prepared, 0, slowBadge = true)
        val normal = renderer.render(prepared, 0)

        // The measures of the web app: 20 from the edges, and as high as its letters and 10 around them
        assertEquals(FrameRenderer.WIDTH - 20f, badge.right, 0.01f)
        assertEquals(20f, badge.top, 0.01f)
        assertTrue("the badge is ${badge.width()} wide", badge.width() in 100f..140f)
        assertTrue("the badge is ${badge.height()} high", badge.height() in 36f..48f)

        val colors = mutableSetOf<Int>()
        for (x in badge.left.toInt() until badge.right.toInt()) {
            colors += slow.getPixel(x, badge.centerY().toInt())
            assertNotEquals(red, normal.getPixel(x, badge.centerY().toInt()))
        }
        assertTrue("the badge is not red", red in colors)
        assertTrue("the badge has no white letters", Color.WHITE in colors)
    }

    @Test
    fun `the badge is round at its ends and leaves the rest of the frame alone`() {
        val prepared = renderer.prepare("Hello world")
        val badge = renderer.slowBadgeBox()

        val slow = renderer.render(prepared, -1, slowBadge = true)
        val normal = renderer.render(prepared, -1)

        assertNotEquals(red, slow.getPixel(badge.left.toInt(), badge.top.toInt()))
        assertEquals(red, slow.getPixel(badge.left.toInt() + 3, badge.centerY().toInt()))
        for (x in 0 until FrameRenderer.WIDTH step 7) {
            for (y in 0 until FrameRenderer.HEIGHT step 7) {
                if (!badge.contains(x.toFloat(), y.toFloat())) {
                    assertEquals("at $x,$y", normal.getPixel(x, y), slow.getPixel(x, y))
                }
            }
        }
    }

    private fun solid(size: Int, color: Int): Bitmap =
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
}
